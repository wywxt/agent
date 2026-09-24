# 16 - Session 会话管理

## 源码位置

- `src/main/java/com/mewcode/session/SessionManager.java` (414 行)

## 一、存储架构

```
{workDir}/.mewcode/sessions/
    ├── 20260719-143052-a3f1.jsonl    ← 会话文件（JSONL 格式）
    ├── 20260718-091520-7b2c.jsonl
    └── 20260715-221034-d8e9.jsonl    ← 超过 30 天自动清理
```

## 二、Session ID 生成

```java
public static String newId() {
    String timestamp = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    byte[] randomBytes = new byte[2];
    SecureRandom.getInstanceStrong().nextBytes(randomBytes);
    return "%s-%s".formatted(timestamp, HexFormat.of().formatHex(randomBytes));
    // 例: "20260719-143052-a3f1"
}
```

## 三、记录格式

### 普通消息记录

```json
{"role": "user", "content": "帮我写一个排序函数", "ts": 1752906052}
{"role": "assistant", "content": "好的，这是快速排序的实现...", "ts": 1752906053}
{"role": "user", "content": "", "ts": 1752906054, "tool_use_id": "toolu_xxx"}
```

字段说明：
- `role` — "user" | "assistant" | "system"
- `type` — 为空表示普通消息，"compact_boundary" 表示压缩边界
- `content` — 消息文本
- `ts` — Unix 时间戳（秒）
- `tool_use_id` — 工具调用 ID（用于 resume 时的 chain validation）

### 压缩边界记录

```json
{
    "role": "system",
    "type": "compact_boundary",
    "content": "{\"summary\":\"本次对话摘要...\",\"keep\":[{\"role\":\"user\",\"content\":\"...\"},{\"role\":\"assistant\",\"content\":\"...\"}]}",
    "ts": 1752906280
}
```

## 四、持久化

### 写入

```java
public static void saveMessage(String workDir, String sessionId, String role, String content) {
    Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
    Map<String, Object> line = Map.of(
        "role", role,
        "content", content,
        "ts", Instant.now().getEpochSecond()
    );
    String json = MAPPER.writeValueAsString(line) + "\n";
    Files.writeString(file, json, CREATE, APPEND);
}
```

Best-effort 策略：写入失败只忽略，不抛异常。

### 读取

```java
public static List<SessionMessage> loadSession(String workDir, String sessionId) {
    Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
    try (BufferedReader reader = Files.newBufferedReader(file)) {
        String line;
        while ((line = reader.readLine()) != null) {
            Map<String, Object> map = MAPPER.readValue(line, Map.class);
            String role = (String) map.get("role");
            String type = (String) map.get("type");
            String content = (String) map.get("content");
            long ts = (Number) map.get("ts");
            messages.add(new SessionMessage(role, type, content, ts, toolUseId));
        }
    }
}
```

### 压缩边界写入

```java
public static void saveCompactBoundary(String workDir, String sessionId,
                                       String summary, List<KeepMessage> keep) {
    String blob = MAPPER.writeValueAsString(new CompactBoundary(summary, keep));
    saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, null);
}
```

## 五、Compaction-aware Resume

### 重建策略

```java
public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
    BoundaryScan scan = findLastCompactBoundary(messages);

    if (!scan.found()) {
        // 无压缩边界 → 全部重放
        return replay(messages);
    }

    // 有压缩边界 → 重建压缩状态
    // = summary (user) + kept tail + after-boundary messages
    String resumeSummary = "本次会话延续自之前的对话..." + scan.boundary().summary();
    replay.add(new SessionMessage("user", resumeSummary));
    for (KeepMessage k : scan.boundary().keep()) {
        replay.add(new SessionMessage(k.role(), k.content()));
    }
    replay.addAll(scan.after());  // 压缩后新增的消息
    return replay(replay);
}
```

### 压缩边界扫描

```java
public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
    int last = -1;
    for (int i = 0; i < messages.size(); i++) {
        if (messages.get(i).isCompactBoundary()) last = i;
    }
    if (last < 0) return notFound;

    CompactBoundary boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
    List<SessionMessage> after = messages.subList(last + 1, messages.size());
    return new BoundaryScan(boundary, after, true);
}
```

## 六、Session 列表

```java
public static List<SessionInfo> listSessions(String workDir) {
    // 遍历 .mewcode/sessions/*.jsonl
    // 提取：id, firstMessage, messageCount, fileSize, gitBranch, modTime
    // 按 modTime 降序排列
}
```

## 七、过期清理

```java
public static void cleanExpiredSessions(String workDir) {
    long cutoffMs = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L; // 30 天
    // 删除 mtime < cutoffMs 的 session 文件
    // 失败静默忽略
}
```

## 八、Git 分支关联

```java
public static String currentGitBranch(String workDir) {
    // git -C {workDir} rev-parse --abbrev-ref HEAD
    // 失败返回空字符串
}
```

Session 列表显示时附带 git 分支信息，方便用户识别。

## 九、格式化工具

```java
formatRelativeTime(Instant)  → "just now" / "5 minutes ago" / "2 days ago"
formatFileSize(long bytes)   → "1.5KB" / "2.3MB"
```
