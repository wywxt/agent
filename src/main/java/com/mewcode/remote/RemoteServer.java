// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.command.Command;
import com.mewcode.command.CommandContext;
import com.mewcode.command.CommandRegistry;
import com.mewcode.compact.ContextCompactor;
import com.mewcode.config.HookConfig;
import com.mewcode.config.McpServerConfig;
import com.mewcode.config.ProviderConfig;
import com.mewcode.control.approval.ApprovalService;
import com.mewcode.control.approval.ToolInvocation;
import com.mewcode.control.directive.DirectiveService;
import com.mewcode.control.event.EventBridge;
import com.mewcode.control.event.EventEnvelope;
import com.mewcode.control.event.EventStore;
import com.mewcode.control.report.ReportService;
import com.mewcode.control.store.JsonlStore;
import com.mewcode.control.task.TaskExecutionHandle;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.filehistory.FileHistory;
import com.mewcode.hook.HookEngine;
import com.mewcode.llm.LlmClient;
import com.mewcode.mcp.McpManager;
import com.mewcode.memory.MemoryManager;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.plan.PlanFile;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.session.SessionManager;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.subagent.AgentTool;
import com.mewcode.subagent.SubAgentTaskManager;
import com.mewcode.task.TaskList;
import com.mewcode.task.TaskTools;
import com.mewcode.teams.TeamManager;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.AskUserTool;
import com.mewcode.tool.impl.ToolSearchTool;
import com.mewcode.tui.dialog.AskUserDialog;
import com.mewcode.worktree.WorktreeManager;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remote Control 服务器，桥接 Agent 事件和 WebSocket 客户端。
 * 对标 Go 版 internal/remote/server.go 的完整功能集。
 */
