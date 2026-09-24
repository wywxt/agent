# 12 - MCP 集成

## 源码位置

- `src/main/java/com/mewcode/mcp/McpManager.java` (209 行)

## 一、MCP 协议概述

Model Context Protocol (MCP) 是 Anthropic 提出的标准协议，允许 LLM 应用通过标准化接口连接外部工具服务器。MewCode 使用官方 `io.modelcontextprotocol.sdk:mcp:1.1.3` SDK。

## 二、连接方式

### 方式 1：Stdio（子进程通信）

```yaml
# .mewcode/config.yaml
mcp_servers:
  - name: filesystem
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed"]
    env:
      NODE_ENV: production
```

实现：

```java
if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
    var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
    if (cfg.getArgs() != null) paramsBuilder.args(cfg.getArgs());
    if (cfg.getEnv() != null) paramsBuilder.env(resolvedEnv);  // 支持 ${VAR} 环境变量插值

    transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
}
```

### 方式 2：HTTP Streamable（远程连接）

```yaml
mcp_servers:
  - name: remote-tools
    url: https://mcp.example.com/mcp
    headers:
      Authorization: Bearer ${MCP_TOKEN}
```

实现：

```java
if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
    var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
    if (cfg.getHeaders() != null) {
        httpBuilder.customizeRequest(rb -> {
            for (var e : cfg.getHeaders().entrySet()) {
                rb.header(e.getKey(), resolveEnvVars(e.getValue()));  // 支持 ${VAR}
            }
        });
    }
    transport = httpBuilder.build();
}
```

## 三、连接流程

```java
public ConnectResult connectAll() {
    for (var entry : configs.entrySet()) {
        try {
            // 1. 创建并初始化客户端
            var client = createClient(cfg);
            client.initialize();             // MCP 握手

            // 2. 获取服务器指令
            String instructions = client.getServerInstructions();

            // 3. 获取工具列表
            var result = client.listTools();
            for (var sdkTool : result.tools()) {
                tools.add(new McpToolWrapper(name, sdkTool, client));
            }
        } catch (Exception e) {
            errors.add("MCP server '" + name + "': " + e.getMessage());
            // 失败不阻断启动，记录错误继续
        }
    }
}
```

## 四、工具包装

MCP 工具通过 `McpToolWrapper` 适配 `Tool` 接口：

```java
private static class McpToolWrapper implements Tool {
    @Override public String name() {
        return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
        // 例："mcp__filesystem__read_file", "mcp__github__search_repos"
    }

    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override public boolean shouldDefer() { return true; }  // ★ 默认延迟加载

    @Override public Map<String, Object> schema() {
        // 将 MCP SDK 的 JsonSchema 转换为 generic Map
        var input = new LinkedHashMap<>();
        input.put("type", jsonSchema.type());
        input.put("properties", jsonSchema.properties());
        input.put("required", jsonSchema.required());
        return Map.of("name", name(), "description", description(), "input_schema", input);
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        var request = new McpSchema.CallToolRequest(sdkTool.name(), args);
        var result = client.callTool(request);
        String text = extractTextContent(result);   // 提取 TextContent
        return result.isError() ? ToolResult.error(text) : ToolResult.success(text);
    }
}
```

## 五、延迟加载机制

所有 MCP 工具默认 `shouldDefer() = true`：

1. Agent 启动时工具 schema 列表不包含 MCP 工具 → 上下文窗口不会被撑爆
2. Agent 被提示 "The following deferred tools are available via ToolSearch..."
3. Agent 需要时调用 `ToolSearch(query="github")`
4. `ToolRegistry.searchDeferred()` 返回匹配的 MCP 工具 schema
5. `ToolRegistry.markDiscovered(name)` 标记为已发现
6. **下一轮循环**开始，`getAllSchemas()` 包含已发现的 MCP 工具

## 六、Windows 兼容

```java
private static final Set<String> WIN_CMD_SUFFIXED = Set.of("npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

static String windowsSafe(String command) {
    if (!System.getProperty("os.name").toLowerCase().contains("win")) return command;
    if (WIN_CMD_SUFFIXED.contains(command.toLowerCase())) return command + ".cmd";
    return command;
}
```

Windows 上 Node.js 工具需要 `.cmd` 后缀才能作为子进程启动。

## 七、环境变量插值

```java
static String resolveEnvVars(String value) {
    // 支持 ${VAR_NAME} 语法引用环境变量
    return ENV_VAR.matcher(value).replaceAll(m -> {
        String env = System.getenv(m.group(1));
        return env != null ? env : m.group(0);  // 未定义则保留原样
    });
}
```

## 八、MCP 指令注入

连接成功后，服务器指令（instructions）在首次用户消息时注入对话：

```java
mcpInstructions = "# MCP Server Instructions\n\n"
    + "The following MCP servers are connected...\n\n"
    + "## filesystem\n" + serverInstructions + "\n"
    + "Available tools: mcp__filesystem__read_file, mcp__filesystem__write_file, ...";

// 首次用户消息前注入
if (!mcpInstructions.isEmpty()) {
    conversation.addSystemReminder(mcpInstructions);
    mcpInstructions = "";
}
```

## 九、生命周期

```java
// 启动
McpManager mcpManager = new McpManager(configs);
List<String> errors = mcpManager.registerAllTools(registry);  // 连接 + 注册工具

// 关闭
mcpManager.shutdown();  // 逐个 closeGracefully()
```
