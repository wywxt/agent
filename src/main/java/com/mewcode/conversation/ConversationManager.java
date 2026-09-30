// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.List;

public class ConversationManager {

    private final List<Message> history = new ArrayList<>();

    private boolean ltmInjected = false;

    public void addUserMessage(String content) {
        history.add(new Message("user", content));
    }

    public void addAssistantMessage(String content) {
        history.add(new Message("assistant", content));
    }

    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addToolResultsMessage(List<ToolResultBlock> results) {
        var msg = new Message("user", "");
        msg.setToolResults(results);
        history.add(msg);
    }

    public void injectLongTermMemory(String instructions, String memories) {
        if (ltmInjected) return;
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# mewcodeMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        if (sections.isEmpty()) return;
        sections.add("# currentDate\nToday's date is " + java.time.LocalDate.now() + ".");
        String body = String.join("\n\n", sections);
        String wrapped = "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n" +
            body +
            "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>";
        history.add(0, new Message("user", wrapped));
        ltmInjected = true;
    }

    public void resetLtmInjected() {
        ltmInjected = false;
    }

    public void addSystemReminder(String content) {
        history.add(new Message("user", "<system-reminder>\n" + content + "\n</system-reminder>"));
    }

    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    /**
     * 任务的原始目标：第一条 role="user"、content 非空、且不是 system-reminder 包装块的消息。
     *
     * <p>供进度审查使用 —— 审查员要判断「有没有方向」，而方向是相对目标而言的。
     * 不给它目标，它只能靠工具名的表面条理去猜。</p>
     *
     * <p><b>不能直接取「第一条 user 消息」</b>，本类里就有三个坑：
     * {@link #injectLongTermMemory} 在 index 0 插入的 &lt;system-reminder&gt; 块 role 也是 user；
     * {@link #addSystemReminder} 注入的提醒同样是 user；
     * {@link #addToolResultsMessage} 产生的工具结果消息也是 user 且 content 为空。
     * 三者都会把真正的用户诉求挤到后面。</p>
     *
     * @return 原始目标；都没有则返回 null（此时调用方按「无目标」降级）
     */
    public String firstUserRequest() {
        for (Message m : history) {
            if (!"user".equals(m.getRole())) continue;
            String c = m.getContent();
            if (c == null || c.isBlank()) continue;
            if (c.stripLeading().startsWith("<system-reminder>")) continue;
            return c;
        }
        return null;
    }

    public List<Message> getMessagesMutable() {
        return history;
    }

    public int size() {
        return history.size();
    }

    public void truncateTo(int index) {
        if (index >= 0 && index < history.size()) {
            history.subList(index, history.size()).clear();
        }
    }

}
