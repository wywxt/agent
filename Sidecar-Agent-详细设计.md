# Sidecar Agent 详细设计

> 项目：`E:\agent-project\code agent java`
> 依据：`C:\Users\MR\Sidecar-Agent-精简实施方案.md` + 用户最终形态诉求
> 状态：设计文档（未开始编码）
> 所有路径相对 `src/main/java/com/mewcode/`。

## 0. 已确认决策

| 决策 | 选择 |
| --- | --- |
| 起步方式 | 先出详细设计文档，评审后再编码 |
| 事件/任务/指令/审批/报告/问答存储 | 文件 JSONL（零新增依赖），不用 SQLite |
| 进程形态 | 第一阶段同 JVM 内 Java 方法调用，之后再拆独立 Sidecar 进程 |
| 保留项 | Java 21、Javalin、现有 LLM 客户端、远程页面、ContextCompactor、ToolResultBudget、RecoveryState、SessionManager |

## 0.1 最终形态诉求（本版核心变化）

用户想要的最终结果，归纳为四条主线：

1. **权限三层链**：工具权限先由主 Agent 做一层判断 → 交 sidecar 润色判断 → 拿不准的交给用户做最终决策。
2. **执行呈现**：主 Agent 做的事（具体耗时、具体做了什么事）经 sidecar 润色后，在界面呈现。
3. **最终报告**：任务结束产出可读的报告。
4. **双向交互**：用户可向 sidecar 提问细节（sidecar 直接答），也可提改动要求（sidecar 转给主 Agent）。

相对上一版的变更：
- 权限闭环（原阶段 4）从"规则引擎 → 用户"两段，重构为"主 Agent 规则层 → sidecar 润色层 → 用户决策层"三段。
- 指令闭环（原阶段 2）拆成两类：**问询（Question，sidecar 直接答）** 与 **指令（Directive，投递主 Agent）**。
- 报告（原阶段 5）从单一"进度摘要"扩展为**进度报告 + 最终报告**两个产物。
- 新增界面草图与问答协议。

---

## 1. 最终形态总览

### 1.1 三条主线

```
主线 A —— 权限三层链
  主 Agent 提议工具调用
    → 第一层：主 Agent 规则引擎判定 (ALLOW / DENY / UNCERTAIN)
    → 第二层：sidecar 润色判断 (解释 + 建议 allow/deny/unsure)
    → 第三层：拿不准的 → 用户审批卡片 (最终决策)
    → 决定是否执行

主线 B —— 执行呈现
  工具执行事实事件 (开始/结束/耗时/文件改动/结果)
    → sidecar 润色成进度报告 (阶段/完成项/阻塞点)
    → 界面时间线 + 状态卡片呈现
    → 任务结束产出最终报告

主线 C —— 双向交互
  用户 → sidecar 提问细节 → sidecar 基于事件时间线 + 小模型直接回答 (不打断主 Agent)
  用户 → sidecar 提改动要求 → sidecar 存为指令 → 检查点投递主 Agent → 回报采用/推迟/冲突
```

### 1.2 界面草图（最终呈现形态）

