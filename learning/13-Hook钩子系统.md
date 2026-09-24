# 13 - Hook 钩子系统

## 源码位置

- `src/main/java/com/mewcode/hook/HookEngine.java` (719 行)

## 一、Hook 是什么

Hook 是在 Agent 生命周期的特定**事件点**触发的自动化动作。对标 Claude Code 的 Hooks 系统。

## 二、支持的 9 种事件

```java
public enum EventName {
    SESSION_START,    // 会话开始
    SESSION_END,      // 会话结束
    TURN_START,       // 每轮对话开始
    TURN_END,         // 每轮对话结束
    PRE_SEND,         // 发送 LLM 请求前
    POST_RECEIVE,     // 接收 LLM 响应后
    PRE_TOOL_USE,     // 工具执行前 ★ (支持 reject)
    POST_TOOL_USE,    // 工具执行后
    SHUTDOWN,         // 程序关闭
}
```

## 三、支持的 4 种动作类型

```java
public enum ActionType {
    COMMAND,   // 执行 Shell 命令 (bash -c)
    PROMPT,    // 注入提示文本到对话
    HTTP,      // 发送 HTTP 请求
    AGENT,     // 启动子 Agent 处理
}
```

## 四、配置格式

```yaml
# .mewcode/config.yaml
hooks:
  - id: "lint-on-write"
    event: post_tool_use
    condition: "tool == WriteFile && file_path =* \"*.java\""
    type: command
    command: "checkstyle ${args.file_path}"
    timeout: 30

  - id: "notify-errors"
    event: post_receive
    condition: "message =~ /error|exception/i"
    type: http
    url: "https://hooks.slack.com/..."
    method: POST

  - id: "prevent-rm-root"
    event: pre_tool_use
    condition: "tool == Bash && message =~ /rm\\s+-rf\\s+\\//"
    type: command
    command: "echo 'BLOCKED'"
    reject: true
```

## 五、核心执行流程

```java
public List<HookResult> runHooks(HookContext ctx) {
    for (Hook h : snapshotHooks()) {
        // 1. 事件匹配
        if (h.event() != ctx.event()) continue;

        // 2. 条件求值
        if (!shouldFire(h, ctx)) continue;

        // 3. 异步模式 → 独立线程执行，立即返回占位结果
        if (h.async()) {
            CompletableFuture.runAsync(() -> {
                HookResult res = executeAction(h, ctx);
                notifications.add(res);
            });
            continue;
        }

        // 4. 同步执行
        HookResult result = executeAction(h, ctx);
        results.add(result);
    }
    return results;
}
```

## 六、条件表达式引擎

### 语法

```
支持的操作符：
  ==   精确相等    tool == Bash
  !=   不等于      tool != ReadFile
  =~   正则匹配    message =~ /error|exception/i
  =*   glob 匹配   file_path =* "*.java"

复合条件：
  &&   逻辑与      tool == Bash && message =~ /git/
  ||   逻辑或
  !    取反        !(tool == Bash)

无操作符：变量非空即为 true
```

### 实现

```java
static boolean evaluateCondition(String condition, HookContext ctx) {
    // 1. 拆分复合条件（按 && / || 分割）
    List<CompToken> tokens = splitComposite(cond);
    if (tokens.size() > 1) {
        boolean result = evaluateCondition(tokens[0].expr, ctx);
        for (int i = 1; i < tokens.size(); i++) {
            boolean rhs = evaluateCondition(tokens[i].expr, ctx);
            if ("&&".equals(tokens[i].op)) result = result && rhs;
            else result = result || rhs;
        }
        return result;
    }

    // 2. 取反
    if (cond.startsWith("!")) return !evaluateCondition(cond.substring(1), ctx);

    // 3. 叶子条件
    return evaluateLeaf(cond, ctx);
}
```

### 变量

| 变量 | 含义 | 适用事件 |
|------|------|---------|
| `tool` | 工具名称 | PRE_TOOL_USE, POST_TOOL_USE |
| `event` | 事件名称 | 全部 |
| `file_path` | 文件路径 | 文件操作事件 |
| `message` | 消息内容 | PRE_SEND, POST_RECEIVE |
| `args.xxx` | 工具参数 | PRE_TOOL_USE, POST_TOOL_USE |

## 七、动作执行

### COMMAND 动作

```java
private HookResult executeCommand(Hook h, HookContext ctx) {
    Duration timeout = h.action().timeout() != null ? h.action().timeout() : 10分钟;
    String command = ctx.expand(h.action().command());  // 模板变量替换

    ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
    // 注入环境变量：MEWCODE_EVENT, MEWCODE_TOOL, MEWCODE_FILE_PATH
    pb.environment().put("MEWCODE_EVENT", ctx.event().value());
    pb.environment().put("MEWCODE_TOOL", ctx.toolName());
    pb.environment().put("MEWCODE_FILE_PATH", ctx.filePath());

    Process proc = pb.start();
    boolean finished = proc.waitFor(timeout.toMillis(), MILLISECONDS);
    if (!finished) { proc.destroyForcibly(); return timeout_error; }

    String stdout = readAllBytes(stdoutStream);
    String stderr = readAllBytes(stderrStream);
    return new HookResult(id, stdout + stderr, exitCode == 0, reject);
}
```

### HTTP 动作

```java
private HookResult executeHTTP(Hook h, HookContext ctx) {
    String url = ctx.expand(h.action().url());
    String body = h.action().body() != null
        ? ctx.expand(h.action().body())
        : autoGenerateJson(ctx);  // 自动构建含 event/tool/file_path 的 JSON

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(timeout)  // 默认 10 秒
        .method(method, BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()
        .send(req, BodyHandlers.ofString());

    // 限制响应体大小 64KB
    return new HookResult(id, "HTTP " + statusCode + ": " + body, 2xx);
}
```

### AGENT 动作

```java
private HookResult executeAgent(Hook h, HookContext ctx) {
    if (agentRunner == null) {
        return error("agent-type hook configured but no AgentRunner registered");
    }
    String prompt = ctx.expand(h.action().message());  // message 优先，command 作为 fallback
    String output = agentRunner.apply(prompt, ctx);
    return new HookResult(id, output, true, reject);
}
```

## 八、Pre-Tool Hook 的 Reject 机制

```java
public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
    for (Hook h : snapshotHooks()) {
        if (h.event() != PRE_TOOL_USE) continue;
        if (!shouldFire(h, ctx)) continue;

        HookResult result = executeAction(h, ctx);

        // 配置了 reject 或 action 失败 + onError=reject → 拦截工具执行
        if (h.reject() || (!result.success() && "reject".equals(h.onError()))) {
            return new PreToolResult(true, result.output());  // rejected=true
        }
    }
    return new PreToolResult(false, "");  // 放行
}
```

## 九、模板变量替换

```java
public String expand(String template) {
    // ${event}     → "pre_tool_use"
    // ${tool}      → "WriteFile"
    // ${file_path} → "/path/to/file.java"
    // ${message}   → "..."
    // ${error}     → "..."
    // ${args.key}  → toolArgs.get("key")
}
```

## 十、Once + Async

- `once: true` — 同一 id 只触发一次（用 `fired` Set 去重）
- `async: true` — 异步执行，不阻塞 Agent 主循环

## 十一、配置校验

`HookEngine.validate()` 在加载时检查：
- 事件名是否合法
- 超时值非负
- 每种 action type 的必填字段（command → command, http → url, prompt → message）
- URL 格式合法性
- 所有错误聚合返回（不因第一个错误就中断）
