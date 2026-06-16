# RAG ↔ MCP 联动全链路梳理

> 本文档描述 RAG 系统（bootstrap 模块）如何在启动阶段发现 MCP 工具、并在对话请求中调用它们，
> 完整覆盖从意图识别到工具返回结果进入 Prompt 的整个链路。
>
> MCP Server 本身的架构（协议层、执行器实现）见 [mcp-server/mcp-server-overview.md](./mcp-server/mcp-server-overview.md)。

---

## 一、整体设计思路

RAG 和 MCP 的联动以**意图树**为桥梁：
- 管理员在意图树上将某个叶子节点的 `kind` 字段设为 `MCP`，并填写 `mcpToolId`（如 `weather_query`）
- 用户提问时，意图识别命中该节点 → 系统自动走 MCP 调用路径
- 无需修改任何代码，纯配置驱动

整个联动分为**启动阶段**（工具发现与注册）和**请求阶段**（意图 → 参数提取 → 调用 → Prompt）两部分。

---

## 二、启动阶段：工具发现与注册

```
bootstrap 启动
    │
    ▼
MCPClientAutoConfiguration.init()          ← @PostConstruct，读取 rag.mcp.servers 配置
    │
    ├─ 遍历每个配置的 MCP Server（name + url）：
    │
    │   1. HttpMCPClient.initialize()
    │         POST /mcp {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
    │         ← 握手：确认协议版本，服务端返回 capabilities/serverInfo
    │         → 握手成功后发送 notifications/initialized 通知（无 id，服务端不需要响应）
    │
    │   2. HttpMCPClient.listTools()
    │         POST /mcp {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
    │         ← 拉取远程工具列表，解析 JSON Schema → List<MCPTool>
    │         → 示例：[weather_query, ticket_query, sales_query]（来自 mcp-server）
    │
    │   3. 为每个 MCPTool 创建 RemoteMCPToolExecutor(mcpClient, tool)
    │         └─ RemoteMCPToolExecutor 实现了 bootstrap 的 MCPToolExecutor 接口
    │            持有 MCPClient 引用，execute() 时通过 HTTP 转发调用
    │
    └─ MCPToolRegistry.register(executor)
          └─ bootstrap 自己的 DefaultMCPToolRegistry
             key=toolId → value=RemoteMCPToolExecutor
             存储在 ConcurrentHashMap 中，供请求阶段按 toolId 查找
```

**配置示例**（`application.yml`）：
```yaml
rag:
  mcp:
    servers:
      - name: ragent-mcp-server
        url: http://localhost:8081
```

> **注意**：bootstrap 和 mcp-server 各有一套同名的 `MCPToolRegistry` / `MCPToolExecutor` 接口，是两套独立体系：
> - `mcp-server` 注册表：管理"服务端能提供哪些工具"（工具实现视角）
> - `bootstrap` 注册表：管理"RAG 客户端能调用哪些工具"（调用方视角）

---

## 三、请求阶段：意图识别 → 参数提取 → 工具调用 → Prompt 组装

以用户提问 **"帮我查一下北京明天天气"** 为例：

```
用户问题："帮我查一下北京明天天气"
    │
    ▼
【1. 意图识别】IntentResolver.resolve()
    │
    │  IntentClassifier 对问题打分，遍历意图节点树
    │  命中一个 kind=MCP、mcpToolId="weather_query" 的叶子节点
    │
    └─ 返回 [NodeScore{
               node.kind    = MCP
               node.mcpToolId = "weather_query"
               score        = 0.92
             }]
    │
    ▼
【2. 检索引擎分发】RetrievalEngine.buildSubQuestionContext()
    │
    ├─ filterKbIntents()   → 空（本问题无 KB 意图）
    └─ filterMCPIntents()  → [weather_query NodeScore]
    │
    ▼
【3. 参数提取】buildMcpRequest()
    │
    ├─ mcpToolRegistry.getExecutor("weather_query")
    │       → RemoteMCPToolExecutor
    │
    ├─ executor.getToolDefinition()
    │       → MCPTool{
    │             toolId:      "weather_query",
    │             description: "查询城市天气...",
    │             parameters:  {city, queryType, days}
    │           }
    │
    └─ LLMMCPParameterExtractor.extractParameters(question, tool)
            │
            │  构建 Prompt：
            │    system: <参数提取专用提示词>
            │    user:   "工具定义如下：\n工具ID: weather_query\n参数: city(必填)..."
            │    user:   "请从下面问题中提取参数：帮我查一下北京明天天气"
            │
            │  调用 LLM（temperature=0.1, topP=0.3，低随机，确保输出稳定）
            │  LLM 返回 JSON：{"city":"北京","queryType":"forecast","days":1}
            │
            └─ 返回 Map{city="北京", queryType="forecast", days=1}
    │
    ▼
【4. MCP 工具调用】executeSingleMcpTool(MCPRequest)
    │
    └─ RemoteMCPToolExecutor.execute(request)
            │
            └─ HttpMCPClient.callTool("weather_query", {city:"北京",...})
                    │
                    │  POST /mcp
                    │  {
                    │    "jsonrpc": "2.0",
                    │    "id": 3,
                    │    "method": "tools/call",
                    │    "params": {
                    │      "name": "weather_query",
                    │      "arguments": {"city":"北京","queryType":"forecast","days":1}
                    │    }
                    │  }
                    │
                    │  ←──── 跨进程 HTTP ────►  mcp-server (port 8081)
                    │                                MCPEndpoint.handle()
                    │                                MCPDispatcher.handleToolsCall()
                    │                                WeatherMCPExecutor.execute()
                    │                                → 生成模拟天气数据
                    │                                → 返回天气文本
                    │
                    └─ 解析响应 content[0].text → 天气文本字符串
    │
    ▼  MCPResponse{success=true, textResult="【北京 未来1天天气预报】..."}
    │
    ▼
【5. 格式化为上下文】ContextFormatter.formatMcpContext(responses, mcpIntents)
    └─ 将 MCPResponse 列表格式化为可放入 Prompt 的文本块
    │
    ▼
【6. Prompt 组装】RAGPromptService.buildStructuredMessages()
    消息组装顺序（固定）：
    1. system prompt（MCP_ONLY / MIXED 场景选不同模板）
    2. MCP 工具返回内容  ← 天气查询结果在这里
    3. KB 检索片段（如有）
    4. 历史对话消息
    5. 当前用户问题
    │
    ▼
LLMService.streamChat() → SSE 流式输出最终回答给用户
```

