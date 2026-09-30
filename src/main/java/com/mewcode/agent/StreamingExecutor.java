// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.compact.RecoveryState;
import com.mewcode.control.report.ReportService;
import com.mewcode.control.task.CancellationToken;
import com.mewcode.hook.HookEngine;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Concurrent tool executor that partitions tool calls into read-only (parallel)
 * and write/command (sequential) batches.
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;
    private final CancellationToken token;
    private final ReportService reportService;

    /**
     * 执行期工具闸门，与 {@link Agent#setToolNameFilter} 是同一份判定，只是那个用在
     * schema 侧、这个用在执行侧。null 表示不限制。
     */
    private final java.util.function.Predicate<String> toolGate;

    /**
     * 等待用户审批的上限，超过即按拒绝处理。
     *
     * <p>取 5 分钟与审批自身的 TTL（{@code ApprovalService.DEFAULT_TTL_MS}）一致：
     * 超过这个时间，前端的补答也已被判过期，再等下去没有意义。
     */
    private static final long APPROVAL_WAIT_MS = 5 * 60 * 1000L;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null, null, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this(registry, checker, hookEngine, eventQueue, recoveryState, null, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, CancellationToken token) {
        this(registry, checker, hookEngine, eventQueue, recoveryState, token, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, CancellationToken token,
                             ReportService reportService) {
        this(registry, checker, hookEngine, eventQueue, recoveryState, token, reportService, null);
    }

    /**
     * @param toolGate 执行期工具闸门，与 {@code Agent.toolNameFilter} 是同一份判定。
     *
     * <p>Agent 每轮迭代只用那个 filter 裁掉**发给模型的 schema**；模型若凭记忆硬吐出
     * 一个已被裁掉的名字，执行侧原本 {@code registry.get()} 拿到就照跑不误 —— 于是
     * 「计划阶段不许动手」之类的限制永远只是提示词层面的君子协定。这道闸门补上执行
     * 侧的那一半，让同一个判定在两侧都生效。null 表示不限制（默认，保持既有行为）。
     */
    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, CancellationToken token,
                             ReportService reportService,
                             java.util.function.Predicate<String> toolGate) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
        this.token = token;
        this.reportService = reportService;
        this.toolGate = toolGate;
    }

    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        // 按相邻性分批：连续的只读工具合成一个并行批次，写/命令工具各自独占一批
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (token != null && token.isCancelled()) break;
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = new ArrayList<java.util.concurrent.Future<ToolExecResult>>(batch.calls.size());
                    for (var call : batch.calls) {
                        futures.add(executor.submit(() -> executeSingleSafely(call)));
                    }
                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            results.add(futures.get(i).get());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            // executeSingleSafely 已兜底，这里理论上不触发；保险起见补一条
                            results.add(new ToolExecResult(batch.calls.get(i).toolId(),
                                    "Tool execution failed: " + e.getMessage(), true));
                        }
                    }
                }
            } else {
                for (var call : batch.calls) results.add(executeSingleSafely(call));
            }
        }

        return results;
    }

    /**
     * 执行单个工具调用，任何异常都转成 error 结果，绝不抛出、绝不丢弃。
     * 保证每个 tool_call_id 都有一条结果，避免下一轮构造出「有 tool_calls
     * 却缺对应 tool 消息」的非法数组（OpenAI 兼容协议会返回 400）。
     */
    private ToolExecResult executeSingleSafely(ToolCallInfo call) {
        try {
            return executeSingle(call);
        } catch (Exception e) {
            var cause = e.getCause();
            String msg = (cause != null && cause.getMessage() != null)
                    ? cause.getMessage() : String.valueOf(e.getMessage());
            return new ToolExecResult(call.toolId(), "Tool execution failed: " + msg, true);
        }
    }

    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {}

    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            boolean safe = tool != null && tool.category() == ToolCategory.READ;

            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private ToolExecResult executeSingle(ToolCallInfo call) {
        // 取消检查前置：已取消则不再派发任何工具
        if (token != null && token.isCancelled()) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "[cancelled]", true, 0));
            return new ToolExecResult(call.toolId(), "[cancelled]", true);
        }

        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }

        // 闸门放在 registry.get 之后：工具确实存在、只是当前阶段不让用，报错文案要说清
        // 这个区别 —— 「未知工具」会让模型去 ToolSearch 找一个它其实不该用的工具。
        if (toolGate != null && !toolGate.test(call.toolName())) {
            String msg = "Error: tool '" + call.toolName()
                    + "' is not available in the current phase. Do not retry it.";
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
            return new ToolExecResult(call.toolId(), msg, true);
        }

        // 权限检查优先于 hook（与 Go 版保持一致）：先拦截无权操作，再让 hook 介入
        if (checker != null) {
            var check = checker.check(tool, call.args());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    String approvalId = "apr_" + Long.toUnsignedString(System.nanoTime(), 36);
                    // 权限第二层：sidecar 润色解释（短超时，失败降级为 null，不阻塞审批）
                    String explanation = reportService != null
                            ? reportService.explainInvocation(call.toolName(), call.args(), check.ruleSource())
                            : null;
                    putSafe(new AgentEvent.PermissionRequestEvent(
                            approvalId, call.toolId(), call.toolName(), call.args(),
                            checker.policyVersion(), desc, explanation, future));
                    PermissionResponse response;
                    try {
                        // 等待用户批准，有上限。原先是不设超时的阻塞，理由写的是「用户
                        // 可能长时间离开，超时会把未及时批准误判为拒绝」。实测下来这更
                        // 糟：客户端断线后没有任何路径能完成这个 future（pendingPerms
                        // 只在这里存、在 RemoteServer.handlePermissionResponse 里取，
                        // 没有扫描也没有清理），整个任务就永久卡死 —— 一次未及时批准的
                        // 代价从「慢一点」变成「永远不动」。上轮 lead 被卡 4 分钟即由此
                        // 而来，且那 4 分钟会被算进实验耗时。
                        response = future.get(APPROVAL_WAIT_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(),
                                "No approval response within " + (APPROVAL_WAIT_MS / 60_000)
                                        + " min; treated as denied", true, 0));
                        response = PermissionResponse.DENY;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        response = PermissionResponse.DENY;
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        // Pre-tool hook 在权限通过后执行，可拦截特定工具调用
        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                return new ToolExecResult(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            putSafe(new AgentEvent.ToolStartEvent(call.toolId(), call.toolName(), call.args()));
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call, result);

        String output = result.output();
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), output, result.isError());
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Capture what ReadFile just returned so the compact recovery block
     * can replay it after a Layer 2 summary wipes the transcript.
     * Re-reads from disk to keep the snapshot independent of how the tool
     * formats its output (e.g. line-number prefixes).
     */
    private void snapshotForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        if (!"ReadFile".equals(call.toolName())) return;
        Object pathObj = call.args() == null ? null : call.args().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            String content = Files.readString(Path.of(path));
            recoveryState.recordFileRead(path, content);
        } catch (IOException ignored) {
            // Best-effort snapshot; if the file vanished between the tool
            // call and now, just skip — the model has the tool output it
            // already saw.
        }
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
