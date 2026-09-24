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

    public record ProgressVerdict(ProgressStatus status, String summary) {}

    /**
     * 审查主 Agent 是否在有效推进，还是缺乏方向地在绕圈（软空转）。
     * 供循环看门狗第二层在轮次超预算时调用。模型不可用/超时/无事件时返回 null。
     */
    public ProgressVerdict assessProgress(String taskId, int iteration) {
        if (model == null) return null;
        var events = store.readByTask(taskId);
        if (events.isEmpty()) return null;
        int from = Math.max(0, events.size() - 40);
        String out = complete(assessPrompt(iteration, events.subList(from, events.size())), DEFAULT_TIMEOUT_MS);
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

    private static ProgressVerdict parseProgressVerdict(String out) {
        ProgressStatus status = null;
        String summary = "";
        for (String line : out.split("\\R")) {
            String s = line.strip();
            if (s.startsWith("STATUS:")) {
                status = parseStatus(s.substring("STATUS:".length()).strip());
            } else if (s.startsWith("SUMMARY:")) {
                summary = s.substring("SUMMARY:".length()).strip();
            }
        }
        if (status == null) return null;
        return new ProgressVerdict(status, summary);
    }

    private static ProgressStatus parseStatus(String s) {
        String u = s.toUpperCase();
        if (u.contains("WANDERING")) return ProgressStatus.WANDERING;
        if (u.contains("STUCK")) return ProgressStatus.STUCK;
        if (u.contains("PROGRESSING")) return ProgressStatus.PROGRESSING;
        return null;
    }

    private static String assessPrompt(int iteration, List<EventEnvelope> events) {
        return """
                你是编码 Agent 的进度审查员。主 Agent 已运行 %d 轮，远超常规任务规模，
                疑似进展过慢或缺乏方向。以下是它最近的工具执行事实（已脱敏）。
                判断它的状态：
                  PROGRESSING —— 仍在有效推进、方向明确
                  WANDERING   —— 有产出但缺乏方向、在绕圈、目标不清
                  STUCK       —— 基本卡住、重复或停滞

                只输出两行，不要其他内容：
                第一行：STATUS: PROGRESSING|WANDERING|STUCK
                第二行：SUMMARY: 一句话中文说明（它在干嘛、卡在哪、是否该收尾）

                事件时间线：
                %s
                """.formatted(iteration, renderEvents(events));
    }

    /** 将事件序列化为脱敏文本行（截断超长输出）。 */
    private static String renderEvents(List<EventEnvelope> events) {
        var sb = new StringBuilder();
        for (var e : events) {
            var p = e.payload();
            String line = switch (e.type()) {
                case TOOL_PROPOSED -> "准备调用 " + p.get("toolName");
                case TOOL_STARTED -> "开始执行 " + p.get("toolName");
                case TOOL_FINISHED -> "完成 " + p.get("toolName")
                        + (Boolean.TRUE.equals(p.get("isError")) ? " [失败]" : " [成功]")
                        + " 耗时 " + p.get("elapsed") + "s";
                case APPROVAL_WAITING -> "等待审批 " + p.get("toolName");
                case DIRECTIVE_STATUS -> "指令状态 " + p.get("status");
                case TASK_TERMINAL -> "任务终态 " + p.get("status");
            };
            sb.append("- ").append(line).append("\n");
        }
        return sb.toString();
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
