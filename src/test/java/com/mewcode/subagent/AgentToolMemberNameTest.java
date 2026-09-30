// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.subagent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.teams.TeamManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成员名解析的回归测试。
 *
 * <p>背景：Agent 工具原先没有 name 参数，lead 传的 {@code name} 被静默丢弃，成员名
 * 回落到 description 派生值。lead 随后把「它以为的名字」告诉队友，队友照那个名字
 * SendMessage，消息就写进了没人会读的收件箱 —— 整条协作链因此断掉。
 */
class AgentToolMemberNameTest {

    private TeamManager.Team newTeam() {
        return new TeamManager.Team("t", TeamManager.TeamMode.IN_PROCESS);
    }

    private void addMember(TeamManager.Team team, String name, String role) {
        team.addMember(name, role, null, null, null, new ProviderConfig());
    }

    @Test
    void explicitNameWinsOverDescription() {
        // 这是本次修复的核心：lead 传什么名字，成员就叫什么名字
        assertEquals("backend",
                AgentTool.resolveMemberName("backend", "负责后端 API 服务", newTeam()));
    }

    @Test
    void nameIsSanitizedToFileNameSafe() {
        // 成员名会被用作 <name>.json（FileMailBox.inboxPath 直接拼接）
        String name = AgentTool.resolveMemberName("web/ui v2", "x", newTeam());
        assertFalse(name.contains("/"), "Name must not contain a path separator: " + name);
        assertFalse(name.contains(" "), "Name must not contain whitespace: " + name);
        assertEquals("web-ui-v2", name);
    }

    @Test
    void fallsBackToDescriptionSlugWhenNameMissing() {
        // 无显式 name 时保持旧行为，避免影响既有调用方
        assertEquals("负责后端-api-服务",
                AgentTool.resolveMemberName(null, "负责后端 API 服务", newTeam()));
    }

    @Test
    void blankNameFallsBackToDescription() {
        assertEquals("reviewer",
                AgentTool.resolveMemberName("   ", "reviewer", newTeam()));
    }

    @Test
    void unusableNameAndDescriptionFallBackToMember() {
        assertEquals("member", AgentTool.resolveMemberName("///", "  ", newTeam()));
    }

    @Test
    void nameIsTruncatedToThirtyChars() {
        String name = AgentTool.resolveMemberName("a".repeat(40), "x", newTeam());
        assertEquals(30, name.length());
    }

    @Test
    void collisionGetsNumericSuffix() {
        var team = newTeam();
        addMember(team, "backend", "负责后端 API 服务");

        assertEquals("backend-2", AgentTool.resolveMemberName("backend", "another", team));

        addMember(team, "backend-2", "另一个后端");
        assertEquals("backend-3", AgentTool.resolveMemberName("backend", "third", team));
    }
}
