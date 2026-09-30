// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manages multi-agent teams with mailbox-based communication.
 */
public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX, ITERM }

    private final Map<String, Team> teams = new LinkedHashMap<>();

    /**
     * 队友事件的出口，由外部（{@code RemoteServer}）接线。
     *
     * <p>在此之前队友的事件全被投进 {@code TeammateRunner} 里一个局部创建的队列，
     * 而那个队列**没有任何消费者** —— 写满 32 条后 {@code offer} 静默失败。
     * 于是队友的 token 用量等数据只能靠 {@code progress.jsonl} 和邮箱两条旁路外流，
     * 外部观测不到多 agent 的真实成本。
     *
     * <p>{@code null} 表示不接线（TUI / PrintMode 即如此），此时
     * {@code TeammateRunner} 退回局部队列，行为与改动前一致。
     */
    private volatile BlockingQueue<AgentEvent> teammateEventSink;

    public void setTeammateEventSink(BlockingQueue<AgentEvent> sink) {
        this.teammateEventSink = sink;
    }

    public BlockingQueue<AgentEvent> getTeammateEventSink() {
        return teammateEventSink;
    }

    public synchronized Team createTeam(String name, TeamMode mode) {
        Team team = new Team(name, mode);
        teams.put(name, team);
        return team;
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            team.stopAll();
        }
    }

    public synchronized List<String> listTeams() {
        return new ArrayList<>(teams.keySet());
    }

    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    public synchronized List<TeammateProgress> getAllTeammateProgress() {
        return teams.values().stream()
                .flatMap(t -> t.getTeammateProgressList().stream())
                .toList();
    }

    /**
     * 有没有队友正在跑某一轮。给 lead 的收工判定用（{@code Agent.setPeersBusyFn}）。
     *
     * <p>判据是 {@link TeammateProgress#isBusy()} 而不是 {@code Member.active}：后者
     * 只说明队友线程还没退出，而干完一轮的队友会一直阻塞在等消息上 —— 照它判，lead
     * 会在每个团队任务的尾巴上白等满等待上限。
     */
    public synchronized boolean hasBusyTeammate() {
        for (Team t : teams.values()) {
            if (t.hasBusyTeammate()) return true;
        }
        return false;
    }

    public static TeamMode detectBackend() {
        return TeamMode.IN_PROCESS;
    }

    /**
     * 面板后端自动检测，优先级：tmux 会话内 > iTerm2 会话内 > tmux 可用 > 进程内回退。
     * 与 Go 版 detectPaneBackend() 对齐。
     */
    public static TeamMode detectPaneBackend() {
        // 已在 tmux 会话中：直接用 tmux
        if (System.getenv("TMUX") != null && !System.getenv("TMUX").isEmpty()) {
            return TeamMode.TMUX;
        }
        // 已在 iTerm2 会话中：通过 ITERM_SESSION_ID 环境变量检测
        if (System.getenv("ITERM_SESSION_ID") != null && !System.getenv("ITERM_SESSION_ID").isEmpty()) {
            return TeamMode.ITERM;
        }
        // tmux 已安装但不在会话中：启动新 tmux 窗格
        try {
            Process p = new ProcessBuilder("which", "tmux").start();
            if (p.waitFor() == 0) return TeamMode.TMUX;
        } catch (Exception ignored) {}
        return TeamMode.IN_PROCESS;
    }

    // ── Inner classes ──────────────────────────────────────────────────

    private static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.dir"), ".mewcode", "teams");
    }

    public static class Team {
        final String name;
        final TeamMode mode;
        final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;

        public Team(String name, TeamMode mode) {
            this.name = name;
            this.mode = mode;
            this.mailBox = new FileMailBox(teamsBaseDir().resolve(name).resolve("inboxes"));
        }

        public String getName() { return name; }
        public TeamMode getMode() { return mode; }

        public FileMailBox getMailBox() { return mailBox; }

        public synchronized Member addMember(String name, String role, LlmClient client, ToolRegistry registry,
                                             String protocol, ProviderConfig cfg) {
            Agent ag = new Agent(client, registry, protocol, cfg);
            Member member = new Member(name, role, ag, new ConversationManager());
            members.put(name, member);
            return member;
        }

        public synchronized BlockingQueue<AgentEvent> startMember(String name, String task) {
            Member member = members.get(name);
            if (member == null) return null;
            member.conv.addUserMessage(task);
            // 与 TeammateRunner.runTurn 同理：usage 累计以 run 为界，跨 run 要标边界。
            if (member.progress != null) member.progress.beginRun();
            BlockingQueue<AgentEvent> queue = member.agent.run(member.conv);
            member.active = true;
            return queue;
        }

        public synchronized void stopMember(String name) {
            Member member = members.get(name);
            if (member != null) {
                member.active = false;
                if (member.thread != null) {
                    member.thread.interrupt();
                }
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) m.thread.interrupt();
            }
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return new ArrayList<>(members.keySet());
        }

        /**
         * 成员名册，每行「名字 —— 职责」。名字是 SendMessage 的寻址标识，职责告诉队友
         * 谁负责什么 —— 只给名字会让队友知道有谁、却不知道该找谁办哪件事。
         *
         * @param excludeName 需要排除的成员（通常是接收名册的成员自己），可为 null
         */
        public synchronized List<String> memberRoster(String excludeName) {
            return members.values().stream()
                    .filter(m -> excludeName == null || !excludeName.equals(m.name))
                    .map(m -> (m.role == null || m.role.isBlank())
                            ? m.name : m.name + " —— " + m.role)
                    .toList();
        }

        public void sendMessage(String from, String to, String content) {
            mailBox.send(to, new FileMailBox.MailMessage(from, content));
        }

        public List<TeammateProgress> getTeammateProgressList() {
            return members.values().stream()
                    .filter(m -> m.progress != null)
                    .map(m -> m.progress)
                    .toList();
        }

        /** 队里有没有人正在跑某一轮。外部后端（tmux/iTerm）的成员没有 progress，恒为 false。 */
        public synchronized boolean hasBusyTeammate() {
            for (Member m : members.values()) {
                if (m.progress != null && m.progress.isBusy()) return true;
            }
            return false;
        }
    }

    public static class Member {
        public final String name;

        /** 该成员的职责描述，来自 lead 派活时的 description。用于给队友看名册。 */
        public final String role;

        public final Agent agent;
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
        /** 由队友自己的线程在开跑时挂上；lead 线程会读（收工判定），故 volatile。 */
        public volatile TeammateProgress progress;

        public Member(String name, String role, Agent agent, ConversationManager conv) {
            this.name = name;
            this.role = role;
            this.agent = agent;
            this.conv = conv;
        }

        public String getName() { return name; }
        public String getRole() { return role; }
        public boolean isActive() { return active; }
    }

}
