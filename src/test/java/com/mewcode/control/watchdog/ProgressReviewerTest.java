// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.control.watchdog;

import com.mewcode.control.report.ReportService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressReviewerTest {

    private static ProgressReviewer newReviewer() {
        // ReportService 传 null 模型/null store，仅用于测升级逻辑，不触发真实审查
        return new ProgressReviewer(new ReportService(null, null));
    }

    @Test
    void wanderTwiceEscalatesToCancel() {
        var r = newReviewer();
        assertSame(LoopWatchdog.Action.NUDGE,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.WANDERING, "x")).action());
        assertSame(LoopWatchdog.Action.CANCEL,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.WANDERING, "x")).action());
    }

    @Test
    void progressingResetsStreak() {
        var r = newReviewer();
        assertSame(LoopWatchdog.Action.NUDGE,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.WANDERING, "x")).action());
        assertSame(LoopWatchdog.Action.NONE,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.PROGRESSING, "x")).action());
        // 重置后再次 wandering → 又是 NUDGE 而非 CANCEL
        assertSame(LoopWatchdog.Action.NUDGE,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.WANDERING, "x")).action());
    }

    @Test
    void stuckAlsoCountsAsWander() {
        var r = newReviewer();
        assertSame(LoopWatchdog.Action.NUDGE,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.STUCK, "x")).action());
        assertSame(LoopWatchdog.Action.CANCEL,
                r.applyVerdict(new ReportService.ProgressVerdict(ReportService.ProgressStatus.STUCK, "x")).action());
    }

    /**
     * 收到队友消息后，预算整档重新起算、连串计数清零。
     *
     * <p>不重置的实测代价：team 实跑里 lead 收完汇报做逐条验收，第 31 轮 NUDGE、第 41 轮
     * CANCEL —— 而那一轮它刚收拾完摊子准备收尾，差一轮就是 SUCCEEDED。
     */
    @Test
    void resetRestartsTheBudgetAndClearsTheStreak() {
        var r = new ProgressReviewer(new ReportService(null, null), 30, 10, 2);

        // 重置前已经攒了一次 wandering
        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering()).action());

        r.reset(41);

        // 预算从重置点整档起算，而不是沿用旧账
        assertEquals(71, r.nextReviewIteration(),
                "重置后应重新给出完整一档（41 + 30），否则等于没重置");
        // 连串清零：下一次 wandering 只能重新从 NUDGE 开始
        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering()).action(),
                "重置后第一次判定不该直接 CANCEL");
    }

    private static ReportService.ProgressVerdict wandering() {
        return new ReportService.ProgressVerdict(ReportService.ProgressStatus.WANDERING, "x");
    }

    private static ReportService.ProgressVerdict wandering(double score) {
        return new ReportService.ProgressVerdict(
                ReportService.ProgressStatus.WANDERING, "x", score, "");
    }

    @Test
    void risingScoreNeverCancelsALongTask() {
        // 大任务、几十轮、每一档都在涨分 —— 这是"推进慢"，不是"卡住"。
        // 不看趋势的话，连着两档 WANDERING 就 CANCEL（默认 maxWander=2），正好误杀这类任务。
        var r = newReviewer();

        // 第一档没有基线可比，趋势无从判断，只能先给一次提醒
        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering(0.3)).action());

        // 之后每一档都在涨 → 连串被清零，永远到不了 CANCEL
        for (double s : new double[]{0.4, 0.5, 0.6, 0.7, 0.8}) {
            assertSame(LoopWatchdog.Action.NONE, r.applyVerdict(wandering(s)).action(),
                    "分数涨到 " + s + " 说明仍在朝目标靠近，不该介入");
        }
    }

    @Test
    void flatScoreStillEscalates() {
        // 分数不涨 = 真的原地打转，该介入还得介入 —— 趋势逻辑不能把判定废掉
        var r = newReviewer();

        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering(0.6)).action());
        assertSame(LoopWatchdog.Action.CANCEL, r.applyVerdict(wandering(0.6)).action());
    }

    @Test
    void unknownScoreFallsBackToThreeStateJudgement() {
        // 模型没给 SCORE 时不能因此就放行，退回原来的三态判定
        var r = newReviewer();

        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering()).action());
        assertSame(LoopWatchdog.Action.CANCEL, r.applyVerdict(wandering()).action());
    }

    @Test
    void nudgeCarriesReviewerGap() {
        // 审查员给出的具体缺口要写进提醒 —— 只给"你缺乏方向"，agent 不知道下一轮补什么
        var r = newReviewer();
        var v = new ReportService.ProgressVerdict(
                ReportService.ProgressStatus.WANDERING, "在绕圈", 0.4, "还没跑测试");

        assertTrue(r.applyVerdict(v).message().contains("还没跑测试"));
    }

    @Test
    void resetClearsTheScoreTrend() {
        var r = newReviewer();

        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering(0.4)).action());
        assertSame(LoopWatchdog.Action.NONE, r.applyVerdict(wandering(0.5)).action(),
                "分数在涨 → 清零连串");

        r.reset(41);

        // 旧分已随重置丢弃：再来 0.6 不该被当成"涨"（那是拿旧现场的分判新账）
        assertSame(LoopWatchdog.Action.NUDGE, r.applyVerdict(wandering(0.6)).action(),
                "重置丢弃旧分数，趋势不跨档沿用");
    }
}
