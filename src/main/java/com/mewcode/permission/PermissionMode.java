// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.permission;

import com.mewcode.tool.ToolCategory;

public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    /**
     * 解析配置里的 {@code permission_mode} 字符串。
     *
     * <p>这个字段以前全项目没有任何地方读：TUI 与 remote 都硬编码 {@code DEFAULT}，
     * 于是配置里写 {@code BYPASS} 也不生效 —— 无人值守跑实验时会一直弹审批等人点，
     * 而脚本化的运行恰恰没人点。
     *
     * <p>无法识别时回落到 {@link #DEFAULT}（逐次询问）而不是 {@code BYPASS}：
     * 配置写错时宁可多问几句，也不能静默放行。
     */
    public static PermissionMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    public enum Decision {
        ALLOW, DENY, ASK
    }
}

