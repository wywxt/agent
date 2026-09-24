package com.mewcode.control.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可序列化的事件信封。seq 全局递增，用于断线重连补拉。
 * 与进程内 {@code AgentEvent} 解耦：这里只承载事实数据，不含 CompletableFuture。
 */
public record EventEnvelope(
        long seq,
        String taskId,
        EventType type,
        String toolCallId,
        long timestampEpochMs,
        Map<String, Object> payload) {

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("seq", seq);
        m.put("taskId", taskId);
        m.put("type", type.name());
        if (toolCallId != null) m.put("toolCallId", toolCallId);
        m.put("ts", timestampEpochMs);
        m.put("payload", payload != null ? payload : Map.of());
        return m;
    }
}
