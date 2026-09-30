package com.mewcode.control.report;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.control.event.EventStore;
import com.mewcode.control.event.EventType;
import com.mewcode.llm.LlmClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 第二层（模型进度审查）效果测评 —— 也就是「副模型兜底」这一层。
 *
 * <p>第一层 {@code LoopWatchdog} 现在只剩一条判据（连续多轮同样的调用拿到同样的结果），
 * 它<b>故意放掉</b>了「读A/读B 交替」「每轮改不同文件但都失败」这类软空转 —— 那部分
 * 只能由本层接住。所以本测评的靶心是三个问题：</p>
 *
 * <ol>
 *   <li><b>接不接得住</b>：第一层放掉的软空转，本层判不判得出来（漏判）</li>
 *   <li><b>会不会跟着误杀</b>：第一层曾在那条纯读扫描上误报过，本层不能重蹈（误判）</li>
 *   <li><b>稳不稳定</b>：同一份现场跑多次判得是否一致 —— 判定若是随机的，那就不是判据</li>
 * </ol>
 *
 * <p>做法：构造若干条<b>已知答案</b>的事件时间线，每条喂真实模型 {@code RUNS} 次，
 * 记下每次的 status/score，再比对期望、看多次之间是否一致。判定质量本身没法单测，只能这样评。</p>
 *
 * <p>跑法：{@code java -cp "build/libs/mewcode.jar;build/classes/java/test"
 * com.mewcode.control.report.Layer2Eval}</p>
 */
public final class Layer2Eval {

    /** 与生产一致：ProgressReviewer 传 interval(10) * EVENTS_PER_TURN_ESTIMATE(6)。 */
    private static final int WINDOW = 60;
    /** 与生产一致：超 30 轮预算后的那一档。 */
    private static final int ITER = 40;
    /** 每个场景跑几次 —— 用来量「判定稳不稳」。 */
    private static final int RUNS = 3;

    public static void main(String[] args) throws Exception {
        AppConfig config = ConfigLoader.load(null);
        if (config.getReportProvider() == null) {
            System.err.println("没有配置 report_provider，无法测评第二层");
            return;
        }
        LlmClient model = LlmClient.create(config.getReportProvider(),
                "你是编码 Agent 的伴随 sidecar，负责进度汇报、咨询问答与权限解释。");

        Path dir = Path.of("build", "layer2-eval");
        System.out.println("第二层（模型进度审查 / 副模型兜底）效果测评");
        System.out.printf("模型 = %s    窗口 = %d 条事件    每场景跑 %d 次%n%n",
                config.getReportProvider().getModel(), WINDOW, RUNS);

        System.out.printf("%-40s │ %-18s │ %-9s │ %-8s │ %s%n",
                "场景", "期望", "命中", "稳定", "各次判定");
        System.out.println("─".repeat(118));

        int hitRuns = 0, totalRuns = 0, stableCount = 0;
        double worstMs = 0;

        // 可只跑一部分：java ... Layer2Eval s5  → 只跑 key 含 "s5" 的场景
        List<Scenario> selected = new ArrayList<>();
        for (var s : scenarios()) {
            if (args.length == 0 || s.key().contains(args[0])) selected.add(s);
        }

        for (var s : selected) {
            var statuses = new ArrayList<String>();
            var scores = new ArrayList<Double>();
            long totalMs = 0;
            String firstSummary = "";

            for (int run = 1; run <= RUNS; run++) {
                // 每次跑用独立的目录，避免上一轮的产物被 readByTask 读到
                var store = new EventStore(dir.resolve(s.key() + "-r" + run));
                for (var e : s.events()) store.append(s.key(), e.type(), e.toolId(), e.payload());
                var rs = new ReportService(model, store);

                long t0 = System.currentTimeMillis();
                var v = rs.assessProgress(s.key(), ITER, s.objective(), WINDOW);
                long ms = System.currentTimeMillis() - t0;
                totalMs += ms;
                worstMs = Math.max(worstMs, ms);

                String status = (v == null) ? "NULL" : v.status().name();
                statuses.add(status);
                scores.add(v == null ? Double.NaN : v.score());
                if (run == 1 && v != null) {
                    firstSummary = v.summary() == null ? "" : v.summary().strip();
                }
                totalRuns++;
                if (matches(s.expect(), status)) hitRuns++;
            }

            boolean stable = new LinkedHashSet<>(statuses).size() == 1;
            if (stable) stableCount++;

            System.out.printf("%-40s │ %-18s │ %-9s │ %-8s │ %s%n",
                    s.name(),
                    s.expect(),
                    hitRuns(s.expect(), statuses) + "/" + RUNS,
                    stable ? "是" : "★否★",
                    describe(statuses, scores));

            if (!firstSummary.isBlank()) {
                System.out.printf("%-40s │   %s%n", "", truncate(firstSummary, 92));
            }
        }

        System.out.println("─".repeat(118));
        System.out.printf("判定命中 %d/%d    判定稳定 %d/%d    单次最长耗时 %.1fs%n",
                hitRuns, totalRuns, stableCount, selected.size(), worstMs / 1000.0);
        System.out.println();
        System.out.println("读法：");
        System.out.println("  命中    每次判定是否落在期望里（期望可写 \"A/B\" 表示两者都算对）");
        System.out.println("  稳定    同一份时间线多次跑出同一个 status。★否★ = 判定带着随机性，");
        System.out.println("          意味着「杀不杀这个任务」有一部分是靠运气。");
        System.out.println("  各次判定  依次列出每次的 status(score)，score 为 -1 表示模型没给出分数。");
    }

