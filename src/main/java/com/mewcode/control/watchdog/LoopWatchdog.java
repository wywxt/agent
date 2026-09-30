package com.mewcode.control.watchdog;

/**
 * 循环看门狗：纯确定性状态机。基于每轮工具的「动作签名」判断主 Agent
 * 是否陷入重复循环，并给出由软到硬的介入建议。
 *
 * <p>不执行任何副作用、不直接持有 token / 会话 —— 只返回 {@link Verdict}，
 * 由调用方（{@code Agent.agentLoop}）决定如何注入提醒或取消任务。</p>
 *
 * <p><b>只有一条判据：重复动作</b> —— 连续 N 轮签名完全相同。签名由调用方构造，
 * 含工具名、参数，以及该次调用的<b>成功/失败位</b>，也就是「同样的调用拿到同样的结果」
 * 才算重复。只取结果的状态、不取结果正文：正文里常带耗时、行数这类每轮都在变的字段，
 * 纳进来会把真重复打散。</p>
 *
 * <p><b>为什么不再判「无产出」</b>（曾经的第二条轴：连续 M 轮没有写/命令类工具成功）：
 * 它无法区分「反复试错」和「正常的纯读探索」—— 两者在它眼里完全一样，都是「有工具在跑、
 * 但没一个是写」。实跑里一次合法的 19 文件读取扫描在第 8 轮被 NUDGE、第 16 轮被 ESCALATE，
 * 差一轮就被误杀。判断「有没有方向」是模型擅长、确定性规则不擅长的，那部分交给第二层
 * {@link ProgressReviewer}。宁可漏判，不可误判。</p>
 *
 * <p><b>自适应检查节奏</b>：不是固定间隔，而是前期宽松、越往后越密集——
 * 刚起步大概率还在正常探索，误判代价高；跑得越久越可能是真卡死，需要盯得勤。
 * 默认节奏见 {@link #DEFAULT_SCHEDULE}。</p>
 *
 * <p>介入阶梯：连续判循环则逐级升级 NUDGE → ESCALATE → CANCEL；一旦换动作，打击计数清零。</p>
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

    private final int[][] schedule;
    private final int repeatThreshold;

    private String lastSignature = null;
    private int repeatStreak = 0;
    private int strikeCount = 0;
    private int nextCheckIteration;

    public LoopWatchdog() {
        this(DEFAULT_SCHEDULE, DEFAULT_REPEAT_THRESHOLD);
    }

    /** 固定间隔构造器（便于测试与精确调参）。 */
    public LoopWatchdog(int checkInterval, int repeatThreshold) {
        this(new int[][]{{1, Math.max(1, checkInterval)}}, repeatThreshold);
    }

    /** 自定义节奏 + 阈值。schedule 每行 = {fromIteration, checkInterval}，须按 fromIteration 升序。 */
    public LoopWatchdog(int[][] schedule, int repeatThreshold) {
        this.schedule = schedule;
        this.repeatThreshold = Math.max(2, repeatThreshold);
        this.nextCheckIteration = intervalFor(1);
    }

    /**
     * 每轮工具执行后喂一次心跳。
     *
     * @param iteration     当前轮次（从 1 起）
     * @param turnSignature 本轮工具调用的规范化签名（工具名+参数+成功/失败位，多调用以 " | " 连接）
     */
    public Verdict onTurn(int iteration, String turnSignature) {
        // 1) 更新连续重复计数
        if (turnSignature != null && !turnSignature.isEmpty()) {
            repeatStreak = turnSignature.equals(lastSignature) ? repeatStreak + 1 : 1;
        } else {
            repeatStreak = 0;
        }
        lastSignature = turnSignature;

        // 2) 自适应：还没到下一个检查点就跳过
        if (iteration < nextCheckIteration) {
            return Verdict.NONE;
        }
        nextCheckIteration = iteration + intervalFor(iteration);

        if (repeatStreak < repeatThreshold) {
            strikeCount = 0;
            return Verdict.NONE;
        }

        strikeCount++;
        return switch (strikeCount) {
            case 1 -> new Verdict(Action.NUDGE, nudgeText(repeatStreak));
            case 2 -> new Verdict(Action.ESCALATE, escalateText(repeatStreak));
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

    private static String nudgeText(int repeat) {
        return "[循环提醒] 检测到你可能在重复操作（连续 " + repeat
                + " 轮执行了完全相同的调用、并拿到相同的结果）。请检查是否陷入循环："
                + "若任务目标已达成，直接给出最终结论；否则换一个与之前不同的动作。";
    }

    private static String escalateText(int repeat) {
        return "[循环告警] 你仍在重复同样的操作（连续 " + repeat
                + " 轮）。请立即停止重复操作：总结当前进度给出结论，或明确下一步的新动作。";
    }

    private static String cancelText() {
        return "连续多轮检测到循环且未能收敛，看门狗终止本次任务。";
    }
}
