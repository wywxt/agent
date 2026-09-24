package com.mewcode.control.event;

import java.util.List;
import java.util.Map;

/**
 * AgentEvent → EventEnvelope 的映射与落盘桥。RemoteServer 的消费循环
 * 在每个事实事件处调用对应 record* 方法，得到信封后广播统一 {@code event} 消息。
 * 本类不依赖 WebSocket（保持与传输协议解耦），广播由 RemoteServer 完成。
 */
public final class EventBridge {

    private final EventStore store;

    public EventBridge(EventStore store) {
        this.store = store;
    }

    public EventEnvelope toolProposed(String taskId, String toolId, String toolName, Map<String, Object> args) {
        return store.append(taskId, EventType.TOOL_PROPOSED, toolId,
                Map.of("toolName", toolName, "args", args != null ? args : Map.of()));
    }

    public EventEnvelope toolStarted(String taskId, String toolId, String toolName) {
        return store.append(taskId, EventType.TOOL_STARTED, toolId, Map.of("toolName", toolName));
    }

    public EventEnvelope toolFinished(String taskId, String toolId, String toolName,
                                      boolean isError, double elapsed, String output) {
        return store.append(taskId, EventType.TOOL_FINISHED, toolId, Map.of(
                "toolName", toolName,
                "isError", isError,
                "elapsed", elapsed,
                "output", output != null ? output : ""));
    }

    public EventEnvelope approvalWaiting(String taskId, String approvalId, String description) {
        return store.append(taskId, EventType.APPROVAL_WAITING, null,
                Map.of("approvalId", approvalId, "description", description));
    }

    public EventEnvelope directiveStatus(String taskId, String directiveId, String status) {
        return store.append(taskId, EventType.DIRECTIVE_STATUS, null,
                Map.of("directiveId", directiveId, "status", status));
    }

    public EventEnvelope taskTerminal(String taskId, String status, String message, int totalTurns) {
        return store.append(taskId, EventType.TASK_TERMINAL, null, Map.of(
                "status", status,
                "message", message != null ? message : "",
                "totalTurns", totalTurns));
    }

    public long lastSeq() { return store.lastSeq(); }
    public List<EventEnvelope> readSince(long afterSeq) { return store.readSince(afterSeq); }
    public List<EventEnvelope> readByTask(String taskId) { return store.readByTask(taskId); }
}