```
┌────────────────────────────────────────────────────────────────┐
│ 任务状态条：RUNNING ● ｜ 已用 4m32s ｜ 工具 12 次 ｜ 文件改动 3  │
├────────────────────────────────────────────────────────────────┤
│ 时间线（sidecar 润色后的事件卡片）                              │
│  ▸ 09:12:03  读取 src/main/Agent.java (ReadFile) 0.8s           │
│  ▸ 09:12:05  执行 git diff (Bash) 1.2s ✅                       │
│  ▸ 09:12:06  ⚠️ 修改 Agent.java (EditFile) — 待审批             │
│      [sidecar 解释] 该操作会改写 run() 返回值类型                │
│      [主Agent判断] ASK   [sidecar建议] unsure                   │
│      [ 允许一次 ] [ 始终允许 ] [ 拒绝 ]                          │
│  ▸ 09:13:40  运行测试 ./gradlew test 42.7s ✅ 通过 14/14        │
├────────────────────────────────────────────────────────────────┤
│ 进度报告（sidecar 生成，约每 15s 刷新）                         │
│  阶段：修复 Agent.run 返回值 → 当前：调整 TUI 调用点             │
│  完成：改 run() 返回句柄 ✅；注入取消检查 ✅                     │
│  阻塞：Bash 子进程无法被 interrupt 终止                          │
├────────────────────────────────────────────────────────────────┤
│ Sidecar 输入框                                                 │
│  提问："为什么 EditFile 这一步在等审批？"                        │
│  改动："顺便把测试也一起跑了"                                    │
│  [ 发送问询 ]  [ 发送指令 ]                                      │
├────────────────────────────────────────────────────────────────┤
│ 指令状态：dir-3f2a  APPEND  RECEIVED → DELIVERED → ACKNOWLEDGED │
├────────────────────────────────────────────────────────────────┤
│ 最终报告（任务结束时）                                          │
│  ✅ 已完成修复：run() 返回 TaskExecutionHandle，取消可传播…      │
│  改动文件：Agent.java / StreamingExecutor.java / RemoteServer.java │
│  耗时分布：模型推理 61%、工具执行 22%、审批等待 17%              │
│  [ 复制 Markdown ]  [ 下载报告 ]                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. 总体架构与类清单

```
用户 / 远程页面 (index.html)
          ↕  WebSocket (Javalin /ws)
Sidecar 控制模块（同 JVM）
  ├─ control/task      TaskExecutionHandle / CancellationToken / TaskStatus / TaskOutcome
  ├─ control/directive DirectiveService / DirectiveInbox / Directive
  ├─ control/approval  ApprovalService / ToolInvocation / ApprovalRequest
  ├─ control/event     EventStore / EventBridge / EventEnvelope / EventType
  ├─ control/report    ReportService  (进度/最终报告 + 问答 + 润色解释)
  └─ control/store     JsonlStore (共享 JSONL 追加/读取)
          ↕  Java 方法调用（阶段6 换本地受认证 HTTP/WS）
现有 Agent + StreamingExecutor（工具执行拦截点 / 权限三层链第一层）
          ↓
ConversationManager / LlmClient / ToolRegistry / PermissionChecker
```

新增类清单（按需创建）：

```
control/store/JsonlStore.java
control/task/TaskStatus.java
control/task/CancellationToken.java
control/task/TaskOutcome.java
control/task/TaskExecutionHandle.java
control/directive/Directive.java
control/directive/DirectiveInbox.java
control/directive/DirectiveService.java
control/event/EventType.java
control/event/EventEnvelope.java
control/event/EventStore.java
control/event/EventBridge.java
control/approval/ToolInvocation.java
control/approval/ApprovalRequest.java
control/approval/ApprovalService.java
control/report/ReportService.java
```

说明：`control/store` 额外于方案五包，是因为指令（阶段 2）先于事件（阶段 3）落地，需要一个共享 JSONL 底层供 directive/event/task/approval/report/qa 复用，避免六份重复追加代码。

---

## 3. 阶段 1 —— 执行生命周期（地基，基本不变）

### 3.1 新建类

**`control/task/TaskStatus.java`**
```java
public enum TaskStatus {
    RUNNING, WAITING_APPROVAL, PAUSED,
    CANCELLING, CANCELLED, SUCCEEDED, FAILED
}
```

**`control/task/CancellationToken.java`**
```java
public final class CancellationToken {
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String reason;
    public void cancel(String reason) { this.reason = reason; cancelled.set(true); }
    public void cancel() { cancel(null); }
    public boolean isCancelled() { return cancelled.get(); }
    public String reason() { return reason; }
}
```

**`control/task/TaskOutcome.java`**
```java
public record TaskOutcome(TaskStatus status, String message, int totalTurns) {
    public static TaskOutcome succeeded(int turns) { return new TaskOutcome(TaskStatus.SUCCEEDED, null, turns); }
    public static TaskOutcome failed(String msg)   { return new TaskOutcome(TaskStatus.FAILED, msg, 0); }
    public static TaskOutcome cancelled()          { return new TaskOutcome(TaskStatus.CANCELLED, null, 0); }
}
```

**`control/task/TaskExecutionHandle.java`**
```java
public final class TaskExecutionHandle {
    private final String taskId;                       // "task-" + UnsignedString(nanoTime,36)
    private volatile Thread executionThread;
    private final CancellationToken token;
    private final java.util.concurrent.CompletableFuture<TaskOutcome> completion;

