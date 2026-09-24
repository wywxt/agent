# 20 - Sandbox 沙箱

## 源码位置

- `src/main/java/com/mewcode/sandbox/Sandbox.java` — 沙箱接口
- `src/main/java/com/mewcode/sandbox/SandboxFactory.java` — 工厂
- `src/main/java/com/mewcode/sandbox/SandboxConfig.java` — 配置
- `src/main/java/com/mewcode/sandbox/BwrapSandbox.java` — Bubblewrap 实现 (Linux)
- `src/main/java/com/mewcode/sandbox/SeatbeltSandbox.java` — macOS Seatbelt 实现
- `src/main/java/com/mewcode/config/SandboxYamlConfig.java` — YAML 配置

## 一、沙箱的作用

当 Agent 执行 Shell 命令时，沙箱提供**操作系统级别的安全隔离**，防止意外或恶意的命令伤害系统。

```
普通模式：       bash -c "command"           ← 无限制
沙箱模式：       bwrap ... bash -c "command"  ← 受限的文件系统和网络
```

## 二、配置

```yaml
# .mewcode/config.yaml
sandbox:
  enabled: true
  network_enabled: false   # 默认禁用网络
  type: auto               # auto | bwrap | seatbelt
```

## 三、Sandbox 接口

```java
public interface Sandbox {
    /** 返回需要添加到 bash 命令前面的前缀参数 */
    List<String> wrapCommand(String workDir);

    /** 沙箱是否已成功初始化 */
    boolean isAvailable();
}
```

## 四、Bubblewrap (Linux)

```java
public class BwrapSandbox implements Sandbox {
    @Override
    public List<String> wrapCommand(String workDir) {
        return List.of(
            "bwrap",
            "--ro-bind", "/usr", "/usr",         // 只读挂载系统目录
            "--ro-bind", "/lib", "/lib",
            "--ro-bind", "/lib64", "/lib64",
            "--ro-bind", "/bin", "/bin",
            "--bind", workDir, workDir,           // 可写挂载工作目录
            "--bind", "/tmp", "/tmp",             // 可写 /tmp
            "--proc", "/proc",                    // 挂载 proc
            "--dev", "/dev",                      // 挂载 dev
            "--unshare-all",                      // 隔离所有命名空间
            "--share-net",                        // （可选）共享网络
            "bash", "-c"
        );
    }
}
```

## 五、Seatbelt (macOS)

```java
public class SeatbeltSandbox implements Sandbox {
    @Override
    public List<String> wrapCommand(String workDir) {
        // macOS 使用 sandbox-exec 和预定义的 .sb 配置文件
        return List.of("sandbox-exec", "-f", profilePath, "bash", "-c");
    }
}
```

## 六、命令执行时的集成

在 `BashTool` 中：

```java
public ToolResult execute(Map<String, Object> args) {
    String command = (String) args.get("command");

    List<String> cmd;
    if (sandbox != null && sandbox.isAvailable()) {
        cmd = new ArrayList<>(sandbox.wrapCommand(workDir));  // 添加沙箱前缀
        cmd.add(command);
    } else {
        cmd = List.of("bash", "-c", command);
    }

    ProcessBuilder pb = new ProcessBuilder(cmd);
    // ...
}
```

## 七、与权限系统的协作

当沙箱启用时，`PermissionChecker` 的 Layer 4c 生效：

```
沙箱已启用 + 命令类工具
  → 拆分复合命令（按 && || ; | 切分）
  → 逐条检查是否有显式 deny/ask 规则
  → 无显式规则 → 直接 ALLOW（OS 沙箱已兜底）
```

这意味着沙箱模式下，Agent 可以自由执行大部分命令（文件系统操作已被限制在项目目录），只有配置了显式 deny 规则的命令才会被拦截。

## 八、denyWrite 保护路径

即使沙箱启用，以下路径仍然受到**应用层保护**（PermissionChecker Layer 2b）：

```java
private static final List<String> DEFAULT_DENY_WRITE = List.of(
    ".mewcode/config.yaml",
    ".mewcode/permissions.local.yaml",
    ".mewcode/skills/"
);
```

这些是 Agent 配置文件，即使沙箱允许写操作，权限检查器也会拒绝。
