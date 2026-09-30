// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令里的双引号必须原样送达 shell。
 *
 * <p><b>守的是什么：</b>命令原先作为 {@code -c} 的**命令行参数**传给 bash
 * （{@code ProcessBuilder(shell, "-c", command)}）。Windows 上 ProcessBuilder 会按 MSVC
 * 规则重新拼命令行，而 Git Bash 的 msys 运行时按另一套规则解析，结果是命令被拆坏、
 * **退出码仍是 0**，调用方看不出任何异常。
 *
 * <p>2026-09-25 的 team 实跑里这不是理论问题：lead 的会话记录显示它发
 * {@code echo "hello world"; echo 'single quoted ok'} 只拿回 {@code hello}，
 * {@code echo "=== root ==="} 只拿回 {@code ===}，{@code grep -E "3000|8080|9222"}
 * 里引号内那个 {@code |} 被当成了管道；它为此烧掉好几轮去诊断「这个 shell 会吞引号」，
 * 队友那边则改用 {@code printf '\042'} 这类八进制转义绕坑。
 *
 * <p>只在 Windows 上跑：这个 bug 是 Windows 的 argv→命令行→msys 解析链特有的，
 * POSIX 平台上 argv 直接 exec，不存在这一层。非 Windows 上这几个用例本来是绿的，
 * 跑它们只是白白多花时间。
 */
@EnabledOnOs(OS.WINDOWS)
class BashToolQuotingTest {

    private static String run(String command) {
        var r = new BashTool().execute(Map.of("command", command, "timeout", 30));
        var out = r.output() == null ? "" : r.output();
        if (out.startsWith("Error executing command")) {
            // 解析不出可用 shell（既没有 Git Bash 也没有 cmd.exe）时跳过断言，
            // 那是环境问题，不是这条改动要守的东西
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "无可用 shell: " + out);
        }
        return out.replace("\r\n", "\n").trim();
    }

    @Test
    void doubleQuotesSurviveToTheShell() {
        assertEquals("hello world / single quoted ok",
                run("echo \"hello world\"; echo 'single quoted ok'").replace("\n", " / "));
    }

    @Test
    void quotedTextIsNotTruncatedAtTheFirstSpace() {
        // 原实现下这条会红：只回 "==="
        assertEquals("=== root ===", run("echo \"=== root ===\""));
    }

    @Test
    void pipeInsideQuotesIsNotAPipe() {
        // 原实现下这条会红：引号被吃掉，| 变成管道，于是 bash 去找 "8080" 这个命令
        String out = run("echo \"a|b\"");
        assertEquals("a|b", out);
    }

    @Test
    void multiLineCommandsWork() {
        String out = run("for i in 1 2 3; do\n  echo \"v=$i\"\ndone");
        assertEquals("v=1\nv=2\nv=3", out);
    }

    @Test
    void exitCodeStillReported() {
        String out = run("echo \"before\"; exit 3");
        assertTrue(out.contains("before"), out);
        assertTrue(out.contains("Exit code 3"), out);
        assertFalse(new BashTool().execute(Map.of("command", "exit 7", "timeout", 30))
                .isError());
    }

    @Test
    void singleQuotedJsonIsPreserved() {
        // curl -d '{"title":"x"}' 这种形状是验收脚本的日常
        String out = run("printf '%s' '{\"title\":\"x\"}'");
        assertEquals("{\"title\":\"x\"}", out);
    }
}
