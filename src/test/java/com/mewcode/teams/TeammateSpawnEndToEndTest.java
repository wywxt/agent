// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.subagent.AgentTool;
import com.mewcode.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命名与消息投递的端到端回归测试。
 *
 * <p>与 {@link TeamMemberIdentityTest} 的分工：那个是单元级（直接调工具），这个把
 * <b>真实的那条链路</b>跑通一遍 —— {@code AgentTool.execute} → {@code resolveMemberName}
 * → {@code SpawnDispatcher} → 真实虚拟线程上的 {@code TeammateRunner} → 真实的
 * {@code Agent} 循环执行真实的 {@code SendMessageTool} → 真实的 {@code FileMailBox}。
 *
 * <p>唯一被替换的是 LLM 本身（{@link ScriptedClient} 按剧本吐 tool_call）。这样整条
 * 链路上没有网络、没有自主 agent，却仍能验证「lead 给的名字 = 队友真名 = 消息能送达」
 * 这条修复前断掉的因果链。原来的断法：{@code name} 参数被静默丢弃，成员名回落成
 * description 派生值，lead 却把「它以为的名字」告诉队友，队友照此发消息就写进了
 * 没人会读的幽灵收件箱。
 */
class TeammateSpawnEndToEndTest {

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

    private Path inboxDir(String teamName) {
        return tempDir.resolve(".mewcode/teams").resolve(teamName).resolve("inboxes");
    }

    /**
     * 按剧本吐事件的假 LLM：第 n 次 stream() 返回第 n 个 turn，超出剧本则直接收尾。
     * 不触网，因此实验结论可复现。
     */
    static final class ScriptedClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        private final AtomicInteger turn = new AtomicInteger();

        ScriptedClient(List<List<StreamEvent>> script) {
            this.script = script;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            var q = new LinkedBlockingQueue<StreamEvent>();
            int i = turn.getAndIncrement();
            var events = (i < script.size())
                    ? script.get(i)
                    : List.<StreamEvent>of(new StreamEvent.StreamEnd("end_turn", 1, 1));
            q.addAll(events);
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {
            // 剧本里不需要 system prompt
        }
    }

    private static Map<String, Object> toolArgs(String to, String content) {
        var m = new LinkedHashMap<String, Object>();
        m.put("to", to);
        m.put("content", content);
        return m;
    }

    private static void await(long timeoutMs, BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("超时未满足条件: " + what);
    }

    @Test
    void leadSuppliedNameIsTheRealMemberNameAndItsToolCallReachesThePeerInbox() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("todo-app", TeamManager.TeamMode.IN_PROCESS);
        // 模拟一个已经存在的队友（SpawnDispatcher 走的就是 addMember 这一条路）。
        // 先注册它，backend 被 spawn 时才拿得到含职责的名册。
        team.addMember("frontend", "负责前端网页", null, null, null, new ProviderConfig());

        // backend 的第一轮：按名册给 frontend 发接口约定；顺手给一个不存在的收件人发一条，
        // 用来验证「未知收件人报错且不落幽灵文件」在真实链路里也成立。
        var script = List.of(
                List.<StreamEvent>of(
                        new StreamEvent.ToolCallComplete("call_1", "SendMessage",
                                toolArgs("frontend", "接口约定：GET /api/todos")),
                        new StreamEvent.ToolCallComplete("call_2", "SendMessage",
                                toolArgs("front", "这条不该送达")),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("后端完成"),
                        new StreamEvent.StreamEnd("end_turn", 1, 1))
        );

        var tool = new AgentTool(new ScriptedClient(script), new ToolRegistry(),
                "openai-compat", new ProviderConfig());
        tool.setTeamManager(tm);

