# 09 - 多 Agent 协作

## 源码位置

- `src/main/java/com/mewcode/teams/TeamManager.java` (189 行) — 团队管理核心
- `src/main/java/com/mewcode/teams/TeammateRunner.java` — 队友运行器
- `src/main/java/com/mewcode/teams/FileMailBox.java` — 文件系统邮箱
- `src/main/java/com/mewcode/teams/TeamTools.java` — 团队管理工具
- `src/main/java/com/mewcode/teams/SpawnDispatcher.java` — 队友生成
- `src/main/java/com/mewcode/teams/Coordinator.java` — 协调模式
- `src/main/java/com/mewcode/teams/TmuxBackend.java` — tmux 后端
- `src/main/java/com/mewcode/teams/ITermBackend.java` — iTerm2 后端

## 一、架构设计

```
TeamManager
    │
    ├── Team "frontend"
    │   ├── Member "react-dev"    (独立 terminal pane)
    │   ├── Member "css-dev"      (独立 terminal pane)
    │   └── FileMailBox           (文件系统消息队列)
    │
    └── Team "backend"
        ├── Member "api-dev"
        ├── Member "db-dev"
        └── FileMailBox
```

## 二、TeamManager

### 核心数据结构

```java
public class TeamManager {
    private final Map<String, Team> teams = new LinkedHashMap<>();

    public class Team {
        final String name;
        final TeamMode mode;         // IN_PROCESS / TMUX / ITERM
        final Map<String, Member> members;
        private final FileMailBox mailBox;  // 消息通信
    }

    public class Member {
        public final String name;
        public final Agent agent;           // 每个成员有自己的 Agent
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
    }
}
```

### 三种运行模式

```java
public enum TeamMode { IN_PROCESS, TMUX, ITERM }
```

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| `IN_PROCESS` | 虚拟线程运行，共享主进程 | 开发/测试 |
| `TMUX` | 独立 tmux 窗格 | Linux/Mac 生产环境 |
| `ITERM` | 独立 iTerm2 窗格 | macOS 用户 |

### 后端自动检测

```java
public static TeamMode detectPaneBackend() {
    if (System.getenv("TMUX") != null) → TMUX     // 已在 tmux 中
    if (System.getenv("ITERM_SESSION_ID") != null) → ITERM
    if (which("tmux") succeeds) → TMUX             // tmux 已安装
    → IN_PROCESS                                    // 回退
}
```

## 三、FileMailBox（文件邮箱通信）

### 存储结构

```
.mewcode/teams/{teamName}/inboxes/
    ├── react-dev.jsonl
    ├── css-dev.jsonl
    └── api-dev.jsonl
```

### 消息格式

```java
public record MailMessage(String from, String content, long timestamp) {}
```

JSONL 存储，每行一条消息。

### 通信 API

```java
public void send(String to, MailMessage msg)    // 发送消息到目标收件箱
public List<MailMessage> poll(String memberName) // 拉取该成员的未读消息
public void clear(String memberName)             // 清空收件箱
```

## 四、TeammateRunner

### 启动流程

```java
// 1. 创建成员
Member member = team.addMember(name, client, registry, protocol, cfg);

// 2. 构建启动上下文
String addendum = buildTeammateAddendum(teamName, memberName, otherMembers);
// 包含：团队信息、邮箱路径、通信协议说明

// 3. 通过 SpawnDispatcher 启动
SpawnConfig config = new SpawnConfig(team, memberName, prompt, addendum, ...);
SpawnDispatcher.spawnTeammate(config);
```

### Teammate Addendum

每个队友启动时被注入的上下文：

```
You are a teammate in team "{teamName}". Your name is "{memberName}".

Communication:
- Send messages: use SendMessage tool
- Check inbox: your inbox is at .mewcode/teams/{teamName}/inboxes/{memberName}.jsonl
- Your teammates: {otherMembers}

IMPORTANT:
- Check your inbox at the start of each turn
- When you receive a task, acknowledge it
- When you complete work, send a summary to the requester
```

## 五、Team 工具

### TeamCreateTool
```
参数: team_name (必填)
效果: 创建新 Team，返回 team 信息
```

### TeamDeleteTool
```
参数: team_name (必填)
效果: 停止所有成员 → 清理邮箱 → 删除 Team
```

### SendMessageTool
```
参数: to (收件人), content (消息内容)
实现: team.sendMessage(from, to, content) → 写入目标收件箱
```

## 六、Coordinator 模式

当 `enableCoordinatorMode = true` 时，lead agent 的可用工具被过滤为 coordinator-only 集合：

```java
agent.setToolNameFilter(name -> {
    if (!enableCoordinatorMode) return true;
    if (teamManager.listTeams().isEmpty()) return true;  // 无 team 时正常
    return Coordinator.isCoordinatorTool(name);  // 只允许协调工具
});
```

协调工具包括：TeamCreate, TeamDelete, SendMessage, Agent（用于创建 team member）

## 七、通知机制

### Lead Agent 的邮箱轮询

```java
agent.setNotificationFn(() -> {
    var notes = new ArrayList<String>();
    // 1. 排空 lead 邮箱
    notes.addAll(TeammateRunner.drainLeadMailbox(teamManager));
    // 2. 排空子 Agent 任务通知
    if (subAgentTaskManager != null) {
        for (var n : subAgentTaskManager.drainNotifications()) {
            notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                    .formatted(n.taskId(), n.name(), n.status()));
        }
    }
    return notes;
});
```

这些通知在 Agent 下一轮循环开始时被注入为 system reminders。
