// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 队友每轮进度的落盘日志（JSONL）。
 *
 * <p>与 {@link Transcript} 并存但用途不同：transcript 是给人读的整段对话，没有
 * 时间戳；这份是给度量用的逐轮记录，带每轮的起止时刻与耗时。要回答「多 agent
 * 比单 agent 快在哪」，需要的是后者。
 *
 * <p>文件位置：{@code .mewcode/teams/{team}/progress.jsonl}，一行一条
 * {@link TeammateProgress.TurnRecord}。
 */
public final class ProgressLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProgressLog() {}

    static Path progressPath(String teamName) {
        return Path.of(System.getProperty("user.dir"),
                ".mewcode", "teams", teamName, "progress.jsonl");
    }

    /**
     * 追加一条进度记录。
     *
     * <p>写失败就丢掉这一条：观测数据不该让队友的任务失败。
     *
     * <p>进程内互斥即可 —— 队友都是同进程的虚拟线程。跨进程（TMUX / iTerm
     * 后端）不在此列，但那两条路径目前压根不可达（
     * {@code TeamManager.detectBackend()} 硬编码返回 {@code IN_PROCESS}）。
     */
    public static void append(TeammateProgress.TurnRecord record) {
        synchronized (ProgressLog.class) {
            try {
                Path path = progressPath(record.team());
                Files.createDirectories(path.getParent());
                Files.writeString(path, MAPPER.writeValueAsString(record) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
