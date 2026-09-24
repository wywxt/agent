# 11 - Skill 技能系统

## 源码位置

- `src/main/java/com/mewcode/skill/SkillCatalog.java` (384 行) — 技能目录
- `src/main/java/com/mewcode/skill/SkillExecutor.java` (69 行) — 技能执行
- `src/main/java/com/mewcode/skill/SkillHost.java` — 宿主 Agent 接口
- `src/main/java/com/mewcode/skill/SkillForkHost.java` — Fork 宿主接口
- `src/main/java/com/mewcode/skill/SkillInstaller.java` — 技能安装
- `src/main/java/com/mewcode/skill/BuiltinSkills.java` — 内置技能

## 一、Skill 是什么

Skill 是一个**可复用的操作手册（SOP）**，包含元数据（名称、描述、触发条件）和提示词正文。当用户输入匹配某个 Skill 时，Agent 加载该 Skill 的 SOP 并按指导执行。

```
用户: "帮我审查代码"  →  匹配 skill "code-review"
                         →  加载 review SOP
                         →  Agent 按 SOP 步骤执行
```

## 二、存储结构

```
~/.mewcode/skills/          ← 用户级 Skill
    ├── my-custom-skill/
    │   ├── skill.yaml      ← 元数据
    │   └── prompt.md       ← SOP 正文
    └── another-skill/
        └── SKILL.md         ← 单文件格式（YAML frontmatter + Markdown）

.mewcode/skills/            ← 项目级 Skill（优先级最高）
    └── project-specific/
        └── skill.yaml + prompt.md

(Builtins)                  ← 内置 Skill（打包在 JAR 中）
```

## 三、两种格式

### 格式 1：skill.yaml + prompt.md

```yaml
# skill.yaml
name: code-review
description: Review code for bugs and improvements
when_to_use: When user asks to review code or check for bugs
tags: [review, quality]
mode: inline
```

```markdown
# prompt.md
You are performing a code review. Follow these steps:
1. Understand the changes
2. Check for correctness
3. Look for performance issues
...
```

### 格式 2：SKILL.md（单文件）

```markdown
---
name: code-review
description: Review code for bugs and improvements
when_to_use: When user asks to review code
tags: [review, quality]
mode: inline
---

You are performing a code review. Follow these steps:
1. Understand the changes
...
```

## 四、三级加载

```java
public static SkillCatalog loadCatalog(String workDir) {
    SkillCatalog c = new SkillCatalog();

    // Tier 1: 内置 Skill（最低优先级）
    for (var skill : BuiltinSkills.load()) {
        c.register(skill, "builtin");
    }

    // Tier 2: 用户级（~/.mewcode/skills/）
    c.loadTier(Path.of(home, ".mewcode", "skills"), "user");

    // Tier 3: 项目级（.mewcode/skills/）——最高优先级
    c.loadTier(Path.of(workDir, ".mewcode", "skills"), "project");

    return c;
}
```

同名 Skill 高层覆盖低层（项目 > 用户 > 内置）。

## 五、Skill 执行模式

### Inline 模式

Skill 的 SOP 直接注入到宿主 Agent 的上下文：

```java
public static String executeInline(Skill skill, String args, SkillHost host) {
    String body = substituteArguments(skill.promptBody(), args);
    host.activateSkill(skill.meta().name(), body);  // 激活（后续对话持续生效）
    host.recordSkillInvocation(skill.meta().name(), body);  // 记录（压缩恢复用）
    return body;
}
```

变量替换：
- `$ARGUMENTS` → 用户传入的参数文本
- 如果不含 `$ARGUMENTS` → 在 SOP 末尾追加 `## User Request\n\n{args}`

### Fork 模式

Skill 在独立子 Agent 中执行：

```java
public static String executeFork(Skill skill, String args, SkillForkHost host) {
    String body = substituteArguments(skill.promptBody(), args);
    List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
    return host.runSubAgent(body, seed, skill.meta().model());
}
```

`forkContext` 控制传递的上下文：
- `"none"` — 不传递父对话
- `"recent"` — 传递最近 5 条消息
- `"full"` — 传递完整对话

## 六、热重载

```java
// 检查 Skill 目录 modtime，有变化则重新加载
public boolean needsReload() {
    for (var entry : dirModTimes.entrySet()) {
        FileTime current = Files.getLastModifiedTime(Path.of(entry.getKey()));
        if (!current.equals(entry.getValue())) return true;
    }
}
```

`getFull()` 每次调用都重新读取文件内容（hot reload），确保编辑后立即生效。

## 七、SkillMeta 字段

```java
public record SkillMeta(
    String name,          // 唯一标识
    String description,   // 简短描述
    String whenToUse,     // 触发条件描述
    List<String> tags,    // 标签
    String mode,          // "inline" | "fork"
    String model,         // 指定模型（为空则继承）
    String forkContext    // fork 上下文: "none" | "recent" | "full"
) {}
```