    public String taskId(); public CancellationToken token();
    public java.util.concurrent.CompletableFuture<TaskOutcome> completion();
    public void cancel(); public boolean isDone();
    void setExecutionThread(Thread t);
}
```

`taskId` 与 `sessionId`（会话持久化 id）解耦：一个 session 可含多个 task。

### 3.2 修改 `agent/Agent.java`

1. `run()` 返回 `TaskExecutionHandle`（由 `new TaskExecutionHandle(taskId, token, completion)` + `Thread.ofVirtual()` 组装）。
2. `agentLoop` 增加 `token`、`completion` 参数与 `cancelled`/`failReason` 标志。
3. 循环检查点 `if (Thread.currentThread().isInterrupted()) break;` 改为 `if (token.isCancelled()) { cancelled = true; break; }`。
4. `finally` 按 `cancelled / loopCompleted / failReason` 发布 `TaskTerminalEvent` 并 `completion.complete(...)`。
5. 现有各 `break`/`return` 路径改设标志：正常完成 / ExitPlanMode → `loopCompleted`；流超时、不可恢复错误、超 maxIterations → `failReason`（当前误判为成功）。

### 3.3 修改 `agent/StreamingExecutor.java`
- 构造器加 `CancellationToken`（保留兼容重载）。
- `executeAll`：每批执行前 `if (token.isCancelled()) break;`。
- `executeSingle`：`tool.execute()` 前取消检查，返回 `[cancelled]` 结果。

> ⚠️ `Thread.interrupt` 不终止外部进程。阶段 1 需评估 `BashTool`/沙箱子进程是否传递取消句柄；若无法中断，记录为已知限制（"Bash 长任务取消为尽力而为"）。

### 3.4 修改 `remote/RemoteServer.java`
- 删除 `streamThread`，改用 `AtomicReference<TaskExecutionHandle> activeTask`，CAS 保护单任务。
- `case "cancel"` → `activeTask.get().cancel()`。
- `consumeAgentEvents(handle)` 以收到 `TaskTerminalEvent` 退出。

### 3.5 验证清单
- [ ] 任务运行中重复 `user_message` 不启动第二任务。
- [ ] 取消 → Agent 线程停止、不再派发新工具、收 `CANCELLED`、`activeTask` 清空。
- [ ] 正常完成 → `SUCCEEDED`；注入流错误 → `FAILED`。
- [ ] TUI 路径不受 `run()` 签名变更破坏（同步 `MewCodeModel` 调用点或保留兼容重载）。

---

## 4. 阶段 2 —— 双向交互：问询 + 指令

### 4.1 目标（较上一版扩展）
用户在运行中与 sidecar 的交互分两类：

| 类型 | 含义 | 处理方 | 是否打断主 Agent |
| --- | --- | --- | --- |
| **Question（问询）** | 询问细节、进度、为什么等待 | sidecar 直接答（事件时间线 + 小模型） | 否 |
| **Directive（指令）** | 要求改动：追加 / 约束 / 重规划 | 投递主 Agent（检查点） | 是（检查点注入） |

`DirectiveType` 因此只保留 `APPEND / CONSTRAINT / REPLAN`；`QUERY` 从指令类型中移除，独立为 Question 概念。

### 4.2 新建类

**`control/directive/Directive.java`**
```java
public enum DirectiveType { APPEND, CONSTRAINT, REPLAN }
public enum DirectiveStatus { RECEIVED, DELIVERED, ACKNOWLEDGED }

