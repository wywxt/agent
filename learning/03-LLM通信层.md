# 03 - LLM 通信层

## 源码位置

- `src/main/java/com/mewcode/llm/LlmClient.java` (33 行) — 接口 + 工厂
- `src/main/java/com/mewcode/llm/AnthropicClient.java` (505 行) — Anthropic 实现
- `src/main/java/com/mewcode/llm/OpenAiClient.java` — OpenAI 实现
- `src/main/java/com/mewcode/llm/OpenAiCompatClient.java` — OpenAI 兼容协议
- `src/main/java/com/mewcode/llm/ModelResolver.java` — 模型名解析
- `src/main/java/com/mewcode/llm/StreamEvent.java` — 流事件定义
- `src/main/java/com/mewcode/llm/LlmException.java` — 异常分类

## 一、架构设计：策略模式 + 工厂方法

```
LlmClient (接口)
    ├── stream(ConversationManager, List<Map>) → BlockingQueue<StreamEvent>
    ├── setMaxOutputTokens(int)
    └── setSystemPrompt(String)

LlmClient.create(ProviderConfig, systemPrompt)  ← 工厂方法
    ├── "anthropic"    → new AnthropicClient(cfg, systemPrompt)
    ├── "openai"       → new OpenAiClient(cfg, systemPrompt)
    └── "openai-compat" → new OpenAiCompatClient(cfg, systemPrompt)
```

每个实现负责将内部的 `ConversationManager` 数据结构转换为对应 LLM 协议的 HTTP 请求格式，并将响应流解析为统一的 `StreamEvent` 类型。

## 二、AnthropicClient 详解

### 2.1 初始化（第 32-52 行）

```java
public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
    String apiKey = cfg.resolvedApiKey();       // 从配置或环境变量获取
    this.sdkClient = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(cfg.getBaseUrl())           // 支持自定义 / 代理端点
            .build();
    this.model = ModelResolver.resolve(cfg.getModel());  // 模型别名解析
    this.thinking = cfg.isThinking();            // 扩展思考模式
    this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

    // 启动时尝试从 API 获取模型的实际 context window 大小
    cfg.setFetchedContextWindow(fetchModelContextWindow());
}
```

### 2.2 流式请求（第 92-256 行）

核心方法 `stream()` 返回一个 `BlockingQueue<StreamEvent>`：

```java
public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
    var queue = new LinkedBlockingQueue<StreamEvent>(64);  // 容量 64 的阻塞队列

    Thread.startVirtualThread(() -> {  // 虚拟线程执行 HTTP 请求
        try {
            doStream(conv, tools, queue);
        } catch (Exception e) {
            queue.put(new StreamEvent.Error(classifyError(e).getMessage()));
        }
    });

    return queue;  // 立即返回，不阻塞调用方
}
```

### 2.3 Prompt Cache 设计（第 116-144 行）

**这是性能优化的核心。** 在三个位置添加 `cache_control` 标记：

```
① System Prompt 整体缓存
   cache_control 标记在 system text block 上
   → 系统指令在会话间不变，每次都命中

② 最后一条 User 消息的最后一个 content block
   如果最后一条 user 消息是 tool_result 列表，标记最后一个 tool_result
   如果最后一条 user 消息是纯文本，标记文本块
   → 对话历史前缀被缓存

③ 工具定义列表最后一项
   工具定义在会话间基本不变
   → 工具定义被缓存
```

实现代码：

```java
// AnthropicClient.java:116-144
var systemBlock = TextBlockParam.builder()
    .text(systemPrompt)
    .cacheControl(CacheControlEphemeral.builder().build())  // ← 缓存点 ①
    .build();

// 工具列表最后一项标记缓存
for (int i = 0; i < tools.size(); i++) {
    boolean isLast = (i == tools.size() - 1);
    paramsBuilder.addTool(buildTool(tools.get(i), isLast));  // ← 缓存点 ③
}

// 最后一条 user 消息的最后一个 block 标记缓存  ← 缓存点 ②
markLastUserTailForCache(messageParams);
```