public class RemoteServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 配置 ──────────────────────────────────────────────────────────
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpConfigs;
    private final List<HookConfig> hookConfigs;
    private final ProviderConfig reportProvider;
    private final String addr;

    // ── WebSocket 连接池 ──────────────────────────────────────────────
    private final ReentrantLock connLock = new ReentrantLock();
    private final Set<WsContext> connections = ConcurrentHashMap.newKeySet();

    // ── Agent 核心组件 ────────────────────────────────────────────────
    private Agent agent;
    private ConversationManager conversation;
    private ToolRegistry registry;
    private LlmClient client;
    private String sessionId;
    private FileHistory fileHistory;
    private PermissionChecker permChecker;

    // ── 流式状态 ──────────────────────────────────────────────────────
    private volatile boolean streaming;
    private BlockingQueue<AgentEvent> agentQueue;
    private final AtomicReference<TaskExecutionHandle> activeTask = new AtomicReference<>();
    private final ReentrantLock taskStartLock = new ReentrantLock();
    private DirectiveService directiveService;
    private EventBridge eventBridge;
    private EventStore eventStore;
    private ApprovalService approvalService;
    private ReportService reportService;

    // ── 权限和 ask_user 的待决响应 ────────────────────────────────────
    private final ReentrantLock pendingPermLock = new ReentrantLock();
    private final Map<String, CompletableFuture<PermissionResponse>> pendingPerms = new ConcurrentHashMap<>();

    private final ReentrantLock pendingAskLock = new ReentrantLock();
    private final Map<String, CompletableFuture<Map<String, String>>> pendingAsks = new ConcurrentHashMap<>();

    // ── 命令和功能模块 ────────────────────────────────────────────────
    private CommandRegistry cmdRegistry;
    private SkillCatalog skillCatalog;
    private MemoryManager memoryManager;
    private McpManager mcpManager;
    private TaskList taskList;
    private SubAgentTaskManager subAgentTaskManager;
    private TeamManager teamManager;
    private AskUserTool askUserTool;
    private HookEngine hookEngine;

    private String instructionsContent = "";
    private String memoryContent = "";
    private volatile String mcpInstructions = "";
    private final boolean enableCoordinatorMode;

    public RemoteServer(List<ProviderConfig> providers, List<McpServerConfig> mcpConfigs,
                        List<HookConfig> hookConfigs, String addr, boolean enableCoordinatorMode,
                        ProviderConfig reportProvider) {
        this.providers = providers;
        this.mcpConfigs = mcpConfigs;
        this.hookConfigs = hookConfigs;
        this.addr = addr;
        this.enableCoordinatorMode = enableCoordinatorMode;
        this.reportProvider = reportProvider;
    }

    /**
     * 启动 HTTP + WebSocket 服务器。
     * 初始化 Agent 后监听指定地址，阻塞直到服务器关闭。
     */
    public void run() throws Exception {
        // 初始化 Agent（复刻 TUI 的 initializeProvider 流程）
        initAgent();
        // 连接 MCP 服务器
        initMcpServers();

        // 解析监听地址（格式 ":18888" 或 "0.0.0.0:18888"）
        int port = parsePort(addr);

        Javalin app = Javalin.create()
                .get("/", ctx -> {
                    ctx.contentType("text/html; charset=utf-8");
                    ctx.result(WebContent.INDEX_HTML);
                })
                .ws("/ws", ws -> {
                    ws.onConnect(ctx -> {
                        connections.add(ctx);
                        // 新连接推送 session 信息
                        broadcast(Map.of(
                                "type", "connected",
                                "data", Map.of(
                                        "session", sessionId,
                                        "cwd", System.getProperty("user.dir")
                                )
                        ));
                        // 推送命令列表
                        broadcast(Map.of(
                                "type", "commands",
                                "data", buildCommandList()
                        ));
                    });
                    ws.onClose(ctx -> connections.remove(ctx));
                    ws.onMessage(ctx -> handleWsMessage(ctx, ctx.message()));
                })
                .start("0.0.0.0", port);

        System.out.printf("%n  Remote UI: http://localhost:%d%n%n", port);

        // 阻塞主线程，让服务器持续运行
        Thread.currentThread().join();
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 初始化（镜像 MewCodeModel.initializeProvider）
    // ────────────────────────────────────────────────────────────────────

    private void initAgent() {
        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = providers.get(0);

        // 记忆管理
        memoryManager = new MemoryManager(workDir);
        instructionsContent = MemoryManager.loadInstructions(workDir);

        // 构建系统提示词
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // 创建 LLM 客户端
        client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();

        // 工具注册
        registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));

        var exitPlanTool = new com.mewcode.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
        exitPlanTool.setPlanExists(() -> PlanFile.planExists());
        registry.register(exitPlanTool);

        // AskUser 工具：Remote 模式通过事件队列桥接到 WebSocket
        askUserTool = new AskUserTool();
        registry.register(askUserTool);

        // 子 Agent 工具
        var agentToolRef = new AgentTool(client, registry, protocol, providerCfg);
        subAgentTaskManager = new SubAgentTaskManager();
        agentToolRef.setTaskManager(subAgentTaskManager);
        registry.register(agentToolRef);

        // Worktree 工具
        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentToolRef.setWorktreeManager(worktreeManager);
        sessionId = SessionManager.newId();
        registry.register(new com.mewcode.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new com.mewcode.tool.impl.ExitWorktreeTool(worktreeManager));

        // 任务工具
        taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        // 团队工具
        teamManager = new TeamManager();
        agentToolRef.setTeamManager(teamManager);
        registry.register(new com.mewcode.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new com.mewcode.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new com.mewcode.teams.TeamTools.SendMessageTool(teamManager, "lead"));

        // 权限检查器
        permChecker = new PermissionChecker(PermissionMode.DEFAULT, Path.of(workDir));

        // Sidecar 指令服务（阶段 2）：运行中追加要求的收件与投递
        directiveService = new DirectiveService(new JsonlStore(
                Path.of(workDir, ".mewcode", "sidecar", "directives.jsonl")));

        // 事实事件存储与桥接（阶段 3）：每任务一个文件，落盘 + seq + 断线补拉
        eventStore = new EventStore(Path.of(workDir, ".mewcode", "sidecar", "events"));
        eventBridge = new EventBridge(eventStore);

        // 审批持久化（阶段 4）：绑定 taskId/参数/策略版本，过期与重复响应不放行
        approvalService = new ApprovalService(new JsonlStore(
                Path.of(workDir, ".mewcode", "sidecar", "approvals.jsonl")));

        // 小模型辅助（阶段 5）：报告/问询/权限润色解释，无 report_provider 时降级
        {
            com.mewcode.llm.LlmClient reportModel = null;
            if (reportProvider != null) {
                try {
                    reportModel = com.mewcode.llm.LlmClient.create(
                            reportProvider, "你是编码 Agent 的伴随 sidecar，负责进度汇报、咨询问答与权限解释。");
                } catch (Exception e) {
                    System.err.println("Report provider init failed, degrading: " + e.getMessage());
                }
            }
            reportService = new ReportService(reportModel, eventStore);
        }

        // 会话和文件历史
        fileHistory = new FileHistory(workDir, sessionId);
        var fileStateCache = new com.mewcode.tool.FileStateCache();
        for (var tool : registry.listTools()) {
            if (tool instanceof com.mewcode.tool.impl.EditFileTool ef) {
                ef.setFileHistory(fileHistory);
                ef.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.mewcode.tool.impl.WriteFileTool wf) {
                wf.setFileHistory(fileHistory);
                wf.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.mewcode.tool.impl.ReadFileTool rf) {
                rf.setFileStateCache(fileStateCache);
            }
        }

        // 构建 Agent
        conversation = new ConversationManager();
        agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setMemoryContent(memoryContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);
        agent.setDirectiveService(directiveService);
        agent.setReportService(reportService);

        // 通知函数：排空团队邮箱和任务通知
        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(com.mewcode.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                            .formatted(n.taskId(), n.name(), n.status()));
                }
            }
            return notes;
        });

        // 工具名过滤器（团队协调模式）
        agent.setToolNameFilter(name -> {
            if (!enableCoordinatorMode) return true;
            if (teamManager.listTeams().isEmpty()) return true;
            return com.mewcode.teams.Coordinator.isCoordinatorTool(name);
        });

        // 子 Agent 关联
        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {}); // remote 模式不需要 TUI 进度回调
        }

        // Hook 引擎
        hookEngine = new HookEngine();
        if (hookConfigs != null && !hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            hookEngine.loadHooks(hooks);
        }
        agent.setHookEngine(hookEngine);

        // Skill 加载
        skillCatalog = new SkillCatalog();
        var skillDir = Path.of(workDir, ".mewcode", "skills");
        if (Files.isDirectory(skillDir)) {
            skillCatalog.loadFromDirectory(skillDir);
        }

        // 命令注册
        cmdRegistry = new CommandRegistry();
    }

    // ────────────────────────────────────────────────────────────────────
    // MCP 服务器连接
    // ────────────────────────────────────────────────────────────────────

    private void initMcpServers() {
        if (mcpConfigs == null || mcpConfigs.isEmpty()) return;

        try {
            mcpManager = new McpManager(mcpConfigs);
            var result = mcpManager.connectAll();
            for (var t : result.tools()) registry.register(t);
            for (var e : result.errors()) System.err.println("MCP error: " + e);

            // 构建 MCP 指令（首次用户消息时注入到对话）
            if (!result.servers().isEmpty()) {
                var mcpParts = new ArrayList<String>();
                for (var s : result.servers()) {
                    var sb = new StringBuilder();
                    sb.append("## ").append(s.name()).append("\n");
                    if (s.instructions() != null && !s.instructions().isBlank()) {
                        sb.append(s.instructions()).append("\n");
                    }
                    var toolNames = registry.listTools().stream()
                            .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                            .map(com.mewcode.tool.Tool::name)
                            .toList();
                    if (!toolNames.isEmpty()) {
                        sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                    }
                    mcpParts.add(sb.toString());
                }
                mcpInstructions = "# MCP Server Instructions\n\n"
                        + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                        + String.join("\n\n", mcpParts);
            }
        } catch (Exception e) {
            System.err.println("MCP init failed: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 消息处理
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleWsMessage(WsContext ctx, String raw) {
        try {
            var msg = MAPPER.readValue(raw, Map.class);
            String type = (String) msg.get("type");
            Object data = msg.get("data");

            switch (type) {
                case "user_message" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");
                        // 用虚拟线程处理用户消息，避免阻塞 WS 读循环
                        Thread.startVirtualThread(() -> handleUserMessage(content));
                    }
                }
                case "send_directive" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");
                        Thread.startVirtualThread(() -> handleDirective(content));
                    }
                }
                case "send_question" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");
                        Thread.startVirtualThread(() -> handleQuestion(content));
                    }
                }
                case "permission_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        String response = (String) d.get("response");
                        handlePermissionResponse(id, response);
                    }
                }
                case "ask_user_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> answers = (Map<String, String>) d.get("answers");
                        handleAskUserResponse(id, answers);
                    }
                }
                case "cancel" -> {
                    // 取消真实执行任务（而非中断事件消费线程）
                    var handle = activeTask.get();
                    if (handle != null) handle.cancel();
                }
                case "ping" -> {
                    // 应用层保活：回复 pong
                    broadcast(Map.of("type", "pong"));
                }
                case "resync" -> {
                    // 断线补拉：按 seq 回放此前落盘的事实事件
                    long afterSeq = 0;
                    if (data instanceof Map<?, ?> d && d.get("afterSeq") instanceof Number n) {
                        afterSeq = n.longValue();
                    }
                    long finalAfterSeq = afterSeq;
                    Thread.startVirtualThread(() -> {
                        for (var env : eventBridge.readSince(finalAfterSeq)) {
                            ctx.send(toJson(Map.of("type", "event", "data", env.toMap())));
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket message parse error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 用户消息处理
    // ────────────────────────────────────────────────────────────────────

    private void handleUserMessage(String content) {
        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;

        // 斜杠命令处理
        if (content.startsWith("/")) {
            handleSlashCommand(content);
            return;
        }

        // 运行中收到消息 → 转为指令投递给主 Agent（不再被 streaming 吞掉）
        var handle = activeTask.get();
        if (handle != null && !handle.isDone()) {
            handleDirective(content);
            return;
        }

        String workDir = System.getProperty("user.dir");
        SessionManager.saveMessage(workDir, sessionId, "user", content);
        conversation.addUserMessage(content);

        // 首次消息时注入 MCP 指令
        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
            mcpInstructions = "";
        }

        startAndConsumeTask();
    }

    /** 运行中指令：保存并立即回执，由 Agent 在检查点投递。 */
    private void handleDirective(String content) {
        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;

        var handle = activeTask.get();
        if (handle == null || handle.isDone()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "没有正在运行的任务，请作为新消息发送以启动任务。")));
            return;
        }
        var d = directiveService.submit(handle.taskId(), content);
        broadcast(Map.of("type", "directive_received", "data",
                Map.of("id", d.id(), "status", d.status().name(), "text", d.originalText())));
    }

    /** 问询：基于事件时间线由 ReportService 回答，小模型不可用时降级为纯事实提示。 */
    private void handleQuestion(String content) {
        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;
        var handle = activeTask.get();
        String taskId = handle != null ? handle.taskId() : sessionId;
        String answer;
        if (reportService != null && reportService.available()) {
            answer = reportService.answerQuestion(taskId, content);
            if (answer == null) answer = "（报告模型暂不可用，请稍后再问）";
        } else {
            answer = "（未配置 report_provider，sidecar 无法生成回答。可在 .mewcode/config.yaml 增加 report_provider 后重试。）";
        }
        broadcast(Map.of("type", "sidecar_answer", "data",
                Map.of("question", content, "answer", answer)));
    }

    // ────────────────────────────────────────────────────────────────────
    // 斜杠命令处理
    // ────────────────────────────────────────────────────────────────────

    private void handleSlashCommand(String input) {
        try {
            // 解析命令名和参数
            String trimmed = input.substring(1).strip();
            String name, args;
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx < 0) {
                name = trimmed;
                args = "";
            } else {
                name = trimmed.substring(0, spaceIdx);
                args = trimmed.substring(spaceIdx + 1).strip();
            }

            if (name.isEmpty()) return;

            var cmd = cmdRegistry.find(name);
            if (cmd.isEmpty()) {
                broadcast(Map.of("type", "error", "data",
                        Map.of("message", "Unknown command: /" + name + " -- type /help to see available commands")));
                broadcast(Map.of("type", "command_done"));
                return;
            }

            Command c = cmd.get();
            var ctx = buildCommandContext(args);

            switch (c.type()) {
                case LOCAL -> {
                    String result = cmdRegistry.execute(name, ctx);
                    if (result != null && !result.isEmpty()) {
                        broadcast(Map.of("type", "system", "data", Map.of("message", result)));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case LOCAL_UI -> {
                    switch (name) {
                        case "clear" -> {
                            conversation = new ConversationManager();
                            broadcast(Map.of("type", "clear"));
                        }
                        case "compact" -> {
                            handleCompact();
                            return; // compact 自己管 command_done
                        }
                        case "plan" -> handlePlan(args);
                        case "resume" -> {
                            handleResume(args);
                            return; // resume 自己管 command_done
                        }
                        case "rewind" -> broadcast(Map.of("type", "system", "data",
                                Map.of("message", "Rewind is not yet supported in remote mode.")));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case PROMPT -> {
                    String prompt = cmdRegistry.execute(name, ctx);
                    if (prompt == null || prompt.isBlank()) return;

                    String displayText = "/" + name;
                    if (!args.isEmpty()) displayText += " " + args;

                    // PROMPT 命令生成 prompt 注入给 Agent
                    String workDir = System.getProperty("user.dir");
                    SessionManager.saveMessage(workDir, sessionId, "user", displayText);
                    conversation.addUserMessage(prompt);

                    if (!mcpInstructions.isEmpty()) {
                        conversation.addSystemReminder(mcpInstructions);
                        mcpInstructions = "";
                    }

                    startAndConsumeTask();
                }
            }
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Command error: " + e.getMessage())));
        }
    }

    private CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = providers.get(0).getModel();
        return new CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{0, 0},
                () -> memoryManager != null ? memoryManager.getMemories() : List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : List.of(),
                () -> 0,
                () -> mcpManager != null ? "MCP connected" : "",
                () -> "不可用",
                null
        );
    }

    // ────────────────────────────────────────────────────────────────────
    // 特殊命令处理
    // ────────────────────────────────────────────────────────────────────

    /** /compact 命令：强制压缩对话上下文 */
    private void handleCompact() {
        if (client == null || conversation == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact requires an active provider.")));
            broadcast(Map.of("type", "command_done"));
            return;
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", "Compacting conversation...")));
        try {
            String workDir = System.getProperty("user.dir");
            int contextWindow = providers.get(0).resolvedContextWindow();
            var schemas = registry.getAllSchemas(providers.get(0).getProtocol());
            String msg = ContextCompactor.forceCompact(
                    conversation, client, contextWindow, workDir, sessionId,
                    agent.getRecoveryState(), schemas, null, reportService);
            broadcast(Map.of("type", "system", "data",
                    Map.of("message", msg.isEmpty() ? "Compacted: nothing to compact" : msg)));
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact failed: " + e.getMessage())));
        }
        broadcast(Map.of("type", "command_done"));
    }

    /** /plan 命令：进入计划模式 */
    private void handlePlan(String args) {
        if (permChecker == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Agent not initialized.")));
            return;
        }
        String workDir = System.getProperty("user.dir");
        permChecker.setMode(PermissionMode.PLAN);
        String planPath = PlanFile.getOrCreatePlanPath(workDir);
        broadcast(Map.of("type", "system", "data",
                Map.of("message", "Entered Plan mode. Plan file: " + planPath
                        + "\nExplore the codebase and design your approach.")));

        // 带参数直接发给 Agent
        if (args != null && !args.isEmpty()) {
            SessionManager.saveMessage(workDir, sessionId, "user", "/plan " + args);
            conversation.addUserMessage(args);

            startAndConsumeTask();
        }
    }

    /** /resume 命令：恢复历史会话 */
    private void handleResume(String args) {
        String workDir = System.getProperty("user.dir");
        var sessions = SessionManager.listSessions(workDir);

        if (args == null || args.isEmpty()) {
            // 列出可选会话
            if (sessions.isEmpty()) {
                broadcast(Map.of("type", "system", "data", Map.of("message", "No previous sessions found.")));
                broadcast(Map.of("type", "command_done"));
                return;
            }
            var sb = new StringBuilder("Available sessions (%d):\n\n".formatted(sessions.size()));
            int limit = Math.min(sessions.size(), 20);
            for (int i = 0; i < limit; i++) {
                var sess = sessions.get(i);
                String first = sess.firstMessage();
                if (first.length() > 60) first = first.substring(0, 60) + "...";
                sb.append("  %d. [%s] %s (%d msgs)\n".formatted(i + 1, sess.id(), first, sess.messageCount()));
            }
            if (sessions.size() > 20) {
                sb.append("  ... and %d more\n".formatted(sessions.size() - 20));
            }
            sb.append("\nUsage: /resume <number> or /resume <session-id>");
            broadcast(Map.of("type", "system", "data", Map.of("message", sb.toString())));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 恢复指定会话
        String targetId = args.strip();
        try {
            int idx = Integer.parseInt(targetId);
            if (idx >= 1 && idx <= sessions.size()) {
                targetId = sessions.get(idx - 1).id();
            }
        } catch (NumberFormatException ignored) {}

        var messages = SessionManager.loadSession(workDir, targetId);
        if (messages.isEmpty()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Session '%s' not found or empty.".formatted(targetId))));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 重建对话
        conversation = SessionManager.rebuildConversation(messages);
        sessionId = targetId;
        if (agent != null) agent.setSessionId(sessionId);

        // 清除旧 UI 并重放消息
        broadcast(Map.of("type", "clear"));
        var scan = SessionManager.findLastCompactBoundary(messages);
        List<SessionManager.SessionMessage> replay;
        if (scan.found()) {
            replay = new ArrayList<>();
            replay.add(new SessionManager.SessionMessage("user", scan.boundary().summary(), 0));
            for (var k : scan.boundary().keep()) {
                replay.add(new SessionManager.SessionMessage(k.role(), k.content(), 0));
            }
            replay.addAll(scan.after());
        } else {
            replay = messages;
        }

        for (var msg : replay) {
            switch (msg.role()) {
                case "user" -> broadcast(Map.of("type", "replay_user", "data",
                        Map.of("content", msg.content())));
                case "assistant" -> broadcast(Map.of("type", "replay_assistant", "data",
                        Map.of("content", msg.content())));
            }
        }

        String restored;
        if (scan.found()) {
            restored = "Session %s restored from compacted state (summary + %d kept + %d newer)."
                    .formatted(targetId, scan.boundary().keep().size(), scan.after().size());
        } else {
            restored = "Session %s restored (%d messages).".formatted(targetId, replay.size());
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", restored)));
        broadcast(Map.of("type", "command_done"));
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 事件消费
    // ────────────────────────────────────────────────────────────────────

    /**
     * 启动可取消任务并消费其事件。用 tryLock 串行化任务创建，避免并发重复启动；
     * 取消通过 activeTask 中的执行句柄传播到 Agent 线程。
     */
    private void startAndConsumeTask() {
        if (!taskStartLock.tryLock()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "A task is already running")));
            return;
        }
        streaming = true;
        try {
            agentQueue = new LinkedBlockingQueue<>(64);
            var handle = agent.runCancellable(conversation, agentQueue,
                    "task-" + Long.toUnsignedString(System.nanoTime(), 36));
            activeTask.set(handle);
            if (askUserTool != null) askUserTool.setEventQueue(agentQueue);
            try {
                consumeAgentEvents(handle);
            } finally {
                activeTask.compareAndSet(handle, null);
                streaming = false;
                agentQueue = null;
            }
        } finally {
            taskStartLock.unlock();
        }
    }

    /** 从 Agent 事件队列中消费所有事件，推送到 WebSocket 客户端 */
    private void consumeAgentEvents(TaskExecutionHandle handle) {
        var streamBuf = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String taskId = handle != null ? handle.taskId() : sessionId;

        while (true) {
            AgentEvent event;
            try {
                // 与 Agent 主循环的流超时对齐：推理模型思考阶段可能长时间无事件，
                // 过短会误判为 Stream timeout 并中断任务。
                event = agentQueue.poll(300, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                // 300s 无事件不一定是卡死：可能在跑长 bash（最长 600s）或长思考。
                // 只有任务确实结束（completion 完成）才算真停了；否则发心跳继续等。
                if (handle != null && !handle.isDone()) {
                    broadcast(Map.of("type", "system", "data", Map.of(
                            "message", "仍在执行中（300s 无新事件，可能在跑长命令/长思考）…")));
                    continue;
                }
                broadcast(Map.of("type", "error", "data", Map.of("message", "Stream timeout")));
                break;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    streamBuf.append(e.text());
                    broadcast(Map.of("type", "stream_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingText e -> {
                    broadcast(Map.of("type", "thinking_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingComplete e -> {
                    // 前端自行处理 thinking 完成状态
                }
                case AgentEvent.ToolUseEvent e -> {
                    broadcast(Map.of("type", "tool_use", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "args", e.args() != null ? e.args() : Map.of()
                    )));
                    // 仅完整参数（ToolCallComplete）落盘为 TOOL_PROPOSED，避免 ToolCallStart 空参重复
                    if (e.args() != null && !e.args().isEmpty()) {
                        recordAndBroadcast(eventBridge.toolProposed(taskId, e.toolId(), e.toolName(), e.args()));
                    }
                }
                case AgentEvent.ToolResultEvent e -> {
                    // 工具结果前先结束当前流式文本
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "tool_result", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "output", e.output() != null ? e.output() : "",
                            "isError", e.isError(),
                            "elapsed", e.elapsed()
                    )));
                    recordAndBroadcast(eventBridge.toolFinished(
                            taskId, e.toolId(), e.toolName(), e.isError(), e.elapsed(), e.output()));
                }
                case AgentEvent.PermissionRequestEvent e -> {
                    // 持久化审批请求（绑定 taskId/参数/策略版本），用 approvalId 桥接前端响应
                    var inv = new ToolInvocation(taskId, e.toolCallId(), e.toolName(),
                            e.args(), System.getProperty("user.dir"), e.policyVersion());
                    approvalService.create(e.approvalId(), inv);
                    pendingPerms.put(e.approvalId(), e.future());
                    broadcast(Map.of("type", "permission_request", "data", Map.of(
                            "id", e.approvalId(),
                            "toolName", e.toolName(),
                            "description", e.description(),
                            "explanation", e.explanation() != null ? e.explanation() : ""
                    )));
                    recordAndBroadcast(eventBridge.approvalWaiting(taskId, e.approvalId(), e.description()));
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    String id = "ask_" + System.nanoTime();
                    pendingAsks.put(id, e.future());
                    // 将 Question 转换为前端可解析的 JSON 结构
                    var questions = e.questions().stream().map(q -> Map.of(
                            "question", q.text() != null ? q.text() : "",
                            "options", q.options() != null
                                    ? q.options().stream().map(o -> Map.of(
                                    "label", o.label() != null ? o.label() : "",
                                    "description", o.description() != null ? o.description() : ""
                            )).toList()
                                    : List.of()
                    )).toList();
                    broadcast(Map.of("type", "ask_user", "data", Map.of(
                            "id", id,
                            "questions", questions
                    )));
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "turn_complete", "data", Map.of("turn", e.turn())));
                }
                case AgentEvent.LoopComplete e -> {
                    // 最后一段流式文本持久化到 session
                    if (!streamBuf.isEmpty()) {
                        String workDir = System.getProperty("user.dir");
                        SessionManager.saveMessage(workDir, sessionId, "assistant", streamBuf.toString());
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    broadcast(Map.of("type", "loop_complete", "data", Map.of(
                            "totalTurns", e.totalTurns(),
                            "elapsed", elapsed
                    )));
                    // 不再 return：终态由后续 TaskTerminalEvent 触发，以区分成功/失败/取消
                }
                case AgentEvent.UsageEvent e -> {
                    broadcast(Map.of("type", "usage", "data", Map.of(
                            "inputTokens", e.inputTokens(),
                            "outputTokens", e.outputTokens()
                    )));
                }
                case AgentEvent.ErrorEvent e -> {
                    broadcast(Map.of("type", "error", "data", Map.of("message", e.message())));
                }
                case AgentEvent.CompactEvent e -> {
                    broadcast(Map.of("type", "compact", "data", Map.of("message", e.message())));
                }
                case AgentEvent.RetryEvent e -> {
                    broadcast(Map.of("type", "retry", "data", Map.of(
                            "reason", e.reason(),
                            "waitMs", e.waitMs()
                    )));
                }
                case AgentEvent.ToolStartEvent e -> {
                    broadcast(Map.of("type", "tool_start", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName()
                    )));
                    recordAndBroadcast(eventBridge.toolStarted(taskId, e.toolId(), e.toolName()));
                }
                case AgentEvent.TaskTerminalEvent e -> {
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    broadcast(Map.of("type", "task_terminal", "data", Map.of(
                            "status", e.status().name(),
                            "message", e.message() != null ? e.message() : "",
                            "elapsed", elapsed
                    )));
                    recordAndBroadcast(eventBridge.taskTerminal(
                            taskId, e.status().name(), e.message(), e.totalTurns()));
                    // 任务结束：异步生成最终报告（不阻塞终态广播）
                    if (reportService != null && reportService.available()) {
                        Thread.startVirtualThread(() -> {
                            String report = reportService.generateFinal(taskId);
                            if (report != null) {
                                broadcast(Map.of("type", "final_report", "data",
                                        Map.of("taskId", taskId, "report", report)));
                            }
                        });
                    }
                    return; // 终态：结束事件消费
                }
                case AgentEvent.DirectiveStatusEvent e -> {
                    broadcast(Map.of("type", "directive_status", "data", Map.of(
                            "id", e.directiveId(),
                            "status", e.status().name()
                    )));
                    recordAndBroadcast(eventBridge.directiveStatus(taskId, e.directiveId(), e.status().name()));
                }
                case AgentEvent.LoopWarningEvent e -> {
                    broadcast(Map.of("type", "loop_warning", "data", Map.of(
                            "iteration", e.iteration(),
                            "level", e.level(),
                            "message", e.message() != null ? e.message() : ""
                    )));
                }
                default -> {
                    // 后续阶段新增的事件类型在此兜底，避免消费中断
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 权限和 AskUser 响应处理
    // ────────────────────────────────────────────────────────────────────

    /** 处理前端权限按钮的回复 */
    private void handlePermissionResponse(String id, String response) {
        var future = pendingPerms.remove(id);
        if (future == null) return;

        boolean allow = "allow".equals(response) || "allowAlways".equals(response);
        var handle = activeTask.get();
        String taskId = handle != null ? handle.taskId() : null;

        // 审批校验：过期 / taskId 不匹配 / 重复响应 → 不放行，向前端回 approval_invalid
        if (taskId == null || !approvalService.resolve(id, allow, taskId)) {
            if (!allow) {
                // 明确拒绝时即使校验失败也直接拒绝
                future.complete(PermissionResponse.DENY);
                return;
            }
            broadcast(Map.of("type", "approval_invalid", "data", Map.of("approvalId", id)));
            future.complete(PermissionResponse.DENY);
            return;
        }

        PermissionResponse resp = switch (response) {
            case "allow" -> PermissionResponse.ALLOW;
            case "allowAlways" -> PermissionResponse.ALLOW_ALWAYS;
            default -> PermissionResponse.DENY;
        };
        future.complete(resp);
    }

    /** 处理前端 AskUser 对话框的回复 */
    private void handleAskUserResponse(String id, Map<String, String> answers) {
        var future = pendingAsks.remove(id);
        if (future == null) return;
        future.complete(answers != null ? answers : Map.of());
    }

    // ────────────────────────────────────────────────────────────────────
    // 命令列表
    // ────────────────────────────────────────────────────────────────────

    /** 构建命令列表供前端斜杠菜单使用 */
    private List<Map<String, String>> buildCommandList() {
        var list = new ArrayList<Map<String, String>>();
        for (var cmd : cmdRegistry.listVisible()) {
            list.add(Map.of(
                    "name", cmd.name(),
                    "description", cmd.description()
            ));
        }
        return list;
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 广播
    // ────────────────────────────────────────────────────────────────────

    /** 广播统一事件信封（带 seq），供时间线渲染与断线补拉使用。 */
    private void recordAndBroadcast(EventEnvelope env) {
        if (env == null) return;
        broadcast(Map.of("type", "event", "data", env.toMap()));
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** 向所有已连接的 WebSocket 客户端广播 JSON 消息 */
    private void broadcast(Map<String, Object> msg) {
        if (connections.isEmpty()) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            for (var ctx : connections) {
                try {
                    ctx.send(json);
                } catch (Exception e) {
                    System.err.println("[ws] send error: " + e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            System.err.println("[ws] JSON serialize error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 工具方法
    // ────────────────────────────────────────────────────────────────────

    /** 从地址字符串解析端口号（支持 ":18888" 和 "0.0.0.0:18888" 格式） */
    private static int parsePort(String addr) {
        if (addr == null || addr.isEmpty()) return 18888;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx >= 0) {
            try {
                return Integer.parseInt(addr.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 18888;
            }
        }
        try {
            return Integer.parseInt(addr);
        } catch (NumberFormatException e) {
            return 18888;
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 MewCodeModel） ──────────────
    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