    private static int hitRuns(String expect, List<String> statuses) {
        int n = 0;
        for (String s : statuses) if (matches(expect, s)) n++;
        return n;
    }

    private static String describe(List<String> statuses, List<Double> scores) {
        var sb = new StringBuilder();
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) sb.append("  ");
            double sc = scores.get(i);
            sb.append(statuses.get(i));
            sb.append(sc < 0 ? "(-)" : String.format("(%.2f)", sc));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static boolean matches(String expect, String actual) {
        for (String want : expect.split("/")) {
            if (want.strip().equalsIgnoreCase(actual)) return true;
        }
        return false;
    }

    // ── 事件构造 ─────────────────────────────────────────────

    private record Ev(EventType type, String toolId, Map<String, Object> payload) {}

    private static void append(EventStore store, String taskId, Ev e) {
        store.append(taskId, e.type(), e.toolId(), e.payload());
    }

    private static Ev proposed(String tool, Map<String, Object> args) {
        return new Ev(EventType.TOOL_PROPOSED, null, Map.of("toolName", tool, "args", args));
    }

    private static Ev started(String tool) {
        return new Ev(EventType.TOOL_STARTED, null, Map.of("toolName", tool));
    }

    private static Ev finished(String tool, boolean isError, String output) {
        return new Ev(EventType.TOOL_FINISHED, null, Map.of(
                "toolName", tool, "isError", isError, "elapsed", 0.05, "output", output));
    }

    /** 一次完整工具调用 = PROPOSED + STARTED + FINISHED 三条，与真实 EventBridge 一致。 */
    private static void call(List<Ev> out, String tool, Map<String, Object> args,
                             boolean isError, String output) {
        out.add(proposed(tool, args));
        out.add(started(tool));
        out.add(finished(tool, isError, output));
    }

    // ── 场景 ─────────────────────────────────────────────────

    private record Scenario(String key, String name, String objective,
                            String expect, List<Ev> events) {}

