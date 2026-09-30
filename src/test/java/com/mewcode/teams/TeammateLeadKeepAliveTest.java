// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import com.mewcode.agent.Agent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.control.task.TaskExecutionHandle;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.control.task.TaskStatus;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.subagent.AgentTool;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「队友还在跑，lead 不许收工」的回归测试。
 *
 * <p><b>守的是什么：</b>lead 的收工判据原先只有一条 —— 本轮没有工具调用。而 lead 的
 * 「没有动作」有两种含义：活儿干完了，和正在等队友。2026-09-25 的 team 实跑里第二种被
 * 当成了第一种：lead 派完活就以 {@code SUCCEEDED} 收尾，终版报告写「本次任务共执行两项
 * 动作，均为 SendMessage 消息发送……无文件改动记录」，而那一刻两个队友正在写文件。
 *
 * <p>两半分开测，各自都不依赖对方：
 * <ul>
 *   <li>{@code busy} 这个判据在真实队友生命周期里是什么意思 —— {@link #busyMeansInATurnNotJustThreadAlive}，
 *       走真实的 {@code AgentTool} → 虚拟线程 → {@code TeammateRunner}。</li>
 *   <li>给定判据之后 lead 到底等不等、等多久、被取消算什么 —— 用可控的开关替身测，
 *       不打时间仗。</li>
 * </ul>
 */
class TeammateLeadKeepAliveTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;
    private TeamManager tm;

    @BeforeEach
    void redirectUserDir() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        tm = new TeamManager();
    }

    @AfterEach
    void restoreUserDir() throws Exception {
        // 收工：队友线程是虚拟线程，退出时才写 transcript，而 @TempDir 的清理排在
        // @AfterEach 之后 —— 不等它写完就删目录，Windows 上会把整个用例记成失败
        // （测试体其实已经过了）。
        if (tm != null) tm.closeAll();
        awaitQuiescent(tempDir);
        if (originalUserDir != null) System.setProperty("user.dir", originalUserDir);
    }

    private static void awaitQuiescent(Path dir) throws Exception {
        String last = null;
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            String now = fingerprint(dir);
            if (now.equals(last)) return;
            last = now;
            Thread.sleep(100);
        }
    }

    private static String fingerprint(Path dir) {
        try (var s = Files.walk(dir)) {
            long n = 0;
            long bytes = 0;
            for (var p : s.filter(Files::isRegularFile).toList()) {
                n++;
                bytes += Files.size(p);
            }
            return n + ":" + bytes;
        } catch (Exception e) {
            return "unstable@" + System.nanoTime();
        }
    }

    // ── 剧本与装配 ────────────────────────────────────────────────────

    /** 一个「说话然后结束本轮」的回合。没有工具调用 —— 正是触发收工判定的那种轮。 */
    private static List<StreamEvent> say(String text) {
        return List.of(
                new StreamEvent.TextDelta(text),
                new StreamEvent.StreamEnd("end_turn", 1, 1));
    }

    private static ToolRegistry registryWithFileTools() {
        var r = new ToolRegistry();
        r.register(new WriteFileTool());
        r.register(new ReadFileTool());
        return r;
    }

    /**
     * 按剧本吐事件，并**记下被调用了几次**。
     *
     * <p>次数是这组用例的关键观测：要区分「lead 又真的跑了一轮」和「它只是没退出」，
     * 只能靠 API 被调用的次数 —— 两者在终态事件上长得一模一样。
     */
    private static final class CountingClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        final AtomicInteger turns = new AtomicInteger();

        CountingClient(List<List<StreamEvent>> script) { this.script = script; }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            var q = new LinkedBlockingQueue<StreamEvent>();
            int i = turns.getAndIncrement();
            q.addAll(i < script.size() ? script.get(i) : List.of(new StreamEvent.StreamEnd("end_turn", 1, 1)));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    private static Agent leadAgent(LlmClient client, BooleanSupplier peersBusy) {
        var agent = new Agent(client, registryWithFileTools(), "openai-compat", new ProviderConfig());
        agent.setPeersBusyFn(peersBusy);
        return agent;
    }

    /**
     * 起一个 lead。事件队列**无界**，且这些用例都不消费它。
     *
     * <p>这一点是被一次假红逼出来的：{@code Agent.putSafe} 是<b>阻塞写</b>（{@code queue.put}），
     * 队列一旦写满，agent 线程就钉在 {@code put} 上 —— 它连下一轮的收尾预算都检查不到。
     * 于是「预算没生效」的用例红了，而真相是测试自己没当消费者（真实运行的队列由 TUI /
     * print 模式持续排空）。既然这里的观测点全在 {@code handle} 上，就让队列永远写不满。
     */
    private static TaskExecutionHandle startLead(Agent agent) {
        var conv = new ConversationManager();
        conv.addUserMessage("实现一个前后端 todo 应用");
        return agent.runCancellable(conv, new LinkedBlockingQueue<>(), "lead-task");
    }

    private static void await(long timeoutMs, BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("超时未满足条件: " + what);
    }

    /**
     * 每一轮都卡在 API 调用上，直到测试放行。
     *
     * <p>脚本化的回合是**瞬间**跑完的：队友从「正在跑」到「轮末空闲」可能不到一毫秒，
     * 拿秒表去抓这个中间态必然 flaky。把 API 调用钉住，状态就成了可重复观测的事实。
     */
    private static final class PermitClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        private final BlockingQueue<Object> permits = new LinkedBlockingQueue<>();
        final AtomicInteger turns = new AtomicInteger();

        PermitClient(List<List<StreamEvent>> script) { this.script = script; }

        /** 放行一轮。测试在自己选定的时刻调用，顺序即剧本顺序。 */
        void allowOneTurn() { permits.add(new Object()); }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            int i = turns.getAndIncrement();
            try {
                permits.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var q = new LinkedBlockingQueue<StreamEvent>();
            q.addAll(i < script.size() ? script.get(i) : List.of(new StreamEvent.StreamEnd("end_turn", 1, 1)));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    /**
     * 每一轮都真的写下一个新文件 —— 没有外力的话，这个 agent 不会自己收工。
     *
     * <p>用来测「收尾预算」：脚本化的回合要么把剧本跑完就自然停（那测的就不是预算了），
     * 要么必须有一个永远不满足的停止条件。
     *
     * <p><b>「写文件」这个动作是被两次假结果逼出来的</b>，两条约束缺一不可，否则用例
     * 测的根本不是收尾预算：
     * <ul>
     *   <li><b>每轮要有产出。</b>{@link com.mewcode.control.watchdog.LoopWatchdog} 连续 8 轮
     *       没有「写/命令」类成功动作就判空转，第 19 轮前后直接 CANCEL。第一版用的是未注册的
     *       {@code Glob}，每轮都报 unknown tool —— agent 二十几毫秒就停了，看上去像「预算生效」，
     *       其实是看门狗把它掐了（把预算设成 0 的对照用例同样会停，一眼看穿）。</li>
     *   <li><b>每轮签名要不同。</b>签名连续 3 轮一模一样同样触发看门狗。</li>
     * </ul>
     * 逐轮写一个不重名的文件，两条同时满足。
     */
    private static final class AlwaysWorkingClient implements LlmClient {
        private final Path dir;
        final AtomicInteger turns = new AtomicInteger();

        AlwaysWorkingClient(Path dir) { this.dir = dir; }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                 List<Map<String, Object>> tools) {
            int n = turns.incrementAndGet();
            var q = new LinkedBlockingQueue<StreamEvent>();
            q.add(new StreamEvent.ToolCallComplete("t" + n, "WriteFile",
                    Map.of("file_path", dir.resolve("turn-" + n + ".txt").toString(),
                            "content", "turn " + n)));
            q.add(new StreamEvent.StreamEnd("tool_use", 1, 1));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    // ── 判据本身：真实队友生命周期里的 busy ───────────────────────────

    /**
     * {@code hasBusyTeammate} 必须只覆盖「正在跑某一轮」，而不是「线程还活着」。
     *
     * <p>这条区分不是细节：队友干完一轮会**永远**阻塞在等消息上，线程一直活着。若判据
     * 用 {@code Member.active}，lead 会在每个团队任务的尾巴上白等满等待上限 —— 修一个
     * 提前收工，换来一个固定收工延迟。
     */
    @Test
    void busyMeansInATurnNotJustThreadAlive() throws Exception {
        var team = tm.createTeam("busy", TeamManager.TeamMode.IN_PROCESS);
        var client = new PermitClient(List.of(
                say("PLAN-V1\n1. 建 backend/server.js\n2. GET /api/todos"),
                say("EXECUTED")));

        assertFalse(tm.hasBusyTeammate(), "一个队友都没有时不该说有人忙");

        var res = agentToolFor(client).execute(Map.of("team_name", "busy", "name", "backend",
                "description", "负责后端 API", "prompt", "实现后端 API"));
        assertFalse(res.isError(), res.output());

        // 计划轮卡在 API 调用里 —— 这一刻「正在跑一轮」是确定的事实
        await(10_000, () -> client.turns.get() == 1, "队友开始第一轮");
        assertTrue(tm.hasBusyTeammate(), "队友正在跑一轮，就该被算作忙");

        client.allowOneTurn();
        // 计划发出去 → 转入等审批：线程活着，但没在干活
        await(20_000, () -> !plans(team).isEmpty(), "队友把计划发给 lead");
        await(5_000, () -> !tm.hasBusyTeammate(), "等审批期间不该算忙");
        assertTrue(team.getMember("backend").isActive(),
                "线程明明还活着 —— 这正是不能拿 active 当判据的原因");

        // 批准 → 开工 → 又忙起来
        team.sendMessage(TeammateRunner.LEAD_NAME, "backend", "APPROVE — plan is exactly what I want");
        await(20_000, () -> client.turns.get() == 2, "队友开始执行轮");
        assertTrue(tm.hasBusyTeammate(), "获批开工后又该算忙");

        // 干完一轮 → 发完 idle 通知 → 回到不忙
        client.allowOneTurn();
        await(20_000, () -> !tm.hasBusyTeammate(), "轮末等消息时不该算忙");
        assertTrue(team.getMember("backend").isActive(), "队友仍然活着，只是没在干活");
    }

    private AgentTool agentToolFor(LlmClient client) {
        var tool = new AgentTool(client, registryWithFileTools(), "openai-compat", new ProviderConfig());
        tool.setTeamManager(tm);
        return tool;
    }

    private static List<String> plans(TeamManager.Team team) {
        return team.getMailBox().readUnread(TeammateRunner.LEAD_NAME).stream()
                .map(m -> m.text())
                .filter(t -> t.stripLeading().startsWith("[plan]"))
                .toList();
    }

    // ── lead 侧：等不等、等多久、取消算什么 ───────────────────────────

    /**
     * 核心回归：队友在跑时，lead 那一轮「没有工具调用」不算干完了。
     *
     * <p>改动前这条会红：lead 一轮只说不做就立刻 {@code SUCCEEDED}，判据里根本没有
     * 「队友还在不在跑」这一项。
     */
    @Test
    void leadWaitsInsteadOfFinishingWhileATeammateIsBusy() throws Exception {
        var busy = new AtomicBoolean(true);
        var client = new CountingClient(List.of(say("已派活，等队友先做完")));
        var lead = leadAgent(client, busy::get);
        lead.setPeerWaitMs(20_000);

        var handle = startLead(lead);

        // 给足时间让它跑到收工判定点（一轮 API 往返远小于此）
        Thread.sleep(1_000);
        assertFalse(handle.completion().isDone(), "队友还在跑，lead 不该宣告终态");
        assertEquals(1, client.turns.get(), "等待不该偷偷再发一次 API");

        busy.set(false);
        await(10_000, handle::isDone, "队友收工后 lead 才收工");
        assertEquals(TaskStatus.SUCCEEDED, handle.completion().getNow(null).status());
    }

    /**
     * 等待期间队友来消息 → lead 接着跑一轮，而不是继续盲等。
     *
     * <p>这是这条改动的**收益**所在：lead 的收尾轮（集成验收、汇总交付）原先发在队友
     * 汇报之前，所以它只能写「无文件改动记录」。
     */
    @Test
    void messageArrivingDuringTheWaitStartsAnotherTurn() throws Exception {
        var busy = new AtomicBoolean(true);
        var notes = new LinkedBlockingQueue<String>();
        var client = new CountingClient(List.of(say("已派活，等队友"), say("队友报完工，我做集成验收")));
        var lead = leadAgent(client, busy::get);
        lead.setPeerWaitMs(20_000);
        lead.setNotificationFn(() -> {
            var drained = new java.util.ArrayList<String>();
            notes.drainTo(drained);
            return drained;
        });

        var handle = startLead(lead);
        await(10_000, () -> client.turns.get() == 1, "第一轮跑完");
        assertFalse(handle.isDone(), "这一刻还没到收工的时候");

        notes.add("<team-notification team=\"todo-app\">[idle] backend: completed initial task</team-notification>");

        await(10_000, () -> client.turns.get() == 2, "消息到手后 lead 应再跑一轮");
        busy.set(false);
        await(10_000, handle::isDone, "队友都收工了，lead 收工");
        assertEquals(TaskStatus.SUCCEEDED, handle.completion().getNow(null).status());
    }

    /** 上限：队友卡死不回时，lead 不能无限期挂着 —— 等满即按原逻辑收工。 */
    @Test
    void theWaitIsBounded() throws Exception {
        var client = new CountingClient(List.of(say("等队友")));
        var lead = leadAgent(client, () -> true);   // 永远有人忙，且永远没有消息
        lead.setPeerWaitMs(300);

        long t0 = System.currentTimeMillis();
        var handle = startLead(lead);

        await(5_000, handle::isDone, "等满上限后必须收工");
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed >= 250, "该等满上限再走，实际只等了 " + elapsed + "ms（上限没生效？）");
        assertEquals(TaskStatus.SUCCEEDED, handle.completion().getNow(null).status());
        assertEquals(1, client.turns.get(), "空等不产生 API 调用");
    }

    /** 不注入判据时行为与改动前逐字一致：一轮没有工具调用就收工。 */
    @Test
    void withoutThePredicateNothingChanges() throws Exception {
        var client = new CountingClient(List.of(say("干完了")));
        var lead = new Agent(client, registryWithFileTools(), "openai-compat", new ProviderConfig());

        var handle = startLead(lead);

        await(5_000, handle::isDone, "没有队友概念时不该有额外等待");
        assertEquals(TaskStatus.SUCCEEDED, handle.completion().getNow(null).status());
        assertEquals(1, client.turns.get());
    }

    /** 等待期间被取消：记成 CANCELLED，不能因为「等到了尽头」被记成 SUCCEEDED。 */
    @Test
    void cancellingDuringTheWaitIsReportedAsCancelled() throws Exception {
        var client = new CountingClient(List.of(say("等队友")));
        var lead = leadAgent(client, () -> true);
        lead.setPeerWaitMs(60_000);

        var handle = startLead(lead);
        await(10_000, () -> client.turns.get() == 1, "第一轮跑完");
        assertFalse(handle.isDone());

        handle.cancel();

        await(10_000, handle::isDone, "取消必须能让等待中的 lead 尽快退出");
        assertEquals(TaskStatus.CANCELLED, handle.completion().getNow(null).status(),
                "一次取消不该被记成正常完工");
    }

    // ── 收尾预算：队友全收工后，lead 不能无限期地串行验收下去 ─────────

    /**
     * 队友全都收工之后，lead 的收尾必须有上限。
     *
     * <p>守的是 2026-09-25 team 实跑的尾巴：队友 +154s 全停工，lead 独自串行跑到 +357s
     * —— 那 203 秒里没有任何并行工作，纯增成本（其中三次 provider 慢请求 66/71/140s 全
     * 落在这一段）。对照 Claude Code 同一道题：队友并行跑完 +59.8s，lead 跑一条
     * {@code verify.sh} 就收工，尾巴 14 秒。
     *
     * <p>超限的语义是「不再往下验」，不是「取消」：终态必须是 SUCCEEDED，报告里如实列出
     * 未验证项。所以这里断言 SUCCEEDED，而不是 CANCELLED。
     */
    @Test
    void theLeadTailIsBoundedAfterEveryTeammateGoesIdle() throws Exception {
        var client = new AlwaysWorkingClient(tempDir);
        var lead = leadAgent(client, () -> false);   // 队友一个都不在跑
        lead.setTailBudgetMs(400);

        long t0 = System.currentTimeMillis();
        var handle = startLead(lead);

        await(20_000, handle::isDone, "收尾预算用尽后必须收工");
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed >= 350, "该用满预算再收，实际只跑了 " + elapsed + "ms");
        assertTrue(elapsed < 10_000, "收尾明显超出预算，实际 " + elapsed + "ms");
        assertEquals(TaskStatus.SUCCEEDED, handle.completion().getNow(null).status(),
                "预算用尽是「不再验了」，不是「任务被取消」");
        assertTrue(client.turns.get() >= 2, "预算用尽后应先提醒一轮再收工，实际只跑了 "
                + client.turns.get() + " 轮");
    }

    /**
     * 反向探针：把预算关掉（0），同样的「永远在干活」的 lead 就不会自己收工。
     *
     * <p>没有这条，上面那条用例无法排除「它其实是因为别的原因停的」。
     */
    @Test
    void withoutATailBudgetTheLeadKeepsGoing() throws Exception {
        var client = new AlwaysWorkingClient(tempDir);
        var lead = leadAgent(client, () -> false);
        lead.setTailBudgetMs(0);

        var handle = startLead(lead);

        Thread.sleep(1_200);
        assertFalse(handle.completion().isDone(), "预算关掉后不该自己收工");
        assertTrue(client.turns.get() >= 3, "这段时间里它应该一直在干活");

        handle.cancel();
        await(10_000, handle::isDone, "收尾时取消要能退出");
    }
}
