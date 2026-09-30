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

    /**
     * 工具实际开始执行（区别于 ToolUseEvent 的"模型准备调用"）。
     *
     * <p>带上 {@code args} 是为了让消费方能据此描述「正在干什么」：被闸门或权限拒掉的
     * 调用**不发这个事件**，所以挂在这里的活动文案天然只反映真的跑起来的那些动作。
     */
    record ToolStartEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    /** 任务终态（成功/失败/取消），是 Sidecar 生命周期闭环的退出信号。 */
    record TaskTerminalEvent(TaskStatus status, String message, int totalTurns) implements AgentEvent {}

    /** 指令状态变化（DELIVERED / ACKNOWLEDGED），由 Agent 检查点/回执解析发布。 */
    record DirectiveStatusEvent(String directiveId, DirectiveStatus status) implements AgentEvent {}

    /** 循环看门狗介入（NUDGE / ESCALATE / CANCEL），供界面/时间线呈现"疑似循环"信号。 */
    record LoopWarningEvent(int iteration, String level, String message) implements AgentEvent {}

    /**
     * 某个队友某一轮的 token 增量。
     *
     * <p><b>为什么必须是独立类型，而不是复用 {@link UsageEvent}：</b>
     * {@code UsageEvent} 是 lead 自己的用量，会被 {@code RemoteServer} 以
     * {@code type=usage} 广播；把队友的用量混进去，lead 的上下文就等于对自己撒谎。
     *
     * <p>带**每轮增量**而非累计，是为了不再重复踩「累计还是增量」的坑 ——
     * {@code Agent} 的 usage 累计以 run 为界，跨 run 会归零（见
     * {@code TeammateProgress.beginRun}）。增量可以直接相加，无需知道 run 边界。
     *
     * <p>此前队友事件只有两条旁路（{@code progress.jsonl} 与邮箱），外部观测不到
     * 多 agent 的真实成本；这条事件是第三个出口。
     */
    record TeammateUsageEvent(String team, String member, int turn, long deltaTokens) implements AgentEvent {}
}