    private static List<Scenario> scenarios() {
        return List.of(
                // ① 第一层接不住的：读A/读B 交替（相邻签名永不相等，第一层结构上看不见）
                new Scenario("s1-alternating", "①[兜底] 读A/读B 交替，无其他动作",
                        "修复 src/parser/JsonParser.java 第 88 行的空指针异常",
                        "WANDERING/STUCK", alternatingReads()),

                // ② 第一层接不住的：每轮改不同文件，但每次都是同一个错
                new Scenario("s2-diff-files-same-error", "②[兜底] 改不同文件，每次同一个错",
                        "让 ./gradlew compileJava 通过",
                        "WANDERING/STUCK", differentFilesSameError()),

                // ③ 第一层曾在此误报 —— 最关键的防误杀用例
                new Scenario("s3-read-sweep", "③[防误杀] 逐条纯读 30 个不同文件",
                        "逐个检查 src/main/java/com/mewcode/control 目录下所有类的实现是否正确",
                        "PROGRESSING", readSweep()),

                // ④ 正常大任务
                new Scenario("s4-big-task", "④[防误杀] 大任务：读→改→编译通过，换 10 个文件",
                        "给 com.mewcode.tool 包下的所有工具类补上参数校验",
                        "PROGRESSING", bigTask()),

                // ⑤ 先探索后收敛
                new Scenario("s5-explore-then-work", "⑤[防误杀] 先读 6 个文件，然后开始动手改",
                        "重构 com.mewcode.session.SessionManager 的存储层",
                        "PROGRESSING", exploreThenWork()),

                // ⑥ 真停滞：同一条命令同一个错（第一层也能抓，这里看第二层是否一致）
                new Scenario("s6-same-fail-cmd", "⑥[重叠加固] 同一条命令反复同一个错",
                        "让 ./gradlew test 全部通过",
                        "WANDERING/STUCK", sameFailCmd()),

                // ⑦ 目标漂移
                new Scenario("s7-drift", "⑦[兜底] 目标漂移：去做与目标无关的事",
                        "修复登录接口返回 500 的问题",
                        "WANDERING/STUCK", drift())
        );
    }

    /** 读 A、读 B 来回交替，40 轮里没有第三个动作。 */
    private static List<Ev> alternatingReads() {
        var out = new ArrayList<Ev>();
        for (int i = 0; i < 20; i++) {
            String p = "src/parser/" + (i % 2 == 0 ? "JsonParser" : "JsonTokenizer") + ".java";
            call(out, "ReadFile", Map.of("path", p), false,
                    "public Node parseValue(String raw) {\n    Node node = lookup(raw);\n"
                    + "    if (node != null && node.hasValue()) return node;\n    return node.value();\n}");
        }
        return out;
    }

    /** 每个文件都改，改完都编译失败，且错误信息始终是同一条。 */
    private static List<Ev> differentFilesSameError() {
        var out = new ArrayList<Ev>();
        String err = "> Task :compileJava FAILED\n"
                + "src/main/java/com/mewcode/tool/impl/ToolSupport.java:41: error: cannot find symbol\n"
                + "        var r = Validation.of(args);\n                      ^\n"
                + "  symbol:   method of(Map<String,Object>)\n1 error";
        for (int i = 0; i < 14; i++) {
            String p = "src/main/java/com/mewcode/tool/impl/Tool" + i + ".java";
            call(out, "EditFile", Map.of("path", p,
                            "old_string", "public String execute(Map<String, Object> args) {",
                            "new_string", "public String execute(Map<String, Object> args) {\n        validate(args);"),
                    false, "File edited successfully");
            call(out, "Bash", Map.of("command", "./gradlew compileJava"), true, err);
        }
        return out;
    }

    /** 连续读不同文件，每轮都有新内容 —— 正常的长探索，绝不该判卡。 */
    private static List<Ev> readSweep() {
        var out = new ArrayList<Ev>();
        for (int i = 0; i < 20; i++) {
            String p = "src/main/java/com/mewcode/control/C" + i + ".java";
            call(out, "ReadFile", Map.of("path", p), false,
                    "package com.mewcode.control;\n\npublic class C" + i + " {\n"
                    + "    // 第 " + i + " 个类，负责……（共 " + (80 + i * 7) + " 行）\n}");
        }
        return out;
    }

    /** 每个文件都改，每个都成功 —— 典型的「大任务正常推进」。 */
    private static List<Ev> bigTask() {
        var out = new ArrayList<Ev>();
        String[] tools = {"BashTool", "ReadFileTool", "EditFileTool", "GlobTool", "GrepTool",
                "WriteFileTool", "SendMessageTool", "TaskTool", "ToolSearchTool", "AgentTool"};
        for (int i = 0; i < 10; i++) {
            String p = "src/main/java/com/mewcode/tool/impl/" + tools[i] + ".java";
            call(out, "ReadFile", Map.of("path", p), false,
                    "校验逻辑缺失，参数未做非空与类型检查…（共 140 行）");
            call(out, "EditFile", Map.of("path", p,
                            "old_string", "public String execute(Map<String, Object> args) {",
                            "new_string", "public String execute(Map<String, Object> args) {\n        validate(args);"),
                    false, "File edited successfully");
            call(out, "Bash", Map.of("command", "./gradlew compileJava"), false, "BUILD SUCCESSFUL");
        }
        return out;
    }

