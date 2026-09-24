package com.mewcode.control.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条审批请求。status 记录最终结果；expiresAtEpochMs 用于过期失效。
 * 过期或重复响应不能放行另一动作。
 */
public final class ApprovalRequest {

    public enum Status { PENDING, ALLOWED, DENIED, EXPIRED }

    private final String approvalId;
    private final ToolInvocation invocation;
    private final long createdAtEpochMs;
    private final long expiresAtEpochMs;
    private volatile Status status;

    public ApprovalRequest(String approvalId, ToolInvocation invocation, long ttlMs) {
        this.approvalId = approvalId;
        this.invocation = invocation;
        this.createdAtEpochMs = System.currentTimeMillis();
        this.expiresAtEpochMs = createdAtEpochMs + ttlMs;
        this.status = Status.PENDING;
    }

    public String approvalId() { return approvalId; }
    public ToolInvocation invocation() { return invocation; }
    public long createdAtEpochMs() { return createdAtEpochMs; }
    public long expiresAtEpochMs() { return expiresAtEpochMs; }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }

    public boolean isExpired(long nowMs) { return nowMs > expiresAtEpochMs; }

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("approvalId", approvalId);
        m.put("taskId", invocation.taskId());
        m.put("toolCallId", invocation.toolCallId());
        m.put("toolName", invocation.toolName());
        m.put("args", invocation.args() != null ? invocation.args() : Map.of());
        m.put("policyVersion", invocation.policyVersion());
        m.put("status", status.name());
        m.put("createdAt", createdAtEpochMs);
        m.put("expiresAt", expiresAtEpochMs);
        return m;
    }
}
