package com.mewcode.control.task;

/**
 * 任务终态与运行态。Sidecar 控制模块据此向页面展示真实状态，
 * 取代原来"循环退出=成功"的单一判定。
 */
public enum TaskStatus {
    RUNNING,
    WAITING_APPROVAL,
    PAUSED,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED
}
