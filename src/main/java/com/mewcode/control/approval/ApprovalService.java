package com.mewcode.control.approval;

import com.mewcode.control.store.JsonlStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批持久化服务。审批绑定 taskId、工具调用与策略版本；解析时校验
 * 存在性、过期、taskId 匹配与重复响应，杜绝"过期/重复响应放行另一动作"。
 *
 * 注意：本阶段持久化为审计与校验依据；放行决策仍由 StreamingExecutor
 * 依据 PermissionChecker 的判定 + 用户决定执行，sidecar 润色层在阶段 5 接入。
 */
public final class ApprovalService {

    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 分钟

    private final JsonlStore store;
    private final ConcurrentHashMap<String, ApprovalRequest> byId = new ConcurrentHashMap<>();

    public ApprovalService(JsonlStore store) {
        this.store = store;
    }

    public ApprovalRequest create(String approvalId, ToolInvocation inv) {
        return create(approvalId, inv, DEFAULT_TTL_MS);
    }

    public ApprovalRequest create(String approvalId, ToolInvocation inv, long ttlMs) {
        var req = new ApprovalRequest(approvalId, inv, ttlMs);
        byId.put(approvalId, req);
        store.append(req.toMap());
        return req;
    }

    /**
     * 解析用户决定。返回 true 表示 allow；false 表示 deny。
     * 过期 / taskId 不匹配 / 不存在 均返回 false（不放行）。
     */
    public boolean resolve(String approvalId, boolean allow, String taskId) {
        var req = byId.get(approvalId);
        if (req == null) return false;
        if (req.isExpired(System.currentTimeMillis())) {
            req.setStatus(ApprovalRequest.Status.EXPIRED);
            store.append(req.toMap());
            return false;
        }
        if (!req.invocation().taskId().equals(taskId)) return false;
        if (req.status() != ApprovalRequest.Status.PENDING) return false; // 重复响应不放行
        req.setStatus(allow ? ApprovalRequest.Status.ALLOWED : ApprovalRequest.Status.DENIED);
        store.append(req.toMap());
        return allow;
    }

    public ApprovalRequest get(String approvalId) {
        return byId.get(approvalId);
    }
}
