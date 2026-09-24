package com.mewcode.control.directive;

import com.mewcode.control.store.JsonlStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 指令收件与投递服务。线程安全：submit 由 WebSocket 虚拟线程调用，
 * drainPending/markDelivered/markAcknowledged 由 Agent 执行线程调用。
 *
 * 状态流转：RECEIVED（submit）→ DELIVERED（检查点投递）→ ACKNOWLEDGED（主 Agent 回应）。
 * 每条状态变化 append 一行 JSONL 作审计；内存 map 为进程内权威。
 */
public final class DirectiveService {

    private final JsonlStore store;
    private final ConcurrentLinkedQueue<Directive> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Directive> byId = new ConcurrentHashMap<>();

    public DirectiveService(JsonlStore store) {
        this.store = store;
    }

    /** 保存指令、立即返回（页面收到回执）。 */
    public Directive submit(String taskId, String text) {
        var d = new Directive(newId(), taskId, text, classify(text),
                DirectiveStatus.RECEIVED, System.currentTimeMillis(), null, null);
        byId.put(d.id(), d);
        pending.add(d);
        store.append(d.toMap());
        return d;
    }

    /** 取出全部待投递指令（Agent 检查点调用）。 */
    public List<Directive> drainPending() {
        var out = new ArrayList<Directive>();
        Directive d;
        while ((d = pending.poll()) != null) {
            out.add(d);
        }
        return out;
    }

    public void markDelivered(String id) {
        var cur = byId.get(id);
        if (cur == null) return;
        var updated = cur.withStatus(DirectiveStatus.DELIVERED);
        byId.put(id, updated);
        store.append(updated.toMap());
    }

    public void markAcknowledged(String id, String agentResponse) {
        var cur = byId.get(id);
        if (cur == null) return;
        var updated = cur.withAgentResponse(agentResponse);
        byId.put(id, updated);
        store.append(updated.toMap());
    }

    public List<Directive> allForTask(String taskId) {
        return byId.values().stream().filter(d -> d.taskId().equals(taskId)).toList();
    }

    private static String newId() {
        return "dir_" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    /** 阶段 2 用简单关键词规则兜底分类；阶段 5 换成小模型分类。 */
    private static DirectiveType classify(String text) {
        if (text.contains("必须") || text.contains("不要") || text.contains("禁止")
                || text.contains("注意") || text.contains("约束")) {
            return DirectiveType.CONSTRAINT;
        }
        if (text.contains("重新") || text.contains("换一个方案") || text.contains("重做")
                || text.contains("从头")) {
            return DirectiveType.REPLAN;
        }
        return DirectiveType.APPEND;
    }
}
