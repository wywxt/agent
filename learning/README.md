# MewCode 源码学习文档

> MewCode 是一个用 Java 21 编写的 LLM 编程助手 CLI 工具（类 Claude Code），约 134 个源文件、23,000 行代码。

## 📚 文档目录

| 序号 | 模块 | 文档 | 说明 |
|------|------|------|------|
| 0 | 总览 | [00-架构总览.md](00-架构总览.md) | 项目整体架构、数据流、设计理念 |
| 1 | 入口 | [01-入口与启动.md](01-入口与启动.md) | MewCode.main() 三种运行模式 |
| 2 | Agent | [02-Agent对话引擎.md](02-Agent对话引擎.md) | agentLoop() 主循环详解 |
| 3 | LLM | [03-LLM通信层.md](03-LLM通信层.md) | Anthropic/OpenAI/OpenAI Compat 客户端 |
| 4 | 压缩 | [04-上下文压缩系统.md](04-上下文压缩系统.md) | Layer 1 工具输出裁剪 + Layer 2 LLM 摘要 |
| 5 | 工具 | [05-工具系统.md](05-工具系统.md) | Tool 接口、注册、延迟加载、内置工具 |
| 6 | 权限 | [06-权限系统.md](06-权限系统.md) | 6 层决策链、安全命令、路径沙箱 |
| 7 | 对话 | [07-对话管理.md](07-对话管理.md) | ConversationManager、消息结构 |
| 8 | 子Agent | [08-子Agent系统.md](08-子Agent系统.md) | Fork/Sync/Async/Team 四种执行路径 |
| 9 | 团队 | [09-多Agent协作.md](09-多Agent协作.md) | TeamManager、文件邮箱通信 |
| 10 | 记忆 | [10-记忆系统.md](10-记忆系统.md) | 记忆提取、存储、整理、注入 |
| 11 | Skill | [11-Skill技能系统.md](11-Skill技能系统.md) | 三级加载、inline/fork 执行 |
| 12 | MCP | [12-MCP集成.md](12-MCP集成.md) | Stdio/HTTP MCP 服务器连接 |
| 13 | Hook | [13-Hook钩子系统.md](13-Hook钩子系统.md) | 9 事件 4 动作、条件表达式引擎 |
| 14 | TUI | [14-TUI终端界面.md](14-TUI终端界面.md) | 自研 Elm Architecture、终端渲染 |
| 15 | Remote | [15-Remote远程模式.md](15-Remote远程模式.md) | WebSocket 桥接、Web UI |
| 16 | Session | [16-Session会话管理.md](16-Session会话管理.md) | JSONL 持久化、压缩边界、resume |
| 17 | 配置 | [17-配置系统.md](17-配置系统.md) | 三级配置合并、Provider 配置 |
| 18 | Prompt | [18-Prompt构建.md](18-Prompt构建.md) | System Prompt 组装、环境检测 |
| 19 | Worktree | [19-Worktree隔离.md](19-Worktree隔离.md) | Git Worktree 创建/清理 |
| 20 | Sandbox | [20-Sandbox沙箱.md](20-Sandbox沙箱.md) | Bubblewrap/Seatbelt 系统级沙箱 |

## 📖 阅读建议

- **快速入门**：先读 00 架构总览 → 01 入口 → 02 Agent 引擎
- **深入核心**：03 LLM 通信 → 04 上下文压缩 → 05 工具系统
- **理解安全**：06 权限系统 → 20 沙箱
- **高级特性**：08 子Agent → 09 多Agent → 10 记忆 → 11 Skill

## 🔗 源文件索引

```
src/main/java/com/mewcode/
├── MewCode.java                  # 入口
├── agent/                        # Agent 核心循环
│   ├── Agent.java
│   ├── AgentEvent.java
│   └── StreamingExecutor.java
├── command/                      # 斜杠命令
│   ├── Command.java
│   ├── CommandRegistry.java
│   └── CommandLoader.java
├── compact/                      # 上下文压缩
│   ├── ContextCompactor.java
│   └── RecoveryState.java
├── config/                       # 配置加载
│   ├── ConfigLoader.java
│   ├── AppConfig.java
│   └── ProviderConfig.java
├── conversation/                 # 对话管理
│   ├── ConversationManager.java
│   └── Message.java
├── hook/                         # Hook 引擎
│   └── HookEngine.java
├── llm/                          # LLM 客户端
│   ├── LlmClient.java
│   ├── AnthropicClient.java
│   ├── OpenAiClient.java
│   └── OpenAiCompatClient.java
├── mcp/                          # MCP 集成
│   └── McpManager.java
├── memory/                       # 记忆系统
│   ├── MemoryManager.java
│   ├── MemoryConsolidator.java
│   └── MemoryRecall.java
├── permission/                   # 权限检查
│   └── PermissionChecker.java
├── prompt/                       # Prompt 构建
│   └── PromptBuilder.java
├── remote/                       # 远程服务器
│   └── RemoteServer.java
├── session/                      # 会话持久化
│   └── SessionManager.java
├── skill/                        # Skill 系统
│   ├── SkillCatalog.java
│   └── SkillExecutor.java
├── subagent/                     # 子 Agent
│   ├── AgentTool.java
│   └── SubAgentSpec.java
├── teams/                        # 多 Agent 团队
│   ├── TeamManager.java
│   └── TeammateRunner.java
├── tool/                         # 工具系统
│   ├── Tool.java
│   ├── ToolRegistry.java
│   └── impl/                     # 工具实现
├── toolresult/                   # 工具结果管理
│   ├── ToolResultBudget.java
│   └── ContentReplacementState.java
├── tui/                          # TUI 界面
│   ├── MewCodeModel.java
│   └── tea/                      # TEA 框架
└── worktree/                     # Worktree 隔离
    └── WorktreeManager.java
```
