package com.mewcode.control.event;

import com.mewcode.control.store.JsonlStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件落盘存储。每个任务（taskId）一个独立的 JSONL 文件，避免所有对话
 * 塞进同一个 events.jsonl 无限膨胀。
 *
 * <p>append 分配全局递增 seq；进程内以 seq 顺序为权威；{@link #readSince} 供
 * 断线补拉（扫描所有任务文件按 seq 排序），{@link #readByTask} 供报告引用。</p>
 */
public final class EventStore {

    private final Path eventsDir;
    private final AtomicLong seq = new AtomicLong(0);
    /** 每任务一个 JsonlStore（各自持锁，避免同一文件并发写竞争）。 */
    private final ConcurrentHashMap<String, JsonlStore> perTask = new ConcurrentHashMap<>();

    public EventStore(Path eventsDir) {
        this.eventsDir = eventsDir;
        try {
            Files.createDirectories(eventsDir);
        } catch (IOException ignored) {
            // 目录不可建时后续 append 会静默失败
        }
    }

    private Path fileFor(String taskId) {
        String safe = taskId == null ? "null" : taskId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return eventsDir.resolve(safe + ".jsonl");
    }

    private JsonlStore storeFor(String taskId) {
        return perTask.computeIfAbsent(taskId, id -> new JsonlStore(fileFor(id)));
    }

    public EventEnvelope append(String taskId, EventType type, String toolCallId,
                                Map<String, Object> payload) {
        long s = seq.incrementAndGet();
        var env = new EventEnvelope(s, taskId, type, toolCallId, System.currentTimeMillis(), payload);
        storeFor(taskId).append(env.toMap());
        return env;
    }

    public long lastSeq() {
        return seq.get();
    }

    /** 读取 seq 大于 afterSeq 的事件（断线补拉）。扫描所有任务文件，按 seq 升序返回。 */
    public List<EventEnvelope> readSince(long afterSeq) {
        var out = new ArrayList<EventEnvelope>();
        for (Path file : listFiles()) {
            for (var rec : new JsonlStore(file).readAll()) {
                long s = rec.get("seq") instanceof Number n ? n.longValue() : 0;
                if (s > afterSeq) out.add(fromMap(rec));
            }
        }
        out.sort(Comparator.comparingLong(EventEnvelope::seq));
        return out;
    }

    public List<EventEnvelope> readByTask(String taskId) {
        var out = new ArrayList<EventEnvelope>();
        for (var rec : storeFor(taskId).readAll()) {
            out.add(fromMap(rec));
        }
        return out;
    }

    private List<Path> listFiles() {
        try (var stream = Files.list(eventsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static EventEnvelope fromMap(Map<String, Object> rec) {
        long s = rec.get("seq") instanceof Number n ? n.longValue() : 0;
        String taskId = (String) rec.get("taskId");
        EventType type = EventType.valueOf((String) rec.get("type"));
        String toolCallId = (String) rec.get("toolCallId");
        long ts = rec.get("ts") instanceof Number n ? n.longValue() : 0;
        Map<String, Object> payload = rec.get("payload") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return new EventEnvelope(s, taskId, type, toolCallId, ts, payload);
    }
}
