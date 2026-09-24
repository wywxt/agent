package com.mewcode.control.approval;

import java.util.Map;

/**
 * 不可变工具调用包装。绑定 taskId、策略版本，供审批持久化与失效判断。
 */
public record ToolInvocation(
        String taskId,
        String toolCallId,
        String toolName,
        Map<String, Object> args,
        String workDir,
        int policyVersion) {}
