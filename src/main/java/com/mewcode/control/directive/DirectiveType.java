package com.mewcode.control.directive;

/** 运行中用户追加要求的类型。 */
public enum DirectiveType {
    APPEND,      // 追加：继续做，顺便再做某事
    CONSTRAINT,  // 约束：必须遵守的新限制
    REPLAN       // 重新规划：方向性变化
}