public record Directive(
    String id,                 // "dir_" + nanoTime
    String taskId,
    String originalText,       // 用户原文（不可被替换）
    DirectiveType type,
    DirectiveStatus status,
    long createdAtEpochMs,
    String modelSummary,       // 小模型整理结果（附加字段）
    String agentResponse       // 主 Agent 的 ADOPT/DEFER/CONFLICT 回应
) {}
```

**`control/directive/DirectiveInbox.java`**
```java
public final class DirectiveInbox {
    private final java.util.concurrent.ConcurrentLinkedQueue<Directive> pending = new ConcurrentLinkedQueue<>();
    private final JsonlStore store;
    public Directive submit(String taskId, String text, DirectiveType type);
    public java.util.List<Directive> drain();
    public void markDelivered(String id);
    public void markAcknowledged(String id, String agentResponse);
    public java.util.List<Directive> allForTask(String taskId);
}
```

**`control/directive/DirectiveService.java`**
```java
public final class DirectiveService {
    private final DirectiveInbox inbox;
    private java.util.function.BiFunction<String,String,DirectiveType> classifier; // 阶段5 换小模型
    public Directive submit(String taskId, String text);
    public java.util.List<Directive> deliver();
    public void acknowledge(String id, String response);
}
```

### 4.3 修改 `agent/Agent.java`
- 新增 `setDirectiveInbox(...)`（对齐 `setNotificationFn` 思路）。
- 检查点排空 inbox，逐条注入 `system-reminder` + 发布 `DELIVERED`：
```
<system-reminder>
用户在运行中追加要求（type=CONSTRAINT），原文如下，必须遵守：
"""
<originalText>
"""
（可选）小模型整理：<modelSummary>
请在回复开头用一行结构化标记回应：
  [DIRECTIVE:ADOPT] 采用   [DIRECTIVE:DEFER] 推迟   [DIRECTIVE:CONFLICT] 冲突
</system-reminder>
```
- `EventBridge` 解析下一个 assistant 流文本中的 `[DIRECTIVE:*]` → `markAcknowledged` + 发布 `ACKNOWLEDGED`。

> 待决：ACKNOWLEDGED 用结构化标记约定（默认，最轻）还是新增 `RespondToDirective` 工具（更稳）。默认标记约定，工具列为回退项。

### 4.4 问询（Question）处理 —— 归 ReportService
问询不走 `DirectiveInbox`，由 `ReportService.answerQuestion(...)`（见第 6 节）基于事件时间线 + 小模型直接回答，落盘 `qa.jsonl`。

### 4.5 修改 `remote/RemoteServer.java`
```java
var h = activeTask.get();
if (h != null && !h.isDone()) {
    if (isQuestion(content)) {   // 前端区分 send_question / send_directive
        reportService.answerQuestion(h.taskId(), content);  // 异步回 sidecar_answer
    } else {
        var d = directiveService.submit(h.taskId(), content);
        broadcast("directive_received", {id, status});
    }
    return;
}
// 否则启动新任务
```
WS 新增 `send_question`、`send_directive`、`pause`、`resume`。

### 4.6 验证清单
- [ ] 问询 → sidecar 直接答，主 Agent 不中断。
- [ ] 指令 → `RECEIVED → DELIVERED → ACKNOWLEDGED`，落盘 directives.jsonl。
- [ ] 任务已结束再发指令 → 提示"创建后续任务"。

---

## 5. 阶段 3 —— 事实事件闭环（润色呈现的原料）

### 5.1 目标
事实事件统一序列化为 `EventEnvelope` 落盘 + 广播；断线按 `seq` 补拉；区分"模型准备调用"与"实际执行"。本阶段产出的是**润色呈现的原始事实**（主线 B 的原料）。

### 5.2 共享存储 `control/store/JsonlStore.java`
```java
public final class JsonlStore {
    public JsonlStore(Path file);
    public synchronized void append(Map<String,Object> record);
    public java.util.List<Map<String,Object>> readAll();
}
```
落盘目录 `.mewcode/sidecar/`，六个文件：
`tasks.jsonl / events.jsonl / directives.jsonl / approvals.jsonl / reports.jsonl / qa.jsonl`

### 5.3 新建类

**`control/event/EventType.java`**
```java
public enum EventType {
    TOOL_PROPOSED,      // 模型准备调用（参数生成，未执行）
    APPROVAL_WAITING,   // 进入审批等待
    TOOL_STARTED,       // 实际开始执行
    TOOL_FINISHED,      // 实际结束（结果/文件改动/耗时/测试结果）
    DIRECTIVE_STATUS,   // 指令状态变化
    TASK_TERMINAL       // 任务终态
}
```

**`control/event/EventEnvelope.java`**
```java
public record EventEnvelope(
    long seq, String taskId, EventType type,
    String toolCallId, long timestampEpochMs,
    Map<String,Object> payload) {}
