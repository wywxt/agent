// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.control.watchdog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LoopWatchdogTest {

    @Test
    void repeatedSignatureEscalatesNudgeThenEscalateThenCancel() {
        var wd = new LoopWatchdog(4, 3);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(2, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(3, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(4, "Read(a)[S]").action());

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(5, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(6, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(7, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.ESCALATE, wd.onTurn(8, "Read(a)[S]").action());

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(9, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(10, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(11, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.CANCEL, wd.onTurn(12, "Read(a)[S]").action());
    }

    @Test
    void changingActionResetsStrike() {
        var wd = new LoopWatchdog(2, 2);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Read(a)[S]").action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(2, "Read(a)[S]").action());
        // 换动作 → 重复计数清零，下一评估不再判循环
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(3, "Edit(a)[S]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(4, "Read(b)[S]").action());
    }

    /**
     * 签名里的成功/失败位必须参与判重，否则「重跑一条偶发失败的命令」会被误判成循环。
     *
     * <p>这条守的是 {@code Agent.agentLoop} 里签名带 {@code [F]/[S]} 的约定：
     * 参数一字不差、只是结果状态在跳，就不算「完全相同的调用」。</p>
     */
    @Test
    void flakyRetryNeverCountsAsRepeat() {
        // 每轮都是检查点，把触发机会放到最大 —— 这样还抓不到，才说明失败位真的在起作用
        var wd = new LoopWatchdog(1, 3);

        for (int i = 1; i <= 12; i++) {
            // 失败、失败、成功 循环：参数始终相同，只有结果状态在变
            String sig = "Bash(./gradlew test)[" + (i % 3 == 0 ? "S" : "F") + "]";
            assertSame(LoopWatchdog.Action.NONE, wd.onTurn(i, sig).action(),
                    "第 " + i + " 轮：重跑偶发失败的命令不该被判成循环");
        }
    }

    /** 失败位不能把判据废掉：同样的调用、同样的结果，第 3 轮起照样介入。 */
    @Test
    void repeatedCallWithSameResultStillEscalates() {
        var wd = new LoopWatchdog(1, 3);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Bash(./gradlew test)[F]").action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(2, "Bash(./gradlew test)[F]").action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(3, "Bash(./gradlew test)[F]").action());
    }

    @Test
    void defaultScheduleDensifiesOverTime() {
        // 默认节奏 8→4→2：全程重复 "Read(a)[S]"，记录产生 verdict 的轮次。
        // 期望检查点 8（每8轮）→ 16（每8轮）→ 20（每4轮），间隔由疏变密。
        var wd = new LoopWatchdog();
        var checked = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= 22; i++) {
            var v = wd.onTurn(i, "Read(a)[S]");
            if (v.action() != LoopWatchdog.Action.NONE) {
                checked.add(i);
            }
        }
        assertEquals(List.of(8, 16, 20), checked);
    }
}
