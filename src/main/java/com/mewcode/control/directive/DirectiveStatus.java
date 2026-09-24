package com.mewcode.control.directive;

/** 指令生命周期：收件 → 送达主 Agent → 主 Agent 确认。 */
public enum DirectiveStatus {
    RECEIVED,
    DELIVERED,
    ACKNOWLEDGED
}
