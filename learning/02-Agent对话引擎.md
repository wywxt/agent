# 02 - Agent 对话引擎

## 源码位置

- `src/main/java/com/mewcode/agent/Agent.java` (465 行)
- `src/main/java/com/mewcode/agent/AgentEvent.java`
- `src/main/java/com/mewcode/agent/StreamingExecutor.java`

## 功能概述

Agent 是所有交互的核心引擎。它实现了一个**无限循环的"思考-行动"模式**：发送对话给 LLM → 解析响应 → 执行工具调用 → 把结果写回对话 → 继续下一轮，直到 LLM 不再发起工具调用。

## 核心方法：agentLoop()

`Agent.java:143-446`，约 300 行的主循环。

### 整体流程图

```
┌────────────────────────────────────────────────────────────────┐
│  for (iteration = 1; ; iteration++) {                          │
│                                                                 │
│  1. 检查 maxIterations 上限                                     │
│  2. Drain 后台任务通知 → 注入为 system reminders                 │
│  3. 计算本轮的 tool schemas (过滤 deferred tools)               │
│  4. 注入 deferred tool names 提示                                │
│  5. Plan mode 注入工作流提醒                                    │
│  6. ★ Layer 1: ToolResultBudget — 超大工具结果溢写磁盘          │
│  7. ★ Layer 2: ContextCompactor — Token 超限对话压缩            │
│  8. client.stream(conv, tools) — 发起 LLM 请求                  │
│  9. ★ 消费流事件 (30s timeout poll)                              │
│ 10. ★ 错误恢复 (context too long / rate limit / other)         │
│ 11. ★ max_tokens 处理 (escalation + 3次恢复)                    │
│ 12. 保存 assistant 消息 + 更新 UsageAnchor                       │
│ 13. 无工具调用 → send LoopComplete → break                      │
│ 14. 有工具调用 → 执行 → 结果写入 conv → 注入 memory recall     │
│ 15. 检测 ExitPlanMode → break                                   │
│  }                                                              │
└────────────────────────────────────────────────────────────────┘
```

### 步骤 1-5：迭代准备

```java
// 注入 deferred tool names，告诉 Agent 这些工具需要通过 ToolSearch 发现
var deferredNames = registry.getDeferredToolNames();
if (!deferredNames.isEmpty()) {
    conv.addSystemReminder("The following deferred tools are available via ToolSearch...");
}

// Plan mode 下注入结构化工作流提醒
if (checker != null && checker.getMode() == PermissionMode.PLAN) {
    String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
    conv.addSystemReminder(reminder);
}
```

### 步骤 6-7：双层上下文压缩

```java
// Layer 1: 就地修改 conversation 中超限的 tool_result
List<ContentReplacementRecord> newRecords =
    ToolResultBudget.apply(conv, sessionDir, replacementState);

// Layer 2: 检查是否需要 LLM 摘要压缩
String compactMsg = ContextCompactor.manage(
    conv, client, contextWindow, maxOutput, workDir, sessionId,
    compactTracking, recoveryState, iterToolSchemas, usageAnchor,
    conv.getMessages()  // ← 传入 budget 裁剪后的消息做精确估算
);
```

关键细节：Layer 2 使用 Layer 1 裁剪**后**的消息做 token 估算，减少误判。

### 步骤 9：流事件消费

```java
while (true) {
    event = streamQueue.poll(30, TimeUnit.SECONDS); // 30 秒超时防止卡死
    if (event == null) {
        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
        return;
    }

    switch (event) {
        case TextDelta td -> {
            text.append(td.text());
            putSafe(queue, new AgentEvent.StreamText(td.text()));  // → TUI 实时刷新
        }
        case ThinkingDelta td -> {}  // 思考过程（不保存到对话历史）
        case ToolCallComplete tcc -> {
            toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
        }
        case StreamEnd se -> {
            stopReason = se.stopReason();
            turnInput = se.inputTokens();      // ← 记录真实 token 用量
            turnOutput = se.outputTokens();
        }
        case Error err -> {
            streamError = true;  // 标记为错误，后续触发恢复
        }
    }
    if (event instanceof StreamEnd || event instanceof Error) break;
}
```

### 步骤 10：错误恢复机制

