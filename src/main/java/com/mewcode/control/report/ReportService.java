package com.mewcode.control.report;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.control.event.EventEnvelope;
import com.mewcode.control.event.EventStore;
import com.mewcode.control.event.EventType;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 低成本模型辅助服务。基于脱敏事件时间线生成进度/最终报告、解答问询，
 * 并对权限第二层提供"这条命令会做什么"的润色解释。
 *
 * 不执行命令、不签发授权；小模型不可用或超时时返回 null/降级文本，
 * 不拖住主任务、不扩大授权。
 */
public final class ReportService {

    private static final long DEFAULT_TIMEOUT_MS = 15_000;
    private static final long EXPLAIN_TIMEOUT_MS = 5_000;

    private final LlmClient model;   // 可空：无模型时降级为纯事实
    private final EventStore store;

    public ReportService(LlmClient model, EventStore store) {
        this.model = model;
        this.store = store;
    }

    public boolean available() { return model != null; }

    // ── 进度 / 最终报告 ──────────────────────────────────────────────

    /** 进度报告：基于最近事件（最多 40 条）生成。 */
    public String generateProgress(String taskId) {
        var events = store.readByTask(taskId);
        if (events.isEmpty()) return null;
        int from = Math.max(0, events.size() - 40);
        return complete(progressPrompt(events.subList(from, events.size())));
    }

    /** 最终报告：基于整个任务事件流生成。 */
    public String generateFinal(String taskId) {
        var events = store.readByTask(taskId);
        if (events.isEmpty()) return null;
        // 只有发生了「真实工作」才生成最终报告；纯读、纯闲聊（含 ls/cat/git status
        // 这类只读 Bash）不生成，避免每次对话都产出一份空泛的最终报告。
        if (!didRealWork(events)) return null;
        return complete(finalPrompt(events));
    }

