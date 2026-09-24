// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.session;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void saveAndLoadRoundtripPreservesOrderRoleAndContent(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-120000";

        SessionManager.saveMessage(workDir, sessionId, "user", "hello");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "hi there");
        SessionManager.saveMessage(workDir, sessionId, "user", "how are you?");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "doing well");

        var loaded = SessionManager.loadSession(workDir, sessionId);

        assertEquals(4, loaded.size());

        assertEquals("user", loaded.get(0).role());
        assertEquals("hello", loaded.get(0).content());

        assertEquals("assistant", loaded.get(1).role());
        assertEquals("hi there", loaded.get(1).content());

        assertEquals("user", loaded.get(2).role());
        assertEquals("how are you?", loaded.get(2).content());

        assertEquals("assistant", loaded.get(3).role());
        assertEquals("doing well", loaded.get(3).content());
    }

    @Test
    void rebuildConversationFromPersistedSessionPreservesRoles(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-130000";

        SessionManager.saveMessage(workDir, sessionId, "user", "hello");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "hi there");
        SessionManager.saveMessage(workDir, sessionId, "user", "how are you?");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "doing well");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        ConversationManager conversation = SessionManager.rebuildConversation(loaded);

        List<Message> messages = conversation.getMessages();
        assertEquals(4, messages.size());

        assertEquals("user", messages.get(0).getRole());
        assertEquals("hello", messages.get(0).getContent());

        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("hi there", messages.get(1).getContent());

        assertEquals("user", messages.get(2).getRole());
        assertEquals("how are you?", messages.get(2).getContent());

        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("doing well", messages.get(3).getContent());
    }

    @Test
    void loadSessionReturnsEmptyListForMissingSession(@TempDir Path dir) {
        var loaded = SessionManager.loadSession(dir.toString(), "does-not-exist");
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void newIdIsNonEmpty() {
        String id = SessionManager.newId();
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    // ── compact_boundary round-trip ─────────────────────────────────────

    @Test
    void resumeRebuildsCompactedStateFromBoundary(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-140000";

        // Pre-compaction prefix: these original messages stay in the file but
        // must NOT be replayed once a boundary exists.
        SessionManager.saveMessage(workDir, sessionId, "user", "original prefix question");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "original prefix answer");

        // Compaction writes a boundary inlining the summary + kept verbatim tail.
        var keep = List.of(
                new SessionManager.KeepMessage("user", "kept user turn"),
                new SessionManager.KeepMessage("assistant", "kept assistant turn"));
        SessionManager.saveCompactBoundary(workDir, sessionId, "THE SUMMARY", keep);

        // Continuation after the boundary (chained resume /续写).
        SessionManager.saveMessage(workDir, sessionId, "user", "post-boundary question");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "post-boundary answer");

        var loaded = SessionManager.loadSession(workDir, sessionId);

        // Boundary scan finds the boundary + the plain messages after it.
        var scan = SessionManager.findLastCompactBoundary(loaded);
        assertTrue(scan.found());
        assertEquals("THE SUMMARY", scan.boundary().summary());
        assertEquals(2, scan.boundary().keep().size());
        assertEquals(2, scan.after().size());
        assertEquals("post-boundary question", scan.after().get(0).content());

        // Compaction-aware rebuild = [summary as user] + keep + after.
        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        assertEquals(5, msgs.size());

        // Summary is the leading user message, wrapped in Chinese framing.
        assertEquals("user", msgs.get(0).getRole());
        assertTrue(msgs.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(msgs.get(0).getContent().contains("THE SUMMARY"));
        assertTrue(msgs.get(0).getContent().contains("近期消息已原样保留"),
                "kept tail is non-empty so the framing should include 近期消息已原样保留");

        // Kept verbatim tail (original text preserved).
        assertEquals("user", msgs.get(1).getRole());
        assertEquals("kept user turn", msgs.get(1).getContent());
        assertEquals("assistant", msgs.get(2).getRole());
        assertEquals("kept assistant turn", msgs.get(2).getContent());

        // Messages appended after the boundary.
        assertEquals("user", msgs.get(3).getRole());
        assertEquals("post-boundary question", msgs.get(3).getContent());
        assertEquals("assistant", msgs.get(4).getRole());
        assertEquals("post-boundary answer", msgs.get(4).getContent());

        // The pre-compaction prefix is NOT replayed.
        boolean prefixReplayed = msgs.stream()
                .anyMatch(m -> m.getContent() != null
                        && (m.getContent().contains("original prefix question")
                            || m.getContent().contains("original prefix answer")));
        assertFalse(prefixReplayed, "pre-boundary original prefix must not be replayed");

        // Raw boundary blob is never replayed as a conversation message.
        boolean blobReplayed = msgs.stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("\"keep\""));
        assertFalse(blobReplayed, "raw boundary JSON blob must not appear in the conversation");
    }

    @Test
    void resumeReplaysEverythingWhenNoBoundary(@TempDir Path dir) {
        // Backward compatibility: an old session with no compact_boundary must
        // replay all messages verbatim, unchanged from the legacy behaviour.
        String workDir = dir.toString();
        String sessionId = "20260101-150000";

        SessionManager.saveMessage(workDir, sessionId, "user", "q1");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "a1");
        SessionManager.saveMessage(workDir, sessionId, "user", "q2");
        SessionManager.saveMessage(workDir, sessionId, "assistant", "a2");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        assertFalse(SessionManager.findLastCompactBoundary(loaded).found());

        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        assertEquals("q1", msgs.get(0).getContent());
        assertEquals("a1", msgs.get(1).getContent());
        assertEquals("q2", msgs.get(2).getContent());
        assertEquals("a2", msgs.get(3).getContent());
    }

    @Test
    void onlyLastBoundaryWinsAcrossChainedCompactions(@TempDir Path dir) {
        // Two compactions in one session (chained): resume must rebuild from the
        // LAST boundary only, dropping the first summary + everything before it.
        String workDir = dir.toString();
        String sessionId = "20260101-160000";

        SessionManager.saveMessage(workDir, sessionId, "user", "very old q");
        SessionManager.saveCompactBoundary(workDir, sessionId, "FIRST SUMMARY",
                List.of(new SessionManager.KeepMessage("assistant", "first-kept")));
        SessionManager.saveMessage(workDir, sessionId, "user", "mid q");
        SessionManager.saveCompactBoundary(workDir, sessionId, "SECOND SUMMARY",
                List.of(new SessionManager.KeepMessage("user", "second-kept")));
        SessionManager.saveMessage(workDir, sessionId, "assistant", "newest a");

        var loaded = SessionManager.loadSession(workDir, sessionId);
        var scan = SessionManager.findLastCompactBoundary(loaded);
        assertTrue(scan.found());
        assertEquals("SECOND SUMMARY", scan.boundary().summary());
        assertEquals(1, scan.after().size());
        assertEquals("newest a", scan.after().get(0).content());

        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        // [SECOND SUMMARY with Chinese framing] + [second-kept] + [newest a]
        assertEquals(3, msgs.size());
        assertTrue(msgs.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(msgs.get(0).getContent().contains("SECOND SUMMARY"));
        assertEquals("second-kept", msgs.get(1).getContent());
        assertEquals("newest a", msgs.get(2).getContent());

        boolean firstSummaryReplayed = msgs.stream()
                .anyMatch(m -> "FIRST SUMMARY".equals(m.getContent())
                        || "first-kept".equals(m.getContent())
                        || "very old q".equals(m.getContent())
                        || "mid q".equals(m.getContent()));
        assertFalse(firstSummaryReplayed, "only the last boundary's state must be rebuilt");
    }

    @Test
    void saveCompactBoundaryIsNoOpWhenSessionIdBlank(@TempDir Path dir) {
        // Defensive: sub-agents / one-shot callers pass blank ids and must not
        // create a session file.
        SessionManager.saveCompactBoundary(dir.toString(), "", "x", List.of());
        SessionManager.saveCompactBoundary(dir.toString(), null, "x", List.of());
        assertTrue(SessionManager.loadSession(dir.toString(), "").isEmpty());
    }

    // ── structured tool-block round-trip ───────────────────────────────

    @Test
    void toolChainRoundTripPreservesToolUseIds(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-170000";

        SessionManager.saveMessage(workDir, sessionId, "user", "改一下 foo");
        SessionManager.saveAssistantTurn(workDir, sessionId, "我先读文件",
                List.of(new ThinkingBlock("thinking...", null)),
                List.of(new ToolUseBlock("toolu_1", "ReadFile", Map.<String, Object>of("file_path", "foo"))));
        SessionManager.saveToolResults(workDir, sessionId,
                List.of(new ToolResultBlock("toolu_1", "file content", false)));

        var loaded = SessionManager.loadSession(workDir, sessionId);
        assertEquals(3, loaded.size());

        // tool_use preserved with id + name + args
        assertEquals("assistant", loaded.get(1).role());
        assertEquals("我先读文件", loaded.get(1).content());
        assertEquals(1, loaded.get(1).toolUses().size());
        assertEquals("toolu_1", loaded.get(1).toolUses().get(0).toolUseId());
        assertEquals("ReadFile", loaded.get(1).toolUses().get(0).toolName());
        assertEquals("foo", loaded.get(1).toolUses().get(0).arguments().get("file_path"));

        // tool_result references the same id, non-error
        assertEquals("tool_result", loaded.get(2).role());
        assertEquals(1, loaded.get(2).toolResults().size());
        assertEquals("toolu_1", loaded.get(2).toolResults().get(0).toolUseId());
        assertFalse(loaded.get(2).toolResults().get(0).isError());

        // rebuild restores structured blocks, ids aligned
        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        assertEquals(3, msgs.size());
        assertEquals("toolu_1", msgs.get(1).getToolUses().get(0).toolUseId());
        assertEquals("toolu_1", msgs.get(2).getToolResults().get(0).toolUseId());
    }

    @Test
    void interruptedTailSynthesizesErrorToolResult(@TempDir Path dir) {
        String workDir = dir.toString();
        String sessionId = "20260101-180000";

        SessionManager.saveMessage(workDir, sessionId, "user", "改 foo");
        SessionManager.saveAssistantTurn(workDir, sessionId, "我这就改", null,
                List.of(new ToolUseBlock("toolu_9", "EditFile", Map.<String, Object>of("file_path", "foo"))));
        // crash: no tool_result was written for toolu_9

        var loaded = SessionManager.loadSession(workDir, sessionId);
        assertEquals(2, loaded.size());

        ConversationManager conv = SessionManager.rebuildConversation(loaded);
        List<Message> msgs = conv.getMessages();
        // user + assistant(tool_use) + synthesized tool_result
        assertEquals(3, msgs.size());

        var repaired = msgs.get(2);
        assertEquals("user", repaired.getRole());
        assertEquals(1, repaired.getToolResults().size());
        assertEquals("toolu_9", repaired.getToolResults().get(0).toolUseId());
        assertTrue(repaired.getToolResults().get(0).isError());
        assertTrue(repaired.getToolResults().get(0).content().contains("结果未知"));
    }
}
