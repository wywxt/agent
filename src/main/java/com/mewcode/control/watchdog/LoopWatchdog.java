package com.mewcode.control.watchdog;

/**
 * 循环看门狗：纯确定性状态机。基于每轮工具的「动作签名」与「是否有产出」
 * 判断主 Agent 是否陷入重复/空转循环，并给出由软到硬的介入建议。
 *
 * <p>不执行任何副作用、不直接持有 token / 会话 —— 只返回 {@link Verdict}，
 * 由调用方（{@code Agent.agentLoop}）决定如何注入提醒或取消任务。</p>
 *
 * <p>两条判定信号（各自独立）：</p>
 * <ul>
 *   <li><b>重复动作</b>：连续 N 轮工具调用签名完全相同（反复读同一文件、改同一处…）</li>
 *   <li><b>无产出</b>：连续 M 轮没有任何「写/命令」类工具成功（纯读、纯思考）</li>
 * </ul>
 *
 * <p><b>自适应检查节奏</b>：不是固定间隔，而是前期宽松、越往后越密集——
 * 刚起步大概率还在正常探索，误判代价高；跑得越久越可能是真卡死，需要盯得勤。
 * 默认节奏见 {@link #DEFAULT_SCHEDULE}。</p>
 *
 * <p>介入阶梯：连续判循环则逐级升级 NUDGE → ESCALATE → CANCEL；
 * 一旦出现换动作/有产出，打击计数清零。</p>
 */
public final class LoopWatchdog {

    public enum Action { NONE, NUDGE, ESCALATE, CANCEL }

    public record Verdict(Action action, String message) {
        static final Verdict NONE = new Verdict(Action.NONE, "");
    }

    /** 默认自适应节奏：每行 = {fromIteration, checkInterval}，前期每 8 轮、中段每 4 轮、后期每 2 轮。 */
    public static final int[][] DEFAULT_SCHEDULE = {
            {1,  8},
            {16, 4},
            {32, 2},
    };

    public static final int DEFAULT_REPEAT_THRESHOLD = 3;
    public static final int DEFAULT_NO_PROGRESS_THRESHOLD = 8;

    private final int[][] schedule;
    private final int repeatThreshold;
    private final int noProgressThreshold;

    private String lastSignature = null;
    private int repeatStreak = 0;
    private int noProgressStreak = 0;
    private int strikeCount = 0;
    private int nextCheckIteration;

    public LoopWatchdog() {
        this(DEFAULT_SCHEDULE, DEFAULT_REPEAT_THRESHOLD, DEFAULT_NO_PROGRESS_THRESHOLD);
    }

    /** 固定间隔构造器（旧行为 / 便于测试与精确调参）。 */
    public LoopWatchdog(int checkInterval, int repeatThreshold, int noProgressThreshold) {
        this(new int[][]{{1, Math.max(1, checkInterval)}}, repeatThreshold, noProgressThreshold);
    }

    /** 自定义节奏 + 阈值。schedule 每行 = {fromIteration, checkInterval}，须按 fromIteration 升序。 */
    public LoopWatchdog(int[][] schedule, int repeatThreshold, int noProgressThreshold) {
        this.schedule = schedule;
        this.repeatThreshold = Math.max(2, repeatThreshold);
        this.noProgressThreshold = Math.max(2, noProgressThreshold);
        this.nextCheckIteration = intervalFor(1);
    }

    /**
     * 每轮工具执行后喂一次心跳。
     *
     * @param iteration     当前轮次（从 1 起）
     * @param turnSignature 本轮工具调用的规范化签名（工具名+参数，多调用以 " | " 连接）
     * @param productive    本轮是否有「写/命令」类工具成功执行
     */
    public Verdict onTurn(int iteration, String turnSignature, boolean productive) {
        // 1) 更新连续重复计数
        if (turnSignature != null && !turnSignature.isEmpty()) {
            repeatStreak = turnSignature.equals(lastSignature) ? repeatStreak + 1 : 1;
        } else {
            repeatStreak = 0;
        }
        lastSignature = turnSignature;

        // 2) 更新连续无产出计数
        noProgressStreak = productive ? 0 : noProgressStreak + 1;

        // 3) 自适应：还没到下一个检查点就跳过
        if (iteration < nextCheckIteration) {
            return Verdict.NONE;
        }
        nextCheckIteration = iteration + intervalFor(iteration);

        boolean looping = repeatStreak >= repeatThreshold || noProgressStreak >= noProgressThreshold;
        if (!looping) {
            strikeCount = 0;
            return Verdict.NONE;
        }

        strikeCount++;
        return switch (strikeCount) {
            case 1 -> new Verdict(Action.NUDGE, nudgeText(repeatStreak, noProgressStreak));
            case 2 -> new Verdict(Action.ESCALATE, escalateText(repeatStreak, noProgressStreak));
            default -> new Verdict(Action.CANCEL, cancelText());
        };
    }

    /** 当前轮次对应的检查间隔（取 schedule 中最后一个 fromIteration ≤ iteration 的行）。 */
    private int intervalFor(int iteration) {
        int interval = schedule[0][1];
        for (int[] row : schedule) {
            if (iteration >= row[0]) {
                interval = row[1];
            }
        }
        return interval;
    }

    private static String nudgeText(int repeat, int idle) {
        return "[循环提醒] 检测到你可能在重复操作（连续重复动作 " + repeat
                + " 轮 / 无实质产出 " + idle + " 轮）。请检查是否陷入循环："
                + "若任务目标已达成，直接给出最终结论；否则换一个与之前不同的动作。";
    }

    private static String escalateText(int repeat, int idle) {
        return "[循环告警] 你仍在重复或空转（重复 " + repeat + " 轮 / 空转 " + idle
                + " 轮）。请立即停止重复操作：总结当前进度给出结论，或明确下一步的新动作。";
    }

    private static String cancelText() {
        return "连续多轮检测到循环且未能收敛，看门狗终止本次任务。";
    }
}
