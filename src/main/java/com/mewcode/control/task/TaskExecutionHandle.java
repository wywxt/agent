package com.mewcode.control.task;

import java.util.concurrent.CompletableFuture;

/**
 * 可取消的任务执行句柄。保存 taskId、执行线程、取消令牌与完成 future。
 * RemoteServer 持有它，通过 {@link #cancel()} 取消真实执行任务，
 * 而不是中断事件消费线程。
 */
public final class TaskExecutionHandle {

    private final String taskId;
    private final CancellationToken token;
    private final CompletableFuture<TaskOutcome> completion;
    private volatile Thread executionThread;

    public TaskExecutionHandle(String taskId, CancellationToken token,
                               CompletableFuture<TaskOutcome> completion) {
        this.taskId = taskId;
        this.token = token;
        this.completion = completion;
    }

    public String taskId() { return taskId; }
    public CancellationToken token() { return token; }
    public CompletableFuture<TaskOutcome> completion() { return completion; }
    public Thread executionThread() { return executionThread; }
    public void setExecutionThread(Thread t) { this.executionThread = t; }

    /**
     * 取消：置位令牌并打断执行线程，使 Agent 在下一个轮询点/流式读取处尽快返回。
     * 打断只影响消费线程，外部进程的终止是尽力而为（见 BashTool 的 destroyForcibly 路径）。
     */
    public void cancel() {
        token.cancel();
        Thread t = executionThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isDone() {
        return completion.isDone();
    }
}
