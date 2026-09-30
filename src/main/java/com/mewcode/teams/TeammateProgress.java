// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import java.util.Map;

public class TeammateProgress {
    public static class ToolActivity {
        public final String toolName;
        public final String description;
        public ToolActivity(String toolName, Map<String, Object> input) {
            this.toolName = toolName;
            this.description = describeActivity(toolName, input);
        }
        private static String describeActivity(String toolName, Map<String, Object> input) {
            return switch (toolName) {
                case "ReadFile" -> "Reading " + input.getOrDefault("file_path", "");
                case "EditFile" -> "Editing " + input.getOrDefault("file_path", "");
                case "WriteFile" -> "Writing " + input.getOrDefault("file_path", "");
                case "Bash" -> {
                    String cmd = String.valueOf(input.getOrDefault("command", ""));
                    yield "Running " + (cmd.length() > 40 ? cmd.substring(0, 40) + "…" : cmd);
                }
                case "Glob" -> "Searching " + input.getOrDefault("pattern", "");
                case "Grep" -> "Grepping " + input.getOrDefault("pattern", "");
                default -> toolName;
            };
        }
    }

    /**
     * 进度汇报的标记前缀。收件方靠它把「每轮进度」和其他消息分开
     * （{@link TeammateRunner#drainLeadMailbox} 只让进度搭车、不单独唤醒 lead）。
     */
    public static final String PROGRESS_PREFIX = "[progress]";

    /**
     * 一轮结束时的产出快照。
     *
     * <p>这是「这一轮干了什么、花了多久」的唯一记录：队友 transcript 里只有
     * {@code role}/{@code content}，没有时间；产物 mtime 会被随后的人工修复
     * 覆盖。少了它，「多 agent 比单 agent 快在哪」就只能得到一个总时长、
     * 无从解释。
     */
    public record TurnRecord(
            String member, String team, int turn, int tools, long tokens,
            long startTs, long endTs, String activity) {

        /** 本轮的墙钟耗时（毫秒）。 */
        public long durationMs() { return endTs - startTs; }
    }

    private int toolUseCount = 0;
    private long tokenCount = 0;
    /**
     * 已经结束的 run 累计的 token 数。
     *
     * <p>{@code Agent} 的 usage 是**以 run 为界**的累计量（{@code agentLoop} 里
     * {@code totalInput/totalOutput} 是局部变量，每次 {@code run()} 归零），
     * 所以跨 run 要自己把上一段折进来。详见 {@link #beginRun()}。
     */
    private long runBaseTokens = 0;
    /** 当前 run 内最后一次收到的原始累计值（即 UsageEvent 里的 input+output）。 */
    private long lastRunRaw = 0;
    private ToolActivity lastActivity;
    /**
     * running = 正在跑某一轮；idle = 线程活着但在轮末等消息。
     *
     * <p>volatile 是因为它会被另一个线程（lead）在收工判定里读（{@link #isBusy()}）：
     * 写都在 {@code synchronized} 里，读没有，光靠锁给不了可见性。
     */
    private volatile String status = "running"; // running|idle|completed|failed|stopped
    private final String name;
    private final String teamName;
    private final long startTime = System.currentTimeMillis();
    private final String spinnerVerb;