```java
if (streamError) {
    // 错误分类 → 三种恢复策略
    if (lastErr.contains("context") || lastErr.contains("too long")) {
        // 策略 1: 上下文过长 → 强制压缩 + 重试（最多 3 次）
        if (contextRetries < 3) {
            contextRetries++;
            ToolResultBudget.apply(conv, ...);  // 先裁剪
            ContextCompactor.forceCompact(conv, ...);  // 再压缩
            continue;  // 回到循环起点
        }
    }
    if (lastErr.toLowerCase().contains("rate limit")) {
        // 策略 2: 速率限制 → 等待 5 秒后重试
        Thread.sleep(5000);
        continue;
    }
    // 策略 3: 其他错误 → 终止循环
    break;
}
```

### 步骤 11：max_tokens 处理

```java
if ("max_tokens".equals(stopReason)) {
    if (!maxTokensEscalated) {
        // 首次触发：将 max_output 提升到 64K 上限
        maxTokensEscalated = true;
        client.setMaxOutputTokens(64_000);
        conv.addUserMessage("Output token limit hit. Resume directly...");
        continue;  // 重试
    } else if (outputRecoveries < 3) {
        // 后续触发：最多允许 3 次恢复
        outputRecoveries++;
        conv.addUserMessage("Output token limit hit. Resume...");
        continue;
    }
    // 3 次用尽 → 正常退出
}
```

### 步骤 12-15：工具执行与结果处理

```java
// 保存 assistant 消息（含 thinking blocks + tool_use blocks）
var toolUseBlocks = toolCalls.stream()
    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
    .toList();
conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

// 更新 UsageAnchor（用于下一轮压缩判断）
if (turnInput > 0 || turnOutput > 0) {
    int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
    usageAnchor = new UsageAnchor(baseline, conv.size());
}

// 无工具调用 → 对话结束
if (toolCalls.isEmpty()) {
    putSafe(queue, new AgentEvent.LoopComplete(iteration));
    break;
}

// 执行工具 → 通过 StreamingExecutor 逐个执行
var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
var results = executor.executeAll(callInfos);

// 结果写回对话
var resultBlocks = results.stream()
    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
    .toList();
conv.addToolResultsMessage(resultBlocks);
```

## 关键设计

### UsageAnchor 锚定机制

```java
// ContextCompactor.UsageAnchor
public record UsageAnchor(int baselineTokens, int anchorCount) {}
```

每次流式请求结束后，记录：
- `baselineTokens` = 该轮 API 返回的 real input + cacheRead + cacheCreation + output
- `anchorCount` = 当时的对话消息数量

下一轮压缩判断时：`estimatedTokens = baselineTokens + estimateOnly(新增消息)`。即使有 Prompt Cache Hit（真实 input 远小于字符估算），也不会误判。

### Memory Recall 异步注入

```java
// 非阻塞：memory recall prefetch 与主 LLM 调用并行
if (memoryRecallFuture != null && !memoryRecallConsumed) {
    if (memoryRecallFuture.isDone()) {
        String recall = memoryRecallFuture.getNow("");
        if (recall != null && !recall.isEmpty()) {
            conv.addSystemReminder(recall);  // 在第 1 轮工具执行后注入
        }
        memoryRecallConsumed = true;
    }
}
```

## AgentEvent 事件类型

Agent 通过 `BlockingQueue<AgentEvent>` 与上层（TUI/Remote）通信：

| 事件 | 说明 |
|------|------|
| `StreamText` | 流式文本片段 |
| `ThinkingText` / `ThinkingComplete` | 思考内容 |
| `ToolUseEvent` | 工具调用（含参数） |
| `ToolResultEvent` | 工具执行结果 |
| `TurnComplete` | 一轮对话结束 |
| `LoopComplete` | Agent 循环结束 |
| `ErrorEvent` | 错误信息 |
| `UsageEvent` | Token 用量统计 |
| `CompactEvent` | 压缩通知 |
| `RetryEvent` | 重试通知（含等待时间） |
| `PermissionRequestEvent` | 权限请求 |
| `AskUserRequestEvent` | 用户询问 |

## 参数配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `MAX_TOKENS_CEILING` | 64,000 | max_tokens 升级上限 |
| `MAX_OUTPUT_RECOVERIES` | 3 | 最多恢复次数 |
| streamQueue timeout | 30s | SSE 事件超时 |
| maxIterations | 无限制（0） | 最大迭代次数 |
