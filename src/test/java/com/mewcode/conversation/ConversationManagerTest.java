// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationManagerTest {

    @Test
    void firstUserRequestSkipsLongTermMemoryBlock() {
        var conv = new ConversationManager();
        conv.addUserMessage("把 run() 的返回值改成句柄");
        // LTM 块被插到 index 0，role 也是 user —— 直接取第一条 user 消息会拿到它，而不是用户诉求
        conv.injectLongTermMemory("项目约定：零新增依赖", "记忆内容");

        assertEquals("把 run() 的返回值改成句柄", conv.firstUserRequest());
    }

    @Test
    void firstUserRequestSkipsSystemReminders() {
        var conv = new ConversationManager();
        conv.addSystemReminder("看门狗提醒：你在重复");
        conv.addUserMessage("修复登录超时");

        assertEquals("修复登录超时", conv.firstUserRequest(),
                "system-reminder 也是 role=user，不能当成目标");
    }

    @Test
    void firstUserRequestSkipsEmptyToolResultMessages() {
        var conv = new ConversationManager();
        // 工具结果消息是 role="user" 且 content 为空，同样不能当成目标
        conv.addToolResultsMessage(java.util.List.of());
        conv.addUserMessage("重构 ContextCompactor");

        assertEquals("重构 ContextCompactor", conv.firstUserRequest());
    }

    @Test
    void firstUserRequestReturnsNullWhenOnlyContextMessages() {
        var conv = new ConversationManager();
        conv.injectLongTermMemory("约定", "");
        conv.addSystemReminder("提醒");

        assertNull(conv.firstUserRequest(), "没有真实用户诉求时应返回 null，由调用方降级");
    }
}
