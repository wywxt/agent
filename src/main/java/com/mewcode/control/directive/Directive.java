package com.mewcode.control.directive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条运行中指令。originalText 为用户原文，不可被替换；
 * modelSummary 为小模型整理结果（阶段 5 接入），agentResponse 为
 * 主 Agent 对指令的 ADOPT/DEFER/CONFLICT 回应。
 */
public record Directive(
        String id,
        String taskId,
        String originalText,
        DirectiveType type,
        DirectiveStatus status,
        long createdAtEpochMs,
        String modelSummary,
        String agentResponse) {

    public Directive withStatus(DirectiveStatus s) {
        return new Directive(id, taskId, originalText, type, s, createdAtEpochMs, modelSummary, agentResponse);
    }

    public Directive withAgentResponse(String resp) {
        return new Directive(id, taskId, originalText, type, DirectiveStatus.ACKNOWLEDGED,
                createdAtEpochMs, modelSummary, resp);
    }

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("taskId", taskId);
        m.put("originalText", originalText);
        m.put("type", type.name());
        m.put("status", status.name());
        m.put("createdAt", createdAtEpochMs);
        if (modelSummary != null) m.put("modelSummary", modelSummary);
        if (agentResponse != null) m.put("agentResponse", agentResponse);
        return m;
    }
}
