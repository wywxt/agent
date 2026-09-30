// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.agent.AgentEvent;
import com.mewcode.tui.SpinnerVerbs;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main loop for in-process teammates.
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**
     * Runs a teammate agent loop in the current thread. Blocks until shutdown
     * or context cancellation (thread interrupt).
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        runInProcessTeammate(team, member, initialPrompt, addendum, null);
    }

    /**
     * @param eventSink 队友事件的出口，可为 {@code null}。
     *
     * <p>传 {@code null} 时退回一个局部队列 —— 那个队列**没有消费者**，写满即静默丢弃。
     * 保留它是为了让 TUI / PrintMode 这两条没接线的路径行为与改动前完全一致
     * （它们本来就不消费队友事件，队友数据走 {@code progress.jsonl} 与邮箱）。
     * 要做外部观测就必须传真实的 sink，见 {@code TeamManager.setTeammateEventSink}。
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum,
            BlockingQueue<AgentEvent> eventSink
    ) {
        BlockingQueue<AgentEvent> eventOut =
                eventSink != null ? eventSink : new LinkedBlockingQueue<>(32);

        // Create progress tracker and attach to member
        var progress = new TeammateProgress(member.getName(), team.getName(), SpinnerVerbs.random());
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // Inject any pending mailbox messages
        injectPendingMessages(team, member.getName(), member.conv);
        // 名册每轮注入：spawn 时其他成员可能还不存在
        injectRoster(team, member.getName(), member.conv);

        try {
            // ── 阶段一：先出计划，交 lead 审批 ──
            // 计划是自包含文本，「发出 → 结束本轮 → 等审批」正好是收件箱那条
            // 轮末投递管道的形状，一跳即可；而实时协商要多个来回，每轮都撞在边界上
            // （实测一次盲等 40.1s）。所以把接口契约的产出时机从「队友事后协商」
            // 前移到「动手之前」，队友之间就不需要对聊了。
            if (!planAndAwaitApproval(team, member, initialPrompt, eventOut, progress)) {
                return;   // 被中断或收到 shutdown
            }

            // ── 阶段二：按批准的计划执行 ──
            // 任务原文在这里才投给队友：计划阶段看到的是「任务 + 先出计划」的包装，
            // 开工时给回原文，免得它把包装里的措辞当成任务本身的一部分。
            member.conv.addUserMessage(initialPrompt);

            var agentQueue = runTurn(member, progress);
            drainAgentEvents(agentQueue, eventOut, team, member, progress);

            // 「先发通知、后置空闲」的顺序不能反：lead 正在按 isBusy 判要不要收工，
            // 先置空闲的话，它可能在这一条通知落进邮箱之前就收工走了，消息白写。
            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "completed initial task"));
            progress.setStatus("idle");

            // Subsequent turns: wait for mailbox messages
            while (!Thread.currentThread().isInterrupted()) {
                var result = waitForNextPromptOrShutdown(team, member.getName());
                if (result.shutdown || result.prompt == null) break;

                member.conv.addUserMessage(result.prompt);
                injectRoster(team, member.getName(), member.conv);
                agentQueue = runTurn(member, progress);
                drainAgentEvents(agentQueue, eventOut, team, member, progress);

                team.sendMessage(member.getName(), LEAD_NAME,
                        createIdleNotification(member.getName(), "completed follow-up"));
                progress.setStatus("idle");
            }
        } finally {
            member.active = false;
            progress.setStatus("completed");

            // 队友退出时持久化对话记录，用于调试（放 finally：计划阶段提前 return
            // 的路径上同样要留档，否则恰恰是没跑完的那些队友查不到现场）
            try {
                Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
            } catch (Exception ignored) {
                // best-effort：持久化失败不影响正常退出
            }
        }
    }

    // ── 计划阶段 ──────────────────────────────────────────────────────

    /**
     * 跑一轮，并把「我正在干活」标出去。
     *
     * <p>这个标记不是给界面看的，是给 lead 的收工判定用的（{@code Agent.awaitBusyPeers}）：
     * lead 只有一轮没有任何工具调用时才会考虑收工，而那一刻若队友正在跑，就该等而不是走。
     * 之前 {@code status} 从构造起一直是 {@code "running"}，直到线程退出才变 —— 那样
     * 「忙」等于「线程活着」，空闲等消息的队友也满足，lead 会在任务尾巴上白等满上限。
     */
    private static BlockingQueue<AgentEvent> runTurn(TeamManager.Member member,
                                                     TeammateProgress progress) {
        progress.setStatus("running");
        // Agent 的 usage 累计以 run 为界，跨 run 会归零；不标边界就会被当成回退，
        // 算出负数（见 TeammateProgress.beginRun）。
        progress.beginRun();
        return member.agent.run(member.conv);
    }

    /**
     * 计划最多出几版，超过就放行。
     *
     * <p>继续来回的代价比「带着未定稿的计划开工」更大：每一版来回都是一整轮 API
     * 往返，而 lead 若始终不肯吐批准词，再耗下去只是把预算烧光。
     */
    public static final int MAX_PLAN_ROUNDS = 3;

    /**
     * 等 lead 审批的上限，超时同样放行。
     *
     * <p>这条上限堵的是本改动自己引入的新失败模式：改动前队友一定会自行开工，现在
     * 它要等 lead 回话 —— 而 lead 可能正卡在一个长轮里，或者干脆没理。没有上限的话，
     * 一个被忽略的计划就能让整支队伍**无限期停摆**，比不做审批还糟。超时后退化回
     * 改动前的行为（队友自行开工），只是留一条明示。
     *
     * <p>非 final 是为了让测试能把它调小 —— 真等满这一档要 3 分钟，那会让测试慢到
     * 没人愿意跑，等于没有测试。
     */
    static volatile long planApprovalTimeoutMs = 3 * 60 * 1000L;

    /** 要求队友把计划写在正文里 —— 计划正文要能被机器取出来，不能只藏在工具调用参数里。 */
    private static final String PLAN_OUTPUT_INSTRUCTION =
            "Output the plan as the TEXT of your final message this turn — nothing else. "
            + "Write it out in full; the lead reads only that message. "
            // 实测队友会顺手用 SendMessage 把计划自己发给 lead，然后补一句
            // 「计划已发送给 lead，等待审批。」收尾 —— 结果计划被投递两次，而且
            // harness 转发的是那句废话。所以这条禁令是必须的，不是客套。
            + "Do NOT also send the plan with SendMessage: the harness forwards your final "
            + "message to the lead for you, so sending it yourself delivers it twice and "
            + "replaces the plan with a sign-off line.";

    /**
     * 让队友先出计划、交 lead 审批，批准后才放开写权限。
     *
     * <p><b>这里为什么是真闸门、不是提示词君子协定：</b>计划阶段给队友的 Agent 装一个
     * 「只读工具」的 {@code toolNameFilter}，而 {@code Agent} 现在会把同一份判定交给
     * {@code StreamingExecutor} 在执行侧复查一遍。所以模型即便凭记忆硬吐出一个
     * {@code WriteFile}，拿到的是一条 error 结果，而不是真的写盘。
     *
     * <p><b>为什么不用 {@code PermissionMode.PLAN}：</b>那个模式对写操作返回 ASK，
     * 而 ASK 会挂起等审批**最多 5 分钟**（{@code StreamingExecutor.APPROVAL_WAIT_MS}）。
     * 无人值守下没人应答 —— 队友一试图写就静默卡死 5 分钟，比不做闸门还糟。
     *
     * @return true 表示可以进入执行阶段；false 表示被中断或收到 shutdown，调用方应直接退出。
     */
    private static boolean planAndAwaitApproval(TeamManager.Team team, TeamManager.Member member,
                                                String task, BlockingQueue<AgentEvent> eventOut,
                                                TeammateProgress progress) {
        member.agent.setToolNameFilter(planningToolFilter(member.agent.getRegistry()));

        String nextPrompt = buildPlanStagePrompt(task);

        for (int round = 1; round <= MAX_PLAN_ROUNDS; round++) {
            // 记下本轮起点，提取计划时只在本轮范围内找 —— 否则重出的那一版会把
            // 上一版的内容原样报上去
            int turnStart = member.conv.size();

            member.conv.addUserMessage(nextPrompt);
            injectRoster(team, member.getName(), member.conv);

            var queue = runTurn(member, progress);
            drainAgentEvents(queue, eventOut, team, member, progress);
            if (Thread.currentThread().isInterrupted()) return false;

            // 结构化提取，不依赖模型记得调 SendMessage：计划正文就是本轮里最长的那条
            // assistant 消息（prompt 里已要求它把计划作为正文输出）。
            String plan = longestAssistantTextSince(member.conv, turnStart);
            team.sendMessage(member.getName(), LEAD_NAME,
                    "[plan] %s（第 %d/%d 版）:\n%s"
                            .formatted(member.getName(), round, MAX_PLAN_ROUNDS, plan));
            // 等审批 = 不干活。lead 若在这期间结束自己那一轮，会看到「没有队友在跑」
            // 而收工 —— 但这条 [plan] 就在它邮箱里，下一轮它会读到并回话。
            progress.setStatus("idle");

            var result = waitForNextPromptOrShutdown(team, member.getName(),
                    System.currentTimeMillis() + planApprovalTimeoutMs);
            if (result == null) {
                return proceedWithoutApproval(team, member,
                        "lead 在 %d 秒内没有回复".formatted(planApprovalTimeoutMs / 1000));
            }
            if (result.shutdown || result.prompt == null) return false;

            if (isApproval(result.leadText)) {
                member.agent.setToolNameFilter(null);   // 放行，回到「队友本来就没有 filter」的常态
                member.conv.addSystemReminder("The lead APPROVED your plan. Execute it now.");
                return true;
            }

            if (round == MAX_PLAN_ROUNDS) {
                return proceedWithoutApproval(team, member,
                        "已出满 %d 版仍未获批准（最后的意见：%s）"
                                .formatted(MAX_PLAN_ROUNDS, truncate(result.leadText, 200)));
            }

            // 未批准 → 当作修改意见，仍在只读阶段重出一版
            nextPrompt = buildReplanPrompt(result.leadText);
        }
        return true;
    }

    /**
     * 放行开工，并把「为什么没等来批准」讲清楚。
     *
     * <p>闸门是手段不是目的。两条异常路径 —— lead 超时不回、来回超过上限 —— 都退化到
     * 改动前的行为（队友自行开工），而不是把整支队伍卡在计划阶段。区别在于这里会给
     * lead 留一条明示，免得它以为自己的意见被采纳了。
     */
    private static boolean proceedWithoutApproval(TeamManager.Team team, TeamManager.Member member,
                                                  String reason) {
        member.agent.setToolNameFilter(null);
        member.conv.addSystemReminder(
                "Plan review ended without an explicit approval (" + reason + "). "
                + "Proceed with execution now, incorporating any feedback you received.");
        team.sendMessage(member.getName(), LEAD_NAME,
                "[plan-limit] %s: %s，直接开工".formatted(member.getName(), reason));
        return true;
    }

    /**
     * 计划阶段的工具白名单：只读工具 + {@code SendMessage}。
     *
     * <p>判据用现成的 {@link com.mewcode.tool.ToolCategory#READ}，而不是硬编码一张工具名
     * 清单 —— 后者会在新增工具时静默过期（新加的写工具会被漏放进来）。{@code SendMessage}
     * 是 COMMAND 类但必须留着：任务本身有歧义时队友得能问一句，否则只能瞎猜着写计划。
     */
    static java.util.function.Predicate<String> planningToolFilter(
            com.mewcode.tool.ToolRegistry registry) {
        return name -> {
            if ("SendMessage".equals(name)) return true;
            var tool = registry.get(name);
            return tool != null && tool.category() == com.mewcode.tool.ToolCategory.READ;
        };
    }

    /**
     * 判定 lead 的回复算不算批准。
     *
     * <p><b>先查否定词、再查肯定词。</b>模型常写「不批准」「REVISE:」「not approved」，
     * 这些都含肯定词 —— 顺序反了就会把否决读成批准，那等于闸门自己把门打开，比不做还糟。
     */
    private static boolean isApproval(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase();
        for (String neg : new String[]{"revise", "reject", "不批准", "不同意",
                "not approv", "don't approv", "cannot approv", "can't approv"}) {
            if (t.contains(neg)) return false;
        }
        // "approve" 是给 lead 的提示词里指定的那个词，必须认；它同时涵盖
        // "[approve]" 与 "approved" 两种写法。
        for (String pos : new String[]{"approve", "批准", "同意"}) {
            if (t.contains(pos)) return true;
        }
        return false;
    }

    private static String buildPlanStagePrompt(String task) {
        return """
                你的任务是：
                %s

                现在处于【计划阶段】：你只有只读工具（ReadFile / Glob / Grep / ToolSearch），
                写文件和执行命令的权限尚未开放 —— 试图调用它们会直接失败，不要试。

                先读懂现状，然后输出一份可执行的计划，必须写清：
                1. 要创建/修改哪些文件 —— 逐个列出路径；
                2. 对外接口契约（函数签名、HTTP 路径与方法、请求/响应字段名与类型）——
                   逐字写死，不要写"视情况而定"或"与队友协商"；
                3. 与队友的分界：哪些归你，哪些你不碰；
                4. 你打算怎么验证自己的产出。

                %s
                计划发出后本轮结束，等 lead 审批；批准后才拿得到写权限。
                """.formatted(task, PLAN_OUTPUT_INSTRUCTION);
    }

    /**
     * 未获批准时的重出提示。
     *
     * <p>{@code feedback} 可能为 null —— 等到的可能是同伴的消息而非 lead 的回复。
     * 那种情况下也得让队友重出一版，不能因为它没听懂而卡死。
     */
    private static String buildReplanPrompt(String feedback) {
        var sb = new StringBuilder("The lead has not approved your plan yet.\n");
        if (feedback == null || feedback.isBlank()) {
            sb.append("No specific feedback arrived. Re-read the task and produce a tighter plan.\n");
        } else {
            sb.append("The lead asked for changes:\n\n").append(feedback.strip())
              .append("\n\nRevise the plan accordingly.\n");
        }
        sb.append(' ').append(PLAN_OUTPUT_INSTRUCTION);
        return sb.toString();
    }

    /**
     * 取本轮里**最长**的那条 assistant 正文，作为计划正文。
     *
     * <p><b>为什么不是「最后一条」：</b>实测模型在计划轮里会先写出计划，再补一句
     * 「计划已发送给 lead，等待审批。」收尾 —— 取最后一条拿到的正是那句废话，真计划
     * 反被丢掉（2026-09-25 实测：lead 收到的计划正文就是这一句）。计划是模型在这一轮
     * 里写的最长的东西，所以按长度取比按位置取稳。
     *
     * @param fromIndex 本轮开始前的对话长度。必须传：不限定范围的话，上一轮的计划会被
     *                  当成本轮的（重出计划时表现为版本号涨了、内容没变）。
     */
    static String longestAssistantTextSince(com.mewcode.conversation.ConversationManager conv,
                                             int fromIndex) {
        var messages = conv.getMessages();
        String best = null;
        for (int i = Math.max(0, fromIndex); i < messages.size(); i++) {
            var m = messages.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            String c = m.getContent();
            if (c == null || c.isBlank()) continue;
            if (best == null || c.strip().length() > best.length()) best = c.strip();
        }
        return best == null ? "(本轮没有输出计划正文)" : best;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /**
     * Drains lead's mailbox across all teams, returning formatted notification strings.
     * Called by the Lead's NotificationFn each iteration.
     *
     * <p><b>每轮进度不单独唤醒 lead。</b>返回非空就意味着 lead 要起一整轮：TUI 走
     * {@code MailboxPollMessage}（{@code tui/MewCodeModel.java:391}），remote 走
     * 邮箱轮询（{@code remote/RemoteServer.java:828}），两者都会把整段上下文再发
     * 一次 API。队友跑 40 轮就是 40 条进度，每条唤醒一次 —— 那份开销是**观测手段
     * 自己造出来的**，会直接把「多 agent 比单 agent 快多少」这个测量搞脏。
     *
     * <p>所以进度只搭车：留在邮箱里（带 ISO 时间戳，本身就是逐轮时间线），等到
     * 下一条实质消息（队友完成 / 提问 / 报错）到达时一起送达。代价是队友跑到一半
     * 时 lead 看不到中间进度 —— 拿监督粒度换测量可信。若哪天更看重中途可见，
     * 把下面那个 {@code hasSubstantive} 的判断去掉即可。
     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            boolean hasSubstantive = messages.stream()
                    .anyMatch(m -> !TeammateProgress.isProgressReport(m.text()));
            // 刻意不 markAllRead：进度留着，下次有实质消息时一并送出
            if (!hasSubstantive) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**
     * Builds the system reminder addendum for a teammate.
     *
     * <p>名册里给的是**真实成员名**。SendMessage 的 {@code to} 只能用它：成员名由
     * Agent 工具的 name 参数决定，lead 在 prompt 里对成员的称呼未必和真名一致，
     * 队友若照 prompt 里的称呼发消息，就会写进没人会读的收件箱。
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            appendRoster(sb, otherMembers);
        }
        // SendMessage 保留给所有人（不做收件人限制），但把默认路径指向 lead：队友之间
        // 点对点协商要多个来回，每个来回都撞在「轮末才投递」的边界上，是最贵的一种
        // 沟通。凡涉及别的成员也依赖的接口，就该由 lead 定，而不是两边对聊。
        sb.append("You can communicate with teammates using the SendMessage tool, but prefer routing "
                + "through the lead (\"").append(LEAD_NAME).append("\"): anything that touches an "
                + "interface another member depends on should be settled by the lead, not negotiated "
                + "peer-to-peer. Message a peer directly only when the lead needn't arbitrate.\n");
        // 这句话以前写的是「消息在每个 turn 开始时到达」，对队友是假的：收件箱只在
        // 整轮结束后才被检查（waitForNextPromptOrShutdown），轮内永远收不到新消息。
        // 说清楚这点，队友才不会在轮内空等一个永远不会到的回复。
        sb.append("Messages from teammates are delivered when your current turn ENDS, never mid-turn: "
                + "finish the turn (stop calling tools) and the reply arrives as your next prompt. "
                + "If you need a peer's answer before proceeding, end your turn and wait for it — "
                + "polling with tools inside the turn will never surface it.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        sb.append("\n\n");
        appendHandoffContract(sb);
        return sb.toString();
    }

    /**
     * 完工交接的格式约定：交证据，不交结论；交脚本，不交一场手工验收。
     *
     * <p><b>为什么必须写死这一段：</b>2026-09-25 的 team 实跑里，backend 干完就把
     * 自己写的 {@code curl-tests.sh} 和 {@code restart-check.sh} {@code rm} 掉了，
     * 也**没有发任何完工报告**——lead 收件箱里只有硬编码的
     * {@code [idle] backend: completed initial task}。于是 lead 手上既没有脚本也没有结论，
     * 只能自己去 {@code ReadFile} 逐个复读源码重建事实，再打回让 backend 改，一条 bug
     * 三个来回。同一轮的 frontend 则花了约 20 轮做手工端到端验收（起浏览器走 CDP、
     * 5 次 curl 重试循环），而代码在前 60 秒就写完了。
     *
     * <p>两半是一件事的两面：**验收的活要么固化成脚本交给 lead，要么就是白花的**——
     * 手工跑一次的结论无法复现，写在报告里的自述又不构成证据。
     */
    private static void appendHandoffContract(StringBuilder sb) {
        sb.append("## Handoff — what your final message must contain\n\n");
        sb.append("Your LAST message this turn is the handoff. The lead decides what to do next "
                + "from that message alone and will NOT re-read your code, so it has to be "
                + "self-contained. Send it with SendMessage to \"").append(LEAD_NAME).append("\":\n\n");
        sb.append("1. **Files you changed** — exact paths, one per line.\n");
        sb.append("2. **A runnable self-test script you left in the repo** — e.g. "
                + "`backend/self-test.sh`, plus the exact command to run it. Write it with "
                + "WriteFile and DO NOT delete it afterwards: the lead runs it, it is the "
                + "evidence. A script that is deleted after one use leaves the lead with nothing.\n");
        sb.append("3. **Its raw output and exit code, pasted verbatim** — not a summary of what "
                + "you concluded. `Exit code 0` with the output above it.\n");
        sb.append("4. **What you did NOT verify** — say so plainly. An honest gap is cheap; "
                + "a gap the lead discovers later is expensive.\n\n");
        sb.append("Do not spend your turns on manual end-to-end verification: no browser "
                + "driving, no install, no long-running services, no curl retry loops. Fold "
                + "whatever check you want into the script from step 2 and let the lead run it "
                + "once. Verification that is not a script does not survive the turn it ran in.");
    }

    private static void appendRoster(StringBuilder sb, List<String> members) {
        sb.append("Team members — use these names EXACTLY as the SendMessage `to` field "
                + "(any other spelling will not reach them):\n");
        for (String m : members) sb.append("- ").append(m).append('\n');
        sb.append('\n');
    }

    /**
     * 每轮开始前注入最新名册。
     *
     * <p>队友 spawn 时名册可能还是空的 —— 第一个成员加入时其他成员尚未创建，所以
     * 只靠 spawn 时的那一份是不够的，必须每轮重新注入才能知道后来加入的队友及职责。
     */
    public static void injectRoster(TeamManager.Team team, String memberName,
                                    com.mewcode.conversation.ConversationManager conv) {
        var roster = team.memberRoster(memberName);
        if (roster.isEmpty()) return;
        var sb = new StringBuilder();
        appendRoster(sb, roster);
        conv.addSystemReminder(sb.toString().strip());
    }

    /**
     * Injects unread mailbox messages as a system reminder.
     */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            com.mewcode.conversation.ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s: %s (at %s)".formatted(memberName, reason,
                java.time.Instant.now().toString());
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * @param prompt   给队友看的整轮提示（含 "From X:" 包装）
     * @param shutdown 收到 shutdown 请求
     * @param leadText 本轮消息里**只来自 lead** 的正文合并，没有则为 null。
     *                 审批判定只认它：同伴喊一句 "approved" 不该把闸门打开。
     */
    private record WaitResult(String prompt, boolean shutdown, String leadText) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        return waitForNextPromptOrShutdown(team, memberName, 0);
    }

    /**
     * @param deadlineMillis 绝对时刻；&lt;= 0 表示无限等（执行阶段的常态 —— 队友本来就该
     *                       一直空闲着等下一条消息）。
     * @return {@code null} 表示等到 deadline 仍没有消息。只有传了 deadline 才可能拿到
     *         null，无 deadline 的调用方无需判空。
     */
    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName,
                                                          long deadlineMillis) {
        while (!Thread.currentThread().isInterrupted()) {
            if (deadlineMillis > 0 && System.currentTimeMillis() >= deadlineMillis) return null;
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, true, null);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (isShutdownRequest(msg.text())) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, true, null);
                }
            }

            // Format as prompt
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            var lead = new StringBuilder();
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
                if (LEAD_NAME.equals(msg.from())) lead.append(msg.text()).append('\n');
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), false, lead.isEmpty() ? null : lead.toString());
        }
        return new WaitResult(null, true, null);
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeamManager.Team team, TeamManager.Member member,
                                         TeammateProgress progress) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;
            sink.offer(event);

            // Record progress from agent events
            if (event instanceof AgentEvent.ToolStartEvent tse) {
                // 文案与计数共用这一个事件。ToolUseEvent 是「模型准备调用」，被闸门或
                // 权限拒掉的调用同样会发 —— 挂那儿会让 [progress] 显示一条根本没跑的
                // 动作，lead 据此判断队友在干什么就被带偏了。
                progress.recordToolExecuted(tse.toolName(), tse.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.TurnComplete tc) {
                reportTurn(team, member, progress, tc.turn(), sink);
            } else if (event instanceof AgentEvent.LoopComplete lc) {
                // 终轮（无工具调用那一轮）不发 TurnComplete，只有 LoopComplete。
                // 上一版在这里直接 return，于是最后一轮的产出与耗时永远丢失 ——
                // 而那恰恰是收尾那一轮，耗时通常还最长。
                reportTurn(team, member, progress, lc.totalTurns(), sink);
                return;
            }
        }
    }

    /**
     * 汇报一轮：落盘（度量用）+ 投递到 lead 邮箱（监督用）。
     *
     * <p>重复轮次（ExitPlanMode 路径上 {@code TurnComplete(n)} 之后紧跟
     * {@code LoopComplete(n)}）与失败占位（{@code LoopComplete(0)}）由
     * {@link TeammateProgress#completeTurn} 过滤掉，这里不再判断。
     */
    private static void reportTurn(TeamManager.Team team, TeamManager.Member member,
                                   TeammateProgress progress, int turnFromEvent,
                                   BlockingQueue<AgentEvent> sink) {
        var record = progress.completeTurn(turnFromEvent);
        if (record == null) return;

        ProgressLog.append(record);
        team.sendMessage(member.getName(), LEAD_NAME, TeammateProgress.formatTurn(record));

        // 第三个出口：让外部能实时看到队友的 token 消耗。
        // 必须放在 completeTurn 之后 —— 此刻 record.tokens() 才是按 run 边界
        // 算准的本轮增量（见 TeammateProgress.beginRun）。
        // 用 offer 而非 put：观测数据不该让队友线程阻塞在没人消费的队列上。
        sink.offer(new AgentEvent.TeammateUsageEvent(
                team.getName(), member.getName(), record.turn(), record.tokens()));
    }
}