    /** 纯读工具：查询/阅读不算「真实工作」。 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("ReadFile", "Glob", "Grep");

    /** 只读/查询类 Bash 命令（前缀匹配），闲聊里跑这些不应触发最终报告。 */
    private static final Set<String> READ_ONLY_BASH_COMMANDS = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc", "find",
            "which", "whereis", "whoami", "hostname", "uname", "date", "cal",
            "uptime", "df", "du", "free", "env", "printenv", "file", "stat",
            "readlink", "realpath", "basename", "dirname", "sort", "uniq", "tr",
            "cut", "grep", "diff", "comm", "true", "false", "test",
            "git status", "git log", "git diff", "git show", "git branch",
            "git tag", "git remote", "git rev-parse", "git ls-files", "git blame",
            "node -v", "npm -v", "python --version", "pip list",
            "cargo --version", "rustc --version", "java -version");

    /**
     * 判断该任务是否发生了「真实工作」：
     * <ul>
     *   <li>任意写/命令/其它非纯读工具（WriteFile/EditFile/Agent…）→ 真实工作</li>
     *   <li>Bash 只要有一条非只读命令 → 真实工作；全是 ls/cat/git status 之类 → 不算</li>
     * </ul>
     */
    private static boolean didRealWork(List<EventEnvelope> events) {
        for (var ev : events) {
            if (ev.type() != EventType.TOOL_STARTED && ev.type() != EventType.TOOL_FINISHED) continue;
            String name = toolName(ev);
            if (name == null || READ_ONLY_TOOLS.contains(name) || "Bash".equals(name)) continue;
            return true;   // WriteFile / EditFile / Agent 等非读工具
        }
        // 剩下的非读工具只有 Bash：看命令内容
        for (var ev : events) {
            if (ev.type() != EventType.TOOL_PROPOSED) continue;
            if (!"Bash".equals(toolName(ev))) continue;
            String cmd = bashCommand(ev);
            if (cmd != null && !isReadOnlyBashCommand(cmd)) return true;
        }
        return false;
    }

    private static String toolName(EventEnvelope ev) {
        Object o = ev.payload().get("toolName");
        return o instanceof String s ? s : null;
    }

    private static String bashCommand(EventEnvelope ev) {
        Object args = ev.payload().get("args");
        if (!(args instanceof Map<?, ?> m)) return null;
        Object cmd = m.get("command");
        return cmd instanceof String s ? s.trim() : null;
    }

    private static boolean isReadOnlyBashCommand(String command) {
        if (command.isEmpty()) return true;
        for (String prefix : READ_ONLY_BASH_COMMANDS) {
            if (command.equals(prefix) || command.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    // ── 问询 ─────────────────────────────────────────────────────────

    /** 基于事件时间线回答用户对细节的提问。 */
    public String answerQuestion(String taskId, String question) {
        var events = store.readByTask(taskId);
        return complete(questionPrompt(events, question));
    }

    // ── 进度审查（循环看门狗第二层） ─────────────────────────────────

    public enum ProgressStatus { PROGRESSING, WANDERING, STUCK }

    /**
     * 一次进度审查的结论。
     *
     * <p>{@code score} 是相对目标的完成度（0~1），缺了它就看不出**趋势**：
     * 0.3 → 0.5 → 0.7 说明仍在推进（只是慢），0.6 → 0.6 才是真的原地打转。
     * 只给一个孤立的三态，这两种情况区分不出来，而它们的处置正好相反。</p>
     *
     * <p>{@code missing} 是「离目标还差什么」—— 有了它，结论才能变成下一轮的指令，
     * 而不是一句泛泛的「你缺乏方向」。</p>
     */
    public record ProgressVerdict(ProgressStatus status, String summary, double score, String missing) {

        /** 分数未知：模型没输出 SCORE，或值不在 [0,1]。 */
        public static final double SCORE_UNKNOWN = -1;

        /** 兼容三态构造：旧调用方 / 模型只回了 STATUS+SUMMARY 时用。 */
        public ProgressVerdict(ProgressStatus status, String summary) {
            this(status, summary, SCORE_UNKNOWN, "");
        }
    }

    /**
     * 审查主 Agent 是否在有效推进，还是缺乏方向地在绕圈（软空转）。
     * 供循环看门狗第二层在轮次超预算时调用。模型不可用/超时/无事件时返回 null。
     *
     * @param objective 任务的原始目标。审查员要判断的是「有没有朝目标推进」，
     *                  没有目标它只能靠工具名的表面条理去猜；null 时按「未提供」降级。
     * @param eventWindow 审查窗口（取最近多少条事件）。要覆盖「上一次审查到现在」这一段，
     *                    否则审查员看到的是一堆碎片截面，会系统性放大「没方向」的错觉。
     *                    条数由调用方按自己的审查间隔算（见 {@code ProgressReviewer}）。
     */
    public ProgressVerdict assessProgress(String taskId, int iteration,
                                          String objective, int eventWindow) {
        if (model == null) return null;
        var events = store.readByTask(taskId);
        if (events.isEmpty()) return null;
        int from = Math.max(0, events.size() - Math.max(1, eventWindow));
        String out = complete(
                assessPrompt(iteration, events.subList(from, events.size()), objective),
                DEFAULT_TIMEOUT_MS);
        return out == null ? null : parseProgressVerdict(out);
    }

    // ── 权限第二层润色解释 ────────────────────────────────────────────

    /**
     * 解释"这条工具调用会做什么"并给出 allow/deny/unsure 建议。
     * 同步调用（带短超时），失败返回 null——调用方据此降级为纯审批卡片。
     */
    public String explainInvocation(String toolName, Map<String, Object> args, String layer1Verdict) {
        if (model == null) return null;
        var prompt = """
                你是编码 Agent 的权限辅助。给定一个待审批的工具调用，用一句话中文解释
                它具体会做什么（含关键参数），再给出建议：allow（明显安全）/ deny（明显危险）
                / unsure（拿不准，交用户决定）。你只做解释和建议，不做最终授权。

                第一层规则引擎结论：%s
                工具：%s
                参数：%s

                请只输出两行，第一行"解释：…"，第二行"建议：allow|deny|unsure"。
                """.formatted(layer1Verdict, toolName, truncate(safeString(args), 500));
        String out = complete(prompt, EXPLAIN_TIMEOUT_MS);
        return out == null ? null : out.trim();
    }

    // ── 上下文压缩辅助：key_points 抽取 + 保真校验 ──────────────────────

    /** 校验结果：摘要遗漏的关键点 + 疑似幻觉的表述。 */
    public record SummaryVerdict(List<String> missing, List<String> hallucinations) {
        public boolean ok() { return missing.isEmpty() && hallucinations.isEmpty(); }
    }

    /**
     * 从将被压缩的历史块中抽取"摘要必须保留"的关键信息点。
     * 失败/超时/无模型时返回空列表，调用方据此降级为不带 key_points 的纯摘要。
     */
    public List<String> extractKeyPoints(String historyBlock) {
        if (model == null || historyBlock == null || historyBlock.isBlank()) {
            return List.of();
        }
        String prompt = """
                你是上下文压缩的关键信息抽取器。下面是一段将被摘要压缩的历史对话（已脱敏，可能含代码片段）。
                请提取出"摘要必须保留、否则后续任务无法继续"的关键信息点。

                要求：
                1. 每条是一个独立的简短陈述（一句以内），只陈述事实/决策/待办，不复述整段。
                2. 覆盖：用户明确要求、关键决策、涉及的文件与改动、报错与修复、未完成事项。
                3. 忽略寒暄、重复、以及可从近期消息轻易推断的琐碎内容。
                4. 直接按行输出，每行一条，不要编号、不要空行、不要额外解释。

                历史块：
                <history>
                %s
                </history>
                """.formatted(truncate(historyBlock, 40_000));
        String out = complete(prompt, DEFAULT_TIMEOUT_MS);
        if (out == null || out.isBlank()) {
            return List.of();
        }
        var points = new ArrayList<String>();
        for (String line : out.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            s = s.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.、)]\\s*", "");
            if (!s.isEmpty()) {
                points.add(s);
            }
            if (points.size() >= 60) break; // 上限，控制校验成本
        }
        return points;
    }

    /**
     * 校验摘要是否覆盖全部 key_points、是否新增幻觉。
     * 输入只含 key_points + 摘要，不喂原始日志，控制 token。
     * 失败/无模型时返回 null，调用方据此跳过校验。
     */
    public SummaryVerdict verifySummary(List<String> keyPoints, String summary) {
        if (model == null || keyPoints == null || keyPoints.isEmpty()
                || summary == null || summary.isBlank()) {
            return null;
        }
        String prompt = """
                你是上下文压缩的保真校验器。下面给出一份"关键信息点清单"和一份"压缩摘要"。
                请校验：
                1. 覆盖：摘要是否包含了清单里的每一条关键信息点？（逐条判断）
                2. 幻觉：摘要是否出现了清单之外、且无依据的结论或细节？

                只输出一个 JSON 对象（不要 Markdown 代码块，不要任何其他文字）：
                {"missing": ["缺失的关键点原文..."], "hallucinations": ["疑似幻觉的表述..."]}
                若没有，对应数组填空 []。

                关键点清单：
                %s

                摘要：
                %s
                """.formatted(joinKeyPoints(keyPoints), truncate(summary, 20_000));
        String out = complete(prompt, EXPLAIN_TIMEOUT_MS);
        if (out == null || out.isBlank()) {
            return null;
        }
        return parseVerdict(out);
    }

    private static String joinKeyPoints(List<String> keyPoints) {
        var sb = new StringBuilder();
        for (int i = 0; i < keyPoints.size(); i++) {
            sb.append(i + 1).append(". ").append(keyPoints.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static SummaryVerdict parseVerdict(String out) {
        try {
            String json = out.strip();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new SummaryVerdict(readStringList(node.get("missing")),
                    readStringList(node.get("hallucinations")));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readStringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var list = new ArrayList<String>();
        for (var item : node) {
            if (item.isTextual()) {
                list.add(item.asText());
            }
        }
        return list;
    }

    // ── 核心调用 ─────────────────────────────────────────────────────

    private String complete(String prompt) {
        return complete(prompt, DEFAULT_TIMEOUT_MS);
    }

    /** 同步补全：构造单轮会话，收集文本直到结束或超时。 */
    private String complete(String prompt, long timeoutMs) {
        if (model == null) return null;
        if (prompt == null || prompt.isBlank()) return null;
        var conv = new ConversationManager();
        conv.addUserMessage(prompt);
        BlockingQueue<StreamEvent> q;
        try {
            q = model.stream(conv, List.of());
        } catch (Exception e) {
            return null;
        }
        var sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            StreamEvent evt;
            try {
                evt = q.poll(remain, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (evt == null) break;
            if (evt instanceof StreamEvent.TextDelta td) sb.append(td.text());
            if (evt instanceof StreamEvent.StreamEnd || evt instanceof StreamEvent.Error) break;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    // ── Prompt 构造 ─────────────────────────────────────────────────

    private static String progressPrompt(List<EventEnvelope> events) {
        return """
                你是编码 Agent 的执行进度汇报助手。以下是近期工具执行事实（已脱敏）。
                用简洁中文输出：1) 当前阶段 2) 已完成事项 3) 阻塞点（若有）4) 下一步。
                不要编造事件之外的信息。

                事件时间线：
                %s
                """.formatted(renderEvents(events));
    }

    private static String finalPrompt(List<EventEnvelope> events) {
        return """
                你是编码 Agent 的最终报告助手。以下是整个任务的工具执行事实（已脱敏）。
                输出一份 Markdown 报告：改动文件、关键动作、测试结果、耗时分布（若可得）、
                结论。只依据给定事件，不要编造。

                事件时间线：
                %s
                """.formatted(renderEvents(events));
    }

    private static String questionPrompt(List<EventEnvelope> events, String question) {
        return """
                你是编码 Agent 的进度咨询助手。基于以下执行事实回答用户关于任务细节的提问。
                只依据事件时间线，不确定就明说"时间线里没有相关记录"。

                事件时间线：
                %s

                用户提问：%s
                """.formatted(renderEvents(events), question);
    }

    static ProgressVerdict parseProgressVerdict(String out) {
        ProgressStatus status = null;
        String summary = "";
        double score = ProgressVerdict.SCORE_UNKNOWN;
        String missing = "";
        for (String line : out.split("\\R")) {
            String s = line.strip();
            if (s.startsWith("STATUS:")) {
                status = parseStatus(s.substring("STATUS:".length()).strip());
            } else if (s.startsWith("SUMMARY:")) {
                summary = s.substring("SUMMARY:".length()).strip();
            } else if (s.startsWith("SCORE:")) {
                score = parseScore(s.substring("SCORE:".length()).strip());
            } else if (s.startsWith("MISSING:")) {
                missing = s.substring("MISSING:".length()).strip();
            }
        }
        if (status == null) return null;
        return new ProgressVerdict(status, summary, score, missing);
    }

    /**
     * 解析 0~1 的分数。认不出来一律记为 {@link ProgressVerdict#SCORE_UNKNOWN}。
     *
     * <p>分数只服务于「趋势」判断，缺了不影响 STATUS 的判定 ——
     * 所以这里宁可返回未知，也不猜一个值出来。</p>
     *
     * <p>模型可能写成 {@code SCORE: 0.7} / {@code SCORE: 0.7（约七成）} / {@code SCORE: 70%}，
     * 所以只取第一个数字，并做 0~1 的范围校验。</p>
     */
    private static double parseScore(String s) {
        var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (!m.find()) return ProgressVerdict.SCORE_UNKNOWN;
        try {
            double v = Double.parseDouble(m.group(1));
            return (v >= 0 && v <= 1) ? v : ProgressVerdict.SCORE_UNKNOWN;
        } catch (NumberFormatException e) {
            return ProgressVerdict.SCORE_UNKNOWN;
        }
    }

    /**
     * 只取首个词做精确匹配。包可见，便于测试。
     *
     * <p><b>为什么不能用 contains：</b>模型会用自然语言补充说明。它写
     * {@code STATUS: 并非 STUCK，仍在进展} 时，{@code contains("STUCK")} 会命中，
     * 把「明确说了不是卡住」读成「卡住」；而 STUCK 经
     * {@code ProgressReviewer.applyVerdict} 连续两次就直接 CANCEL。
     * 这是最不该存在的误杀路径：模型说没卡，系统读成卡了，然后终止任务。
     * 同理 {@code NOT WANDERING} 会被读成 WANDERING。</p>
     *
     * <p><b>为什么认不出来要返回 null：</b>解析失败必须降级为「不介入」，
     * 绝不能降级为「介入」。null 传到 {@code ProgressReviewer.applyVerdict}
     * 会变成 {@code Verdict.NONE}，任务照常跑 —— 这是安全的失败方向。</p>
     */
    static ProgressStatus parseStatus(String s) {
        if (s == null) return null;
        // 按首个非字母字符切开，只看第一个词；模型补充的说明（中文/括号/标点）
        // 一律不进判定。首词不是三个状态之一就返回 null。
        String first = s.strip().toUpperCase().split("[^A-Z]", 2)[0];
        return switch (first) {
            case "PROGRESSING" -> ProgressStatus.PROGRESSING;
            case "WANDERING" -> ProgressStatus.WANDERING;
            case "STUCK" -> ProgressStatus.STUCK;
            default -> null;
        };
    }

    /**
     * 进度审查 prompt。三处措辞是刻意的，改动前请先看 {@code 循环检测优化设计.md} 的 C1/C3/C4：
     *
     * <ul>
     *   <li><b>给了目标</b>：审查员判断的是「有没有朝目标推进」，而方向是相对目标而言的。
     *       原版没有任何目标，它只能靠工具名的表面条理去猜，这是判断不准的根本原因。</li>
     *   <li><b>去掉了诱导</b>：原版开头写「已运行 %d 轮，远超常规任务规模，疑似进展过慢或缺乏方向」——
     *       而它只在超预算后才会被调用，等于每次调用都先告诉它「这很可能有问题」，
     *       偏置方向正好指向 CANCEL。</li>
     *   <li><b>分开「慢」和「卡」</b>：任务规模大就会轮次多，轮次数本身不是判据。</li>
     * </ul>
     */
    private static String assessPrompt(int iteration, List<EventEnvelope> events, String objective) {
        String goal = (objective == null || objective.isBlank())
                ? "（未提供 —— 只能依据事件时间线自身的连贯性判断）"
                : truncate(objective.strip(), 2_000);
        return """
                你是编码 Agent 的进度审查员。主 Agent 已完成 %d 轮，请判断它相对任务目标的推进情况。

                <objective>
                %s
                </objective>

                先列出该目标隐含的、可核查的具体要求（3~7 条），再逐条到下面的事件时间线里找证据。
                只有时间线里看得到的证据才算数；没体现的要求明确记为"未验证"。最后才下结论。

                判据是"相对目标有没有推进"，不是轮次多少：
                  PROGRESSING —— 每轮都在朝目标靠近（哪怕慢，哪怕这是要改 20 个文件的大任务）
                  WANDERING   —— 有产出但在绕圈：反复试探、来回改同一处、目标漂移
                  STUCK       —— 基本停滞：重复同样的动作，或反复撞同一个错误且没有新信息
                轮次数多本身不算问题 —— 任务规模大就会轮次多，只有"没有净推进"才算卡。

                拿不准时判 PROGRESSING。你的结论会直接导致任务被终止，
                误判 STUCK 的代价（终止一个正在收敛的任务）远高于漏判的代价。

                只输出四行，不要其他内容：
                STATUS: PROGRESSING|WANDERING|STUCK
                SCORE: 0 到 1 之间的一个数，表示相对目标的完成度（只看证据，不猜）
                MISSING: 离目标还差什么，分号分隔（没有就留空）
                SUMMARY: 一句话中文说明（它在干嘛、卡在哪、是否该收尾）

                事件时间线：
                %s
                """.formatted(iteration, goal, renderEvents(events));
    }

    /**
     * 将事件序列化为脱敏文本行（截断超长输出）。包可见，便于测试。
     *
     * <p><b>为什么要把 output / args 也渲染出来：</b>这些数据本来就在事件里
     * （{@code EventBridge.toolFinished} 的 {@code output}、{@code toolProposed}
     * 的 {@code args}），只是渲染时被丢掉了。而读这份时间线的是「进度审查员」，
     * 它要判断的正是「有没有朝目标推进」—— 只给它「完成 ReadFile [成功] 0.02s」，
     * 它看不到改了哪个文件、测试报了什么错，判断只能靠工具名的表面条理去猜。
     * 数据在手上却扔掉，是这一层判断不准的直接原因之一。</p>
     *
     * <p>每条截断 300 字符：窗口放大后（见 C7）prompt 体积的主要成本在这里。</p>
     */
    static String renderEvents(List<EventEnvelope> events) {
        var lines = new ArrayList<String>(events.size());
        for (var e : events) {
            var p = e.payload();
            String line = switch (e.type()) {
                case TOOL_PROPOSED -> "准备调用 " + p.get("toolName")
                        + " 参数 " + truncate(safeString(asArgs(p.get("args"))), 300);
                case TOOL_STARTED -> "开始执行 " + p.get("toolName");
                case TOOL_FINISHED -> "完成 " + p.get("toolName")
                        + (Boolean.TRUE.equals(p.get("isError")) ? " [失败]" : " [成功]")
                        + " 耗时 " + p.get("elapsed") + "s"
                        + "\n    输出：" + truncate(asText(p.get("output")), 300);
                // payload 的 key 是 description，不是 toolName —— 读错会渲染成「等待审批 null」
                case APPROVAL_WAITING -> "等待审批 " + p.get("description");
                case DIRECTIVE_STATUS -> "指令状态 " + p.get("status");
                case TASK_TERMINAL -> "任务终态 " + p.get("status");
            };
            lines.add("- " + line + "\n");
        }
        return joinWithinBudget(lines);
    }

    /** 时间线总预算（字符）。 */
    private static final int RENDER_TOTAL_BUDGET = 60_000;

    /**
     * 拼接时间线，超预算时保留首尾、省略中段。
     *
     * <p><b>为什么必须有总预算：</b>带每行 300 字符的输出后，单行从约 40 字符涨到约 330 字符。
     * 进度审查和进度报告只取最近 40 条（有界），但最终报告和问答 {@code answerQuestion}
     * 会喂**整个任务事件流** —— 长任务上千条事件时，prompt 会比改动前放大近 10 倍，
     * 直接撞上下文上限。所以按总字符数封顶。</p>
     *
     * <p><b>为什么省略中段而不是丢掉尾部：</b>首段是任务怎么起头的（目标、涉及哪些文件），
     * 尾段是结论和最后一次测试结果 —— 报告和审查员主要靠这两头；中段是最密集的重复调用，
     * 信息增量最低。省略中段比丢尾部损失小。</p>
     */
    private static String joinWithinBudget(List<String> lines) {
        int total = 0;
        for (String l : lines) total += l.length();
        if (total <= RENDER_TOTAL_BUDGET) return String.join("", lines);

        int half = RENDER_TOTAL_BUDGET / 2;
        var head = new ArrayList<String>();
        var tail = new ArrayList<String>();
        int used = 0;
        for (String l : lines) {
            if (used + l.length() > half) break;
            head.add(l);
            used += l.length();
        }
        used = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (used + l.length() > half) break;
            tail.add(0, l);
            used += l.length();
        }
        head.add("- ...（中间省略 " + (lines.size() - head.size() - tail.size())
                + " 条事件，控制 prompt 体积）\n");
        head.addAll(tail);
        return String.join("", head);
    }

    /** 事件 payload 里的 args 声明为 Object，取出为 Map 再序列化。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asArgs(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String asText(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String safeString(Map<String, Object> args) {
        if (args == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }
}
