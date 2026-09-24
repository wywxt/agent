# 15 - Remote 远程模式

## 源码位置

- `src/main/java/com/mewcode/remote/RemoteServer.java` (944 行)
- `src/main/java/com/mewcode/remote/WebContent.java` — 内嵌 Web UI HTML

## 一、架构概览

Remote 模式将 TUI 替换为 Web 浏览器界面，Agent 核心完全不变。

```
浏览器 (Web UI)
    ↕ WebSocket (JSON 消息协议)
Javalin (HTTP + WS 服务器, 监听 :18888)
    ↕ 消费 AgentEvent 队列
Agent (与 TUI 共享的核心, 100% 复用)
```

与 TUI 模式的关系：**RemoteServer 是 MewCodeModel 的镜像实现**。TUI 通过 Timer + poll 驱动 Agent 事件，RemoteServer 通过 WebSocket 消息驱动。

## 二、WebSocket 消息协议

### 浏览器 → 服务器

| 消息类型 | 数据 | 说明 |
|---------|------|------|
| `user_message` | `{content: "..."}` | 用户输入 |
| `permission_response` | `{id: "...", response: "allow"}` | 权限决策 |
| `ask_user_response` | `{id: "...", answers: {...}}` | 问答回复 |
| `cancel` | - | 中断当前流 |
| `ping` | - | 保活心跳 |

### 服务器 → 浏览器

| 消息类型 | 数据 | 说明 |
|---------|------|------|
| `stream_text` | `{text: "..."}` | 流式文本片段 |
| `thinking_text` | `{text: "..."}` | 思考过程 |
| `tool_use` | `{toolId, toolName, args}` | 工具调用 |
| `tool_result` | `{toolId, toolName, output, isError, elapsed}` | 工具结果 |
| `permission_request` | `{id, toolName, description}` | 权限请求 |
| `ask_user` | `{id, questions}` | 用户问答 |
| `turn_complete` | `{turn}` | 一轮结束 |
| `loop_complete` | `{totalTurns, elapsed}` | Agent 结束 |
| `usage` | `{inputTokens, outputTokens}` | Token 统计 |
| `error` | `{message}` | 错误信息 |
| `system` | `{message}` | 系统通知 |
| `compact` | `{message}` | 压缩通知 |
| `replay_user` / `replay_assistant` | `{content}` | 恢复历史 |
| `clear` | - | 清屏 |
| `commands` | `[{name, description}]` | 命令列表 |
| `command_done` | - | 命令完成 |

## 三、核心流程

### 用户消息处理

```java
private void handleUserMessage(String content) {
    if (streaming) return;  // 防止并发

    // 斜杠命令处理
    if (content.startsWith("/")) {
        handleSlashCommand(content);
        return;
    }

    streaming = true;
    // 保存到 session
    SessionManager.saveMessage(workDir, sessionId, "user", content);
    conversation.addUserMessage(content);

    // 首次消息注入 MCP 指令
    if (!mcpInstructions.isEmpty()) {
        conversation.addSystemReminder(mcpInstructions);
        mcpInstructions = "";
    }

    // 启动 Agent
    agentQueue = agent.run(conversation);
    consumeAgentEvents();  // 阻塞消费 + 广播到 WebSocket
    streaming = false;
}
```

### Agent 事件消费 + 广播

```java
private void consumeAgentEvents() {
    var streamBuf = new StringBuilder();

    while (true) {
        AgentEvent event = agentQueue.poll(30, TimeUnit.SECONDS);  // 30s 超时

        switch (event) {
            case StreamText e → {
                streamBuf.append(e.text());
                broadcast(type="stream_text", data={text: e.text()});
            }
            case ToolUseEvent e → {
                broadcast(type="tool_use", data={toolId, toolName, args});
            }
            case PermissionRequestEvent e → {
                // ★ 生成 ID → CompletableFuture → 等待前端回复
                String id = "perm_" + System.nanoTime();
                pendingPerms.put(id, e.future());
                broadcast(type="permission_request", data={id, toolName, description});
                // 前端显示按钮后回复 permission_response → future.complete()
            }
            case AskUserRequestEvent e → {
                String id = "ask_" + System.nanoTime();
                pendingAsks.put(id, e.future());
                broadcast(type="ask_user", data={id, questions});
            }
            case LoopComplete e → {
                // 保存最后一段文本到 session
                SessionManager.saveMessage(workDir, sessionId, "assistant", streamBuf.toString());
                broadcast(type="loop_complete");
                return;  // ← 退出循环
            }
        }
    }
}
```

### 权限桥接

TUI 模式显示终端弹窗 → Remote 模式通过 WebSocket 往返：

```
Agent 需要权限
  → PermissionRequestEvent
  → pendingPerms["perm_123"] = future
  → broadcast({type: "permission_request", id: "perm_123", ...})
  → [前端显示 Allow/Deny/Always Allow 按钮]
  → 用户点击 → WebSocket 发送 {type: "permission_response", id: "perm_123", response: "allow"}
  → handlePermissionResponse() → future.complete(PermissionResponse.ALLOW)
  → Agent 继续执行
```

### 命令处理

支持与 TUI 模式相同的命令系统：

```java
// /clear  → 重置 ConversationManager → broadcast("clear")
// /compact → 强制压缩 → broadcast system 消息 + command_done
// /plan    → 进入计划模式
// /resume  → 恢复历史会话（加载 JSONL → 重建对话 → 广播 replay 消息）
```

## 四、Web UI

`WebContent.INDEX_HTML` 内嵌完整的单页应用 HTML，包含：
- Chat 消息列表（支持 Markdown 渲染）
- 输入框 + 发送按钮
- 斜杠命令菜单
- 权限对话框
- AskUser 问答界面
- 工具调用展开/折叠面板

## 五、Agent 初始化

`RemoteServer.initAgent()` 与 `MewCodeModel.initializeProvider()` 执行**完全相同**的初始化流程。大约 140 行代码重复（这是设计缺陷，两次维护相同的逻辑）。

## 六、线程模型

```
主线程：Javalin HTTP 监听
  ├── WebSocket 读线程 → handleWsMessage() → 启动虚拟线程处理用户消息
  ├── streamThread → consumeAgentEvents() → 阻塞等待 Agent 事件
  └── 后台线程 → 内存 + 后台子 Agent 任务
```
