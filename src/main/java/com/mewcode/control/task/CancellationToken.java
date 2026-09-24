package com.mewcode.control.task;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨线程取消令牌。Agent 循环在检查点轮询 {@link #isCancelled()}，
 * 工具执行前再查一次，实现"禁止派发新工具"的取消语义。
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String reason;

    public void cancel(String reason) {
        this.reason = reason;
        cancelled.set(true);
    }

    public void cancel() {
        cancel(null);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String reason() {
        return reason;
    }
}