---

## 四、并行调用机制

当用户问题被拆分为**多个子问题**，或单个子问题匹配**多个 MCP 意图**时，工具调用并行执行：

```java
// RetrievalEngine.executeMcpTools()
List<CompletableFuture<MCPResponse>> futures = requests.stream()
    .map(request -> CompletableFuture.supplyAsync(
        () -> executeSingleMcpTool(request),
        mcpBatchExecutor  // 专属线程池
    ))
    .toList();
// 等待全部完成后合并结果
```

单个工具失败不影响其他工具，失败的工具返回 `MCPResponse.error`，最终格式化时只包含成功的结果。

---

## 五、参数提取失败兜底

`LLMMCPParameterExtractor` 有三层兜底：

| 异常情况 | 处理策略 |
|---|---|
| LLM 返回非 JSON | 捕获 `JsonSyntaxException`，改用所有参数的 `defaultValue` |
| JSON 中某个参数缺失 | `fillDefaults()` 自动补入 `defaultValue`（如 `days=3`） |
| LLM 调用本身异常 | 捕获通用 `Exception`，同样 fallback 到默认参数 |
| 工具无参数定义 | 直接返回空 Map，跳过 LLM 调用 |

> 意图节点上可以配置 `paramPromptTemplate` 字段，替换默认的参数提取提示词，实现业务定制化提取。

---

## 六、核心类索引

### bootstrap 模块（调用方）

| 类 | 路径 | 职责 |
|---|---|---|
| `MCPClientAutoConfiguration` | `rag/core/mcp/client/` | 启动时连接远程 MCP Server，注册 `RemoteMCPToolExecutor` |
| `HttpMCPClient` | `rag/core/mcp/client/` | 封装 JSON-RPC 2.0，`initialize` / `tools/list` / `tools/call` |
| `RemoteMCPToolExecutor` | `rag/core/mcp/client/` | 实现 `MCPToolExecutor`，将调用代理给 `HttpMCPClient` |
| `MCPClientProperties` | `rag/core/mcp/client/` | 配置：`rag.mcp.servers[].name/url` |
| `DefaultMCPToolRegistry` | `rag/core/mcp/` | bootstrap 端工具注册表（toolId → 执行器映射） |
| `MCPTool` | `rag/core/mcp/` | 工具元数据（toolId、description、参数定义） |
| `MCPRequest` / `MCPResponse` | `rag/core/mcp/` | 工具调用的入参 / 出参 |
| `LLMMCPParameterExtractor` | `rag/core/mcp/` | 用 LLM 从用户问题中提取工具参数 |
| `MCPParameterExtractor` | `rag/core/mcp/` | 参数提取器接口 |
| `IntentNode` | `rag/core/intent/` | 意图节点（含 `kind=MCP`、`mcpToolId`、`paramPromptTemplate` 字段） |
| `IntentKind` | `rag/enums/` | 意图类型枚举：KB / SYSTEM / MCP |
| `IntentResolver` | `rag/core/intent/` | 并行意图识别，按 KB / MCP 分组 |
| `RetrievalEngine` | `rag/core/retrieve/` | 检索总协调：KB 多通道检索 + MCP 工具并行调用 |
| `ContextFormatter` | `rag/core/prompt/` | 将 MCP 结果格式化为 Prompt 文本块 |

### mcp-server 模块（服务端）

| 类 | 路径 | 职责 |
|---|---|---|
| `MCPEndpoint` | `mcp/endpoint/` | 接收 `POST /mcp` 请求 |
| `MCPDispatcher` | `mcp/endpoint/` | 分发 `tools/call` 到具体执行器 |
| `WeatherMCPExecutor` | `mcp/executor/` | 天气查询工具实现 |
| `TicketMCPExecutor` | `mcp/executor/` | 工单查询工具实现 |
| `SalesMCPExecutor` | `mcp/executor/` | 销售数据查询工具实现 |
