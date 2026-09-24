package com.mewcode.control.task;

/**
 * 任务结束结果：终态 + 失败原因 + 实际迭代轮数。
 * 由 Agent 的 agentLoop 在 finally 中计算并完成对应 future。
 */
public record TaskOutcome(TaskStatus status, String message, int totalTurns) {

    public static TaskOutcome succeeded(int turns) {
        return new TaskOutcome(TaskStatus.SUCCEEDED, null, turns);
    }

    public static TaskOutcome failed(String msg) {
        return new TaskOutcome(TaskStatus.FAILED, msg, 0);
    }

    public static TaskOutcome cancelled() {
        return new TaskOutcome(TaskStatus.CANCELLED, null, 0);
    }
}
