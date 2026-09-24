// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.control.directive.Directive;
import com.mewcode.control.directive.DirectiveService;
import com.mewcode.control.directive.DirectiveStatus;
import com.mewcode.control.task.CancellationToken;
import com.mewcode.control.task.TaskExecutionHandle;
import com.mewcode.control.task.TaskOutcome;
import com.mewcode.control.task.TaskStatus;
import com.mewcode.control.report.ReportService;
import com.mewcode.control.watchdog.LoopWatchdog;
import com.mewcode.control.watchdog.ProgressReviewer;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.plan.PlanFile;
import com.mewcode.prompt.PlanModePrompt;
import com.mewcode.session.SessionManager;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.toolresult.ContentReplacementRecord;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.toolresult.ReplacementRecordsIO;
import com.mewcode.toolresult.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    private static final int MAX_TOKENS_CEILING = 64_000;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private PermissionChecker checker;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    /**
     * Session log id for the on-disk transcript. Plumbed so that an in-loop
     * compaction can append a compact_boundary record into the same session file
     * (enabling resume to rebuild the compacted state). Null for sub-agents /
     * one-shot callers that should not write boundaries into the main session.
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;
    private DirectiveService directiveService;
    private ReportService reportService;

    private java.util.function.Predicate<String> toolNameFilter;
    private String instructions = "";
    private String memoryContent = "";

    // 非阻塞 memory recall：prefetch 与主 LLM 调用并行，工具执行后注入
    private CompletableFuture<String> memoryRecallFuture;
    private boolean memoryRecallConsumed;
    private final com.mewcode.compact.ContextCompactor.AutoCompactTrackingState compactTracking =
            new com.mewcode.compact.ContextCompactor.AutoCompactTrackingState();

    /**
     * Real API-usage anchor for the compaction decision. Refreshed after each
     * stream ends with the provider-reported usage; null until the first turn
     * reports usage, so the compactor falls back to character estimation on a
     * cold start. See {@link com.mewcode.compact.ContextCompactor.UsageAnchor}.
     */
    private com.mewcode.compact.ContextCompactor.UsageAnchor usageAnchor;

    /**
     * Per-conversation-thread tool-result decision log. Carries across
     * iterations so Anthropic's prompt cache sees byte-stable prefixes.
     * Forks (see {@code AgentTool}) clone this for their child agent.
     */
    private ContentReplacementState replacementState = new ContentReplacementState();

    public ContentReplacementState getReplacementState() { return replacementState; }
    public void setReplacementState(ContentReplacementState state) { this.replacementState = state; }

    /**
     * Holds the snapshots needed to rebuild working context after Layer 2
     * collapses the conversation: most-recent file reads + skill SOPs.
     * Recorded on each ReadFile / skill call; consumed by ContextCompactor
     * when the threshold trips.
     */
    private final com.mewcode.compact.RecoveryState recoveryState =
            new com.mewcode.compact.RecoveryState();

    public com.mewcode.compact.RecoveryState getRecoveryState() { return recoveryState; }

    private com.mewcode.filehistory.FileHistory fileHistory;
    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public com.mewcode.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }
    public void setDirectiveService(DirectiveService service) { this.directiveService = service; }
    public void setReportService(ReportService service) { this.reportService = service; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public void setMemoryRecallFuture(CompletableFuture<String> future) {
        this.memoryRecallFuture = future;
        this.memoryRecallConsumed = false;
    }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        run(conv, queue);
        return queue;
    }

    // 使用调用方提供的 queue，允许 TUI 预先创建 queue 立即开始轮询
    public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        launch(conv, queue, new CancellationToken(), null);
    }

    /**
     * 启动可取消任务并返回执行句柄。供 RemoteServer（Sidecar）持有，
     * 通过句柄取消真实执行任务。旧调用方（TUI / 子 Agent / print）继续
     * 使用 {@link #run(ConversationManager, BlockingQueue)}。
     */
    public TaskExecutionHandle runCancellable(ConversationManager conv,
                                              BlockingQueue<AgentEvent> queue, String taskId) {
        var token = new CancellationToken();
        var handle = new TaskExecutionHandle(taskId, token, new CompletableFuture<TaskOutcome>());
        launch(conv, queue, token, handle);
        return handle;
    }

    private void launch(ConversationManager conv, BlockingQueue<AgentEvent> queue,
                        CancellationToken token, TaskExecutionHandle handle) {
        Thread t = Thread.ofVirtual()
                .name(handle != null ? "agent-" + handle.taskId() : "agent")
                .start(() -> {
                    try {
                        agentLoop(conv, queue, token,
                                handle != null ? handle.completion() : null,
                                handle != null ? handle.taskId() : null);
                    } catch (Exception e) {
                        if (handle != null) {
                            handle.completion().complete(TaskOutcome.failed("Agent error: " + e.getMessage()));
                        }
                        putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
                    }
                });
        if (handle != null) handle.setExecutionThread(t);
    }

    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue,
                           CancellationToken token, CompletableFuture<TaskOutcome> completion,
                           String taskId) {
        conv.injectLongTermMemory(instructions, memoryContent);

        int totalInput = 0, totalOutput = 0;
        int outputRecoveries = 0;
        boolean maxTokensEscalated = false;

        int contextRetries = 0;
        boolean loopCompleted = false;
        boolean cancelled = false;
        String failReason = null;
        int lastIteration = 0;
        // 本轮已投递、等待主 Agent 回执的指令 id 列表
        var pendingAckIds = new ArrayList<String>();
        // 循环看门狗：基于每轮工具签名/产出判断是否陷入重复或空转，由软到硬介入
        var watchdog = new LoopWatchdog();
        // 第二层进度审查：超轮次预算后异步请小模型判断是否"软空转"（有产出但没方向）
        var progressReviewer = reportService != null ? new ProgressReviewer(reportService) : null;

        try {
        for (int iteration = 1; ; iteration++) {
            lastIteration = iteration;
            if (maxIterations > 0 && iteration > maxIterations) {
                failReason = "Agent reached maximum iterations (%d)".formatted(maxIterations);
                putSafe(queue, new AgentEvent.ErrorEvent(failReason));
                break;
            }

            if (token.isCancelled()) { cancelled = true; break; }
            if (Thread.currentThread().isInterrupted()) { cancelled = true; break; }

            // Drain background task notifications and inject as system reminders
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            // 检查点：投递运行中收到的 sidecar 指令（工具结果写回后、下一次模型调用前）
            if (directiveService != null) {
                for (var d : directiveService.drainPending()) {
                    pendingAckIds.add(d.id());
                    conv.addSystemReminder(buildDirectiveReminder(d));
                    directiveService.markDelivered(d.id());
                    putSafe(queue, new AgentEvent.DirectiveStatusEvent(d.id(), DirectiveStatus.DELIVERED));
                }
            }

            // Compute the tool schemas once per iteration so the recovery
            // attachment (when compact fires) and the Stream call below see
            // the same set. Skill filters can only change between iterations.
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // Inject deferred tool names as system reminder
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                var sb = new StringBuilder();
                sb.append("The following deferred tools are available via ToolSearch. ");
                sb.append("Their schemas are NOT loaded - use ToolSearch with ");
                sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
                for (var dn : deferredNames) {
                    sb.append(dn).append("\n");
                }
                conv.addSystemReminder(sb.toString());
            }

            // Plan mode: inject structured workflow reminder
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.addSystemReminder(reminder);
            }

            // Layer 1: apply tool-result budget（就地修改 conv，Design A）
            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
            List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (!newRecords.isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, newRecords);
                } catch (Exception ignored) {}
            }

            // Layer 2: auto-compact check
            // 用 Layer 1 就地裁剪后的 conv 消息估算 token，判断更精确
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = com.mewcode.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor,
                        conv.getMessages(), reportService);
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // 压缩把旧消息替换成摘要，旧锚点失效，下次 stream 重新锚定
                if (conv.size() < sizeBefore) {
                    usageAnchor = null;
                    conv.resetLtmInjected();
                    conv.injectLongTermMemory(instructions, memoryContent);
                    // 压缩后 conv 已变，重新应用 tool-result budget
                    newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
                }
            } catch (Exception ignored) {}

            var tools = iterToolSchemas;
            var streamQueue = client.stream(conv, tools);

            // Consume stream events, collect tool calls
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;

            while (true) {
                if (token.isCancelled()) { cancelled = true; return; }
                StreamEvent event;
                try {
                    // 推理模型（deepseek-v4-pro 等 thinking:true）思考阶段可能很长，
                    // 用与 HTTP 请求相同的 300s 超时，避免长思考被误判为 Stream timeout。
                    event = streamQueue.poll(300, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // 取消通过 TaskExecutionHandle.cancel() 打断执行线程触发
                    cancelled = true;
                    return;
                }

                if (event == null) {
                    failReason = "Stream timeout";
                    putSafe(queue, new AgentEvent.ErrorEvent(failReason));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        lastStreamError = err.message();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }

                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            // Error recovery
            if (streamError) {
                var lastErr = events_drain_last_error(queue);
                if (lastErr != null && (lastErr.contains("context") || lastErr.contains("too long")
                        || lastErr.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));
                        // 先裁剪再压缩，确保预算内的结果不会被误压缩
                        Path forceSessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
                        List<ContentReplacementRecord> forceRecords = ToolResultBudget.apply(conv, forceSessionDir, replacementState);
                        if (!forceRecords.isEmpty()) {
                            try { ReplacementRecordsIO.append(forceSessionDir, forceRecords); } catch (Exception ignored) {}
                        }
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null ? workDir : System.getProperty("user.dir");
                            com.mewcode.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas,
                                    conv.getMessages(), reportService);
                        } catch (Exception ignored) {}
                        // forceCompact shrinks the conversation (summary + kept
                        // tail), so the prior anchor's message count no longer
                        // lines up; drop it and re-anchor on the next stream.
                        if (conv.size() < sizeBeforeForce) {
                            usageAnchor = null;
                            conv.resetLtmInjected();
                            conv.injectLongTermMemory(instructions, memoryContent);
                        }
                        continue;
                    }
                }
                if (lastErr != null && lastErr.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) { cancelled = true; break; }
                    continue;
                }
                failReason = lastErr;
                break;
            }

            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

            // Max tokens handling
            if ("max_tokens".equals(stopReason)) {
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    outputRecoveries++;
                    conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
                // Exhausted: fall through to normal completion
            } else {
                outputRecoveries = 0;
            }

            // 解析主 Agent 对已投递指令的回应标记，发布 ACKNOWLEDGED
            String ack = extractDirectiveAck(text.toString());
            if (ack != null && !pendingAckIds.isEmpty() && directiveService != null) {
                for (var id : pendingAckIds) {
                    directiveService.markAcknowledged(id, ack);
                    putSafe(queue, new AgentEvent.DirectiveStatusEvent(id, DirectiveStatus.ACKNOWLEDGED));
                }
                pendingAckIds.clear();
            }

            // Save assistant message to conversation
            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

            // Checkpoint: persist an assistant turn that carries tool_use blocks so a
            // resume can rebuild the exact tool chain. The final no-tool turn is
            // persisted by the TUI as plain text, so only turns with tool calls are
            // written here (avoids double-writing the final answer). Sub-agents /
            // one-shot callers have null sessionId/workDir and are skipped.
            if (!toolCalls.isEmpty() && sessionId != null && workDir != null) {
                SessionManager.saveAssistantTurn(workDir, sessionId,
                        text.toString(), thinkingBlocks, toolUseBlocks);
            }

            // Re-anchor the compaction estimate on this turn's real usage. The
            // baseline = input + cacheRead + cacheCreation + output covers the
            // sent context and the assistant message just appended; messages
            // added after this point (tool results, next user turn) are
            // estimated incrementally on top. A cache hit reports a small real
            // input, so the anchor tracks the true window far better than the
            // raw character estimate.
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                usageAnchor = new com.mewcode.compact.ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }

            // No tool calls → done
            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                // No TurnComplete on the terminal (no-tool) turn — aligning Go
                // (agent.go emits only LoopComplete here). The TUI's TurnComplete
                // handler flushes+clears streamBuf without persisting; if we emitted
                // it first, LoopComplete would see an empty buffer and the final
                // assistant message would never be saved to the session file.
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            // Execute tool calls
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState, token, reportService);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // Add results to conversation
            var resultBlocks = results.stream()
                    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
                    .toList();
            conv.addToolResultsMessage(resultBlocks);

            // ── 循环看门狗：喂本轮"动作签名 + 是否有产出"，由软到硬介入 ──
            var sigNames = new ArrayList<String>();
            for (var tc : toolCalls) sigNames.add(tc.toolName() + "(" + tc.args() + ")");
            Collections.sort(sigNames);
            boolean productive = false;
            for (var r : results) {
                if (r.isError()) continue;
                for (var tc : toolCalls) {
                    if (!tc.toolId().equals(r.toolId())) continue;
                    var t = registry.get(tc.toolName());
                    if (t != null && t.category() != ToolCategory.READ) { productive = true; break; }
                }
                if (productive) break;
            }
            var verdict = watchdog.onTurn(iteration, String.join(" | ", sigNames), productive);
            switch (verdict.action()) {
                case NUDGE, ESCALATE -> {
                    conv.addSystemReminder(verdict.message());
                    putSafe(queue, new AgentEvent.LoopWarningEvent(
                            iteration, verdict.action().name(), verdict.message()));
                }
                case CANCEL -> {
                    putSafe(queue, new AgentEvent.LoopWarningEvent(
                            iteration, verdict.action().name(), verdict.message()));
                    token.cancel(verdict.message());
                    cancelled = true;
                }
                case NONE -> {}
            }
            if (cancelled) break;

            // ── 第二层：进度审查（模型）—— 超轮次预算后异步审查，抓"有产出但没方向"的软空转 ──
            if (progressReviewer != null) {
                var pv = progressReviewer.onTurn(iteration, taskId);
                switch (pv.action()) {
                    case NUDGE -> {
                        conv.addSystemReminder(pv.message());
                        putSafe(queue, new AgentEvent.LoopWarningEvent(
                                iteration, "PROGRESS_NUDGE", pv.message()));
                    }
                    case CANCEL -> {
                        putSafe(queue, new AgentEvent.LoopWarningEvent(
                                iteration, "PROGRESS_CANCEL", pv.message()));
                        token.cancel(pv.message());
                        cancelled = true;
                    }
                    default -> {}
                }
                if (cancelled) break;
            }

            // Checkpoint: persist the tool results answering the tool_uses above.
            if (sessionId != null && workDir != null) {
                SessionManager.saveToolResults(workDir, sessionId, resultBlocks);
            }

            // 非阻塞 memory recall：工具执行完后检查 prefetch 是否就绪
            // 记忆在第 1 轮工具执行后、第 2 轮迭代前注入
            if (memoryRecallFuture != null && !memoryRecallConsumed) {
                if (memoryRecallFuture.isDone()) {
                    try {
                        String recall = memoryRecallFuture.getNow("");
                        if (recall != null && !recall.isEmpty()) {
                            conv.addSystemReminder(recall);
                        }
                    } catch (Exception ignored) {}
                    memoryRecallConsumed = true;
                }
            }

            boolean exitPlanCalled = toolCalls.stream()
                    .anyMatch(tc -> "ExitPlanMode".equals(tc.toolName));
            if (exitPlanCalled) {
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
        }
        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
            // 可取消路径：发布明确终态并完成 future。旧调用方 completion 为 null，行为不变。
            if (completion != null) {
                TaskStatus status;
                if (cancelled) status = TaskStatus.CANCELLED;
                else if (loopCompleted) status = TaskStatus.SUCCEEDED;
                else status = TaskStatus.FAILED;
                putSafe(queue, new AgentEvent.TaskTerminalEvent(status, failReason, lastIteration));
                completion.complete(new TaskOutcome(status, failReason, lastIteration));
            }
        }
    }

    private String lastStreamError;

    private String events_drain_last_error(BlockingQueue<AgentEvent> queue) {
        return lastStreamError;
    }

    /** 构造注入给主 Agent 的指令提醒，原文与辅助解释分开。 */
    private static String buildDirectiveReminder(Directive d) {
        var sb = new StringBuilder();
        sb.append("用户在运行中追加了要求（type=").append(d.type()).append("），原文如下，必须遵守：\n")
          .append("\"\"\"\n").append(d.originalText()).append("\n\"\"\"");
        if (d.modelSummary() != null && !d.modelSummary().isBlank()) {
            sb.append("\n（可选）小模型整理：").append(d.modelSummary());
        }
        sb.append("\n请在你的回复开头用一行结构化标记回应本条指令：\n")
          .append("  [DIRECTIVE:ADOPT] 采用并执行\n")
          .append("  [DIRECTIVE:DEFER] 推迟（说明原因）\n")
          .append("  [DIRECTIVE:CONFLICT] 与当前任务冲突（说明冲突点）");
        return sb.toString();
    }

    /** 从主 Agent 回复中解析 [DIRECTIVE:ADOPT|DEFER|CONFLICT]，无则返回 null。 */
    private static String extractDirectiveAck(String text) {
        if (text == null || text.isBlank()) return null;
        var m = java.util.regex.Pattern
                .compile("\\[DIRECTIVE:(ADOPT|DEFER|CONFLICT)\\]")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}
}
