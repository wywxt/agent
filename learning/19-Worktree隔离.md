# 19 - Worktree 隔离

## 源码位置

- `src/main/java/com/mewcode/worktree/WorktreeManager.java` — Worktree 管理
- `src/main/java/com/mewcode/worktree/AgentWorktree.java` — Agent 专用 Worktree API
- `src/main/java/com/mewcode/worktree/WorktreeChanges.java` — 变更检测
- `src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java` — 进入 Worktree 工具
- `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java` — 退出 Worktree 工具

## 一、什么是 Worktree

Git Worktree 允许从同一个仓库创建多个独立的工作目录，每个目录有自己的分支。MewCode 用它来为子 Agent 提供**隔离的文件系统环境**。

```
主仓库: /project  (branch: main)
    │
    ├── Worktree 1: .claude/worktrees/agent-a1b2c3d/  (branch: mewcode-agent-a1b2c3d)
    │   子 Agent 可以自由修改，不影响主工作区
    │
    └── Worktree 2: .claude/worktrees/agent-e4f5g6h/  (branch: mewcode-agent-e4f5g6h)
        另一个子 Agent 的隔离环境
```

## 二、创建 Worktree

```java
public record Result(String worktreePath, String worktreeBranch, String gitRoot, String headCommit) {}

public static Result create(String slug, String projectRoot, List<String> symlinkDirs) {
    // 1. git worktree add .claude/worktrees/{slug} origin/main
    //    (或从当前 HEAD 创建新分支)

    // 2. 创建符号链接（如 node_modules, .venv 等大目录）

    // 3. 返回 result，包含路径、分支名、HEAD commit
}
```

## 三、变更检测

```java
public static boolean hasChanges(String worktreePath, String headCommit) {
    // 1. git -C {worktreePath} status --porcelain → 是否有未提交变更
    // 2. git -C {worktreePath} rev-parse HEAD → 当前 HEAD
    // 3. 如果 HEAD != headCommit（有新 commit）→ 有变更
    // 4. 否则 → 无变更
}
```

## 四、清理

```java
public static void remove(String worktreePath, String branch, String gitRoot) {
    // 1. git -C {gitRoot} worktree remove {worktreePath} --force
    // 2. git -C {gitRoot} branch -D {branch}   (删除遗留分支)
}
```

## 五、Agent 集成

### 使用方式

Agent 工具的参数 `isolation: "worktree"` 触发：

```java
// AgentTool.runSync() 中
if ("worktree".equals(isolation) && worktreeManager != null) {
    var wtResult = AgentWorktree.create(slug, projectRoot, symlinkDirs);
    subAgent.setWorkDir(wtResult.worktreePath());  // 子 Agent 在隔离目录工作

    // 注入通知：
    String notice = AgentWorktree.buildNotice(originalDir, wtResult.worktreePath());
    prompt = notice + "\n\n" + prompt;
}

// 执行完成后
if (!WorktreeChanges.hasChanges(wtResult)) {
    AgentWorktree.remove(wtResult);  // 无变更 → 自动清理
} else {
    // 有变更 → 保留 Worktree，通知用户
}
```

### Worktree 通知

```
You are working in an isolated git worktree at:
  /project/.claude/worktrees/agent-a1b2c3d/

This is a complete copy of the repository. All file operations
(paths, globs, grep) should use this worktree path.

The original project is at /project. You can reference it for
context but should NOT modify files there directly.
```

## 六、Enter/Exit Worktree 工具

主 Agent 也可以手动进入/退出 Worktree：

- `EnterWorktreeTool` — 创建或进入已有 Worktree
- `ExitWorktreeTool` — 退出 Worktree（keep 保留 / remove 清理）

```java
// EnterWorktreeTool: 切换当前 Session 的工作目录到 Worktree
// ExitWorktreeTool: 切换回原始目录，可选清理 Worktree
```

## 七、WorktreeManager

```java
public class WorktreeManager {
    private final String projectRoot;
    private final List<String> symlinkDirs;  // 需要符号链接的目录
    private final int ttlMinutes;            // Worktree 生存时间（默认 720 分钟 = 12 小时）

    // 管理活跃的 Worktree 列表，超时自动清理
}
```