### 2.4 SSE 流事件解析（第 157-256 行）

```
SDK 流事件                         → 内部事件
═══════════════════════════════════════════════
ContentBlockStart.thinking          → 进入思考模式 (inThinking = true)
ContentBlockStart.tool_use          → ToolCallStart(id, name)
ContentBlockDelta.text              → TextDelta(text)
ContentBlockDelta.thinking          → ThinkingDelta(text)   [累积到 thinkingAccum]
ContentBlockDelta.signature         → 存储签名
ContentBlockDelta.input_json        → ToolCallDelta(json)   [累积到 jsonAccum]
ContentBlockStop                    → 如果 inThinking → ThinkingComplete
                                   → 如果有 toolName → ToolCallComplete (Jackson 解析 JSON)
MessageStart                        → 提取 input/cache tokens
MessageDelta                        → 提取 stop_reason + output tokens
```

### 2.5 消息合并（第 258-359 行）

`buildMessages()` 将内部的 `List<Message>` 转换为 Anthropic SDK 的 `List<MessageParam>`：

- Assistant 消息含 tool_use → 构建为 content block 列表（thinking + text + tool_use）
- User 消息含 tool_result → 构建为 tool_result block 列表
- 连续同角色消息 → `mergeConsecutiveSameRole()` 合并（API 要求 alternating roles）

### 2.6 兼容性处理：deltaUsageLong()

```java
// Anthropic 官方 SDK 只保证 output_tokens 在 message_delta 中
// 但兼容提供商（如 MiniMax）可能在 message_delta 中也返回 input_tokens
// 这个方法先用 typed accessor，失败后回退到 _additionalProperties
private static int deltaUsageLong(Optional<Long> typed, MessageDeltaUsage usage, String jsonKey) {
    if (typed.isPresent()) return typed.get().intValue();
    // Fallback: 从 raw JSON 中提取
    var extra = usage._additionalProperties();
    if (extra != null && extra.containsKey(jsonKey)) {
        Optional<Number> num = extra.get(jsonKey).asNumber();
        if (num.isPresent()) return num.get().intValue();
    }
    return 0;
}
```

### 2.7 异常分类（第 446-471 行）

```java
private LlmException classifyError(Exception e) {
    if (e instanceof UnauthorizedException) → AuthenticationException
    if (e instanceof RateLimitException)     → RateLimitException
    if (e instanceof BadRequestException
        && msg.contains("prompt is too long")) → ContextTooLongException
    if (e instanceof AnthropicServiceException
        && statusCode == 413)                → ContextTooLongException
    if (e instanceof AnthropicIoException)   → NetworkException
    default                                  → Generic LlmException
}
```

## 三、OpenAiClient

与 AnthropicClient 结构相同，差异在于：
- 使用 OpenAI SDK 的流式 API
- 工具 schema 格式为 `{type: "function", function: {name, description, parameters}}`
- 不支持 thinking/cache_control

## 四、OpenAiCompatClient

为国产 LLM 提供商（MiniMax、DeepSeek 等）设计：
- 请求格式兼容 OpenAI
- 额外处理非标准响应字段
- 支持 `base_url` 指向兼容网关

## 五、StreamEvent 统一事件模型

```java
public sealed interface StreamEvent {
    record TextDelta(String text) {}
    record ThinkingDelta(String text) {}
    record ThinkingComplete(String thinking, String signature) {}
    record ToolCallStart(String toolId, String toolName) {}
    record ToolCallDelta(String partialJson) {}
    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) {}
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) {}
    record Error(String message) {}
}
```

所有 LLM 协议的事件都被归一化到这个类型体系中，上层（Agent、TUI）不感知底层是 Anthropic 还是 OpenAI。

## 六、ModelResolver

模型别名解析，比如 `"sonnet"` → `"claude-sonnet-4-6"`，`"opus"` → `"claude-opus-4-8"`。也处理模型的能力检测（是否支持 adaptive thinking）。
