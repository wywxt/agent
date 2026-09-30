// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import com.mewcode.config.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成员身份与消息投递的回归测试。
 *
 * <p>背景：SendMessageTool 原有一个「tmux 兜底」分支 —— 收件人不在成员表里时也照样
 * 写邮箱文件并回报成功。IN_PROCESS 模式下成员名本来是已知的，于是对不存在收件人的
 * 消息被静默写进「幽灵收件箱」：文件建出来了，但没有任何成员会去读它。实测中两次
 * 送达的 API 约定就是这么丢的。
 */
class TeamMemberIdentityTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void redirectUserDir() {
        // 团队状态落在 user.dir/.mewcode/teams/，指到临时目录避免污染仓库
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void restoreUserDir() {
        if (originalUserDir != null) System.setProperty("user.dir", originalUserDir);
    }

    private Path inboxDir(String teamName) {
        return tempDir.resolve(".mewcode/teams").resolve(teamName).resolve("inboxes");
    }

    private static void addMember(TeamManager.Team team, String name, String role) {
        team.addMember(name, role, null, null, null, new ProviderConfig());
    }

    private TeamManager.Team newTeamWithBackend(TeamManager tm) {
        var team = tm.createTeam("todo-app", TeamManager.TeamMode.IN_PROCESS);
        addMember(team, "backend", "负责后端 API 服务");
        addMember(team, "frontend", "负责前端网页");
        return team;
    }

    @Test
    void rosterShowsNameAndRole() {
        var tm = new TeamManager();
        var team = newTeamWithBackend(tm);

        List<String> roster = team.memberRoster(null);
        assertTrue(roster.contains("backend —— 负责后端 API 服务"), "roster=" + roster);
        assertTrue(roster.contains("frontend —— 负责前端网页"), "roster=" + roster);
    }

    @Test
    void rosterExcludesSelf() {
        var tm = new TeamManager();
        var team = newTeamWithBackend(tm);

        List<String> roster = team.memberRoster("backend");
        assertEquals(1, roster.size(), "roster=" + roster);
        assertTrue(roster.get(0).startsWith("frontend"), "roster=" + roster);
    }

    @Test
    void sendMessageToUnknownRecipientFailsAndWritesNothing() throws IOException {
        var tm = new TeamManager();
        var team = newTeamWithBackend(tm);

        var tool = new TeamTools.SendMessageTool(tm, "backend");
        var result = tool.execute(Map.of("to", "frontend-team", "content", "接口约定如下…"));

        assertTrue(result.isError(), "Unknown recipient must not be reported as success");
        // 报错要带上可用收件人，否则 agent 只会继续猜名字
        assertTrue(result.output().contains("backend"), result.output());
        assertTrue(result.output().contains("lead"), result.output());

        // 关键断言：不得留下幽灵收件箱
        Path dir = inboxDir("todo-app");
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                assertEquals(0, files.count(),
                        "A message to an unknown recipient must not create an inbox file");
            }
        }
    }

    @Test
    void sendMessageToKnownRecipientReachesTheirInbox() throws IOException {
        var tm = new TeamManager();
        newTeamWithBackend(tm);

        var tool = new TeamTools.SendMessageTool(tm, "backend");
        var result = tool.execute(Map.of("to", "frontend", "content", "接口约定如下…"));

        assertFalse(result.isError(), result.output());

        // 消息必须落在收件人真名的邮箱里，并且能被它读到
        Path inbox = inboxDir("todo-app").resolve("frontend.json");
        assertTrue(Files.exists(inbox), "Inbox of the real member name should exist");

        var team = tm.getTeam("todo-app");
        var unread = team.getMailBox().readUnread("frontend");
        assertEquals(1, unread.size());
        assertEquals("backend", unread.get(0).from());
    }

    @Test
    void sendMessageToLeadStillWorks() {
        var tm = new TeamManager();
        newTeamWithBackend(tm);

        var tool = new TeamTools.SendMessageTool(tm, "backend");
        var result = tool.execute(Map.of("to", "lead", "content", "后端已完成"));

        assertFalse(result.isError(), result.output());
        assertEquals(1, tm.getTeam("todo-app").getMailBox().readUnread("lead").size());
    }

    @Test
    void addendumStatesExactNamesAndTurnBoundary() {
        var addendum = TeammateRunner.buildTeammateAddendum(
                "todo-app", "backend", List.of("frontend —— 负责前端网页"));

        assertTrue(addendum.contains("frontend"), addendum);
        assertTrue(addendum.contains("负责前端网页"), "职责必须在名册里出现");
        // 以前这里写的是「消息在每个 turn 开始时到达」，对队友是假的
        assertTrue(addendum.contains("ENDS"), "必须说清消息只在整轮结束后送达: " + addendum);
        assertFalse(addendum.contains("arrive as system reminders at the start of each turn"),
                "不能保留那句会让队友在轮内空等的假说法");
    }
}
