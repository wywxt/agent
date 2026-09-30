package com.mewcode.control.watchdog;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环看门狗效果测评（非单元测试）。
 *
 * <p>第一层现在只剩一条判据：<b>连续 N 轮「同样的调用拿到同样的结果」</b>。
 * 本测评拿一组**已知该抓 / 绝不该抓**的场景喂进去，比对实际判定 ——
 * 判断质量本身没法单测，只能用这种办法看它有没有抓错。</p>
 *
 * <p>签名规则与 {@code Agent.agentLoop} 一致：{@code 工具名(参数)[成功F/S位]}。</p>
 *
 * <p>跑法：{@code java -cp "build/libs/mewcode.jar;build/classes/java/test"
 * com.mewcode.control.watchdog.LoopEval}</p>
 */
public final class LoopEval {

    private static final int TURNS = 40;

    /** 一次判定的结果：首次 NUDGE / ESCALATE / CANCEL 的轮次（0 = 从未发生）。 */
    private record Outcome(int nudgeAt, int escalateAt, int cancelAt) {
        String show() {
            if (nudgeAt == 0 && escalateAt == 0 && cancelAt == 0) return "不介入";
            return "NUDGE@" + nudgeAt
                    + (escalateAt > 0 ? " ESCAL@" + escalateAt : "")
                    + (cancelAt > 0 ? " CANCEL@" + cancelAt : "");
        }
    }

    public static void main(String[] args) {
        System.out.println("循环看门狗 · 效果测评（每个场景 " + TURNS + " 轮）\n");
        System.out.printf("%-42s │ %-9s │ %-26s │ %s%n",
                "场景", "期望", "实际", "结论");
        System.out.println("─".repeat(112));

        int hit = 0, miss = 0, falseAlarm = 0;
        for (var s : scenarios()) {
            var o = run(s.turns());
            boolean fired = o.cancelAt() > 0 || o.escalateAt() > 0 || o.nudgeAt() > 0;
            boolean ok = fired == s.shouldFire();
            if (ok) hit++;
            else if (s.shouldFire()) miss++;
            else falseAlarm++;

            System.out.printf("%-42s │ %-9s │ %-26s │ %s%n",
                    s.name(),
                    s.shouldFire() ? "该抓" : "不该抓",
                    o.show(),
                    ok ? "✅" : (s.shouldFire() ? "❌ 漏判" : "❌ 误报"));
        }

        System.out.println("─".repeat(112));
        System.out.println("符合期望 " + hit + " / " + (hit + miss + falseAlarm)
                + "    漏判 " + miss + "    误报 " + falseAlarm);
        System.out.println();
        System.out.println("设计取舍：第一层只管「一模一样地重复」，认的是事实不是判断。");
        System.out.println("标注 [交给第二层] 的场景第一层故意不抓 —— 它们要靠模型判「有没有方向」，");
        System.out.println("确定性规则判不了，硬判就会误杀（这正是删掉「无产出轴」的原因）。");
    }

    private static Outcome run(List<String> turns) {
        var wd = new LoopWatchdog();
        int nudge = 0, escalate = 0, cancel = 0;
        for (int i = 0; i < turns.size(); i++) {
            var v = wd.onTurn(i + 1, turns.get(i));
            int it = i + 1;
            switch (v.action()) {
                case NUDGE -> { if (nudge == 0) nudge = it; }
                case ESCALATE -> { if (escalate == 0) escalate = it; }
                case CANCEL -> { if (cancel == 0) cancel = it; }
                case NONE -> {}
            }
        }
        return new Outcome(nudge, escalate, cancel);
    }

    // ── 场景 ─────────────────────────────────────────────────

    private record Scenario(String name, boolean shouldFire, List<String> turns) {}

    private static List<Scenario> scenarios() {
        return List.of(
                // — 该抓：完全相同的调用，完全相同的结果 —
                new Scenario("[该抓] 反复读同一文件", true,
                        repeat("ReadFile({\"path\":\"A.java\"})[S]")),
                new Scenario("[该抓] 反复跑同一条失败命令", true,
                        repeat("Bash({\"command\":\"./gradlew test\"})[F]")),
                new Scenario("[该抓] 同一处编辑反复失败（参数一字不差）", true,
                        repeat("EditFile({\"path\":\"F.java\",\"old\":\"x\",\"new\":\"y\"})[F]")),

                // — 该抓：多工具的一轮，整组签名都不变 —
                new Scenario("[该抓] 每轮都是「读A + 跑测试失败」同一组", true,
                        repeat("Bash({\"command\":\"./gradlew test\"})[F] | ReadFile({\"path\":\"A.java\"})[S]")),

                // — 不该抓：结果状态在变（这就是加失败位要挡的误报）—
                new Scenario("[不该抓] flaky 命令：失败→失败→成功 循环", false,
                        flaky("Bash({\"command\":\"./gradlew test\"})")),

                // — 不该抓：交给第二层的软空转（第一层结构上就看不见）—
                new Scenario("[交给第二层] 读A/读B 交替", false, alternatingReads()),
                new Scenario("[交给第二层] 每轮改不同文件、但都失败", false, allFail()),

                // — 不该抓：正常推进 —
                new Scenario("[不该抓] 逐条纯读 30 个不同文件", false, readSweep()),
                new Scenario("[不该抓] 连续改 30 个不同文件（成功）", false, bigTask()),
                new Scenario("[不该抓] TDD：改→测→改→测", false, tddLoop()),
                new Scenario("[不该抓] 先探索后收敛：读 6 轮后开始改", false, exploreThenWork())
        );
    }

    private static List<String> repeat(String sig) {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) l.add(sig);
        return l;
    }

    /** 同一条命令，失败/失败/成功后重来 —— 参数始终相同，只有结果状态在跳。 */
    private static List<String> flaky(String cmd) {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) l.add(cmd + "[" + (i % 3 == 2 ? "S" : "F") + "]");
        return l;
    }

    /** 读 A、读 B 交替 —— 相邻签名永不相等，第一层结构上看不到重复。 */
    private static List<String> alternatingReads() {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) {
            l.add("ReadFile({\"path\":\"" + (char) ('A' + i % 2) + ".java\"})[S]");
        }
        return l;
    }

    /** 每轮换一个文件改，但全都报错 —— 签名不同，结果相同。 */
    private static List<String> allFail() {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) {
            l.add("EditFile({\"path\":\"F" + (i % 5) + ".java\"})[F]");
        }
        return l;
    }

    private static List<String> readSweep() {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) l.add("ReadFile({\"path\":\"F" + i + ".java\"})[S]");
        return l;
    }

    private static List<String> bigTask() {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) l.add("EditFile({\"path\":\"F" + i + ".java\"})[S]");
        return l;
    }

    private static List<String> tddLoop() {
        var l = new ArrayList<String>();
        for (int i = 0; i < TURNS; i++) {
            int k = i / 2;
            l.add(i % 2 == 0
                    ? "EditFile({\"path\":\"F" + k + ".java\"})[S]"
                    : "Bash({\"command\":\"./gradlew test\"})[F]");
        }
        return l;
    }

    private static List<String> exploreThenWork() {
        var l = new ArrayList<String>();
        for (int i = 0; i < 6; i++) l.add("ReadFile({\"path\":\"P" + i + ".java\"})[S]");
        for (int i = 6; i < TURNS; i++) l.add("EditFile({\"path\":\"F" + i + ".java\"})[S]");
        return l;
    }
}