```

**`control/event/EventStore.java`**
```java
public final class EventStore {
    public EventEnvelope append(String taskId, EventType type, String toolCallId, Map<String,Object> payload);
    public java.util.List<EventEnvelope> readSince(long afterSeq);
    public java.util.List<EventEnvelope> readByTask(String taskId);
    public long lastSeq();
}
```

**`control/event/EventBridge.java`**
```java
public final class EventBridge {
    private final EventStore store;
    private final java.util.Set<io.javalin.websocket.WsContext> connections;
    private final DirectiveInbox directiveInbox;  // 解析 [DIRECTIVE:*]

    public void onAgentEvent(AgentEvent e, String taskId, StringBuilder streamBuf);
    public void sendCatchup(long afterSeq, WsContext ctx);
}
```

### 5.4 事件映射表

| AgentEvent | EventType | payload（关键字段） |
| --- | --- | --- |
| `ToolUseEvent`（带 args） | `TOOL_PROPOSED` | `{toolName, args(脱敏截断)}` |
| `PermissionRequestEvent` | `APPROVAL_WAITING` | `{approvalId, description}` |
| 新增 `ToolStartEvent` | `TOOL_STARTED` | `{toolName}` |
| `ToolResultEvent` | `TOOL_FINISHED` | `{toolName, isError, elapsed, output(截断), filesModified?}` |
| `DirectiveStatusEvent` | `DIRECTIVE_STATUS` | `{directiveId, status}` |
| `TaskTerminalEvent` | `TASK_TERMINAL` | `{status, message, totalTurns}` |

`StreamingExecutor.executeSingle` 需在 `tool.execute()` 前新增 `AgentEvent.ToolStartEvent`——这是"实际开始"事实，与"准备调用"（TOOL_PROPOSED）区分。

### 5.5 修改 `agent/AgentEvent.java`
新增（保留现有 sealed 接口）：
```java
record ToolStartEvent(String toolId, String toolName) implements AgentEvent {}
record TaskTerminalEvent(com.mewcode.control.task.TaskStatus status, String message) implements AgentEvent {}
record DirectiveStatusEvent(String directiveId, com.mewcode.control.directive.DirectiveStatus status) implements AgentEvent {}
```
含 `CompletableFuture` 的 `PermissionRequestEvent/AskUserRequestEvent` 不进 JSONL，只在进程内桥接审批。

### 5.6 修改 `remote/RemoteServer.java`
- `consumeAgentEvents` 主体改为 `eventBridge.onAgentEvent(...)`，广播走非阻塞（每连接虚拟线程 send）。
- 断线补拉：WS `resync {afterSeq}` → `sendCatchup`。
- 浏览器断开只 `connections.remove`，不中断执行。

### 5.7 验证清单
- [ ] 事件按 `PROPOSED → STARTED → FINISHED` 落盘，时间线可见。
- [ ] 断线重连 `resync` 补拉无重复/缺失。
- [ ] `StreamText` 不逐条落盘；页面慢不阻塞主执行。

---

## 6. 阶段 4 —— 权限三层链

### 6.1 目标（较上一版重构）
把原"规则引擎 → 用户"两段改为**三层判断链**：

```
主 Agent 提议工具调用
  → 第一层：主 Agent 规则引擎（PermissionChecker，确定性）
       DENY      → 硬拒绝（危险命令/denyWrite/明确 deny 规则），直接拒，不可逆
       ALLOW     → 明确允许（安全只读/allow 规则/allow-always）
       UNCERTAIN → 拿不准（ASK 地带），进入第二层
  → 第二层：sidecar 润色判断（低成本模型）
       输入：ToolInvocation + 第一层依据 + 脱敏上下文
       输出：解释（"这条命令会做什么"）+ 建议 allow / deny / unsure
       对第一层 ALLOW：复核 —— 无风险放行；发现风险 → 升级 UNCERTAIN → 用户
       对第一层 UNCERTAIN：只给解释 + 建议，不决策
  → 第三层：用户最终决策（审批卡片）
       展示：原始命令 + 第一层结论 + sidecar 润色解释 + 风险
       用户 allow / allow always / deny
