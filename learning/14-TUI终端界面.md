# 14 - TUI 终端界面

## 源码位置

- `src/main/java/com/mewcode/tui/MewCodeModel.java` (~1500 行) — 主 TUI 模型
- `src/main/java/com/mewcode/tui/tea/Program.java` (330 行) — TEA 运行时
- `src/main/java/com/mewcode/tui/tea/Model.java` (15 行) — 模型接口
- `src/main/java/com/mewcode/tui/tea/Command.java` — 命令抽象
- `src/main/java/com/mewcode/tui/tea/KeyPressMessage.java` — 按键事件
- `src/main/java/com/mewcode/tui/tea/MouseMessage.java` — 鼠标事件
- `src/main/java/com/mewcode/tui/tea/WindowSizeMessage.java` — 窗口大小
- `src/main/java/com/mewcode/tui/tea/Style.java` — 样式
- `src/main/java/com/mewcode/tui/tea/UpdateResult.java` — 更新结果
- `src/main/java/com/mewcode/tui/dialog/PlanApprovalDialog.java` — 计划审批弹窗
- `src/main/java/com/mewcode/tui/dialog/AskUserDialog.java` — 用户问答弹窗

## 一、自研 TEA 框架

MewCode 从零实现了 Elm Architecture (TEA = The Elm Architecture)：

```
┌──────────────────────────────────────┐
│             Model (接口)              │
│  init()     → Command                │
│  update(msg) → UpdateResult<Model>   │
│  view()     → String                 │
└──────────────────────────────────────┘
         ↕ 消息驱动
┌──────────────────────────────────────┐
│           Program (运行时)            │
│  - keyReaderLoop() → 消息队列        │
│  - 主循环: poll → update → render    │
│  - renderView(): ANSI 内联渲染       │
└──────────────────────────────────────┘
```

### Model 接口

```java
public interface Model {
    Command init();                          // 初始命令（通常是获取窗口大小）
    UpdateResult<? extends Model> update(Message msg);  // 状态转换
    String view();                           // 渲染为字符串
    default String dumpHistory() { return ""; }
}
```

### Program 主循环

```java
public void run() {
    terminal = TerminalBuilder.builder().system(true).build();
    terminal.enterRawMode();  // 原始模式（逐字符读取）

    // 信号 → 消息转换
    terminal.handle(Signal.INT, sig → msgQueue.offer(new KeyPressMessage("ctrl+c")));
    terminal.handle(Signal.WINCH, sig → msgQueue.offer(new WindowSizeMessage(...)));

    // 虚拟线程读按键
    Thread.startVirtualThread(this::keyReaderLoop);

    executeCommand(model.init());  // 初始命令
    renderView();                  // 首次渲染

    while (running) {
        Message msg = msgQueue.poll(16, MILLISECONDS);  // 16ms = ~60fps
        if (msg == null) continue;
        if (msg instanceof QuitMessage) break;

        var result = model.update(msg);     // 状态转换
        if (result.command() != null) executeCommand(result.command());
        renderView();                       // 重新渲染
    }
}
```

### 内联渲染算法

```java
private void renderView() {
    String view = model.view();

    // cursor up 回到 view 起始行（避免整屏刷新闪烁）
    if (linesRendered > 0) {
        writer.print("\033[" + linesRendered + "A");
    }
    writer.print("\r");

    // 逐行写入 + 清除行尾残余
    for (int i = 0; i < lines.length; i++) {
        sb.append(lines[i]).append("\033[K");  // \033[K = 清除光标到行尾
        if (i < lines.length - 1) sb.append("\n");
    }
    writer.print(sb);
    writer.print("\033[J");  // 清除多余行

    // 计算物理行数（考虑终端宽度换行 + CJK 全角字符）
    linesRendered = physicalLinesForCursorUp(lines);
    writer.flush();
}
```

### CJK 全角字符宽度

```java
// 手写的 Unicode 区间判断
private static boolean isWide(int cp) {
    return (cp >= 0x1100 && cp <= 0x115F)   // Hangul Jamo
        || (cp >= 0x2E80 && cp <= 0x303E)   // CJK Radicals
        || (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK Unified Ideographs
        // ... 13 个区间，覆盖中日韩文字
}
```

