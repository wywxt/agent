package com.mewcode.control.watchdog;

import com.mewcode.control.report.ReportService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 第二层循环看门狗：基于小模型的「进度审查」。
 *
 * <p>补第一层 {@link LoopWatchdog} 的盲区——后者只能靠确定性规则抓「重复」和「无产出」，
 * 抓不到「每轮都有产出、还换着工具、但缺乏方向地在绕圈」的软空转。</p>
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
    private final int interval;
    private final int maxWanderBeforeCancel;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<ReportService.ProgressVerdict> pending = new AtomicReference<>();
    private int wanderStreak = 0;
    private int nextReviewIteration;

    public ProgressReviewer(ReportService report) {
        this(report, DEFAULT_BUDGET, DEFAULT_INTERVAL, DEFAULT_MAX_WANDER);
    }

    public ProgressReviewer(ReportService report, int budget, int interval, int maxWanderBeforeCancel) {
        this.report = report;
        this.interval = Math.max(1, interval);
        this.maxWanderBeforeCancel = Math.max(1, maxWanderBeforeCancel);
        this.nextReviewIteration = Math.max(1, budget);
    }

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
        if (v.status() == ReportService.ProgressStatus.PROGRESSING) {
            wanderStreak = 0;
            return LoopWatchdog.Verdict.NONE;
        }
        wanderStreak++;
        if (wanderStreak >= maxWanderBeforeCancel) {
            return new LoopWatchdog.Verdict(LoopWatchdog.Action.CANCEL,
                    "进度审查连续多次判定任务缺乏方向且未收敛，看门狗终止本次任务。");
        }
        return new LoopWatchdog.Verdict(LoopWatchdog.Action.NUDGE, constrainText(v.summary()));
    }

    private void maybeTrigger(int iteration, String taskId) {
        if (report == null || taskId == null) return;
        if (iteration < nextReviewIteration) return;
        if (!inFlight.compareAndSet(false, true)) return;
        nextReviewIteration = iteration + interval;
        Thread.ofVirtual().name("progress-review").start(() -> {
            try {
                pending.set(report.assessProgress(taskId, iteration));
            } catch (Exception ignored) {
                // 小模型异常/超时 → 不介入，降级为无审查
            } finally {
                inFlight.set(false);
            }
        });
    }

    private static String constrainText(String summary) {
        String detail = (summary == null || summary.isBlank()) ? "" : "审查摘要：" + summary + "\n";
        return "[进度审查] 你已运行过多轮次，疑似缺乏明确方向、进展过慢。请先停下，按下面四点逐一回答：\n"
                + "1) 目标（一句话）\n2) 已完成（清单）\n3) 还差（清单）\n4) 下一步的唯一一个动作\n"
                + detail
                + "如果说不清目标或下一步，直接结束本轮并说明卡点。";
    }
}