```

**安全不变量（关键）**：sidecar 只能**收紧授权**（把第一层 ALLOW 复核后升级为需要用户，或对 UNCERTAIN 给建议），**不能放宽**（不能把 DENY 改放行，不能自行签发 ALLOW）。放行必须满足"第一层已允许"或"用户明确授权"。这符合方案第 6 节"小模型可解释但不能签发 ALLOW"。

### 6.2 术语映射（待确认）
- 用户说"主 Agent 做一层判断"。本设计映射为**主 Agent 侧的确定性规则引擎 `PermissionChecker`**（不额外增加主 LLM 往返，避免每个工具调用多一次模型调用）。
- 若用户希望**主 LLM 也参与自评**，可作后续增强：在 `WAITING_APPROVAL` 时把工具调用附进上下文让主 Agent 自评。列为本节待决项。

### 6.3 新建类

**`control/approval/ToolInvocation.java`**
```java
public record ToolInvocation(
    String taskId, String toolCallId, String toolName,
    Map<String,Object> args, String workDir, int policyVersion) {}
```

**`control/approval/ApprovalRequest.java`**
```java
public enum ApprovalStatus { PENDING, ALLOWED, DENIED, EXPIRED }
public record ApprovalRequest(
    String approvalId, ToolInvocation invocation, ApprovalStatus status,
    long createdAtEpochMs, long expiresAtEpochMs,
    String layer1Verdict, String sidecarExplanation, String sidecarSuggestion) {}
```

**`control/approval/ApprovalService.java`**
```java
public final class ApprovalService {
    private final JsonlStore store;
    public ApprovalRequest create(ToolInvocation inv, String layer1Verdict, java.time.Duration ttl);
    public void attachSidecar(String approvalId, String explanation, String suggestion);
    public boolean resolve(String approvalId, boolean allow, String taskId);
    // 校验：approvalId 存在、未过期、taskId 匹配；过期/重复响应返回 false（不放行另一动作）
    public boolean stillValid(ApprovalRequest req, ToolInvocation current);
}
```

### 6.4 修改 `agent/StreamingExecutor.java`（拦截点）
`executeSingle` 统一流程：
```
构造 ToolInvocation
  → token.isCancelled() → 返回取消结果
  → 第一层 checker.check(tool, args) → CheckResult(decision, reason, ruleSource, policyVersion)
       DENY → 发布 TOOL_FINISHED(isError) + 返回拒绝结果
       ALLOW → 进第二层复核（见下）
       ASK   → 第二层润色解释（异步）→ 用户审批
  → 第二层（sidecar）：reportService.explainInvocation(inv) → 解释 + 建议
       ALLOW 复核：建议 allow → 执行；建议 unsure → 升级用户审批
       ASK 润色：附解释 + 建议到审批卡片，等用户
  → 第三层：用户审批（allow/allow always/deny），resolve 后再次校验（策略版本/参数未变）再执行
```
- `ALLOW_ALWAYS` 保留：写回 `checker.addAllowAlwaysRule`（进策略版本），审批记录本身绑定具体操作。
- `pendingPerms` 的 key 由 `"perm_"+nanoTime` 改为 `approvalId`，持久化 `approvals.jsonl`。

### 6.5 修改 `permission/PermissionChecker.java`（两处缺陷 + 可解释结果）
1. **拒绝优先级提前**：`DANGEROUS_PATTERNS` 与 `denyWrite` 提到 `SAFE_COMMANDS` 之前。新顺序：
```
Layer 0  Plan mode 例外
Layer 1  Dangerous command 检测 (DENY)
Layer 1b denyWrite 保护路径 (DENY)
Layer 2  File-based deny 规则 (DENY，先于 allow)
Layer 3  Safe command 自动允许（收紧后）
Layer 4  Path sandbox (ASK)
Layer 5  File-based allow/ask 规则 + allow-always + mode matrix
```
2. **收紧安全列表**：移除 `npx/tee/xargs/sed/awk`；`git` 只保留只读子命令，`reset/checkout/clean` 绝不进安全列表。不按前缀判定安全，`isSafeCommand` 需检查整条命令无管道/重定向/命令替换且本体在白名单。
3. **可解释结果**：
```java
public record CheckResult(PermissionMode.Decision decision, String reason,
                          String ruleSource, int policyVersion) { ... }
