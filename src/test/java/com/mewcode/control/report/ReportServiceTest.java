package com.mewcode.control.report;

import com.mewcode.control.event.EventStore;
import com.mewcode.control.event.EventType;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
