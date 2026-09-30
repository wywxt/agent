package com.mewcode.control.report;

import com.mewcode.control.event.EventEnvelope;
import com.mewcode.control.event.EventStore;
import com.mewcode.control.event.EventType;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    /** 返回固定报告文本的 stub 模型，用于区分「走到了生成」与「提前跳过」。 */
    private static final class StubReportClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                 List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta("report body"));
            q.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    @Test
    void generateFinalSkipsPureChat(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        // 纯闲聊：只有 TASK_TERMINAL，没有任何工具执行
        store.append("task-1", EventType.TASK_TERMINAL, null,
                Map.of("status", "completed", "message", "", "totalTurns", 1));

        ReportService svc = new ReportService(new StubReportClient(), store);
        assertNull(svc.generateFinal("task-1"), "纯闲聊（无工具执行）不应生成报告");
    }

    @Test
    void generateFinalRunsAfterMutatingTool(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        store.append("task-2", EventType.TOOL_STARTED, "t1", Map.of("toolName", "WriteFile"));
        store.append("task-2", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "WriteFile", "isError", false, "elapsed", 0.1, "output", "x"));
        store.append("task-2", EventType.TASK_TERMINAL, null,
                Map.of("status", "completed", "message", "", "totalTurns", 2));

        ReportService svc = new ReportService(new StubReportClient(), store);
        assertNotNull(svc.generateFinal("task-2"), "有写/命令工具执行时应生成报告");
    }

    @Test
    void generateFinalSkipsReadOnlyChat(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        // 纯读：只有 ReadFile，无任何写/命令工具
        store.append("task-3", EventType.TOOL_STARTED, "t1", Map.of("toolName", "ReadFile"));
        store.append("task-3", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "ReadFile", "isError", false, "elapsed", 0.1, "output", "x"));
        store.append("task-3", EventType.TASK_TERMINAL, null,
                Map.of("status", "completed", "message", "", "totalTurns", 2));

        ReportService svc = new ReportService(new StubReportClient(), store);
        assertNull(svc.generateFinal("task-3"), "纯读不应生成最终报告");
    }

    @Test
    void generateFinalSkipsReadOnlyBash(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        // 闲聊里跑 ls / git status 这类只读 Bash
        store.append("task-4", EventType.TOOL_PROPOSED, "t1",
                Map.of("toolName", "Bash", "args", Map.of("command", "ls")));
        store.append("task-4", EventType.TOOL_STARTED, "t1", Map.of("toolName", "Bash"));
        store.append("task-4", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "Bash", "isError", false, "elapsed", 0.1, "output", "x"));
        store.append("task-4", EventType.TASK_TERMINAL, null,
                Map.of("status", "completed", "message", "", "totalTurns", 2));

        ReportService svc = new ReportService(new StubReportClient(), store);
        assertNull(svc.generateFinal("task-4"), "只读 Bash（ls）不应生成最终报告");
    }

    @Test
    void generateFinalRunsAfterMutatingBash(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        store.append("task-5", EventType.TOOL_PROPOSED, "t1",
                Map.of("toolName", "Bash", "args", Map.of("command", "npm test")));
        store.append("task-5", EventType.TOOL_STARTED, "t1", Map.of("toolName", "Bash"));
        store.append("task-5", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "Bash", "isError", false, "elapsed", 0.1, "output", "x"));
        store.append("task-5", EventType.TASK_TERMINAL, null,
                Map.of("status", "completed", "message", "", "totalTurns", 2));

        ReportService svc = new ReportService(new StubReportClient(), store);
        assertNotNull(svc.generateFinal("task-5"), "写/构建类 Bash（npm test）应生成报告");
    }

    // ── 状态解析：不能被自然语言的补充说明带偏 ──────────────────────────

    @Test
    void parseStatusIgnoresNegatedStatusInProse() {
        // 模型明确说了「并非 STUCK」，旧的 contains("STUCK") 会把它读成 STUCK，
        // 而 STUCK 经 ProgressReviewer 连续两次就直接 CANCEL —— 说没卡却被判卡。
        assertNull(ReportService.parseStatus("并非 STUCK，仍在进展"),
                "负向表述不得被读成 STUCK");
        assertNull(ReportService.parseStatus("NOT WANDERING"),
                "NOT WANDERING 不得被读成 WANDERING");
    }

    @Test
    void parseStatusAcceptsFirstTokenWithTrailingNote() {
        assertSame(ReportService.ProgressStatus.STUCK,
                ReportService.parseStatus("STUCK"));
        assertSame(ReportService.ProgressStatus.STUCK,
                ReportService.parseStatus("STUCK（基本卡住）"));
        assertSame(ReportService.ProgressStatus.PROGRESSING,
                ReportService.parseStatus("progressing - 正在推进"));
        assertSame(ReportService.ProgressStatus.WANDERING,
                ReportService.parseStatus("  WANDERING  "));
    }

    @Test
    void parseStatusReturnsNullWhenUnrecognizable() {
        // 认不出来必须返回 null（→ Verdict.NONE，不介入）。
        // 解析失败只能降级为「不介入」，绝不能降级为「介入」。
        assertNull(ReportService.parseStatus(""));
        assertNull(ReportService.parseStatus("我无法判断"));
    }

    @Test
    void parseProgressVerdictDropsNegatedStuck() {
        assertNull(ReportService.parseProgressVerdict(
                "STATUS: 并非 STUCK，仍在进展\nSUMMARY: 正在推进"));
    }

    // ── 时间线渲染：带上 output / args，供进度审查员判断方向 ──────────

    /** 直接构造事件信封，不必经 EventStore 落盘。 */
    private static EventEnvelope env(EventType type, Map<String, Object> payload) {
        return new EventEnvelope(1, "task-1", type, null, 0, payload);
    }

    @Test
    void renderEventsKeepsArgsAndOutput() {
        String text = ReportService.renderEvents(List.of(
                env(EventType.TOOL_PROPOSED,
                        Map.of("toolName", "Bash", "args", Map.of("command", "npm test"))),
                env(EventType.TOOL_FINISHED, Map.of("toolName", "Bash", "isError", true,
                        "elapsed", 1.5, "output", "3 tests failed"))));

        assertTrue(text.contains("npm test"),
                "参数要进时间线：只给工具名，审查员看不出它要干什么");
        assertTrue(text.contains("3 tests failed"),
                "输出要进时间线：只给成败，审查员看不出卡在哪");
    }

    @Test
    void renderEventsLabelsApprovalByDescription() {
        // payload 的 key 是 description，不是 toolName —— 读错会渲染出「等待审批 null」
        String text = ReportService.renderEvents(List.of(
                env(EventType.APPROVAL_WAITING,
                        Map.of("approvalId", "a1", "description", "删除 build 目录"))));

        assertTrue(text.contains("删除 build 目录"));
        assertFalse(text.contains("null"), "审批行不得渲染出 null");
    }

    @Test
    void renderEventsTruncatesLongOutput() {
        String text = ReportService.renderEvents(List.of(
                env(EventType.TOOL_FINISHED, Map.of("toolName", "Bash", "isError", false,
                        "elapsed", 0.1, "output", "x".repeat(1000)))));

        assertTrue(text.contains("..."), "超长输出必须截断，否则放大窗口会撑爆 prompt");
        assertTrue(text.length() < 1000, "截断后总长应远小于原始输出");
    }

    // ── 进度审查 prompt：必须带上目标，且不得诱导 ──────────────────────

    /** 记录收到的 prompt，用来断言「审查员到底看到了什么」。 */
    private static final class CapturingReportClient implements LlmClient {
        final List<String> prompts = new ArrayList<>();
        private final String reply;

        CapturingReportClient(String reply) { this.reply = reply; }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                List<Map<String, Object>> tools) {
            for (var m : conv.getMessages()) {
                if ("user".equals(m.getRole()) && m.getContent() != null) {
                    prompts.add(m.getContent());
                }
            }
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta(reply));
            q.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    @Test
    void assessPromptCarriesObjective(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        store.append("t", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "Bash", "isError", false, "elapsed", 0.1, "output", "ok"));

        var client = new CapturingReportClient("STATUS: PROGRESSING\nSUMMARY: 在推进");
        var svc = new ReportService(client, store);
        svc.assessProgress("t", 31, "把 run() 的返回值改成句柄", 60);

        String prompt = String.join("\n", client.prompts);
        assertTrue(prompt.contains("<objective>"),
                "审查员必须拿到目标：判断「有没有方向」是相对目标而言的");
        assertTrue(prompt.contains("把 run() 的返回值改成句柄"));
        assertFalse(prompt.contains("远超常规任务规模"),
                "不得再用结论性措辞诱导审查员往坏处判");
    }

    @Test
    void assessPromptDegradesWhenObjectiveMissing(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        store.append("t", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "Bash", "isError", false, "elapsed", 0.1, "output", "ok"));

        var client = new CapturingReportClient("STATUS: PROGRESSING\nSUMMARY: 在推进");
        var svc = new ReportService(client, store);
        svc.assessProgress("t", 31, null, 60);

        String prompt = String.join("\n", client.prompts);
        assertTrue(prompt.contains("<objective>"), "无目标时结构仍在，只是内容降级");
        assertTrue(prompt.contains("未提供"));
    }

    @Test
    void assessProgressParsesReplyEndToEnd(@TempDir Path tmp) {
        EventStore store = new EventStore(tmp.resolve("events"));
        store.append("t", EventType.TOOL_FINISHED, "t1",
                Map.of("toolName", "Bash", "isError", false, "elapsed", 0.1, "output", "ok"));

        var svc = new ReportService(
                new CapturingReportClient("STATUS: WANDERING\nSUMMARY: 在绕圈"), store);
        var verdict = svc.assessProgress("t", 31, "修复登录超时", 60);

        assertNotNull(verdict);
        assertSame(ReportService.ProgressStatus.WANDERING, verdict.status());
        assertEquals("在绕圈", verdict.summary());
    }

    // ── 分数与缺口：让结论能形成趋势和下一轮指令 ──────────────────────

    @Test
    void parsesScoreAndMissing() {
        var v = ReportService.parseProgressVerdict("""
                STATUS: WANDERING
                SCORE: 0.7
                MISSING: 还没跑测试；README 未更新
                SUMMARY: 在绕圈""");

        assertNotNull(v);
        assertEquals(0.7, v.score(), 1e-9);
        assertEquals("还没跑测试；README 未更新", v.missing());
    }

    @Test
    void scoreToleratesProseAndPercent() {
        assertEquals(0.7, ReportService.parseProgressVerdict(
                "STATUS: STUCK\nSCORE: 0.7（约七成）").score(), 1e-9,
                "模型附带的说明不应影响取数");
        assertEquals(-1, ReportService.parseProgressVerdict(
                "STATUS: STUCK\nSCORE: 70%").score(), 1e-9,
                "超出 0~1 的值一律记为未知，不猜");
    }

    @Test
    void scoreDefaultsToUnknownWhenAbsent() {
        // 旧格式（只有 STATUS+SUMMARY）必须仍能解析 —— 模型漏输出 SCORE 不能拖垮整条判定
        var v = ReportService.parseProgressVerdict("STATUS: PROGRESSING\nSUMMARY: 在推进");

        assertNotNull(v, "缺 SCORE/MISSING 不应导致解析失败");
        assertEquals(ReportService.ProgressVerdict.SCORE_UNKNOWN, v.score(), 1e-9);
        assertEquals("", v.missing());
    }

    @Test
    void threeArgVerdictConstructionStaysUnknown() {
        // 兼容构造器：老调用方拿不到分数时记为未知，而不是 0（0 会被趋势逻辑当成"没推进"）
        var v = new ReportService.ProgressVerdict(ReportService.ProgressStatus.STUCK, "x");

        assertEquals(ReportService.ProgressVerdict.SCORE_UNKNOWN, v.score(), 1e-9);
        assertEquals("", v.missing());
    }

    @Test
    void renderEventsElidesMiddleWhenOverBudget() {
        // 最终报告 / 问答会喂整个任务事件流，而带上 output 后单行涨到约 330 字符：
        // 上千条事件就会撑爆 prompt，所以必须有总预算，且超预算时保留首尾、省略中段。
        var events = new ArrayList<EventEnvelope>();
        for (int i = 0; i < 500; i++) {
            events.add(env(EventType.TOOL_FINISHED, Map.of(
                    "toolName", "Bash", "isError", false, "elapsed", 0.1,
                    "output", "first-line-" + i + "-" + "x".repeat(400))));
        }

        String text = ReportService.renderEvents(events);

        assertTrue(text.contains("first-line-0-"), "首段（任务起头）要保留");
        assertTrue(text.contains("first-line-499-"), "尾段（结论、最后一次测试）要保留");
        assertFalse(text.contains("first-line-250-"), "中段应被省略");
        assertTrue(text.length() < 61_000, "总长必须被预算封顶");
    }
}