        try {
            var result = tool.execute(Map.of(
                    "team_name", "todo-app",
                    "name", "backend",
                    "description", "负责后端 API 服务",
                    "prompt", "实现后端 API"));

            assertFalse(result.isError(), result.output());

            // ① lead 传的 name 就是成员真名。修复前这里是 description 派生值
            //    「负责后端-api-服务」，而 lead 会照自己给的名字称呼队友。
            assertTrue(result.output().contains("\"backend\""), result.output());
            assertFalse(result.output().contains("负责后端-api-服务"), result.output());
            assertTrue(team.hasMember("backend"), "members=" + team.memberNames());

            var member = team.getMember("backend");

            // ② 队友真的被告知了自己的真名，以及同伴的名字 + 职责。
            //    等待 backend 跑完整轮：收尾信号是给 lead 的 idle 通知。
            //    注意条件要排除每轮进度 —— 进度在轮内就到了，用它当「跑完」的信号
            //    会提前放行，后面的断言就落在半成品状态上。
            await(20_000,
                    () -> team.getMailBox().readUnread("lead").stream()
                            .anyMatch(m -> !TeammateProgress.isProgressReport(m.text())),
                    "backend 跑完并发出 idle 通知");

            String conversation = member.conv.getMessages().stream()
                    .map(m -> m.getContent() == null ? "" : m.getContent())
                    .collect(Collectors.joining("\n"));
            assertTrue(conversation.contains("Your name is \"backend\""), conversation);
            assertTrue(conversation.contains("frontend —— 负责前端网页"), conversation);

            // ③ 队友发出的消息落在 frontend 的**真**收件箱里
            var unread = team.getMailBox().readUnread("frontend");
            assertEquals(1, unread.size(), "frontend inbox=" + unread);
            assertEquals("backend", unread.get(0).from());
            assertTrue(unread.get(0).text().contains("/api/todos"), unread.get(0).text());
            assertTrue(Files.exists(inboxDir("todo-app").resolve("frontend.json")));

            // ④ 未知收件人不产生幽灵收件箱 —— 这正是消息无声丢失的成因
            assertFalse(Files.exists(inboxDir("todo-app").resolve("front.json")),
                    "对不存在的收件人不得写出收件箱文件: " + listInbox());

            // ⑤ 每轮汇报：真实链路上必须逐轮落盘，而不是只在单测里成立。
            //    修复前 drainAgentEvents 忽略 TurnComplete，队友 40 轮下来 lead
            //    手上只有两条空 [idle]，中间全空 —— 「多 agent 快在哪」无从回答。
            Path progress = tempDir.resolve(".mewcode/teams/todo-app/progress.jsonl");
            assertTrue(Files.exists(progress), "逐轮进度必须落盘");
            var lines = Files.readAllLines(progress);
            assertEquals(2, lines.size(), "一轮一行: " + lines);

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var firstTurn = mapper.readTree(lines.get(0));
            assertEquals(1, firstTurn.get("turn").asInt(), lines.get(0));
            assertEquals(2, firstTurn.get("tools").asInt(),
                    "第一轮真的执行了 2 次 SendMessage: " + lines.get(0));
            assertTrue(firstTurn.get("endTs").asLong() >= firstTurn.get("startTs").asLong(), lines.get(0));

            // 终轮没有工具调用，只发 LoopComplete —— 它同样要被记下来，否则
            // 最长的收尾那一轮永远是空白
            var lastTurn = mapper.readTree(lines.get(1));
            assertEquals(2, lastTurn.get("turn").asInt(), lines.get(1));
            assertEquals(0, lastTurn.get("tools").asInt(), lines.get(1));

            // 进度进 lead 收件箱（可读记录），但 drainLeadMailbox 不会为它单独唤醒 lead
            assertTrue(team.getMailBox().readUnread("lead").stream()
                            .anyMatch(m -> TeammateProgress.isProgressReport(m.text())),
                    "每轮进度必须进 lead 收件箱");
        } finally {
            team.stopAll();
        }
    }

    /**
     * 队友的 token 用量必须既**算得准**（不为负），又**出得去**（进 sink）。
     *
     * <p>修复前两件事都是坏的，且是两个独立的缺陷：
     * <ul>
     *   <li>数值：{@code TeammateProgress.recordTokens} 覆盖式赋值，而 {@code Agent}
     *       的累计量每次 {@code run()} 归零 → 跨回合算出负数。实测 49 条里 45 条为 0、
     *       1 条为 -176。</li>
     *   <li>出口：{@code runInProcessTeammate} 里那个局部 sink 队列**没有消费者**，
     *       写满 32 条后静默丢弃，所以队友的用量根本到不了外部。</li>
     * </ul>
     * 这条测试走的是真实链路（真虚拟线程 + 真 Agent 循环），因此能同时覆盖两者。
     */
    @Test
    void teammateTokensAreBothNonNegativeAndVisibleToTheSink() throws Exception {
        var tm = new TeamManager();
        var sink = new LinkedBlockingQueue<AgentEvent>(64);
        tm.setTeammateEventSink(sink);
        var team = tm.createTeam("token-team", TeamManager.TeamMode.IN_PROCESS);

        // 两个回合的 usage **一大一小**（10 然后 2），这是关键：每次 run() 的累计都从 0
        // 重数，所以第二个回合的值比第一个小。旧实现是覆盖式赋值，
        // 第二轮的增量 = 2 - 10 = **-8** —— 正是实测里 -176 那个形状。
        // 若两轮都用同一个值，旧实现只会算出 0，这条测试就抓不住 bug 了。
        var script = List.of(
                List.<StreamEvent>of(
                        new StreamEvent.StreamEnd("end_turn", 5, 5)),
                List.<StreamEvent>of(
                        new StreamEvent.StreamEnd("end_turn", 1, 1))
        );

        var tool = new AgentTool(new ScriptedClient(script), new ToolRegistry(),
                "openai-compat", new ProviderConfig());
        tool.setTeamManager(tm);

        // 计划阶段会等 lead 审批整整 3 分钟才放行开工。本测试要的就是**开工那次 run**
        // （跨 run 的复位只在这里发生），所以把等待压到 1s。
        long savedTimeout = TeammateRunner.planApprovalTimeoutMs;
        TeammateRunner.planApprovalTimeoutMs = 1_000;
        try {
            var result = tool.execute(Map.of(
                    "team_name", "token-team",
                    "name", "solo",
                    "description", "负责一件小事",
                    "prompt", "做一件小事"));
            assertFalse(result.isError(), result.output());

            // 等**真正的收尾信号**，而不是「lead 收到任意非进度消息」——
            // 后者会被计划阶段的 [plan] 消息提前满足，于是断言落在只跑完计划轮
            // 的半成品状态上（跨 run 的场景根本没进入）。
            await(30_000,
                    () -> team.getMailBox().readUnread("lead").stream()
                            .anyMatch(m -> m.text().contains("[idle]")),
                    "队友跑完执行阶段并发出 idle 通知");

            // ① 落盘的每轮 token 都非负 —— 这正是旧实现失守的地方
            Path progress = tempDir.resolve(".mewcode/teams/token-team/progress.jsonl");
            assertTrue(Files.exists(progress), "逐轮进度必须落盘");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var lines = Files.readAllLines(progress);
            // 必须真的跨过 run 边界：计划 run 与开工 run 各至少一条，否则本测试根本没
            // 走到「累计复位」那一幕，即便 token 实现是坏的也会绿（这正是它此前失效的原因）。
            assertTrue(lines.size() >= 2,
                    "计划阶段与开工阶段各应留下轮记录，跨 run 场景才被覆盖到: " + lines);
            boolean sawPositive = false;
            for (String line : lines) {
                long tokens = mapper.readTree(line).get("tokens").asLong();
                assertTrue(tokens >= 0, "每轮 token 不得为负: " + line);
                if (tokens > 0) sawPositive = true;
            }
            assertTrue(sawPositive, "ScriptedClient 每轮都报了 usage，累计量不应全为 0");

            // ② 同一批数据必须也走通了 sink —— 否则外部观测不到多 agent 的真实成本
            var usageEvents = new ArrayList<AgentEvent.TeammateUsageEvent>();
            AgentEvent e;
            while ((e = sink.poll()) != null) {
                if (e instanceof AgentEvent.TeammateUsageEvent u) usageEvents.add(u);
            }
            assertFalse(usageEvents.isEmpty(), "队友 token 必须经 sink 外流，否则埋点等于没做");
            for (var u : usageEvents) {
                assertEquals("token-team", u.team());
                assertEquals("solo", u.member());
                assertTrue(u.deltaTokens() >= 0, "增量不得为负: " + u);
            }
            assertTrue(usageEvents.stream().anyMatch(u -> u.deltaTokens() > 0), usageEvents.toString());
        } finally {
            TeammateRunner.planApprovalTimeoutMs = savedTimeout;
            team.stopAll();
        }
    }

    private List<String> listInbox() {
        Path dir = inboxDir("todo-app");
        if (!Files.exists(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return new ArrayList<>(List.of("<unreadable: " + e.getMessage() + ">"));
        }
    }
}
