// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.sandbox.Sandbox;
import com.mewcode.sandbox.SandboxConfig;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;

    // 解析出的 shell 命令前缀（如 {"bash", "-c"} 或 {"cmd.exe", "/c"}），惰性缓存
    private static volatile String[] cachedShellPrefix = null;

    // 特殊命令 exit code 1 的语义提示（grep 没匹配到、diff 文件有差异等）
    private static final Map<String, String> EXIT_ONE_HINTS = Map.of(
            "grep", "no matches found",
            "egrep", "no matches found",
            "fgrep", "no matches found",
            "rg", "no matches found",
            "diff", "files differ",
            "find", "some directories were inaccessible",
            "test", "condition is false",
            "[", "condition is false"
    );

    // 工作目录
    private String workDir;

    // OS 级沙箱：包装命令在隔离环境中执行
    private Sandbox sandbox;
    private SandboxConfig sandboxConfig;

    public BashTool() {
        this.workDir = null;
    }

    public BashTool(String workDir) {
        this.workDir = workDir;
    }

    /** 设置 OS 级沙箱，命令执行前会通过沙箱包装 */
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public void setSandboxConfig(SandboxConfig config) { this.sandboxConfig = config; }

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            // 如果沙箱可用，将命令包装在沙箱中执行
            String actualCommand = command;
            if (sandbox != null && sandbox.isAvailable() && sandboxConfig != null) {
                actualCommand = sandbox.wrap(command, sandboxConfig);
            }

            ProcessBuilder pb = new ProcessBuilder(withShellPrefix(actualCommand));
            // 合并 stdout 和 stderr 到同一个流，简化输出解析
            pb.redirectErrorStream(true);

            // 设置工作目录
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            // 后台线程持续排空 stdout，避免管道缓冲填满导致子进程写阻塞；
            // 主线程用 timeout 兜底。否则 readAllBytes() 会阻塞到子进程退出，
            // 长驻进程（如 node server.js）会永久卡住，timeout 形同虚设，
            // 并触发上层 300s 的 "Stream timeout" 误报。
            final java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream stream = process.getInputStream()) {
                    stream.transferTo(outBuf);
                } catch (IOException ignored) {
                }
            });

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();
            try {
                reader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output = outBuf.toString();

            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }

            // 非零 exit code 时附加退出码信息，但不设置 isError
            if (exitCode != 0) {
                sb.append("Exit code ").append(exitCode);
                // 对特殊命令附加语义提示
                String hint = getExitCodeHint(command, exitCode);
                if (hint != null) {
                    sb.append(" (").append(hint).append(")");
                }
                sb.append('\n');
            }

            // 正常执行完成，isError 始终为 false（仅超时和中断才为 true）
            return new ToolResult(sb.toString(), false);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    /** 把命令包装成 {shell, "-c"|"/c", command} 形式。 */
    private static String[] withShellPrefix(String command) {
        String[] prefix = shellPrefix();
        String[] cmd = new String[prefix.length + 1];
        System.arraycopy(prefix, 0, cmd, 0, prefix.length);
        cmd[prefix.length] = command;
        return cmd;
    }

    /** 返回 shell 命令前缀，惰性解析并缓存。 */
    private static String[] shellPrefix() {
        String[] cached = cachedShellPrefix;
        if (cached != null) {
            return cached;
        }
        String[] resolved = resolveShell();
        cachedShellPrefix = resolved;
        return resolved;
    }

    /**
     * 解析当前平台可用的 shell 命令前缀。
     *
     * 背景：Windows 上硬编码 "bash" 会命中 C:\Windows\System32\bash.exe（WSL stub），
     * 当未安装任何 WSL 发行版时该 stub 无法执行任何命令，导致整个 Bash 工具失效。
     * 因此在 Windows 上优先探测真正的 bash（Git Bash / MSYS2），失败再退回 cmd.exe。
     */
    private static String[] resolveShell() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!windows) {
            return new String[]{"bash", "-c"};
        }

        // SHELL 环境变量优先（用户显式指定）
        String shellEnv = System.getenv("SHELL");
        if (shellEnv != null && !shellEnv.isBlank() && probeShell(shellEnv, "-c")) {
            return new String[]{shellEnv, "-c"};
        }

        // 候选 bash：Git 安装目录推导 + 常见目录 + PATH 扫描，逐个探测可用性
        for (String bash : candidateBashPaths()) {
            if (probeShell(bash, "-c")) {
                return new String[]{bash, "-c"};
            }
        }

        // 兜底：cmd.exe（Windows 必定存在）
        return new String[]{"cmd.exe", "/c"};
    }

    /** 运行 shell 的 "exit 0" 探测其是否真正可用（能排除坏的 WSL stub）。 */
    private static boolean probeShell(String shell, String arg) {
        try {
            Process p = new ProcessBuilder(shell, arg, "exit 0")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 收集 Windows 上候选的 bash.exe 绝对路径，按优先级去重。 */
    private static List<String> candidateBashPaths() {
        Set<String> result = new LinkedHashSet<>();
        String path = System.getenv("PATH");

        // 1) 从 PATH 中的 git.exe 反推 Git 安装目录（覆盖 E:\Git 这类自定义安装路径）
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File gitExe = new File(dir, "git.exe");
                if (!gitExe.isFile()) {
                    continue;
                }
                File parent = gitExe.getParentFile();
                if (parent == null) {
                    continue;
                }
                File gitRoot = (parent.getName().equalsIgnoreCase("cmd")
                        || parent.getName().equalsIgnoreCase("bin"))
                        ? parent.getParentFile() : parent;
                if (gitRoot == null) {
                    continue;
                }
                for (String sub : new String[]{"bin\\bash.exe", "usr\\bin\\bash.exe", "cmd\\bash.exe"}) {
                    result.add(new File(gitRoot, sub).getAbsolutePath());
                }
            }
        }

        // 2) 常见安装目录
        String sysDrive = System.getenv("SystemDrive");
        String[] roots = {
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("LOCALAPPDATA"),
                sysDrive == null ? null : sysDrive + "\\"
        };
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            for (String sub : new String[]{"Git\\bin\\bash.exe", "Git\\usr\\bin\\bash.exe", "Git\\cmd\\bash.exe"}) {
                result.add(root + "\\" + sub);
            }
        }

        // 3) PATH 中已有的 bash.exe（可能是 WSL stub，交给 probeShell 验证）
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                result.add(new File(dir, "bash.exe").getAbsolutePath());
            }
        }

        return new ArrayList<>(result);
    }

    /**
     * 对特殊命令的 exit code 1 返回语义提示。
     * 管道命令取最后一段（bash 默认返回最后一个命令的 exit code），
     * 对于 grep/diff/find/test 等命令，exit code 1 属于正常结果，附加提示帮助理解。
     */
    private String getExitCodeHint(String command, int exitCode) {
        if (exitCode != 1) {
            return null;
        }
        String baseCmd = extractBaseCommand(command);
        return EXIT_ONE_HINTS.get(baseCmd);
    }

    /**
     * 从完整命令字符串中提取基础命令名。
     * 处理管道（取最后一段）、路径前缀（取 basename）、env 前缀等。
     */
    private String extractBaseCommand(String command) {
        String cmd = command.strip();

        // 管道：取最后一段，因为 bash 的 exit code 由管道最后一个命令决定
        int pipeIdx = cmd.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < cmd.length() - 1) {
            cmd = cmd.substring(pipeIdx + 1).strip();
        }

        // 跳过 env 变量赋值前缀（如 FOO=bar grep ...）
        while (cmd.contains("=") && !cmd.startsWith("=")) {
            int spaceIdx = cmd.indexOf(' ');
            int eqIdx = cmd.indexOf('=');
            if (eqIdx < spaceIdx || spaceIdx == -1) {
                // 这一段是环境变量赋值，跳过
                if (spaceIdx == -1) break;
                cmd = cmd.substring(spaceIdx + 1).strip();
            } else {
                break;
            }
        }

        // 取第一个 token（命令名本身）
        String[] parts = cmd.split("\\s+", 2);
        String token = parts[0];

        // 处理路径前缀，如 /usr/bin/grep → grep
        int slashIdx = token.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < token.length() - 1) {
            token = token.substring(slashIdx + 1);
        }

        return token;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
