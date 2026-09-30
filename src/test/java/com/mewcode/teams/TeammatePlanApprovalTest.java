// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import com.mewcode.config.ProviderConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「队友先出计划 → lead 审批 → 才开工」这条链路的端到端测试。
 *
 * <p>跑法与 {@link TeammateSpawnEndToEndTest} 一致：真的走
 * {@code AgentTool.execute} → {@code SpawnDispatcher} → 虚拟线程上的真实
 * {@code TeammateRunner} → 真实的 {@code Agent} 循环 + 真实的 {@code FileMailBox}，
 * 只有 LLM 被换成按剧本吐事件的 {@link TeammateSpawnEndToEndTest.ScriptedClient}。
 *
 * <p><b>为什么必须端到端而不是单测 {@code planningToolFilter}：</b>这条改动的全部价值
 * 在于「计划阶段的写真的落不了盘」。而 {@code toolNameFilter} 在这次改动之前**只过滤
 * 发给模型的 schema**，执行侧根本不查它 —— 也就是说，一个只测 filter 返回值、或只
 * 断言「schema 里没有 WriteFile」的测试，在改动前同样会全绿。唯一能区分新旧行为的
 * 断言是：脚本命令式地调用 {@code WriteFile}，然后检查文件**到底有没有落盘**。
 */
class TeammatePlanApprovalTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void redirectUserDir() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void restoreUserDir() throws Exception {
        // @TempDir 的清理排在 @AfterEach 之后，而队友的虚拟线程是在自己的 finally 里
        // 写 transcript 的 —— Team.stopAll() 只置标志位 + interrupt，**不等线程结束**。
        // 于是清理会和那次写抢，Windows 上表现成「目录删不掉」，整个用例被记成失败
        // （测试体其实已经过了）。等目录安静下来再交还，比让它随机变红强。
        awaitQuiescent(tempDir);
        if (originalUserDir != null) System.setProperty("user.dir", originalUserDir);
        TeammateRunner.planApprovalTimeoutMs = 3 * 60 * 1000L;
    }

    /** 轮询文件数与总大小，连续两次采样不变即认为队友线程已经收尾。上限 3s。 */
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
            // 正被写入的目录里文件可能刚刚消失：给一个必然不相等的值，让下一轮重采
            return "unstable@" + System.nanoTime();
        }
    }

    // ── 剧本构造 ──────────────────────────────────────────────────────

    /** 一个「说话然后结束本轮」的回合 —— 计划正文就是这么产出的。 */
    private static List<StreamEvent> say(String text) {
        return List.of(
                new StreamEvent.TextDelta(text),
                new StreamEvent.StreamEnd("end_turn", 1, 1));
    }

    /** 一个「调用工具然后继续」的回合。 */
    private static List<StreamEvent> call(String id, String tool, Map<String, Object> args) {
        return List.of(
                new StreamEvent.ToolCallComplete(id, tool, args),
                new StreamEvent.StreamEnd("tool_use", 1, 1));
    }

    private static Map<String, Object> writeArgs(Object path, String content) {
        var m = new LinkedHashMap<String, Object>();
        m.put("file_path", path.toString());
        m.put("content", content);
        return m;
    }

    /**
     * 注册 WriteFile/ReadFile 的父注册表。
     *
     * <p>WriteFile 必须**真的存在于注册表里**，否则闸门测试会以错误的理由通过：
     * 工具不存在时会走 {@code StreamingExecutor} 的 "unknown tool" 分支，照样拒绝、
     * 照样不落盘 —— 那样测的是「工具缺失」，不是「阶段闸门」。
     */
    private static ToolRegistry registryWithFileTools() {
        var r = new ToolRegistry();
        r.register(new WriteFileTool());
        r.register(new ReadFileTool());
        return r;
    }

    private static AgentTool agentToolFor(List<List<StreamEvent>> script, TeamManager tm) {
        return agentToolFor(script, tm, registryWithFileTools());
    }

    private static AgentTool agentToolFor(List<List<StreamEvent>> script, TeamManager tm,
                                          ToolRegistry registry) {
        var tool = new AgentTool(new TeammateSpawnEndToEndTest.ScriptedClient(script),
                registry, "openai-compat", new ProviderConfig());
        tool.setTeamManager(tm);
        return tool;
    }

    // ── 观测辅助 ──────────────────────────────────────────────────────

    /** lead 收件箱里的原始消息正文（readUnread 不标记已读，可反复取）。 */
    private static List<String> leadInbox(TeamManager.Team team) {
        return team.getMailBox().readUnread("lead").stream().map(m -> m.text()).toList();
    }

    private static List<String> plans(TeamManager.Team team) {
        return leadInbox(team).stream()
                .filter(t -> t.stripLeading().startsWith("[plan]"))
                .toList();
    }

    private static boolean sawIdle(TeamManager.Team team, String reason) {
        return leadInbox(team).stream()
                .anyMatch(t -> t.stripLeading().startsWith("[idle]") && t.contains(reason));
    }

    /**
     * 队友的完整现场：正文 + 工具结果。
     *
     * <p>工具结果必须一并取出 —— 闸门的拒绝理由只存在于 {@code ToolResultBlock.content()}
     * 里，{@code Message.getContent()} 是看不到的。
     */
    private static String transcript(TeamManager.Team team, String member) {
        var sb = new StringBuilder();
        for (var m : team.getMember(member).conv.getMessages()) {
            if (m.getContent() != null) sb.append(m.getContent()).append('\n');
            if (m.getToolResults() != null) {
                for (var r : m.getToolResults()) {
                    sb.append("[tool-result] ").append(r.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void await(long timeoutMs, BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("超时未满足条件: " + what);
    }

    // ── 用例 ──────────────────────────────────────────────────────────

    /**
     * 主用例：计划阶段的写被真拦下，获批后同一操作真的落盘。
     *
     * <p>两半缺一不可。只测「被拦」无法区分「闸门生效」和「工具压根不存在」；只测
     * 「获批后能写」不能说明计划阶段拦过。合起来才排除这两种误判。
     */
    @Test
    void planningWritesAreRefusedAndApprovalOpensTheGate() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("gate", TeamManager.TeamMode.IN_PROCESS);

        Path duringPlan = tempDir.resolve("probe-during-plan.txt");
        Path afterApproval = tempDir.resolve("probe-after-approval.txt");

        var script = List.of(
                // 计划轮：命令式地试图写盘，证明闸门是真拦而不是提示词劝告
                call("c1", "WriteFile", writeArgs(duringPlan, "should never land")),
                // 同轮继续说话，把计划作为正文输出
                say("PLAN-V1\n1. 建 backend/server.js\n2. 接口 GET /api/todos"),
                // 获批后的执行轮：同一个 WriteFile，这次必须真的落盘
                call("c2", "WriteFile", writeArgs(afterApproval, "landed")),
                say("done"));

        var tool = agentToolFor(script, tm);

        try {
            var res = tool.execute(Map.of(
                    "team_name", "gate", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API"));
            assertFalse(res.isError(), res.output());

            // ── 计划阶段 ──
            await(20_000, () -> !plans(team).isEmpty(), "队友把计划发给 lead");

            String plan = plans(team).get(0);
            assertTrue(plan.contains("PLAN-V1"), "计划正文要能被机器取出来，不靠模型记得调 SendMessage: " + plan);
            assertTrue(plan.startsWith("[plan] backend"), "计划要标明是谁的: " + plan);

            // 闸门真的拦住了写操作
            assertFalse(Files.exists(duringPlan),
                    "计划阶段的写必须被拒绝，而不是落盘: " + duringPlan);

            // 而且拒绝理由必须是「当前阶段不可用」。若退化成 "unknown tool"，模型会
            // 跑去 ToolSearch 找一个它本来就不该用的工具，那是另一种失败。
            String t = transcript(team, "backend");
            assertTrue(t.contains("not available in the current phase"),
                    "拒绝理由要说清是阶段限制，不是工具不存在:\n" + t);
            assertFalse(t.contains("unknown tool"), "不该退化成「未知工具」:\n" + t);

            // 审批前不得动手：此时还没有任何 idle 通知（idle 是开工后才发的）
            assertFalse(sawIdle(team, "completed initial task"),
                    "获批之前不该进入执行阶段: " + leadInbox(team));

            // ── 批准 ──
            team.sendMessage(TeammateRunner.LEAD_NAME, "backend", "APPROVE — 契约没问题，开工");

            await(20_000, () -> sawIdle(team, "completed initial task"), "队友跑完执行阶段");
            assertTrue(Files.exists(afterApproval),
                    "获批后写权限必须真的放开: " + afterApproval);

            // 计划阶段那次写自始至终没有落盘（获批不得追溯性地把它补上）
            assertFalse(Files.exists(duringPlan), "计划阶段的写不该事后补落盘: " + duringPlan);

            // 获批的信号以 system reminder 形式进入了队友上下文
            assertTrue(transcript(team, "backend").contains("APPROVED"),
                    "队友要明确知道自己获批了:\n" + transcript(team, "backend"));
        } finally {
            team.stopAll();
        }
    }

    /**
     * 闸门对 Bash 同样有效 —— 只测 WriteFile 说明不了闸门是「按类别拦」还是「只拦了写文件」。
     *
     * <p><b>这条是被实测逼出来的。</b>2026-09-25 的 team 实跑里，backend-dev 计划阶段
     * 第一轮的 activity 就是 {@code Running ls -la}，而 progress.jsonl 记那一轮
     * {@code tools: 1}。计数走 {@code ToolStartEvent}，它是在闸门之后才发的
     * （{@code StreamingExecutor} :191 闸门 / :269 发事件）——照着读，那次 Bash 像是
     * 真执行了。可队友的 transcript 没落盘（进程被硬杀），日志里只有 activity 文案，
     * 断不干净。所以让剧本在计划阶段命令式地调 Bash 写盘，看文件到底出不出现。
     */
    @Test
    void planningBashIsRefusedToo() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("bashgate", TeamManager.TeamMode.IN_PROCESS);

        // 路径要给正斜杠：命令是交给 shell 的，反斜杠会被当转义符吃掉
        String dir = tempDir.toString().replace('\\', '/');
        Path duringPlan = tempDir.resolve("bash-during-plan.txt");
        Path afterApproval = tempDir.resolve("bash-after-approval.txt");

        var registry = registryWithFileTools();
        registry.register(new com.mewcode.tool.impl.BashTool(tempDir.toString()));

        var script = List.of(
                call("b1", "Bash", Map.of("command", "echo leaked > " + dir + "/bash-during-plan.txt")),
                say("PLAN-V1 先勘察目录"),
                call("b2", "Bash", Map.of("command", "echo landed > " + dir + "/bash-after-approval.txt")),
                say("done"));

        var tool = agentToolFor(script, tm, registry);

        try {
            var res = tool.execute(Map.of(
                    "team_name", "bashgate", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API"));
            assertFalse(res.isError(), res.output());

            await(20_000, () -> !plans(team).isEmpty(), "队友把计划发给 lead");

            assertFalse(Files.exists(duringPlan),
                    "计划阶段的 Bash 必须被拒绝，而不是落盘: " + duringPlan);

            String t = transcript(team, "backend");
            assertTrue(t.contains("not available in the current phase"),
                    "Bash 的拒绝理由也必须是阶段限制:\n" + t);
            assertFalse(t.contains("unknown tool"),
                    "Bash 已在注册表里，退化成「未知工具」说明测的是工具缺失而不是闸门:\n" + t);

            // 被拒的 Bash 不得在活动文案里留痕。本用例的剧本里，计划轮唯一的调用就是那次
            // 被拒的 Bash，所以此刻文案必须还是初始的 spinner 文案 —— 若它显示 "Running …"，
            // 就说明文案又回到了「模型准备调用」那一侧，lead 读到的 [progress] 会被带偏。
            var prog = team.getTeammateProgressList().stream()
                    .filter(x -> "backend".equals(x.getName())).findFirst().orElseThrow();
            assertFalse(prog.getActivitySummary().startsWith("Running"),
                    "被闸门拒掉的 Bash 不该出现在活动文案里，实际是: " + prog.getActivitySummary());

            team.sendMessage(TeammateRunner.LEAD_NAME, "backend", "APPROVE");
            await(20_000, () -> sawIdle(team, "completed initial task"), "队友跑完执行阶段");
            // 这一半用来排除「Bash 压根不可用」：获批后同一操作必须真的落盘
            assertTrue(Files.exists(afterApproval),
                    "获批后 Bash 必须真的放开: " + afterApproval);
        } finally {
            team.stopAll();
        }
    }

    /**
     * 只有 lead 能批准：同伴喊一句 "approved" 不打开闸门，队友必须重出一版计划。
     *
     * <p>这条不是吹毛求疵。{@code waitForNextPromptOrShutdown} 等的是**任何**消息，
     * 若审批判定不复核发件人，一个热心的队友随口一句 "looks good, approved" 就能把
     * 另一个队友的写权限打开 —— 那 lead 的审批环节就形同虚设。
     */
    @Test
    void aPeerSayingApprovedDoesNotOpenTheGate() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("peer", TeamManager.TeamMode.IN_PROCESS);

        var script = List.of(
                say("PLAN-V1\n初版计划"),
                say("PLAN-V2\n按意见改过的计划"),
                say("done"));

        var tool = agentToolFor(script, tm);

        try {
            assertFalse(tool.execute(Map.of(
                    "team_name", "peer", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API")).isError());

            await(20_000, () -> !plans(team).isEmpty(), "第一版计划送达");

            // 同伴（不是 lead）说批准 —— 不得打开闸门
            team.sendMessage("frontend", "backend", "approved! looks good to me");

            // 判据：队友应当**重出一版计划**，而不是进入执行阶段。
            // 若同伴的话被当成了批准，接下来走的是执行轮，就不会再有第二条 [plan]。
            await(20_000, () -> plans(team).size() >= 2,
                    "同伴的批准不该生效，队友应重出计划");
            assertTrue(plans(team).get(1).contains("PLAN-V2"), plans(team).get(1));
            assertFalse(sawIdle(team, "completed initial task"),
                    "同伴的话不该让队友开工: " + leadInbox(team));

            // lead 自己来才有效
            team.sendMessage(TeammateRunner.LEAD_NAME, "backend", "APPROVE");
            await(20_000, () -> sawIdle(team, "completed initial task"), "lead 批准后队友开工");
        } finally {
            team.stopAll();
        }
    }

    /**
     * 否定词不能被读成批准。
     *
     * <p>"REVISE: ..." / "不批准" 里都不含肯定词，但 "not approved" 含 "approved"。
     * 判定若先查肯定词，就会把否决读成批准 —— 闸门自己把门打开，比不做闸门更糟。
     */
    @Test
    void rejectionIsNotMistakenForApproval() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("reject", TeamManager.TeamMode.IN_PROCESS);

        var script = List.of(
                say("PLAN-V1\n初版计划"),
                say("PLAN-V2\n改过的计划"),
                say("done"));

        var tool = agentToolFor(script, tm);

        try {
            assertFalse(tool.execute(Map.of(
                    "team_name", "reject", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API")).isError());

            await(20_000, () -> !plans(team).isEmpty(), "第一版计划送达");

            // 这条同时含 "not" 和 "approved"
            team.sendMessage(TeammateRunner.LEAD_NAME, "backend",
                    "This is not approved yet — REVISE: 请把响应字段名写死");

            await(20_000, () -> plans(team).size() >= 2, "否决后应重出计划");
            assertFalse(sawIdle(team, "completed initial task"),
                    "否决不该让队友开工: " + leadInbox(team));
        } finally {
            team.stopAll();
        }
    }

    /**
     * 重出计划有上限：出满 {@link TeammateRunner#MAX_PLAN_ROUNDS} 版仍未获批就放行。
     *
     * <p>不设上限的话，一个始终不肯吐批准词的 lead 能让队友永远停在计划阶段 ——
     * 每一步来回都是一整轮 API 往返，最后把预算烧光却什么都没交付。
     */
    @Test
    void planRoundsAreCappedAndThenExecutionProceeds() throws Exception {
        var tm = new TeamManager();
        var team = tm.createTeam("cap", TeamManager.TeamMode.IN_PROCESS);

        int max = TeammateRunner.MAX_PLAN_ROUNDS;
        var script = new java.util.ArrayList<List<StreamEvent>>();
        for (int i = 1; i <= max; i++) script.add(say("PLAN-V" + i));
        script.add(say("executed"));

        var tool = agentToolFor(script, tm);

        try {
            assertFalse(tool.execute(Map.of(
                    "team_name", "cap", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API")).isError());

            for (int round = 1; round <= max; round++) {
                final int r = round;
                await(20_000, () -> plans(team).size() >= r, "第 " + r + " 版计划送达");
                team.sendMessage(TeammateRunner.LEAD_NAME, "backend", "REVISE: 再改改（第 " + r + " 次）");
            }

            // 出满上限后必须放行 —— 不会再发第 max+1 版计划
            await(20_000, () -> sawIdle(team, "completed initial task"), "超限后自行开工");
            assertEquals(max, plans(team).size(),
                    "超出上限就不该再出计划: " + plans(team));

            // 放行这件事要说清楚，否则 lead 以为自己的意见被采纳了
            assertTrue(leadInbox(team).stream().anyMatch(m -> m.contains("已出满")),
                    "超限放行要有明示: " + leadInbox(team));
        } finally {
            team.stopAll();
        }
    }

    /**
     * lead 不回复时不能无限期停摆。
     *
     * <p>这是本次改动自己引入的新风险：改动前队友一定会自行开工，现在它等 lead 回话，
     * 而 lead 可能正卡在一个长轮里、或干脆没理。没有超时的话，一个被忽略的计划就能
     * 让整支队伍卡死 —— 比不做审批更糟。超时后必须退化回改动前的行为。
     */
    @Test
    void aSilentLeadDoesNotStallTheTeammateForever() throws Exception {
        TeammateRunner.planApprovalTimeoutMs = 1_000;   // 别真等 3 分钟

        var tm = new TeamManager();
        var team = tm.createTeam("silent", TeamManager.TeamMode.IN_PROCESS);

        var script = List.of(
                say("PLAN-V1\n计划已发，但没人理我"),
                say("executed anyway"));

        var tool = agentToolFor(script, tm);

        try {
            assertFalse(tool.execute(Map.of(
                    "team_name", "silent", "name", "backend",
                    "description", "负责后端 API", "prompt", "实现后端 API")).isError());

            await(20_000, () -> !plans(team).isEmpty(), "计划送达");

            // 全程没人回复 lead 的邮箱 —— 队友必须自己走出来
            await(20_000, () -> sawIdle(team, "completed initial task"),
                    "lead 沉默时队友应超时自行开工，而不是永久阻塞");

            assertTrue(leadInbox(team).stream().anyMatch(m -> m.startsWith("[plan-limit]") && m.contains("没有回复")),
                    "超时放行要给 lead 留明示，免得它以为意见被采纳了: " + leadInbox(team));
            assertEquals(1, plans(team).size(), "超时不该额外再出一版计划: " + plans(team));
        } finally {
            team.stopAll();
        }
    }

    /**
     * 计划阶段的白名单：只读工具 + SendMessage，其余一律不放。
     *
     * <p>上面的端到端用例证明了「闸门会拦」，这个用例证明「拦的是哪些」——
     * 两者独立：闸门可能拦得很准，但白名单本身开得过大（比如漏放了 Bash）。
     */
    /**
     * 计划正文要取「本轮最长的那条」assistant 正文，不能取「最后一条」。
     *
     * <p>这条用例是照着**实测的失败现场**写的。2026-09-25 真跑时 backend 计划轮的实际
     * 序列是：先写出完整计划 → 顺手用 SendMessage 自己发给 lead → 再补一句
     * 「计划已发送给 lead，等待审批。」收尾。取最后一条拿到的正是那句废话 ——
     * lead 收到的计划正文就是它，真计划被丢掉了。
     */
    @Test
    void planTextIsTheLongestAssistantMessageNotTheLast() {
        var conv = new com.mewcode.conversation.ConversationManager();
        conv.addUserMessage("<addendum>");
        int turnStart = conv.size();          // 本轮从这里开始

        String plan = "PLAN: 一、要创建的文件 - backend/server.js - backend/store.js "
                + "二、接口契约 GET /api/todos 返回 [{id,title,completed}] 三、验证方式 curl 自测";

        conv.addAssistantFull("我先读一下现状。", null, List.of());
        conv.addAssistantFull(plan, null,
                List.of(new com.mewcode.conversation.ToolUseBlock("t1", "SendMessage", Map.of())));
        conv.addToolResultsMessage(List.of(
                new com.mewcode.conversation.ToolResultBlock("t1", "Message sent to lead.", false)));
        conv.addAssistantFull("计划已发送给 lead，等待审批。", null, List.of());

        assertEquals(plan, TeammateRunner.longestAssistantTextSince(conv, turnStart));
    }

    /**
     * 提取范围必须限定在本轮之内。
     *
     * <p>不限范围的话，重出计划那一版会把**上一版**的内容原样报上去 —— 版本号涨了、
     * 内容没变，lead 会以为队友无视了它的意见，而实际上队友改了、只是没被取到。
     */
    @Test
    void planTextExtractionIsScopedToTheCurrentRound() {
        var conv = new com.mewcode.conversation.ConversationManager();
        conv.addUserMessage("<addendum>");
        conv.addAssistantFull("旧的、很长很长的一版计划".repeat(20), null, List.of());

        int turnStart = conv.size();          // 新一轮从这里开始
        String current = "新版计划：按 lead 意见把字段名写死";
        conv.addAssistantFull(current, null, List.of());

        assertEquals(current, TeammateRunner.longestAssistantTextSince(conv, turnStart));
    }

    /** 本轮一条正文都没有时不能抛出，也不能把上一轮的内容报上去。 */
    @Test
    void planTextExtractionHandlesAnEmptyRound() {
        var conv = new com.mewcode.conversation.ConversationManager();
        conv.addUserMessage("<addendum>");
        conv.addAssistantFull("上一轮的计划", null, List.of());

        int turnStart = conv.size();
        String plan = TeammateRunner.longestAssistantTextSince(conv, turnStart);

        assertFalse(plan.contains("上一轮的计划"), plan);
        assertTrue(plan.contains("没有输出计划正文"), plan);
    }

    @Test
    void planningFilterAllowsOnlyReadToolsAndSendMessage() {
        var registry = registryWithFileTools();
        registry.register(new com.mewcode.tool.impl.GlobTool());
        registry.register(new com.mewcode.tool.impl.GrepTool());
        registry.register(new com.mewcode.tool.impl.BashTool());
        registry.register(new com.mewcode.teams.TeamTools.SendMessageTool(new TeamManager(), "backend"));

        var filter = TeammateRunner.planningToolFilter(registry);

        // 放行：读得了现状，也问得了人
        assertTrue(filter.test("ReadFile"));
        assertTrue(filter.test("Glob"));
        assertTrue(filter.test("Grep"));
        assertTrue(filter.test("SendMessage"), "任务有歧义时队友得能问一句，否则只能瞎猜着写计划");

        // 拦下：动手的一切
        assertFalse(filter.test("WriteFile"));
        assertFalse(filter.test("EditFile"));
        assertFalse(filter.test("Bash"));
        assertFalse(filter.test("InstallSkill"));

        // 注册表里没有的名字也不能放行 —— 判据是「存在且只读」，不是「不在黑名单里」
        assertFalse(filter.test("SomeToolThatDoesNotExist"));
    }
}
