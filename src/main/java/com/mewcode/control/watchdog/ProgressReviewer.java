package com.mewcode.control.watchdog;

import com.mewcode.control.report.ReportService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 第二层循环看门狗：基于小模型的「进度审查」。
 *
 * <p>补第一层 {@link LoopWatchdog} 的盲区——后者只认一条确定性事实「连续多轮同样的调用
 * 拿到同样的结果」，抓不到「每轮都换动作、但缺乏方向地在绕圈」的软空转。那类情况确定性
 * 规则判不了（分不清「反复试错」和「正常探索」），只能交给模型。</p>
 *
 * <p>触发：iteration ≥ budget 后，每 interval 轮异步审查一次（不阻塞主循环）。
 * 介入：审查结果在后续轮次由主循环线程应用（保证注入/取消的线程安全），
 * 连续 maxWanderBeforeCancel 次 WANDERING/STUCK 才 CANCEL，中途 PROGRESSING 则清零。</p>
 */
public final class ProgressReviewer {

    public static final int DEFAULT_BUDGET = 30;
    public static final int DEFAULT_INTERVAL = 10;
    public static final int DEFAULT_MAX_WANDER = 2;

    private final ReportService report;
    /**
     * 任务的原始目标，随每次审查一起交给模型。
     *
     * <p>审查员要回答的是「有没有方向」，而方向是相对目标而言的 ——
     * 不给目标，它只能靠工具名的表面条理去猜。见
     * {@code ConversationManager#firstUserRequest}。</p>
     */
    private final String objective;
    private final int budget;
    private final int interval;
    private final int maxWanderBeforeCancel;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<ReportService.ProgressVerdict> pending = new AtomicReference<>();
    private int wanderStreak = 0;
    private int nextReviewIteration;
    /** 上一档审查给出的完成度（0~1）；{@link ReportService.ProgressVerdict#SCORE_UNKNOWN} 表示未知。 */
    private double lastScore = ReportService.ProgressVerdict.SCORE_UNKNOWN;

    /**
     * 估算每轮产生的事件条数：一次工具调用会写下 PROPOSED/STARTED/FINISHED 三条，
     * 常规一轮约两次调用 —— 取 6。用于把审查间隔换算成事件窗口。
     */
    private static final int EVENTS_PER_TURN_ESTIMATE = 6;

    public ProgressReviewer(ReportService report) {
        this(report, null, DEFAULT_BUDGET, DEFAULT_INTERVAL, DEFAULT_MAX_WANDER);
    }

    public ProgressReviewer(ReportService report, String objective) {
        this(report, objective, DEFAULT_BUDGET, DEFAULT_INTERVAL, DEFAULT_MAX_WANDER);
    }

    public ProgressReviewer(ReportService report, int budget, int interval, int maxWanderBeforeCancel) {
        this(report, null, budget, interval, maxWanderBeforeCancel);
    }

    public ProgressReviewer(ReportService report, String objective,
                            int budget, int interval, int maxWanderBeforeCancel) {
        this.report = report;
        this.objective = objective;
        this.budget = Math.max(1, budget);
        this.interval = Math.max(1, interval);
        this.maxWanderBeforeCancel = Math.max(1, maxWanderBeforeCancel);
        this.nextReviewIteration = this.budget;
    }

    /**
     * 收到外部输入后把预算整档重新起算（当前唯一调用方：{@code Agent.awaitBusyPeers}
     * 等到了队友消息那一刻）。
     *
     * <p><b>为什么必须重新起算：</b>预算的前提是「你已经在**没有外部输入**的情况下自转了
     * 很多轮」。队友消息恰恰是外部输入 —— 它带来新事实（谁写完了什么、卡在哪），此前
     * 「缺乏方向」的判断不再成立。继续拿旧账催这个 agent 收手，等于在惩罚「它去听了队友
     * 的话」。所以在途的那次审查结果也一并丢掉：那是按旧现场打的，重启后再应用就是用
     * 旧证据判新账。
     *
     * <p><b>实测代价（2026-09-25 的 team 实跑，这一档不重置就会撞上）：</b>lead 收到队友
     * 汇报后逐条验收（一条命令一轮），第 31 轮被 NUDGE、**第 41 轮被 CANCEL** —— 而那一轮
     * 它刚做完 TaskUpdate + TeamDelete，也就是收拾摊子准备收尾。差一轮就是 SUCCEEDED；
     * 取消之后整支队伍的任务被记成 CANCELLED，而交付物其实是 9/9 通过。
     *
     * @param iteration 重新起算的轮次（通常是收到消息的那一轮）
     */
    public void reset(int iteration) {
        wanderStreak = 0;
        lastScore = ReportService.ProgressVerdict.SCORE_UNKNOWN;
        pending.set(null);
        nextReviewIteration = iteration + budget;
    }