### 按键解析

```java
// 手动解析 escape 序列
private Message parseInput(int c, NonBlockingReader reader) {
    if (c == 0x1B) {  // Escape
        int next = reader.peek(80);
        if (next == '[') → parseCSI()    // CSI: \033[...X
        if (next == 'O') → parseSS3()    // SS3: \033OX (Windows Terminal 方向键)
    }
    if (c == 0x0D || c == 0x0A) → "enter"
    if (c == 0x09) → "tab"
    if (c == 0x03) → "ctrl+c"
    if (c >= 1 && c <= 26) → "ctrl+" + (char)('a' + c - 1)
    if (c == 0x7F) → "backspace"
    // ...
}

// CSI 解析：识别 A(上) B(下) C(右) D(左) H(home) F(end) Z(shift+tab) ~(功能键)
// SGR 鼠标：识别滚轮、点击
```

## 二、MewCodeModel 状态机

```java
enum AppState { PROVIDER_SELECT, CHAT }

// 50+ 个状态字段：
private AppState state;
private List<ChatMessage> chatMessages;   // 消息展示列表
private StringBuilder streamBuf;          // 流式文本缓冲区
private StringBuilder inputBuffer;        // 输入缓冲区
private BlockingQueue<AgentEvent> agentQueue;  // Agent 事件队列
private CompletableFuture<PermissionResponse> pendingPermission;  // 权限弹窗
```

### Provider 选择界面

```java
// 多 Provider 配置时显示选择界面
if (providers.size() > 1) {
    state = AppState.PROVIDER_SELECT;
    // view() 渲染 provider 列表 + 选择光标
    // update() 处理上下键 + enter
}
```

### Chat 界面的 view 渲染

```
┌─────────────────────────────────────────┐
│ [历史消息区域]                           │
│                                         │
│ User: 帮我重构这个函数                   │
│                                         │
│ Assistant: 好的，我来分析一下...          │
│   ├── [Tool: ReadFile] main.java        │
│   └── [Tool: EditFile] main.java        │
│                                         │
│ ⠋ Thinking...                          │  ← 流式输出
│                                         │
├─────────────────────────────────────────┤
│ > 用户输入光标█                          │  ← 输入行
└─────────────────────────────────────────┘
```

### 流式渲染

Agent 的流事件通过 `agentQueue.poll(50ms)` 驱动 TUI 更新：

```java
// MewCodeModel 中启动一个定时 Command.Tick
Command.tick(50ms, () → new AgentEventMessage())

// update() 收到 AgentEventMessage 后：
// 1. poll agentQueue
// 2. 根据事件类型更新显示状态
// 3. 返回 UpdateResult → Program 自动重新渲染
```

## 三、Scrollback + 内联分离

```
终端窗口
├── [Scrollback 区域 — 已提交的历史消息]
│   (通过 Command.println() 写入，永久保留在终端滚动历史中)
│
├── [内联 View 区域 — 当前可见的 TUI]
│   (cursor up + 覆写重绘，退出时清除)
│
└── 用户输入行
```

`committedUpTo` 索引标记哪些消息已输出到 scrollback，view() 只渲染尚未提交的新消息。

## 四、特殊功能

### 斜杠菜单（/help, /clear, /compact, /plan...）

输入 `/` 时弹出命令菜单，支持模糊搜索。

### @ 文件菜单

输入 `@` 时弹出项目文件列表（通过 Glob 获取），支持模糊匹配。

### Rewind 对话框

支持恢复到之前的快照（`FileHistory`），三个恢复选项：代码+对话 / 仅对话 / 仅代码。

### AskUser 对话框

Agent 调用 AskUser 工具时，TUI 渲染问题选项，用户通过键盘选择后返回答案。

### Plan Approval 对话框

Plan 模式下，Agent 完成设计后通过 ExitPlanMode 退出，TUI 显示计划审批对话框。
