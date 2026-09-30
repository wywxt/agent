// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每轮汇报与「进度不唤醒 lead」的回归测试。
 *
 * <p>背景：{@code Agent} 每轮都发 {@code TurnComplete}，但两处都没人消费 ——
 * {@code TeammateRunner.drainAgentEvents} 直接忽略它，于是 lead 手上每个队友只有
 * 两条空 {@code [idle]}，中间全空；而队友 transcript 没有时间戳、产物 mtime 又
 * 会被事后人工修复覆盖，导致「多 agent 快在哪」根本没有数据可答。
 *
 * <p>另一半同样重要：进度是高频消息，若让它单独唤醒 lead，lead 每轮都要带着整段
 * 上下文再发一次 API —— 那是观测手段自己造出来的耗时，会把实验测量搞脏。
 */
class TeammateProgressReportTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void redirectUserDir() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void restoreUserDir() {
        if (originalUserDir != null) System.setProperty("user.dir", originalUserDir);
    }

    /** 真实事件序列里只有 {@code ToolStartEvent} 参与计量与文案（一个调用恰好一次）。 */
    private static void tool(TeammateProgress p, String name, String path) {
        p.recordToolExecuted(name, Map.of("file_path", path));
    }

    /**
     * 一次工具调用只算一次，活动文案取自同一个事件。
     *
     * <p>真实 provider 对每个工具调用发 {@code ToolCallStart} + {@code ToolCallComplete}，
     * {@code Agent} 两个都转成 {@code ToolUseEvent}。计数若挂在那上面就会翻倍 ——
     * 那正是 TUI「N tools」显示虚高的原因，也会让逐轮记录里的 tools 变成假数据。
     * 现在计数与文案都只认 {@code ToolStartEvent}，两者天然一致。
     */
    @Test
    void announcedAndCompletedToolCallIsCountedOnce() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        p.recordToolExecuted("Bash", Map.of("command", "ls -la"));

        assertEquals(1, p.getToolUseCount(), "一次工具调用只能算一次");
        assertEquals(1, p.completeTurn(1).tools(), "逐轮计数同样只能算一次");
        assertEquals("Running ls -la", p.getActivitySummary(), "文案要反映真的跑起来的那个调用");
    }

    /**
     * 没跑起来的调用不得在活动文案里留下痕迹 —— 这是 2026-09-25 team 实跑里踩到的坑。
     *
     * <p>当时某队友计划阶段的活动文案是 {@code Running ls -la}，同轮 tools 计数却是 1：
     * 真相是它先调了一个只读工具（真的跑了，计 1），随后又试了一次 Bash（被只读闸门
     * 拒掉）。文案原先挂在 {@code ToolUseEvent}（模型准备调用）上，被拒那次照样覆盖了
     * 文案，于是 lead 读到的 {@code [progress]} 显示它在跑命令 —— 我据此还差点误判
     * 闸门失效。现在的接口里根本没法记下「没跑的动作」，这个坑不再可表达。
     */
    @Test
    void refusedToolCallLeavesNoTraceInActivity() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        // 那一轮唯一真的跑起来的动作
        p.recordToolExecuted("ReadFile", Map.of("file_path", "PLAN.md"));

        // 同轮被闸门拒掉的 Bash：只发 ToolUseEvent、不发 ToolStartEvent，
        // 也就是这里什么都不调 —— 正因为它调不动任何东西，文案才不会被污染。

        assertEquals("Reading PLAN.md", p.getActivitySummary(),
                "被拒的调用不该覆盖掉真正跑起来的那个动作");
        assertEquals(1, p.completeTurn(1).tools(), "被拒的调用不计数");
    }

    // ── 逐轮计量 ────────────────────────────────────────────────────

    @Test
    void turnRecordCarriesPerTurnDeltasNotRunningTotals() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        tool(p, "WriteFile", "backend/server.js");
        tool(p, "Bash", "node server.js");
        p.recordTokens(100, 20);
        var first = p.completeTurn(1);

        assertNotNull(first);
        assertEquals(1, first.turn());
        assertEquals(2, first.tools(), "本轮工具数");
        assertEquals(120, first.tokens(), "本轮 token 应是增量，不是累计");
        assertTrue(first.durationMs() >= 0);

        // 第二轮：累计值必须不影响本轮的增量。Agent 发的 UsageEvent 带的是
        // **本次 run 内的累计**（Agent.java:497 传的是 totalInput/totalOutput，
        // 而那两个变量是 agentLoop 的局部量，每次 run() 归零），所以 400+50 表示
        // 「本次 run 累计到 450」，本轮增量 = 450 - 120 = 330。
        // 注意这里**没有** beginRun() —— 在同一 run 内跨 turn，正是真实的多 iteration 形态。
        tool(p, "EditFile", "backend/server.js");
        p.recordTokens(400, 50);
        var second = p.completeTurn(2);

        assertNotNull(second);
        assertEquals(1, second.tools());
        assertEquals(330, second.tokens(), "第二轮 token 增量 = 累计值之差");
        assertEquals(3, p.getToolUseCount(), "累计工具数仍要正确");
        assertEquals(450, p.getTokenCount(), "累计 token 保持 UsageEvent 的绝对值");
    }

    /**
     * 跨 run 的 token 累计。
     *
     * <p>真实链路里每个队友回合都新起一次 {@code agent.run()}（{@code TeammateRunner.runTurn}），
     * 而 {@code Agent} 的累计量是方法局部变量、每次归零。旧实现直接覆盖赋值，第二个回合
     * 必然算出负数 —— 实测 49 条轮记录里 45 条为 0、1 条为 -176。
     */
    @Test
    void beginRunMakesCrossRunTokensAccumulateInsteadOfGoingNegative() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        p.recordTokens(100, 20);
        var first = p.completeTurn(1);
        assertNotNull(first);
        assertEquals(120, first.tokens());

        // 新的一次 agent.run()：Agent 内部累计归零，从 2 重新开始数。
        p.beginRun();
        p.recordTokens(1, 1);
        var second = p.completeTurn(2);

        assertNotNull(second);
        assertEquals(2, second.tokens(), "跨 run 后本轮增量应重新起算，而不是 2-120");
        assertEquals(122, p.getTokenCount(), "跨 run 的累计要垒起来");
    }

    /** provider 某轮不上报 usage（StreamEnd 给 0/0）时，增量应为 0 而不是负数。 */
    @Test
    void missingUsageDoesNotProduceNegativeTokens() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        p.recordTokens(100, 20);
        p.completeTurn(1);

        p.beginRun();
        p.recordTokens(0, 0);
        var second = p.completeTurn(2);

        assertNotNull(second);
        assertEquals(0, second.tokens(), "没上报就是 0，不能是负数");
        assertEquals(120, p.getTokenCount(), "已累计的量不应被清零");
    }

    /**
     * 兜底：漏调 {@code beginRun()} 时，值回退本身就是 run 边界。
     *
     * <p>这条防的是「将来有人新增 {@code agent.run()} 调用点忘了标边界」。
     */
    @Test
    void fallingUsageWithoutBeginRunIsTreatedAsRunBoundary() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        p.recordTokens(100, 20);
        p.completeTurn(1);

        // 故意不调 beginRun()：值从 120 掉到 2，只能靠回退检测自愈。
        p.recordTokens(1, 1);
        var second = p.completeTurn(2);

        assertNotNull(second);
        assertEquals(2, second.tokens());
        assertEquals(122, p.getTokenCount());
    }

    /**
     * 精确边界与启发式检测的分水岭 —— 这条是 {@code beginRun()} 存在的理由。
     *
     * <p>上一轮很短（run 内累计 10），这一轮首个 turn 就很大（run 内累计 500）。
     * 值**不回退**，纯靠「变小了就是新 run」的检测会把它当成同一 run 的续接，
     * 于是少算 10。只有显式标边界才准确。
     */
    @Test
    void beginRunIsExactWhenNewRunStartsLargerThanPreviousTotal() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        p.recordTokens(8, 2);          // run 1 内累计 10
        assertEquals(10, p.completeTurn(1).tokens());

        p.beginRun();
        p.recordTokens(300, 200);      // run 2 首个 turn 累计 500 —— 比上一轮大
        var second = p.completeTurn(2);

        assertEquals(500, second.tokens(), "本轮增量只能算 run 2 自己的量");
        assertEquals(510, p.getTokenCount(), "两次 run 的累计要垒起来");
    }

    /**
     * {@code Agent} 在 ExitPlanMode 路径上连着发 {@code TurnComplete(n)} 与
     * {@code LoopComplete(n)}（同一个 n）—— 收两次会重复汇报同一轮。
     */
    @Test
    void duplicateTurnNumberIsReportedOnlyOnce() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        tool(p, "Bash", "ls");

        assertNotNull(p.completeTurn(3), "TurnComplete(3) 应付汇报");
        assertNull(p.completeTurn(3), "紧随其后的 LoopComplete(3) 不得再报一轮");
    }

    /** 失败/取消路径在 finally 里发 {@code LoopComplete(0)}，那不是「跑过一轮」。 */
    @Test
    void failurePlaceholderTurnIsNeverReported() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        assertNull(p.completeTurn(0));
    }

    /** 终轮（无工具调用那轮）只有 LoopComplete，但它的轮次号必须能被汇报。 */
    @Test
    void terminalTurnNumberIsReported() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        assertNotNull(p.completeTurn(1));
        assertNotNull(p.completeTurn(2), "终轮的 LoopComplete(2) 是新的一轮");
    }

    /**
     * 第二个 run 的轮次号必须继续往后排，而不是从 1 重数被去重掉。
     *
     * <p>轮次号与 usage 一样是以 run 为界的：{@code Agent.agentLoop} 的轮计数是局部变量，
     * 开工那次 {@code run()} 又从 1 开始。而 {@code reportedTurn} 是跨 run 的全局游标，
     * 于是开工之后**每一轮都撞上「1 &le; 已汇报的 N」**被静默丢弃 ——
     * 实测里的形状就是「计划轮有记录、开工之后一条不落」（连同 token 一起消失），
     * 多 agent 的分解诊断因此只能看到最没信息量的那一段。
     */
    @Test
    void secondRunTurnsContinueNumberingInsteadOfBeingDedupedAway() {
        var p = new TeammateProgress("backend", "todo-app", "v");

        // run 1（计划阶段）：跑了两轮
        assertNotNull(p.completeTurn(1));
        assertNotNull(p.completeTurn(2));

        // run 2（开工）：Agent 的轮计数从 1 重数
        p.beginRun();
        var third = p.completeTurn(1);
        var fourth = p.completeTurn(2);

        assertNotNull(third, "第二个 run 的第一轮不得因轮次号重数而被丢掉");
        assertNotNull(fourth);
        assertEquals(3, third.turn(), "全局轮次号要继续往后排");
        assertEquals(4, fourth.turn());
    }

    @Test
    void reportTextIsTaggedAndCarriesDurationAndActivity() {
        var p = new TeammateProgress("backend", "todo-app", "v");
        tool(p, "WriteFile", "backend/server.js");
        var text = TeammateProgress.formatTurn(p.completeTurn(1));

        assertTrue(TeammateProgress.isProgressReport(text), text);
        assertTrue(text.contains("backend"), text);
        assertTrue(text.contains("turn 1"), text);
        assertTrue(text.contains("Writing backend/server.js"), text);
        // 邮箱时间戳只记轮末，轮的起点得靠正文里的耗时反推，否则算不出并行度
        assertTrue(text.contains("s"), "必须带本轮耗时: " + text);
        assertFalse(TeammateProgress.isProgressReport("[idle] backend: completed"), "别把 idle 也当进度");
    }

    // ── 落盘 ────────────────────────────────────────────────────────

    @Test
    void eachTurnIsAppendedAsOneJsonLine() throws Exception {
        var p = new TeammateProgress("backend", "todo-app", "v");
        tool(p, "WriteFile", "backend/server.js");
        ProgressLog.append(p.completeTurn(1));
        tool(p, "Bash", "node server.js");
        ProgressLog.append(p.completeTurn(2));

        Path log = tempDir.resolve(".mewcode/teams/todo-app/progress.jsonl");
        assertTrue(Files.exists(log), "逐轮日志必须落盘");

        var lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), "一轮一行: " + lines);

        var mapper = new ObjectMapper();
        var node = mapper.readTree(lines.get(0));
        assertEquals("backend", node.get("member").asText());
        assertEquals("todo-app", node.get("team").asText());
        assertEquals(1, node.get("turn").asInt());
        assertEquals(1, node.get("tools").asInt());
        assertEquals("backend/server.js", node.get("activity").asText().replace("Writing ", ""));
        assertTrue(node.get("endTs").asLong() >= node.get("startTs").asLong(),
                "逐轮区间要可用于算墙钟与并行度: " + lines.get(0));
    }

    // ── 投递策略：进度不单独唤醒 lead ────────────────────────────────

    private TeamManager.Team newTeam(TeamManager tm) {
        return tm.createTeam("todo-app", TeamManager.TeamMode.IN_PROCESS);
    }

    private static String progressLine(String who, int turn) {
        return TeammateProgress.PROGRESS_PREFIX + " " + who + ": turn " + turn
                + " done — 1 tool call, 10 tokens, 1.0s";
    }

    @Test
    void progressAloneDoesNotWakeTheLead() {
        var tm = new TeamManager();
        var team = newTeam(tm);
        team.sendMessage("backend", "lead", progressLine("backend", 1));
        team.sendMessage("backend", "lead", progressLine("backend", 2));

        assertTrue(TeammateRunner.drainLeadMailbox(tm).isEmpty(),
                "只有进度时不得返回 note —— 否则 lead 每轮队友结束都要起一整轮");

        // 但进度必须留在收件箱里（既是给 lead 的可读记录，也是逐轮时间线）
        assertEquals(2, team.getMailBox().readUnread("lead").size(),
                "进度不投递也不该被标记已读");
    }

    @Test
    void substantiveMessageFlushesAccumulatedProgressInOneNote() {
        var tm = new TeamManager();
        var team = newTeam(tm);
        team.sendMessage("backend", "lead", progressLine("backend", 1));
        team.sendMessage("backend", "lead", progressLine("backend", 2));
        team.sendMessage("backend", "lead", "[idle] backend: completed initial task");

        var notes = TeammateRunner.drainLeadMailbox(tm);

        assertEquals(1, notes.size(), "应合并成一条，而不是三条各唤醒一次");
        String note = notes.get(0);
        assertTrue(note.contains("turn 1"), note);
        assertTrue(note.contains("turn 2"), note);
        assertTrue(note.contains("completed initial task"), "实质消息不能被进度挤掉");
        assertTrue(note.contains("<team-notification team=\"todo-app\">"), note);

        assertTrue(team.getMailBox().readUnread("lead").isEmpty(), "送达后应全部标记已读");
        assertTrue(TeammateRunner.drainLeadMailbox(tm).isEmpty(), "已读的不得重复送达");
    }

    @Test
    void noMessagesMeansNoNoteAndNoTeamMeansNoCrash() {
        assertTrue(TeammateRunner.drainLeadMailbox(null).isEmpty());
        assertTrue(TeammateRunner.drainLeadMailbox(new TeamManager()).isEmpty());
    }

    @Test
    void idleNotificationIsSubstantive() {
        assertFalse(TeammateProgress.isProgressReport(
                TeammateRunner.createIdleNotification("backend", "completed initial task")));
    }
}
