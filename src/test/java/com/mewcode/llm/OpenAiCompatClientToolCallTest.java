// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：assistant 的 tool_calls 中，凡是没有对应 tool 结果的调用必须被剔除，
 * 绝不构造「有 tool_calls 却缺 tool 消息」的非法数组。
 */
class OpenAiCompatClientToolCallTest {

    private static OpenAiCompatClient newClient() {
        var cfg = new ProviderConfig();
        cfg.setName("test");
        cfg.setProtocol("openai-compat");
        cfg.setBaseUrl("https://example.com");
        cfg.setModel("test-model");
        cfg.setApiKey("dummy-key");
        return new OpenAiCompatClient(cfg, "sys");
    }

    @Test
    void danglingToolUseIsDroppedFromToolCalls() throws Exception {
        var conv = new ConversationManager();
        conv.addUserMessage("read two files");

        // assistant 发出 2 个 tool_use
        var assistant = new Message("assistant", null);
        assistant.setToolUses(List.of(
                new ToolUseBlock("call_1", "ReadFile", Map.of("file_path", "a.txt")),
                new ToolUseBlock("call_2", "ReadFile", Map.of("file_path", "b.txt"))
        ));
        conv.getMessagesMutable().add(assistant);

        // 只有 call_1 有结果，call_2 缺失（悬空）
        conv.addToolResultsMessage(List.of(new ToolResultBlock("call_1", "content-a", false)));

        var arr = buildChatMessages(newClient(), conv.getMessages());

        // 找到 assistant 节点并检查 tool_calls
        var assistantNode = arr.get(0);
        for (var node : arr) {
            if ("assistant".equals(node.get("role").asText())) {
                assistantNode = node;
            }
        }
        assertEquals("assistant", assistantNode.get("role").asText());

        var toolCalls = assistantNode.get("tool_calls");
        assertNotNull(toolCalls, "assistant 应携带 tool_calls");
        assertEquals(1, toolCalls.size(), "悬空的 call_2 必须被剔除，只保留有结果的 call_1");
        assertEquals("call_1", toolCalls.get(0).get("id").asText());

        // 且必须存在对应 call_1 的 tool 消息
        boolean foundToolMsg = false;
        for (var node : arr) {
            if ("tool".equals(node.get("role").asText())
                    && "call_1".equals(node.get("tool_call_id").asText())) {
                foundToolMsg = true;
            }
        }
        assertTrue(foundToolMsg, "call_1 必须有对应的 tool 消息");
    }

    @SuppressWarnings("unchecked")
    private static ArrayNode buildChatMessages(OpenAiCompatClient client, List<Message> messages) throws Exception {
        var method = OpenAiCompatClient.class.getDeclaredMethod("buildChatMessages", List.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, messages);
    }
}
