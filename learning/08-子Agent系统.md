# 08 - 子 Agent 系统

## 源码位置

- `src/main/java/com/mewcode/subagent/AgentTool.java` (593 行) — 核心实现
- `src/main/java/com/mewcode/subagent/SubAgentSpec.java` — Agent 规格定义
- `src/main/java/com/mewcode/subagent/SubAgentTaskManager.java` — 后台任务管理
- `src/main/java/com/mewcode/subagent/ToolFilter.java` — 工具过滤
- `src/main/java/com/mewcode/subagent/AgentLoader.java` — Agent 定义加载

## 一、AgentTool 的四种执行路径

```
Agent.execute(args)
    │
    ├─ team_name 参数? ──────────────────→ runAsTeammate()
    │   创建长期运行的 team member，有自己的终端
    │
    ├─ subagent_type 为空? ──────────────→ runFork()
    │   克隆父对话，继承完整工具池，后台执行
    │
    ├─ run_in_background: true? ─────────→ runAsync()
    │   新对话 + 过滤工具，后台执行，通过通知返回结果
    │
    └─ 默认 ─────────────────────────────→ runSync()
        新对话 + 过滤工具，阻塞等待结果
```

## 二、Fork 模式（runFork）

### 用途
Agent 需要并行执行多个独立任务时的"分身"机制。

### 实现

```java
private ToolResult runFork(String description, String prompt, String modelOverride) {
    // ★ 双重嵌套防护
    // 防护 1: querySource 标记（压缩安全）
    if (FORK_QUERY_SOURCE.equals(querySource)) {
        return error("cannot fork from a forked agent");
    }
    // 防护 2: 对话内容扫描
    for (var msg : parentConversation.getMessages()) {
        if (msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
            return error("cannot fork from a forked agent");
        }
    }

    // 构建分叉对话：复制父对话 + fork 模板 + 任务提示
    ConversationManager forkedConv = buildForkedConversation(parentConversation, prompt);

    // fork 子 Agent 获得完整工具集（clone for fork）
    ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);

    // 后台启动
    String taskId = taskManager.spawnForkAgent(subClient, forkedRegistry, ...);
    return success("Forked agent launched in background (task %s)".formatted(taskId));
}
```

### Fork 模板

```text
<fork_boilerplate>
You are a forked worker process. You are NOT the main agent.
Rules (non-negotiable):
1. Do NOT fork again.
2. Do NOT converse, ask questions, or request confirmation.
3. Use tools directly: read files, search code, make changes.
4. Stay strictly within your assigned task scope.
5. Final report must be under 500 characters, starting with "Scope:".
</fork_boilerplate>

Your task:
{prompt}
```

### 对话复制策略

```java
private static ConversationManager buildForkedConversation(ConversationManager parent, String task) {
    for (var msg : parent.getMessages()) {
        if (msg 有 tool_use 但无 tool_result) {
            // 父 Agent 正在执行工具（未完成的 tool call）
            // → 补充占位结果，防止 orphan
            forkedConv.addAssistantFull(...);
            forkedConv.addToolResultsMessage(placeholders);
        } else {
            // 正常复制
            appendMessage(forkedConv, msg);
        }
    }
    forkedConv.addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task);
}
```

## 三、同步子 Agent（runSync）

### 工具过滤

```java
ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
```

根据 `SubAgentSpec` 定义的工具策略过滤：
- `GENERAL_PURPOSE` — 全部工具
- `PLAN` — 只读工具（不允许 EditFile/WriteFile/Bash）
- `EXPLORE` — 只读 + 搜索工具
- 自定义 Agent — 按 spec.tools() 白名单过滤

### Worktree 隔离

```java
if ("worktree".equals(isolation) && worktreeManager != null) {
    var wtResult = AgentWorktree.create(slug, projectRoot, symlinkDirs);
    subAgent.setWorkDir(wtResult.worktreePath());
    prompt = worktreeNotice + "\n\n" + prompt;  // 告知子Agent 在独立目录中工作
}

// 执行完成后清理
if (!WorktreeChanges.hasChanges(wtResult)) {
    AgentWorktree.remove(wtResult);  // 无变更则自动删除
} else {
    // 有变更则保留
}
```

### 事件消费

```java
BlockingQueue<AgentEvent> queue = subAgent.run(conv);

while (true) {
    event = queue.poll(60, TimeUnit.SECONDS);  // 60 秒超时
    switch (event) {
        case StreamText st → output.append(st.text())
        case ToolResultEvent tre → toolCount++; emitProgress(...)
        case ErrorEvent err → return error("Agent failed: " + err.message())
        case LoopComplete lc → return success(output, elapsed, worktreeInfo)
    }
}
```

## 四、后台子 Agent（runAsync）

```java
private ToolResult runAsync(SubAgentSpec spec, String description, String prompt, String modelOverride) {
    String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol, providerConfig, spec, prompt);
    return success("Agent launched in background (task %s)".formatted(taskId));
    // 结果通过 notificationFn 注入回父 Agent 的下一轮循环
}
```

## 五、Team Member 路径（runAsTeammate）

区别于普通子 Agent：
- 创建为 TeamManager 的长期成员
- 拥有独立的 terminal（tmux/iTerm2 pane）
- 可以与其他队友通过 SendMessage 通信
- 在 lead agent 返回后继续运行

详细见 [09-多Agent协作.md](09-多Agent协作.md)。

## 六、模型选择

```java
private LlmClient selectClient(String specModel, String overrideModel) {
    String model = overrideModel != null ? overrideModel : specModel;
    if (model == null || model.isEmpty() || "inherit".equals(model)) {
        return client;  // 继承父 Agent 的模型
    }
    if (modelResolver != null) {
        return modelResolver.apply(model);  // "haiku"/"sonnet"/"opus" → LlmClient
    }
    return client;
}
```

## 七、SubAgentSpec

```java
public record SubAgentSpec(
    String name,
    String description,
    String systemPromptOverride,
    List<String> tools,       // 允许的工具名称白名单
    int maxTurns,            // 最大轮次限制
    String model             // 默认模型
) {}
```

内置 Spec：
- `GENERAL_PURPOSE` — 全部工具，maxTurns=200
- `PLAN` — 只读工具
- `EXPLORE` — 只读+搜索工具

用户自定义 Spec 从 `.mewcode/agents/` 目录加载。
