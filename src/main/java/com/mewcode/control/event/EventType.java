package com.mewcode.control.event;

/**
 * 事实事件类型。区分"模型准备调用"（TOOL_PROPOSED）与"实际执行"
 * （TOOL_STARTED / TOOL_FINISHED），供时间线与报告引用。
 */
public enum EventType {
    TOOL_PROPOSED,      // 模型准备调用（参数生成，未执行）
    APPROVAL_WAITING,   // 进入审批等待
    TOOL_STARTED,       // 实际开始执行
    TOOL_FINISHED,      // 实际结束（结果/耗时/是否错误）
    DIRECTIVE_STATUS,   // 指令状态变化
    TASK_TERMINAL       // 任务终态
}
