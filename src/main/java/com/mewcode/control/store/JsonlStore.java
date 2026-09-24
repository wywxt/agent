package com.mewcode.control.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 零依赖的 JSONL 追加存储。Sidecar 模块用它持久化
 * directives / events / approvals / reports / qa 等记录。
 * append-only + 单行一条 JSON，进程内以内存结构为权威，落盘作审计。
 */
public final class JsonlStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonlStore(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ignored) {
            // 目录不可建时后续 append 会静默失败
        }
    }

    public void append(Map<String, Object> record) {
        lock.lock();
        try {
            String json = MAPPER.writeValueAsString(record) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort：落盘失败不影响主执行
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    out.add(MAPPER.readValue(line, Map.class));
                } catch (IOException ignored) {
                    // 跳过坏行
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return out;
    }
}
