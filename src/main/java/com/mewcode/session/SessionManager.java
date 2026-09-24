// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public class SessionManager {

    /**
     * TYPE_COMPACT_BOUNDARY marks a session record as a compaction boundary
     * rather than a plain conversation message. A boundary record's content
     * holds a JSON blob (see {@link CompactBoundary}) carrying the summary text
     * plus the recent tail (keep) preserved verbatim at compaction time. Plain
     * messages leave {@code type} null/empty, so old sessions and normal turns
     * are unaffected (append-only, backward-compatible).
     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**
     * A session record. {@code type} distinguishes record kinds: empty/null (the
     * default) means a plain conversation message; {@link #TYPE_COMPACT_BOUNDARY}
     * means {@code content} is a {@link CompactBoundary} JSON blob written by
     * {@link #saveCompactBoundary}.
     * <p>
     * {@code toolUseId} records the tool_use block ID from the API response so
     * that chain validation can work correctly on resume — the model requires
     * tool_result blocks to reference the exact tool_use_id they respond to.
     */
    /**
     * A session record. {@code toolUses} / {@code toolResults} carry the
     * structured tool blocks so a resume can rebuild the exact tool chain
     * (including the {@code tool_use_id}s that {@code tool_result} blocks must
     * reference). {@code thinking} holds the assistant's (possibly multi-block)
     * thinking text, joined into one string — best-effort, never required for
     * replay.
     */
    public record SessionMessage(
            String role, String type, String content, long timestamp,
            String thinking, List<ToolUseBlock> toolUses, List<ToolResultBlock> toolResults) {
        /** Convenience constructor for plain (non-boundary) messages. */
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp, null, null, null);
        }

        /** Convenience constructor with type but no structured blocks. */
        public SessionMessage(String role, String type, String content, long timestamp) {
            this(role, type, content, timestamp, null, null, null);
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    /**
     * One verbatim message preserved in the recent tail at compaction time. Only
     * role + content text is stored, matching how the session log already
     * persists messages (text only, no tool blocks).
     */
    public record KeepMessage(String role, String content) {}

    /**
     * Structured payload stored (as JSON) in the content of a boundary record.
     * {@code summary} is the LLM-produced summary of the older prefix; {@code keep}
     * is the recent tail kept verbatim. On resume the compacted state is rebuilt
     * as: [user message = summary] + keep + any plain messages appended after the
     * boundary.
     */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /** Result of {@link #findLastCompactBoundary}: the boundary and the plain messages after it. */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serializes append across threads so multi-tool turns don't interleave lines. */
    private static final ReentrantLock APPEND_LOCK = new ReentrantLock();

    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".mewcode", "sessions");
    }

    // ---- ID generation ----

    /**
     * 生成带随机后缀的 session ID，格式为 yyyyMMdd-HHmmss-xxxx。
     * 随机后缀使用 SecureRandom 生成 2 字节十六进制，防止同秒并发冲突。
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] randomBytes = new byte[2];
        try {
            java.security.SecureRandom.getInstanceStrong().nextBytes(randomBytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SecureRandom 极少失败；兜底用纳秒低 16 位
            int fallback = (int) (System.nanoTime() & 0xFFFF);
            return "%s-%04x".formatted(timestamp, fallback);
        }
        return "%s-%s".formatted(timestamp,
                java.util.HexFormat.of().formatHex(randomBytes));
    }

    // ---- Persistence ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        saveRecord(workDir, sessionId, role, null, content, null, null, null);
    }

    /**
     * Persist an assistant turn together with its tool_use blocks (and optional
     * thinking text). The tool_use ids are kept verbatim so a resume can rebuild
     * the exact chain — a later {@code tool_result} record must reference these
     * ids. This is the checkpoint written at {@code addAssistantFull}.
     */
    public static void saveAssistantTurn(String workDir, String sessionId, String text,
                                         List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        saveRecord(workDir, sessionId, "assistant", null, text,
                joinThinking(thinking), toolUses, null);
    }

    /**
     * Persist the tool_result blocks produced for one assistant turn. The role is
     * {@code tool_result} to distinguish it from a plain user message on replay.
     */
    public static void saveToolResults(String workDir, String sessionId, List<ToolResultBlock> results) {
        saveRecord(workDir, sessionId, "tool_result", null, "", null, null, results);
    }

    private static String joinThinking(List<ThinkingBlock> thinking) {
        if (thinking == null || thinking.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ThinkingBlock t : thinking) {
            if (t.thinking() == null || t.thinking().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t.thinking());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Append a compaction boundary record so a later resume can rebuild the
     * compacted state (summary + kept tail) instead of replaying the full
     * pre-compaction transcript. Append-only: the original prefix messages stay
     * in the file but won't be replayed past this boundary (see
     * {@link #findLastCompactBoundary}). The summary + keep are inlined into the
     * record's content as a {@link CompactBoundary} JSON blob. No-op when
     * workDir/sessionId is null/blank (tests, one-shot callers).
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, null, null, null);
        } catch (JsonProcessingException ignored) {
            // best-effort: a failed boundary just means the next resume replays
            // verbatim, which is still correct (backward-compatible).
        }
    }

    private static void saveRecord(String workDir, String sessionId, String role, String type,
                                   String content, String thinking,
                                   List<ToolUseBlock> toolUses, List<ToolResultBlock> toolResults) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            // omit `type` for plain messages so old readers and old sessions are
            // unaffected (matches Go's `omitempty`).
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content == null ? "" : content);
            if (thinking != null && !thinking.isEmpty()) {
                line.put("thinking", thinking);
            }
            if (toolUses != null && !toolUses.isEmpty()) {
                line.put("tool_uses", toolUses.stream().map(tu -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tool_use_id", tu.toolUseId());
                    m.put("tool_name", tu.toolName());
                    m.put("arguments", tu.arguments() == null ? Map.of() : tu.arguments());
                    return m;
                }).toList());
            }
            if (toolResults != null && !toolResults.isEmpty()) {
                line.put("tool_results", toolResults.stream().map(tr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tool_use_id", tr.toolUseId());
                    m.put("content", tr.content() == null ? "" : tr.content());
                    m.put("is_error", tr.isError());
                    return m;
                }).toList());
            }
            line.put("ts", Instant.now().getEpochSecond());
            String json = MAPPER.writeValueAsString(line) + "\n";
            APPEND_LOCK.lock();
            try {
                Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } finally {
                APPEND_LOCK.unlock();
            }
        } catch (IOException ignored) {
            // best-effort, same as the Go version
        }
    }

    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    String thinking = (String) map.get("thinking");
                    List<ToolUseBlock> toolUses = parseToolUses(map.get("tool_uses"));
                    List<ToolResultBlock> toolResults = parseToolResults(map.get("tool_results"));
                    boolean hasContent = content != null && !content.isEmpty();
                    boolean hasTools = toolUses != null || toolResults != null;
                    if (hasContent || hasTools) {
                        messages.add(new SessionMessage(role, type, content, ts, thinking, toolUses, toolResults));
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolUseBlock> parseToolUses(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        var out = new ArrayList<ToolUseBlock>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = (String) m.get("tool_use_id");
            String name = (String) m.get("tool_name");
            if (id == null || name == null) continue;
            Object args = m.get("arguments");
            Map<String, Object> arguments = args instanceof Map ? (Map<String, Object>) args : Map.of();
            out.add(new ToolUseBlock(id, name, arguments));
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolResultBlock> parseToolResults(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        var out = new ArrayList<ToolResultBlock>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = (String) m.get("tool_use_id");
            Object content = m.get("content");
            boolean isError = Boolean.TRUE.equals(m.get("is_error"));
            if (id == null) continue;
            out.add(new ToolResultBlock(id, content == null ? "" : content.toString(), isError));
        }
        return out.isEmpty() ? null : out;
    }

    // ---- Compaction-boundary scanning ----

    /**
     * Scan the loaded records for the LAST compaction boundary. Returns the
     * parsed boundary plus the plain (non-boundary) messages appended after it.
     * When no boundary exists (or its blob is corrupt) {@code found} is false and
     * the caller should replay all records verbatim — backward-compatible with
     * old sessions that have no boundary records.
     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isCompactBoundary()) {
                last = i;
            }
        }
        if (last < 0) {
            return new BoundaryScan(null, List.of(), false);
        }
        CompactBoundary boundary;
        try {
            boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
        } catch (IOException e) {
            // Corrupt boundary blob — fall back to full replay rather than losing
            // the conversation.
            return new BoundaryScan(null, List.of(), false);
        }
        List<SessionMessage> after = new ArrayList<>();
        for (int i = last + 1; i < messages.size(); i++) {
            SessionMessage m = messages.get(i);
            if (m.isCompactBoundary()) continue; // defensive; we targeted the final one
            after.add(m);
        }
        return new BoundaryScan(boundary, after, true);
    }

    // ---- Conversation rebuild ----

    /**
     * Compaction-aware rebuild. If the session contains a {@code compact_boundary},
     * the live conversation is the compacted state — [summary as user message] +
     * kept tail + any plain messages appended after the boundary — and the
     * original pre-compaction prefix is NOT replayed (it stays in the file for
     * audit). Without a boundary (old sessions) everything is replayed verbatim.
     */
    /**
     * Content of a synthesized tool_result when the process died while a tool was
     * running. Kept explicit so the model re-verifies state instead of assuming
     * the tool succeeded.
     */
    public static final String INTERRUPTED_TOOL_MSG =
            "工具执行因进程中断而未完成，结果未知。请先重新读取相关文件/核实当前状态，再决定是否需要重试。";

    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        messages = repairInterruptedTail(messages);
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        List<SessionMessage> replay = new ArrayList<>();
        // Summary becomes the leading user message with the same Chinese framing
        // as autoCompact, so the model sees a consistent context header on resume.
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        replay.add(new SessionMessage("user", resumeSummary, 0L));
        for (KeepMessage k : scan.boundary().keep()) {
            replay.add(new SessionMessage(k.role(), k.content(), 0L));
        }
        replay.addAll(scan.after());
        return replay(replay);
    }

    /**
     * If the transcript ends on an assistant message that still has pending
     * tool_use blocks (the tool_result was never written because the process
     * died mid-execution), synthesize an error tool_result for each pending id so
     * the replay is API-valid and the model knows to re-verify state.
     */
    private static List<SessionMessage> repairInterruptedTail(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        SessionMessage last = messages.get(messages.size() - 1);
        if (last.toolUses() == null || last.toolUses().isEmpty()) {
            return messages; // clean tail — normal user/assistant/tool_result/boundary end
        }
        var results = last.toolUses().stream()
                .map(tu -> new ToolResultBlock(tu.toolUseId(), INTERRUPTED_TOOL_MSG, true))
                .toList();
        var out = new ArrayList<>(messages);
        out.add(new SessionMessage("tool_result", null, "", 0L, null, null, results));
        return out;
    }

    private static ConversationManager replay(List<SessionMessage> messages) {
        ConversationManager conversation = new ConversationManager();
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue; // never replay the raw boundary blob
            if (msg.toolUses() != null && !msg.toolUses().isEmpty()) {
                List<ThinkingBlock> thinking = (msg.thinking() == null || msg.thinking().isEmpty())
                        ? List.of()
                        : List.of(new ThinkingBlock(msg.thinking(), null));
                conversation.addAssistantFull(
                        msg.content() == null ? "" : msg.content(), thinking, msg.toolUses());
            } else if (msg.toolResults() != null && !msg.toolResults().isEmpty()) {
                conversation.addToolResultsMessage(msg.toolResults());
            } else if ("assistant".equals(msg.role())) {
                conversation.addAssistantMessage(msg.content());
            } else {
                conversation.addUserMessage(msg.content());
            }
        }
        return conversation;
    }

    // ---- Session expiry cleanup ----

    /** 过期阈值：30 天 */
    private static final long EXPIRY_DAYS = 30;

    /**
     * 自动清理超过 30 天的过期 session 文件。
     * 根据文件的最后修改时间判断是否过期。
     * 失败时静默忽略——清理是尽力而为，不应影响正常流程。
     */
    public static void cleanExpiredSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         long mtime = Files.getLastModifiedTime(p).toMillis();
                         if (mtime < cutoffMs) {
                             Files.deleteIfExists(p);
                         }
                     } catch (IOException ignored) {
                         // 单个文件清理失败不影响其它
                     }
                 });
        } catch (IOException ignored) {
            // 目录不可读时静默忽略
        }
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        String branch = currentGitBranch(workDir);
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.add(new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}