    /** 下一次审查的轮次。包可见：给测试断言预算真的整档重算。 */
    int nextReviewIteration() { return nextReviewIteration; }

    /**
     * 每轮（主循环线程）调用。先应用上一轮异步审查的结果（若有），再按需触发新一轮审查。
     * 返回要执行的介入动作；无则返回 {@link LoopWatchdog.Verdict#NONE}。
     */
    public LoopWatchdog.Verdict onTurn(int iteration, String taskId) {
        LoopWatchdog.Verdict apply = LoopWatchdog.Verdict.NONE;
        var v = pending.getAndSet(null);
        if (v != null) {
            apply = applyVerdict(v);
        }
        maybeTrigger(iteration, taskId);
        return apply;
    }

    /** 把一次审查结论转成介入动作，并更新连续 wandering 计数。包可见，便于测试。 */
    LoopWatchdog.Verdict applyVerdict(ReportService.ProgressVerdict v) {
        if (v == null || v.status() == null) return LoopWatchdog.Verdict.NONE;

        // 趋势优先：分数在涨说明它仍在朝目标靠近，哪怕这一档被判成 WANDERING/STUCK。
        // 这条专门兜「任务大、推进慢」—— 每档都在涨分就不该被连串计数催停。
        // 分数未知（模型没给 SCORE）时不动趋势，退回原来的三态判定。
        boolean rising = v.score() >= 0 && lastScore >= 0 && v.score() > lastScore;
        if (v.score() >= 0) lastScore = v.score();

        if (v.status() == ReportService.ProgressStatus.PROGRESSING || rising) {
            wanderStreak = 0;
            return LoopWatchdog.Verdict.NONE;
        }
        wanderStreak++;
        if (wanderStreak >= maxWanderBeforeCancel) {
            return new LoopWatchdog.Verdict(LoopWatchdog.Action.CANCEL,
                    "进度审查连续多次判定任务缺乏方向且未收敛，看门狗终止本次任务。");
        }
        return new LoopWatchdog.Verdict(LoopWatchdog.Action.NUDGE,
                constrainText(v.summary(), v.missing()));
    }

    private void maybeTrigger(int iteration, String taskId) {
        if (report == null || taskId == null) return;
        if (iteration < nextReviewIteration) return;
        if (!inFlight.compareAndSet(false, true)) return;
        nextReviewIteration = iteration + interval;
        Thread.ofVirtual().name("progress-review").start(() -> {
            try {
                pending.set(report.assessProgress(taskId, iteration, objective,
                        interval * EVENTS_PER_TURN_ESTIMATE));
            } catch (Exception ignored) {
                // 小模型异常/超时 → 不介入，降级为无审查
            } finally {
                inFlight.set(false);
            }
        });
    }

    private static String constrainText(String summary, String missing) {
        String detail = (summary == null || summary.isBlank()) ? "" : "审查摘要：" + summary + "\n";
        // 审查员给出的具体差距，优先级最高 —— 它把「你缺乏方向」这句泛泛的结论
        // 换成了可执行的缺口清单，agent 才知道下一轮该补什么。
        String gap = (missing == null || missing.isBlank()) ? "" : "审查员认为还差：" + missing + "\n";
        return "[进度审查] 审查员认为你的推进相对目标不够明确。请先停下，按下面四点逐一回答：\n"
                + "1) 目标（一句话）\n2) 已完成（清单）\n3) 还差（清单）\n4) 下一步的唯一一个动作\n"
                + gap + detail
                + "如果说不清目标或下一步，直接结束本轮并说明卡点。";
    }
}
