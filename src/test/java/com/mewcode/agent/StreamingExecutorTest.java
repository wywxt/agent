// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.agent;

import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：并发批次的工具执行即使抛异常，也必须为每个 tool_call_id 回填一条结果，
 * 否则下一轮发给 OpenAI 兼容 provider 的 messages 会出现「有 tool_calls 却缺
 * tool 消息」的非法数组（400: insufficient tool messages）。
 */
class StreamingExecutorTest {

    /** 一个只读工具，category()==READ 使其进入并发批次。 */
    private static Tool fakeReadTool() {
        return new Tool() {
            @Override public String name() { return "FakeRead"; }
            @Override public String description() { return "fake read tool"; }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public Map<String, Object> schema() {
                return Map.of("name", "FakeRead", "description", "fake read tool", "input_schema", Map.of());
            }
            @Override public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
        };
    }

    @Test
    void concurrentBatchFailureStillReturnsOneResultPerCall() {
        var registry = new ToolRegistry();
        registry.register(fakeReadTool());

        // 让 check() 抛出一个逃出 executeSingle 内部 try/catch 的异常，
        // 复现「工具执行路径未捕获异常 → future.get() 抛异常」的场景。
        PermissionChecker throwingChecker = new PermissionChecker(PermissionMode.BYPASS, Path.of(".")) {
            @Override
            public CheckResult check(Tool tool, Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };

        var executor = new StreamingExecutor(registry, throwingChecker, null,
                new LinkedBlockingQueue<>(), null, null, null);

        var calls = List.of(
                new StreamingExecutor.ToolCallInfo("call_1", "FakeRead", Map.of()),
                new StreamingExecutor.ToolCallInfo("call_2", "FakeRead", Map.of())
        );

        var results = executor.executeAll(calls);

        // 修复后：每个 tool_call_id 都有一条结果，且标记为错误（而非被静默丢弃）
        assertEquals(2, results.size(), "每个 tool_call 都必须有一条结果");
        assertEquals(Set.of("call_1", "call_2"),
                results.stream().map(StreamingExecutor.ToolExecResult::toolId).collect(Collectors.toSet()));
        assertTrue(results.stream().allMatch(StreamingExecutor.ToolExecResult::isError),
                "失败的调用应以 error 结果回填，而不是被丢弃");
    }

    @Test
    void sequentialBatchFailureReturnsErrorResultInsteadOfThrowing() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "FakeWrite"; }
            @Override public String description() { return "fake write tool"; }
            @Override public ToolCategory category() { return ToolCategory.WRITE; }
            @Override public Map<String, Object> schema() {
                return Map.of("name", "FakeWrite", "description", "fake write tool", "input_schema", Map.of());
            }
            @Override public ToolResult execute(Map<String, Object> args) { return ToolResult.success("ok"); }
        });

        PermissionChecker throwingChecker = new PermissionChecker(PermissionMode.BYPASS, Path.of(".")) {
            @Override
            public CheckResult check(Tool tool, Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };

        var executor = new StreamingExecutor(registry, throwingChecker, null,
                new LinkedBlockingQueue<>(), null, null, null);

        // 单个写工具走顺序批次：executeSingle 抛异常时不能向上传播，必须转成 error 结果
        var results = executor.executeAll(
                List.of(new StreamingExecutor.ToolCallInfo("call_1", "FakeWrite", Map.of())));

        assertEquals(1, results.size(), "顺序批次也必须返回一条结果，而非抛出异常");
        assertEquals("call_1", results.get(0).toolId());
        assertTrue(results.get(0).isError());
    }
}