// ruleSource: SAFE_COMMAND / DANGEROUS_PATTERN:<p> / DENY_WRITE:<path> / FILE_RULE:<pattern> / MODE_MATRIX / ALLOW_ALWAYS
```
4. **策略版本**：`policyVersion()` 在 `addAllowAlwaysRule/appendLocalRule` 时自增。

### 6.6 修改 `remote/RemoteServer.java`
- `handlePermissionResponse(id, response)` → `approvalService.resolve(id, allow, activeTaskId)`；失败回 `approval_invalid`。

### 6.7 验证清单
- [ ] `sed/awk/tee/npx` 不再自动放行。
- [ ] deny 规则能拦截原本被安全列表放行的命令。
- [ ] sidecar 对第一层 ALLOW 复核能升级为审批。
- [ ] 审批超时默认拒绝；过期/重复响应不放行。
- [ ] 参数/策略版本变化后原审批失效，需重审。
- [ ] `approvals.jsonl` 记录 layer1Verdict / sidecar 解释建议 / 用户决策，可审计。

---

## 7. 阶段 5 —— 润色呈现 + 报告 + 问答

### 7.1 目标（较上一版扩展）
`ReportService` 承担三个职责，全部基于事件时间线 + 低成本模型：

| 职责 | 输入 | 输出 | 时机 |
| --- | --- | --- | --- |
| **进度报告** | 近期脱敏事件 + 上份摘要 | 阶段/完成项/阻塞点/evidenceEventIds | 约每 15s 合并 |
| **最终报告** | 整个任务事件流 | 总结 + 改动文件 + 耗时分布 + 结果 | 任务终态时 |
| **问答** | 用户问题 + 事件时间线 | 直接回答（含事件引用） | 用户提问时 |
| **润色解释** | ToolInvocation + 第一层结论 | "这条命令会做什么" + 建议 | 权限第二层 |

### 7.2 `control/report/ReportService.java`
```java
public final class ReportService {
    public record Report(String kind /*progress|final*/, String stage,
                         java.util.List<String> done, java.util.List<String> blockers,
                         java.util.List<Long> evidenceEventIds) {}

    public ReportService(LlmClient smallModel, java.time.Duration minInterval,
                         java.time.Duration timeout, int maxRetries);

    public void onEvents(java.util.List<EventEnvelope> recent);          // 攒批
    public java.util.concurrent.CompletableFuture<Report> generateProgress(String taskId);
    public java.util.concurrent.CompletableFuture<Report> generateFinal(String taskId);

