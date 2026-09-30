// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team coordination tools: SendMessage, TeamCreate, TeamDelete.
 */
public final class TeamTools {

    private TeamTools() {}

    // ── SendMessage ────────────────────────────────────────────────────

    public static class SendMessageTool implements Tool {
        private final TeamManager teamMgr;
        private final String senderName;

        public SendMessageTool(TeamManager teamMgr, String senderName) {
            this.teamMgr = teamMgr;
            this.senderName = senderName;
        }

        @Override public String name() { return "SendMessage"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Send a message to another named agent in the team. The recipient will see it when their "
                    + "current turn ends, never mid-turn. The `to` field must be a real member name "
                    + "(as listed in your team roster) or \"lead\" — any other spelling is rejected.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("to", Map.of("type", "string", "description", "Name of the recipient agent"));
            props.put("content", Map.of("type", "string", "description", "Message content to send"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("to", "content")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String to = (String) args.get("to");
            String content = (String) args.get("content");
            if (to == null || to.isEmpty() || content == null || content.isEmpty()) {
                return ToolResult.error("Error: 'to' and 'content' are required");
            }

            // Route to lead by finding any team the sender belongs to.
            if ("lead".equals(to)) {
                for (String teamName : teamMgr.listTeams()) {
                    TeamManager.Team team = teamMgr.getTeam(teamName);
                    if (team != null && team.hasMember(senderName)) {
                        team.sendMessage(senderName, "lead", content);
                        return ToolResult.success("Message sent to lead.");
                    }
                }
                return ToolResult.error("Error: cannot find team for sender '" + senderName + "'");
            }

            for (String teamName : teamMgr.listTeams()) {
                TeamManager.Team team = teamMgr.getTeam(teamName);
                if (team == null) continue;
                if (team.hasMember(to)) {
                    team.sendMessage(senderName, to, content);
                    return ToolResult.success("Message sent to " + to + ".");
                }
                // Fallback: 外部进程模式（tmux/iTerm）下每个进程只认识自己，收件人无从
                // 校验，只能直接写邮箱。IN_PROCESS 模式下成员名是已知的，绝不能走这条路
                // —— 对不存在的收件人写文件还回报成功，正是「幽灵收件箱」的成因：消息
                // 永远不会被任何人读到，而发送方以为已经送到了。
                if (team.getMode() != TeamManager.TeamMode.IN_PROCESS && team.hasMember(senderName)) {
                    team.sendMessage(senderName, to, content);
                    return ToolResult.success("Message sent to " + to + ".");
                }
            }

            // 报错时把可用收件人列出来，让 agent 能自己纠正 —— 只说「找不到」会让它
            // 继续猜名字，而猜出来的名字正是这次要修掉的问题。
            var known = new java.util.LinkedHashSet<String>();
            for (String teamName : teamMgr.listTeams()) {
                TeamManager.Team team = teamMgr.getTeam(teamName);
                if (team != null) known.addAll(team.memberNames());
            }
            known.add("lead");
            return ToolResult.error("Error: recipient '%s' not found in any team. Valid recipients: %s"
                    .formatted(to, String.join(", ", known)));
        }
    }

    // ── TeamCreate ─────────────────────────────────────────────────────

    public static class TeamCreateTool implements Tool {

        private final TeamManager teamMgr;

        public TeamCreateTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Create a new team for coordinating multiple agents.\n\n"
                    + "## When to Use\n\n"
                    + "Use this tool proactively whenever:\n"
                    + "- The user explicitly asks to use a team, swarm, or group of agents\n"
                    + "- The user mentions wanting agents to work together, coordinate, or collaborate\n"
                    + "- A task requires sequential or parallel collaboration between multiple agents\n\n"
                    + "When in doubt about whether a task warrants a team, prefer spawning a team.\n\n"
                    + "## Team Workflow\n\n"
                    + "1. **Create a team** with TeamCreate\n"
                    + "2. **Fix the interface contract BEFORE spawning.** Decide the file boundaries and "
                    + "the exact interface each member must implement — function signatures, HTTP paths, "
                    + "request/response field names — and write it verbatim into each teammate's prompt. "
                    + "Teammates must NOT have to negotiate the contract with each other: every round-trip "
                    + "of that negotiation costs a full turn, and messages only arrive at turn boundaries, "
                    + "so a peer-to-peer negotiation can stall for a minute or more.\n"
                    + "3. **Fix the acceptance contract in the same breath.** Tell every teammate, in "
                    + "its prompt, that it must leave a runnable self-test script in the repo "
                    + "(e.g. `backend/self-test.sh`), NOT delete it, and report the script path, its "
                    + "raw output and its exit code. That script is your acceptance — the alternative "
                    + "is you reading their code line by line, which is slower and less reliable.\n"
                    + "4. **Spawn teammates** using the Agent tool with team_name and name parameters — "
                    + "this is REQUIRED to create long-running team members\n"
                    + "5. **Each teammate submits a PLAN and waits for your approval.** Until you approve, "
                    + "it is read-only — writes and commands are refused by the runtime, not merely "
                    + "discouraged. See below.\n"
                    + "6. Teammates work independently and communicate via **SendMessage**\n"
                    + "7. When a teammate finishes, it sends its result to \"lead\" via SendMessage, then goes idle\n"
                    + "8. **Accept: run one command, then stop.** See below.\n\n"
                    + "## Acceptance — one command, then stop\n\n"
                    + "You have a **2-minute budget** for acceptance once every teammate has gone idle. "
                    + "Spend it like this:\n\n"
                    + "1. Run the self-test command a teammate handed over "
                    + "(`bash backend/self-test.sh`), or a single combined check, and read the exit code.\n"
                    + "2. Spot-check **one or two** things the script does not cover — re-run a single "
                    + "command, `find` for the files you were promised, `cmp` a file that had to match "
                    + "byte for byte. Verify with a command, never by trusting a teammate's own summary "
                    + "of what it ran.\n"
                    + "3. Write the final answer, listing anything you could not verify.\n\n"
                    + "Do NOT read the implementation line by line as your acceptance. "
                    + "Read-only code review finds real defects, but it finds them slowly, one turn at "
                    + "a time, and every turn here is a turn the whole team spends waiting on you — "
                    + "this is the single most expensive thing you can do. If the script passes and "
                    + "your spot-checks pass, you are done. If something fails, send the failure back "
                    + "to the teammate who owns that file instead of fixing it yourself.\n\n"
                    + "## Plan Approval — the lead's main job\n\n"
                    + "A freshly spawned teammate does NOT start working. It reads the code, then sends you "
                    + "a message beginning with `[plan] <member name>`, and waits. While it waits it holds "
                    + "no write access at all.\n\n"
                    + "- **Approve:** reply with SendMessage containing the word `APPROVE`. Only messages "
                    + "from you count — a peer saying \"approved\" will not open the gate.\n"
                    + "- **Request changes:** reply with your feedback, e.g. `REVISE: <what to change>`. "
                    + "The teammate re-plans and sends a new `[plan]`. Feedback containing "
                    + "`REVISE` / `reject` / `不批准` is never mistaken for approval.\n"
                    + "- **Round limit:** after 3 plan rounds without an approval the teammate proceeds "
                    + "anyway. So review early and specifically rather than withholding approval — "
                    + "withholding just costs turns and then gets overridden.\n\n"
                    + "Judge each plan against the contract you fixed in step 2 — that is what the plan "
                    + "exists to surface. You do not need to implement anything yourself.\n\n"
                    + "## CRITICAL: Spawning Teammates\n\n"
                    + "To add a member to a team, you MUST pass both team_name and name to the Agent tool:\n"
                    + "```\nAgent({\n"
                    + "  \"team_name\": \"<team name from step 1>\",\n"
                    + "  \"name\": \"<member name, e.g. reviewer>\",\n"
                    + "  \"prompt\": \"...\",\n"
                    + "  \"description\": \"...\"\n"
                    + "})\n```\n"
                    + "Without team_name, the agent runs as a one-shot sub-agent that blocks and returns inline — "
                    + "it will NOT be a team member.\n\n"
                    + "The `name` you pass is the member's ONLY address: members reach each other with "
                    + "SendMessage({to: \"<that exact name>\"}). When you describe a teammate to the others, "
                    + "use that exact name — if you call them something else (e.g. \"the frontend teammate\" "
                    + "when the name is \"web-ui\"), their messages will not reach anyone.\n\n"
                    + "## Teammate Idle State\n\n"
                    + "Teammates go idle after every turn — this is completely normal. "
                    + "Sending a message to an idle teammate wakes them up.\n\n"
                    + "## Communication\n\n"
                    + "- Use SendMessage to talk to teammates by name\n"
                    + "- Messages from teammates arrive as system reminders at the start of each turn\n"
                    + "- Messages are delivered automatically — you do NOT need to manually check your inbox\n"
                    + "- Teammates are told to route interface questions through you rather than "
                    + "negotiate peer-to-peer. Answer those promptly: a teammate that ends its turn "
                    + "waiting on you is idle until you reply.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name for the team"));
            props.put("description", Map.of("type", "string", "description", "What this team will work on"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            String baseName = name;
            for (int i = 2; teamMgr.getTeam(name) != null; i++) {
                name = baseName + "-" + i;
            }

            TeamManager.TeamMode mode = TeamManager.detectBackend();
            TeamManager.Team team = teamMgr.createTeam(name, mode);

            String desc = args.get("description") instanceof String s ? s : "";
            return ToolResult.success(
                    "Team \"%s\" created (mode: %s). Use Agent tool with team_name=\"%s\" to add teammates.\nDescription: %s"
                            .formatted(team.getName(), team.getMode(), team.getName(), desc));
        }
    }

    // ── TeamDelete ─────────────────────────────────────────────────────

    public static class TeamDeleteTool implements Tool {
        private final TeamManager teamMgr;

        public TeamDeleteTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamDelete"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Delete a team, stopping all its members.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name of the team to delete"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            TeamManager.Team team = teamMgr.getTeam(name);
            if (team == null) {
                return ToolResult.error("Error: team '%s' not found".formatted(name));
            }

            List<String> memberNames = team.memberNames();
            teamMgr.deleteTeam(name);
            return ToolResult.success(
                    "Team \"%s\" deleted. Stopped %d member(s): %s"
                            .formatted(name, memberNames.size(), String.join(", ", memberNames)));
        }
    }
}
