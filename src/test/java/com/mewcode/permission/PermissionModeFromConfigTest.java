// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置里的 {@code permission_mode} 解析。
 *
 * <p>背景：这个字段原先全项目没有任何地方读 —— TUI 与 remote 都硬编码
 * {@code DEFAULT}，于是配置里写 {@code BYPASS} 也不生效，无人值守跑实验时一直弹
 * 审批等人点。解析本身必须能承受配置写错，且**写错时不能变成放行**。
 */
class PermissionModeFromConfigTest {

    @Test
    void parsesKnownModes() {
        assertEquals(PermissionMode.BYPASS, PermissionMode.fromConfig("BYPASS"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("DEFAULT"));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.fromConfig("ACCEPT_EDITS"));
        assertEquals(PermissionMode.PLAN, PermissionMode.fromConfig("PLAN"));
    }

    @Test
    void toleratesCaseAndWhitespace() {
        assertEquals(PermissionMode.BYPASS, PermissionMode.fromConfig("  bypass  "));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.fromConfig("accept_edits"));
    }

    @Test
    void missingOrBlankMeansDefault() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig(null));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig(""));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("   "));
    }

    /**
     * 最关键的一条：认不出来的值必须回落到「逐次询问」，绝不能变成 BYPASS。
     * 配置里一个拼写错误不该让审批闸门静默消失。
     */
    @Test
    void unrecognizedValueFailsSafeToDefaultNotBypass() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("bypas"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("bypass_all"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("是"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromConfig("YOLO"));
    }

    /** 回落值要与「需要询问」的语义一致：写类别必须还是会问。 */
    @Test
    void fallbackStillAsksForWritesAndCommands() {
        var fallback = PermissionMode.fromConfig("nonsense");
        assertEquals(PermissionMode.Decision.ASK, fallback.decide(com.mewcode.tool.ToolCategory.WRITE));
        assertEquals(PermissionMode.Decision.ASK, fallback.decide(com.mewcode.tool.ToolCategory.COMMAND));
        assertEquals(PermissionMode.Decision.ALLOW, fallback.decide(com.mewcode.tool.ToolCategory.READ));
    }
}