    public java.util.concurrent.CompletableFuture<String> answerQuestion(String taskId, String question);
    public record InvocationExplanation(String explanation, String suggestion); // allow/deny/unsure
    public java.util.concurrent.CompletableFuture<InvocationExplanation> explainInvocation(ToolInvocation inv, String layer1Verdict);
}
```
- 独立小模型配置（新增便宜 `ProviderConfig`/`LlmClient`，不与主模型共用），只做摘要/分类/解释/问答，**不执行命令、不签发授权**。
- 输入脱敏：事件 payload 命令/文件内容按规则截断/打码。
- 失败降级：超时/429/不可用 → 返回空/降级文本，保留事实时间线，不拖主任务、不扩大授权。
- 报告落盘 `reports.jsonl`，问答落盘 `qa.jsonl`。
- 进度报告与最终报告 → 界面"进度报告区"/"最终报告区"；问答 → 界面"Sidecar 输入框"下方直接显示。

### 7.3 验证清单
- [ ] 进度报告 ≥15s 才触发一次。
- [ ] 最终报告含改动文件 + 耗时分布 + 结果，可复制 Markdown / 下载。
- [ ] 问答基于事件时间线回答，可引用 evidenceEventIds。
- [ ] 小模型超时/429/不可用 → 主任务不受影响，时间线完整。

---

## 8. 阶段 6 —— 独立 Sidecar 进程（概要，暂缓）

以 `DirectiveService / ApprovalService / EventStore / ReportService` 为 seam，把 Java 方法调用换成本地受认证 HTTP/WS：loopback token 认证、幂等重传（事件/审批带 id 去重）、断连重连、sidecar 崩溃不影响主 Agent 落盘的事实记录。协议留待阶段 5 后单独设计。

---

## 9. WebSocket 协议汇总

### 客户端 → 服务端
| type | data | 说明 |
| --- | --- | --- |
| `user_message` | `{content}` | 空闲 → 启动任务 |
| `send_question` | `{content}` | 问询，sidecar 直接答 |
| `send_directive` | `{content}` | 改动指令，投递主 Agent |
| `cancel` / `pause` / `resume` | `{}` | 任务控制（确定性接口） |
| `permission_response` | `{id: approvalId, response}` | 审批回复 |
| `resync` | `{afterSeq}` | 断线补拉事件 |

### 服务端 → 客户端
| type | data | 说明 |
| --- | --- | --- |
| `directive_received` / `directive_status` | `{id, status}` | 指令回执/状态 |
| `sidecar_answer` | `{question, answer, evidenceEventIds}` | 问询回答 |
| `event` | `EventEnvelope` JSON | 事实事件（含 seq） |
| `approval_waiting` | `{approvalId, layer1Verdict, sidecarExplanation, sidecarSuggestion, description}` | 三层审批卡片 |
| `approval_invalid` | `{approvalId}` | 审批过期/不匹配 |
| `progress_report` / `final_report` | `Report` JSON | 报告 |
| `task_terminal` | `{status, message, totalTurns}` | 终态 |
| `tool_use` / `tool_result` / `stream_text` 等 | 保留 | 兼容现有前端 |

---

## 10. 关键时序

### 10.1 权限三层链
```
StreamingExecutor.executeSingle
  → ToolInvocation(含 policyVersion)
  → 第一层 checker.check → CheckResult(decision, reason, ruleSource, policyVersion)
       DENY → 直接拒
       ALLOW → reportService.explainInvocation → 建议 allow(执行) / unsure(升级用户)
       ASK   → explainInvocation → 审批卡片(含 layer1 + sidecar解释建议) → 等用户
  → 第三层 handlePermissionResponse(approvalId, allow) → resolve(校验过期/taskId/版本) → 执行或拒
```

### 10.2 问询与指令
```
问询 send_question → reportService.answerQuestion(taskId, text) → sidecar_answer（主Agent不打断）
指令 send_directive → directiveService.submit → directive_received
Agent 检查点 → inbox.drain → 注入 system-reminder + DELIVERED
模型回复 [DIRECTIVE:*] → EventBridge 解析 → ACKNOWLEDGED
```

### 10.3 报告
```
事件 → EventStore.append → EventBridge.onEvents(recent)
progress: 每 ≥15s generateProgress → progress_report
final: TaskTerminalEvent → generateFinal → final_report（可复制/下载）
```

---

## 11. 实施顺序与验收

1. 执行生命周期（阶段 1）→ 2. 双向交互（阶段 2）→ 3. 事实事件（阶段 3）→ 4. 权限三层链（阶段 4）→ 5. 润色呈现 + 报告 + 问答（阶段 5）→ 6. 独立进程（阶段 6，暂缓）。

**首版验收场景**：
> 主 Agent 修复 bug；受限工具出现三层审批（主Agent判断 + sidecar 解释建议 + 用户决策）；用户中途补要求（页面显示收件与送达）；用户向 sidecar 提问细节得到直接回答；用户取消后实际执行停止；浏览器断线重连能看到此前事件；任务结束产出最终报告。

---

## 12. 待决事项

1. **"主 Agent 第一层判断"的映射**：本设计映射为确定性规则引擎 `PermissionChecker`。是否需要主 LLM 也参与自评（后续增强）？
2. **ACKNOWLEDGED 捕获方式**：结构化标记约定（默认） vs `RespondToDirective` 工具（更稳）。
3. **sidecar 第二层能否自动放行**：本设计守住"只能收紧、不能放宽"，sidecar 对第一层 ALLOW 复核无风险才放行。是否接受？
4. **PAUSED 语义**：暂停粒度（仅阻止新工具 vs 挂起整个循环）。
5. **Bash 外部进程取消**：`Thread.interrupt` 杀不掉子进程，需评估 `BashTool` 是否传递取消句柄。
6. **广播非阻塞方案**：每连接队列 vs 虚拟线程直接 send。
7. **`Agent.run` 签名变更对 TUI 影响**：同步 `MewCodeModel` 调用点或保留兼容重载。
