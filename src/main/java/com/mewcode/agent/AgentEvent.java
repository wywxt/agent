// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.control.directive.DirectiveStatus;
import com.mewcode.control.task.TaskStatus;
import com.mewcode.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {

    record StreamText(String text) implements AgentEvent {}

    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsed) implements AgentEvent {}

    record TurnComplete(int turn) implements AgentEvent {}

    record LoopComplete(int totalTurns) implements AgentEvent {}

    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}

    record ErrorEvent(String message) implements AgentEvent {}

    record CompactEvent(String message) implements AgentEvent {}

    record RetryEvent(String reason, long waitMs) implements AgentEvent {}

    record PermissionRequestEvent(String approvalId, String toolCallId, String toolName,
                                  Map<String, Object> args, int policyVersion, String description,
                                  String explanation,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}

    record AskUserRequestEvent(
            java.util.List<com.mewcode.tui.dialog.AskUserDialog.Question> questions,
            CompletableFuture<Map<String, String>> future) implements AgentEvent {}

    /** 工具实际开始执行（区别于 ToolUseEvent 的"模型准备调用"）。 */
    record ToolStartEvent(String toolId, String toolName) implements AgentEvent {}

    /** 任务终态（成功/失败/取消），是 Sidecar 生命周期闭环的退出信号。 */
    record TaskTerminalEvent(TaskStatus status, String message, int totalTurns) implements AgentEvent {}

    /** 指令状态变化（DELIVERED / ACKNOWLEDGED），由 Agent 检查点/回执解析发布。 */
    record DirectiveStatusEvent(String directiveId, DirectiveStatus status) implements AgentEvent {}

    /** 循环看门狗介入（NUDGE / ESCALATE / CANCEL），供界面/时间线呈现"疑似循环"信号。 */
    record LoopWarningEvent(int iteration, String level, String message) implements AgentEvent {}
}