    // ── 逐轮计量（用于每轮的 TurnRecord）────────────────────────────
    private int toolsThisTurn = 0;
    /** 本轮开始时的累计 token 数；本轮用量 = tokenCount - tokensAtTurnStart。 */
    private long tokensAtTurnStart = 0;
    private long turnStartTs = startTime;
    /**
     * 已汇报过的最大轮次，用于去重。
     *
     * <p>{@code Agent} 在 ExitPlanMode 路径上会连着发 {@code TurnComplete(n)} 和
     * {@code LoopComplete(n)}（同一个轮次号）；正常结束路径的终轮又只有
     * {@code LoopComplete}。两种情况都要收下，却又不能重复收。
     */
    private int reportedTurn = 0;
    /**
     * 已结束的 run 累计走过的轮数。
     *
     * <p><b>轮次号也是以 run 为界的。</b>{@code Agent.agentLoop} 的轮计数同样是局部变量，
     * 每次 {@code run()} 从 1 重数；而这里的 {@link #reportedTurn} 是跨 run 的全局去重游标。
     * 于是第二个 run 的每一轮都撞上「turn 1 &lt;= 已汇报的 turn N」而被静默丢弃 ——
     * 实测表现为「计划轮有记录、开工之后的轮一条不落」。加上这个偏移量，
     * 事件里的轮次号才重新变成全局单调的。
     */
    private int runTurnOffset = 0;
    /** 当前 run 内见到的最大轮次号，{@link #beginRun()} 时折进 {@link #runTurnOffset}。 */
    private int turnsInRun = 0;

    public TeammateProgress(String name, String teamName, String spinnerVerb) {
        this.name = name;
        this.teamName = teamName;
        this.spinnerVerb = spinnerVerb;
    }

    /**
     * 记一次「工具真的执行了」，并据此更新活动文案。
     *
     * <p><b>文案与计数必须挂在同一个事件上。</b>原先文案走 {@code ToolUseEvent}、
     * 计数走 {@code ToolStartEvent}，而前者是「模型准备调用」——被闸门或权限拒掉的
     * 调用同样会发，于是文案里留下一条**根本没跑**的动作。2026-09-25 的 team 实跑
     * 就撞上了：某队友计划阶段的活动文案是 {@code Running ls -la}，那其实是被只读
     * 闸门拒掉的一次调用（同轮另一个只读工具真的跑了，所以 tools 计数为 1）。lead
     * 读到的 {@code [progress]} 因此被带偏，我也据此差点误判闸门失效。
     *
     * <p>{@code ToolStartEvent} 在闸门与权限检查之后、{@code tool.execute()} 之前发出，
     * 每个调用恰好一次，且带着真实参数 —— 挂在这里，文案和计数天然一致。
     */
    public synchronized void recordToolExecuted(String toolName, Map<String, Object> input) {
        toolUseCount++;
        toolsThisTurn++;
        lastActivity = new ToolActivity(toolName, input);
    }

    /**
     * 收到一条 {@code UsageEvent}。
     *
     * <p>事件里的值是**本次 run 内的累计**，不是这个队友的终身累计 ——
     * {@code Agent.agentLoop} 的 {@code totalInput/totalOutput} 是局部变量，
     * 每次 {@code run()} 都归零。所以跨 run 时这个值会**回退**。早先的实现
     * 直接覆盖（{@code tokenCount = input + output}），第二个回合必然算出负数：
     * 实测 49 条轮记录里 45 条为 0、1 条为 -176。
     *
     * <p>跨 run 的边界由 {@link #beginRun()} 精确标定；下面的「值回退」检测只是兜底，
     * 保证将来新增 {@code agent.run()} 调用点忘了调 {@code beginRun()} 时也不会出负数。
     */
    public synchronized void recordTokens(long input, long output) {
        long raw = input + output;
        if (raw < lastRunRaw) {
            // 值比上一次小 = 换了 run（或这次 provider 没上报）。把上一段折进来再续。
            runBaseTokens += lastRunRaw;
        }
        lastRunRaw = raw;
        tokenCount = runBaseTokens + raw;
    }

    /**
     * 标定一次 {@code agent.run(...)} 的开始，把上一段的累计折进 base。
     *
     * <p>为什么必须由调用方显式标定，而不是靠 {@link #recordTokens} 里的值回退推断：
     * 若上一轮很短而这一轮的首个 turn 很大，新值可能 ≥ 旧值，回退检测不触发 →
     * 被误判为同一 run 的续接 → **静默少算**。显式边界没有这个洞。
     */
    public synchronized void beginRun() {
        runBaseTokens += lastRunRaw;
        lastRunRaw = 0;
        runTurnOffset += turnsInRun;
        turnsInRun = 0;
    }

