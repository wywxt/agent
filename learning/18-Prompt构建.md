# 18 - Prompt 构建

## 源码位置

- `src/main/java/com/mewcode/prompt/PromptBuilder.java` (141 行) — 组装器
- `src/main/java/com/mewcode/prompt/PromptSections.java` — 各段内容定义
- `src/main/java/com/mewcode/prompt/PlanModePrompt.java` — Plan 模式提示

## 一、System Prompt 组装

### Builder 模式

```java
public class PromptBuilder {
    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));  // 按优先级排序
        return sections.stream()
            .map(Section::content)
            .filter(notEmpty)
            .collect(joining("\n\n"));
    }
}
```

### Section 优先级

```java
public record Section(String name, int priority, String content) {}
```

优先级决定了 system prompt 中各部分的排列顺序。

## 二、组装顺序

```java
public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
    var builder = new PromptBuilder();

    // 基础段（按优先级自动排序）
    builder.add(PromptSections.identitySection());      // 你是谁
    builder.add(PromptSections.systemSection());         // 系统规则
    builder.add(PromptSections.doingTasksSection());     // 如何执行任务
    builder.add(PromptSections.executingActionsSection()); // 如何执行动作
    builder.add(PromptSections.usingToolsSection());     // 如何使用工具
    builder.add(PromptSections.toneStyleSection());      // 语气风格
    builder.add(PromptSections.outputEfficiencySection()); // 输出效率
    builder.add(PromptSections.environmentSection(env)); // 环境信息

    // 可选段
    if (options.skillSection() != null) {
        builder.add(new Section("Skills", 90, options.skillSection()));
    }
    if (options.customInstructions() != null) {
        builder.add(new Section("CustomInstructions", 80, options.customInstructions()));
    }
    if (options.memorySection() != null) {
        builder.add(new Section("Memory", 85, options.memorySection()));
    }

    return builder.build();
}
```

## 三、环境检测

```java
public static EnvironmentContext detectEnvironment(String model) {
    String workDir = System.getProperty("user.dir");
    String osName = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");
    String shell = System.getenv("SHELL") != null ? System.getenv("SHELL") : "bash";

    // Git 检测
    boolean isGitRepo = false;
    String gitBranch = "";
    try {
        // git rev-parse --is-inside-work-tree → 是否在 git 仓库中
        // git rev-parse --abbrev-ref HEAD → 当前分支
    } catch (Exception ignored) {}

    return new EnvironmentContext(workDir, osName, arch, shell, isGitRepo, gitBranch, model, date);
}
```

## 四、Environment Context 用法

环境信息被格式化为：

```
You are Claude Code, Anthropic's official CLI for Claude.

# Environment
 - Primary working directory: /Users/xxx/project
 - Is a git repository: true (branch: main)
 - Platform: darwin (macOS)
 - Shell: zsh
 - Model: claude-sonnet-4-6
 - Today's date: 2026-07-19
```

## 五、Plan Mode Prompt

```java
public class PlanModePrompt {
    public static String buildReminder(String planPath, boolean planExists, int iteration) {
        if (!planExists && iteration == 1) {
            return "You are in Plan mode. Explore the codebase and design an approach...";
        }
        if (planExists) {
            return "You have written a plan to " + planPath + ". Continue working on it...";
        }
        // ...
    }
}
```