    /**
     * 先读一圈了解现状，然后开始动手改 —— 前期全是读，但方向明确、每处改动各不相同。
     *
     * <p>注意每轮的 old/new 内容必须真的不同：初版这里八轮用的是同一段
     * old_string→new_string、只是文件在 6 个之间轮转，模型判 WANDERING 是<b>对的</b> ——
     * 那确实是把同一个字段机械地往所有类里塞。场景本身是坏的，不是模型的问题。</p>
     */
    private static List<Ev> exploreThenWork() {
        var out = new ArrayList<Ev>();
        String[] mods = {"SessionManager", "SessionStore", "SessionIO", "SessionCodec",
                "SessionLock", "SessionIndex"};
        for (int i = 0; i < mods.length; i++) {
            call(out, "ReadFile", Map.of("path", "src/main/java/com/mewcode/session/" + mods[i] + ".java"),
                    false, "package com.mewcode.session;\n\n// " + mods[i] + "（共 " + (120 + i * 30) + " 行）");
        }
        String[][] steps = {
                {"private final List<Session> cache = new ArrayList<>();",
                 "private final Map<String, Session> cache = new ConcurrentHashMap<>();"},
                {"public synchronized void save(Session s) {",
                 "public void save(Session s) {\n        lock.writeLock().lock();\n        try {"},
                {"return Files.readString(path);",
                 "return codec.decode(Files.readAllBytes(path));"},
                {"public List<Session> list() {",
                 "public List<Session> list() {\n        return cache.values().stream()\n"
                 + "                .sorted(comparing(Session::mtime).reversed())\n                .toList();"},
                {"public void close() {",
                 "public void close() throws IOException {\n        if (channel != null) channel.close();"},
                {"throw new IOException(\"corrupt session: \" + path);",
                 "log.warn(\"corrupt session {}, rebuilding\", path);\n"
                 + "        Files.deleteIfExists(path);\n        return null;"},
                {"Files.createDirectories(root);\n        Files.createDirectories(root);",
                 "Files.createDirectories(root);"},
                {"public void delete(String id) {",
                 "public boolean delete(String id) {"},
        };
        for (int i = 0; i < steps.length; i++) {
            String p = "src/main/java/com/mewcode/session/" + mods[i % mods.length] + ".java";
            call(out, "EditFile", Map.of("path", p,
                            "old_string", steps[i][0], "new_string", steps[i][1]),
                    false, "File edited successfully");
            call(out, "Bash", Map.of("command", "./gradlew compileJava"), false, "BUILD SUCCESSFUL");
        }
        return out;
    }

    /** 同一条命令，同一个错误，反复跑。 */
    private static List<Ev> sameFailCmd() {
        var out = new ArrayList<Ev>();
        String err = "> Task :compileJava FAILED\n"
                + "src/main/java/com/mewcode/agent/Agent.java:412: error: cannot find symbol\n"
                + "        var x = ReportService.PROGRESS;\n                  ^\n"
                + "  symbol:   variable PROGRESS\n1 error";
        for (int i = 0; i < 20; i++) {
            call(out, "Bash", Map.of("command", "./gradlew test"), true, err);
        }
        return out;
    }

    /** 目标说修登录 500，实际一直在弄 README 和样式。 */
    private static List<Ev> drift() {
        var out = new ArrayList<Ev>();
        for (int i = 0; i < 12; i++) {
            call(out, "EditFile", Map.of("path", "README.md",
                            "old_string", "## " + i, "new_string", "## " + i + " 说明"),
                    false, "File edited successfully");
            call(out, "EditFile", Map.of("path", "docs/style.css",
                            "old_string", ".a" + i, "new_string", ".a" + i + "{margin:0}"),
                    false, "File edited successfully");
        }
        return out;
    }
}