    public synchronized void setStatus(String s) { status = s; }

    /**
     * 是否正在跑某一轮 —— 供 lead 的收工判定用（{@code Agent.awaitBusyPeers}）。
     *
     * <p>与 {@code "running".equals(getStatus())} 是同一个判据，单独起个名字是因为它的
     * 含义容易被读错：{@code idle} 的队友**线程还活着**（{@code Member.active} 仍为
     * true，正阻塞在等消息），但它没在干活。lead 若按「线程活着」判，会在每个团队任务
     * 的尾巴上白等满等待上限。
     */
    public boolean isBusy() { return "running".equals(status); }

    /**
     * 结束当前轮并返回本轮快照；该轮次已经汇报过（或轮次号不合法）时返回
     * {@code null}，由调用方跳过。
     *
     * <p>轮次号取自事件而非自增：终轮只发 {@code LoopComplete(totalTurns)}，
     * 自增计数会与事件里的编号错位。但事件里的编号是 **run 内** 的，去重要用
     * 加上 {@link #runTurnOffset} 之后的全局编号，否则第二个 run 会被整体丢掉。
     */
    public synchronized TurnRecord completeTurn(int turnFromEvent) {
        // <= 0 是失败/取消路径上的 LoopComplete(0) 占位，不是真的跑过一轮
        if (turnFromEvent <= 0) return null;
        int turn = runTurnOffset + turnFromEvent;
        if (turn <= reportedTurn) return null;
        reportedTurn = turn;
        turnsInRun = Math.max(turnsInRun, turnFromEvent);

        long now = System.currentTimeMillis();
        var record = new TurnRecord(name, teamName, turn, toolsThisTurn,
                tokenCount - tokensAtTurnStart, turnStartTs, now,
                lastActivity != null ? lastActivity.description : "");

        toolsThisTurn = 0;
        tokensAtTurnStart = tokenCount;
        turnStartTs = now;
        return record;
    }

    /** 一条消息是不是每轮进度汇报。 */
    public static boolean isProgressReport(String text) {
        return text != null && text.stripLeading().startsWith(PROGRESS_PREFIX);
    }

    /**
     * 每轮汇报的单行文本，发往 lead 的邮箱。
     *
     * <p>把耗时写进正文是因为邮箱时间戳只记**轮末**，轮的起点要由
     * {@code 轮末 - 耗时} 反推 —— 有了每个队友每轮的区间，才谈得上算并行度。
     * 文案用英文，和队友/lead 看到的其他系统提示一致。
     */
    public static String formatTurn(TurnRecord r) {
        var sb = new StringBuilder();
        sb.append(PROGRESS_PREFIX).append(' ').append(r.member())
          .append(": turn ").append(r.turn()).append(" done — ")
          .append(r.tools()).append(r.tools() == 1 ? " tool call, " : " tool calls, ")
          .append(formatTokens(r.tokens())).append(" tokens, ")
          .append(String.format(java.util.Locale.ROOT, "%.1fs", r.durationMs() / 1000.0));
        if (r.activity() != null && !r.activity().isEmpty()) {
            sb.append(" (last: ").append(r.activity()).append(')');
        }
        return sb.toString();
    }

    // Getters
    public String getName() { return name; }
    public String getTeamName() { return teamName; }
    public int getToolUseCount() { return toolUseCount; }
    public long getTokenCount() { return tokenCount; }
    public String getStatus() { return status; }
    public String getSpinnerVerb() { return spinnerVerb; }
    public ToolActivity getLastActivity() { return lastActivity; }

    public synchronized String getActivitySummary() {
        if (lastActivity != null) return lastActivity.description;
        return spinnerVerb;
    }

    public static String formatTokens(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }
}
