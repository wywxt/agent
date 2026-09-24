// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.control.watchdog;

import com.mewcode.control.report.ReportService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

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
}
