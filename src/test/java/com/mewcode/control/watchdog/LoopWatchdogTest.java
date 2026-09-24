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
        // 只看「重复动作」，把无产出阈值调高避免干扰
        var wd = new LoopWatchdog(4, 3, 100);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(2, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(3, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(4, "Read(a)", false).action());

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(5, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(6, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(7, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.ESCALATE, wd.onTurn(8, "Read(a)", false).action());

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(9, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(10, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(11, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.CANCEL, wd.onTurn(12, "Read(a)", false).action());
    }

    @Test
    void changingActionResetsStrike() {
        var wd = new LoopWatchdog(2, 2, 100);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(2, "Read(a)", false).action());
        // 换动作 → 重复计数清零，下一评估不再判循环
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(3, "Edit(a)", true).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(4, "Read(b)", false).action());
    }

    @Test
    void productiveTurnResetsNoProgressStreak() {
        // 只看「无产出」，把重复阈值调高避免干扰
        var wd = new LoopWatchdog(3, 100, 2);

        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(1, "Read(a)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(2, "Read(b)", false).action());
        // 第三轮有产出 → 无产出计数清零
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(3, "Edit(b)", true).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(4, "Read(c)", false).action());
        assertSame(LoopWatchdog.Action.NONE, wd.onTurn(5, "Read(d)", false).action());
        assertSame(LoopWatchdog.Action.NUDGE, wd.onTurn(6, "Read(e)", false).action());
    }

    @Test
    void defaultScheduleDensifiesOverTime() {
        // 默认节奏 8→4→2：全程重复 "Read(a)"，记录产生 verdict 的轮次。
        // 期望检查点 8（每8轮）→ 16（每8轮）→ 20（每4轮），间隔由疏变密。
        var wd = new LoopWatchdog();
        var checked = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= 22; i++) {
            var v = wd.onTurn(i, "Read(a)", false);
            if (v.action() != LoopWatchdog.Action.NONE) {
                checked.add(i);
            }
        }
        assertEquals(List.of(8, 16, 20), checked);
    }
}
