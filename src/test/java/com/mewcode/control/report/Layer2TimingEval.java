package com.mewcode.control.report;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.control.event.EventStore;
import com.mewcode.control.event.EventType;
import com.mewcode.control.watchdog.LoopWatchdog;
import com.mewcode.control.watchdog.ProgressReviewer;
import com.mewcode.llm.LlmClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第二层「兜底来不来得及」的时序测评。
 *
 * <p>{@link Layer2Eval} 测的是「判得准不准、稳不稳」，本测评测的是另一件事：
 * <b>从陷入循环到被终止，兜底要花多少轮</b>。这决定了它能不能真的当兜底用。</p>
 *
 * <p>做法：把一条已知会被判定的停滞时间线灌进 {@link EventStore}，然后用真实
 * {@link ProgressReviewer} 驱动 {@code onTurn(1..N)}，记录第一次 NUDGE 与第一次
 * CANCEL 发生在第几轮。这里测的是完整链路（预算 → 异步触发 → 结果回收 → 升级），
 * 不是单次模型调用。</p>
 *
 * <p>跑法：{@code java -cp "build/libs/mewcode.jar;build/classes/java/test"
 * com.mewcode.control.report.Layer2TimingEval}</p>
 */
public final class Layer2TimingEval {

    private static final int MAX_TURNS = 60;
    /** 每轮之间的等待：让异步审查（虚拟线程 + 网络调用）有机会落地。 */
    private static final long STEP_MS = 300;

    public static void main(String[] args) throws Exception {
        AppConfig config = ConfigLoader.load(null);
        if (config.getReportProvider() == null) {
            System.err.println("没有配置 report_provider，无法测评第二层");
            return;
        }
        LlmClient model = LlmClient.create(config.getReportProvider(),
                "你是编码 Agent 的伴随 sidecar，负责进度汇报、咨询问答与权限解释。");

        String taskId = "timing";
        var store = new EventStore(Path.of("build", "layer2-timing"));
        for (var e : stuckTimeline()) store.append(taskId, e.type(), e.toolId(), e.payload());

        var report = new ReportService(model, store);
        var reviewer = new ProgressReviewer(report, "修复 src/parser/JsonParser.java 第 88 行的空指针异常");

        System.out.println("第二层「兜底来不来得及」时序测评");
        System.out.printf("时间线：读A/读B 交替 20 轮（第一层结构上看不见，只能靠本层兜）%n");
        System.out.printf("预算 %d 轮 / 每 %d 轮审一次 / 连续 %d 次判 WANDERING 才 CANCEL%n%n",
                ProgressReviewer.DEFAULT_BUDGET, ProgressReviewer.DEFAULT_INTERVAL,
                ProgressReviewer.DEFAULT_MAX_WANDER);

        int firstNudge = 0, firstCancel = 0;
        for (int i = 1; i <= MAX_TURNS; i++) {
            var v = reviewer.onTurn(i, taskId);
            if (v.action() != LoopWatchdog.Action.NONE) {
                System.out.printf("  第 %2d 轮  %-8s  %s%n", i, v.action(), firstLine(v.message()));
                if (v.action() == LoopWatchdog.Action.NUDGE && firstNudge == 0) firstNudge = i;
                if (v.action() == LoopWatchdog.Action.CANCEL && firstCancel == 0) firstCancel = i;
            }
            if (firstCancel > 0) break;
            Thread.sleep(STEP_MS);
        }

        System.out.println();
        System.out.println("─".repeat(82));
        System.out.printf("%-14s │ %-14s │ %s%n", "", "第一层（确定性）", "第二层（模型兜底）");
        System.out.printf("%-14s │ %-14s │ %s%n", "首次 NUDGE",
                "第 8 轮", firstNudge == 0 ? "未触发" : "第 " + firstNudge + " 轮");
        System.out.printf("%-14s │ %-14s │ %s%n", "首次 CANCEL",
                "第 20 轮", firstCancel == 0 ? "未触发" : "第 " + firstCancel + " 轮");
        System.out.println("─".repeat(82));
        System.out.println("第一层抓的是「一模一样的重复」，第 8 轮就开口；");
        System.out.println("第二层要等预算跑满、再等两次判定坐实，晚得多 —— 它的价值不在「早」，");
        System.out.println("而在「接住第一层看不见的那类空转」。两者是分工，不是备份。");
    }

    private static String firstLine(String msg) {
        if (msg == null || msg.isBlank()) return "";
        int nl = msg.indexOf('\n');
        String line = nl > 0 ? msg.substring(0, nl) : msg;
        return line.length() > 60 ? line.substring(0, 59) + "…" : line;
    }

    // ── 时间线 ───────────────────────────────────────────────

    private record Ev(EventType type, String toolId, Map<String, Object> payload) {}

    private static void call(List<Ev> out, String tool, Map<String, Object> args,
                             boolean isError, String output) {
        out.add(new Ev(EventType.TOOL_PROPOSED, null, Map.of("toolName", tool, "args", args)));
        out.add(new Ev(EventType.TOOL_STARTED, null, Map.of("toolName", tool)));
        out.add(new Ev(EventType.TOOL_FINISHED, null, Map.of(
                "toolName", tool, "isError", isError, "elapsed", 0.05, "output", output)));
    }

    /** 读 A、读 B 来回交替 —— 相邻签名永不相等，第一层结构上看不见。 */
    private static List<Ev> stuckTimeline() {
        var out = new ArrayList<Ev>();
        for (int i = 0; i < 20; i++) {
            String p = "src/parser/" + (i % 2 == 0 ? "JsonParser" : "JsonTokenizer") + ".java";
            call(out, "ReadFile", Map.of("path", p), false,
                    "public Node parseValue(String raw) {\n    Node node = lookup(raw);\n"
                    + "    if (node != null && node.hasValue()) return node;\n    return node.value();\n}");
        }
        return out;
    }
}
