# Phase 4: Module Notes

## MCP 工具调用模块

### 主流程校验

1. `GET /rag/v3/chat` 是用户发起 AI Agent/RAG 对话的 SSE 入口，Controller 创建 `SseEmitter` 后把请求交给 `RAGChatServiceImpl.streamChat(...)`。
2. 主流程进入服务层前会经过幂等和 `@ChatRateLimit`，由 Redis/Redisson 控制全局并发和排队，真正执行逻辑落到 `chatEntryExecutor`。
3. `RAGChatServiceImpl` 会先加载会话历史并追加用户消息，再做问题改写、子问题拆分和意图识别。
4. 意图识别后，`RetrievalEngine.retrieve(...)` 是 KB 检索和 MCP 工具调用的统一阶段：KB 意图走多通道检索，MCP 意图走工具调用。
5. 检索/工具结果会被合并为 `RetrievalContext`，再由 `RAGPromptService` 按 KB、MCP 或混合场景选择 Prompt 模板并构造模型消息。
6. 最终回答通过 `LLMService.streamChat(...)` 流式生成，`StreamChatEventHandler` 把增量内容发给前端，并在完成时把助手回答写入会话消息。
7. 这条链路已经足够支撑“简历中的 AI Agent/RAG 项目讲法”：可以讲清楚用户请求、会话记忆、意图路由、RAG 检索、MCP 工具调用、Prompt 组装、流式模型调用和结果落库。
8. 当前没有阻止进入模块阅读的主流程关键断点；仍需后续验证的是运行时数据能否稳定触发 KB + MCP 混合路径，以及 SSE 事件格式和事务边界。

### Files Inspected

#### 前序文档

- `docs/reading/00-reading-plan.md`：确认 Phase 4 模块阅读边界。
- `docs/reading/01-project-map.md`：确认项目模块和 MCP 独立服务位置。
- `docs/reading/02-startup-and-config.md`：确认 MCP Server 启动、主服务 MCP client 初始化、端口和配置。
- `docs/reading/03-main-flow.md`：确认 MCP 分支位于 `/rag/v3/chat` 主流程的检索阶段。
- `docs/reading/99-open-questions.md`：承接 Phase 2/3 未解决问题。

#### 主服务 MCP 注册和远程调用

- `bootstrap/src/main/resources/application.yaml`：确认默认 MCP server 配置为 `http://localhost:9099`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/HttpClientConfig.java`：确认 MCP HTTP 调用复用全局 `OkHttpClient`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`：确认 MCP 批量调用使用 `mcpBatchThreadPoolExecutor`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPTool.java`：确认主服务侧工具定义结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPRequest.java`：确认主服务侧工具调用请求结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPResponse.java`：确认主服务侧工具调用响应结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPToolExecutor.java`：确认主服务侧工具执行器接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPToolRegistry.java`：确认主服务侧工具注册表接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/DefaultMCPToolRegistry.java`：确认主服务启动时自动注册本地 `MCPToolExecutor` Bean。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/MCPClientProperties.java`：确认 `rag.mcp.servers` 配置绑定。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/MCPClientAutoConfiguration.java`：确认主服务启动时连接远程 MCP Server、发现工具并注册远程 executor。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/MCPClient.java`：确认 MCP client 抽象方法。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/HttpMCPClient.java`：确认 JSON-RPC `initialize`、`tools/list`、`tools/call` 的 HTTP 实现。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/RemoteMCPToolExecutor.java`：确认远程工具 executor 如何把主流程请求转成 MCP client 调用。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPParameterExtractor.java`：确认 MCP 参数抽取接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/LLMMCPParameterExtractor.java`：确认通过 LLM 从用户问题中抽取工具参数。
- `bootstrap/src/main/resources/prompt/mcp-parameter-extract.st`：确认 MCP 参数抽取 Prompt 规则。

#### 主流程中的 MCP 选择和结果回灌

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/DefaultIntentClassifier.java`：确认意图分类 Prompt 会把 MCP 节点标为 `type=MCP` 并带上 `toolId`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentResolver.java`：确认 MCP 意图从 `NodeScore` 中过滤出来，并要求节点类型为 MCP 且存在 `mcpToolId`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentNode.java`：确认意图节点包含 `kind`、`mcpToolId`、`promptSnippet`、`promptTemplate`、`paramPromptTemplate`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/NodeScore.java`：确认意图节点和分数组成 MCP 选择输入。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/IntentKind.java`：确认 `MCP` 类型编码。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/IntentNodeDO.java`：确认数据库意图节点表字段包含 `mcpToolId`、`kind`、`paramPromptTemplate`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`：确认 MCP 工具选择、参数抽取、并行调用和结果合并。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/DefaultContextFormatter.java`：确认 MCP 工具结果如何格式化为 LLM 上下文。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/RetrievalContext.java`：确认 MCP 上下文和 KB 上下文共同承载。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java`：确认 Prompt 构建阶段携带 MCP 上下文。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`：确认 MCP-only 和 MCP+KB 混合场景的 Prompt 组装。
- `bootstrap/src/main/resources/prompt/answer-chat-mcp.st`：确认 MCP-only 回答模板面向结构化动态数据。
- `bootstrap/src/main/resources/prompt/answer-chat-mcp-kb-mixed.st`：确认 MCP+KB 混合回答模板同时使用动态数据和文档内容。
- `bootstrap/src/main/resources/prompt/intent-classifier.st`：确认意图分类输出 JSON 数组，并按分类节点选择。

#### MCP Server 端点和代表性工具

- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/endpoint/MCPEndpoint.java`：确认 MCP Server 暴露 `POST /mcp`。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/endpoint/MCPDispatcher.java`：确认 JSON-RPC 方法分发、工具列表和工具调用返回格式。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/JsonRpcRequest.java`：确认 JSON-RPC 请求结构。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/JsonRpcResponse.java`：确认 JSON-RPC 响应结构。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/JsonRpcError.java`：确认 JSON-RPC 错误码。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/MCPToolSchema.java`：确认 `tools/list` 返回的工具 schema。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolExecutor.java`：确认服务端工具执行器接口。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolRegistry.java`：确认服务端工具注册表接口。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/DefaultMCPToolRegistry.java`：确认 MCP Server 启动时自动注册所有工具执行器 Bean。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolDefinition.java`：确认服务端工具定义结构。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolRequest.java`：确认服务端工具调用请求结构。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolResponse.java`：确认服务端工具调用响应结构。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/WeatherMCPExecutor.java`：作为代表性工具路径，确认一个工具如何声明参数并返回文本结果。

### Responsibility

MCP 工具调用模块负责把外部实时能力接入 RAG 主流程：

- MCP Server 端：发现 Spring 容器中的工具执行器，暴露 `initialize`、`tools/list`、`tools/call`。
- 主服务端：启动时连接 MCP Server，拉取工具 schema，包装成远程 `MCPToolExecutor` 并注册到主服务工具注册表。
- 对话运行时：意图识别选中 MCP 节点后，根据节点上的 `mcpToolId` 找到 executor，通过 LLM 抽取参数，远程调用工具，并把成功结果格式化为 LLM 上下文。

### Core Classes

- `MCPClientAutoConfiguration`：主服务远程工具发现和注册入口。
- `HttpMCPClient`：主服务 JSON-RPC over HTTP 客户端。
- `RemoteMCPToolExecutor`：主服务侧远程工具执行适配器。
- `DefaultMCPToolRegistry`：主服务和 MCP Server 各自的工具注册表实现。
- `RetrievalEngine`：`/rag/v3/chat` 主流程中真正选择并调用 MCP 工具的编排类。
- `LLMMCPParameterExtractor`：通过 LLM 将自然语言问题转换为工具参数。
- `MCPDispatcher`：MCP Server 端 JSON-RPC 方法分发器。
- `WeatherMCPExecutor`：本轮读取的代表性工具实现。
- `DefaultContextFormatter`：将 MCP 工具结果格式化成 Prompt 上下文。
- `RAGPromptService`：将 MCP 上下文放入最终 LLM 消息。

### Key Data Structures

- 主服务侧：
  - `MCPTool`：工具定义，包含 `toolId`、`description`、`parameters`、`requireUserId`、`mcpServerUrl`。
  - `MCPRequest`：工具调用请求，包含 `toolId`、`userId`、`conversationId`、`userQuestion`、`parameters`。
  - `MCPResponse`：工具调用响应，包含 `success`、`toolId`、`data`、`textResult`、`errorMessage`、`errorCode`、`costMs`。
  - `IntentNode`：意图节点，MCP 路由依赖 `kind=MCP` 和 `mcpToolId`。
  - `NodeScore`：意图节点和分数。
  - `RetrievalContext`：承载 `mcpContext`、`kbContext`、`intentChunks`。
- MCP Server 侧：
  - `MCPToolDefinition`：服务端工具定义。
  - `MCPToolRequest`：服务端工具请求。
  - `MCPToolResponse`：服务端工具响应。
  - `MCPToolSchema`：`tools/list` 对外返回的标准 schema。
  - `JsonRpcRequest` / `JsonRpcResponse` / `JsonRpcError`：JSON-RPC 协议结构。

### Flow Position

MCP 调用发生在 `/rag/v3/chat` 主流程的检索阶段。

位置是：

`RAGChatServiceImpl.streamChat(...)`
-> `queryRewriteService.rewriteWithSplit(...)`
-> `intentResolver.resolve(...)`
-> `retrievalEngine.retrieve(...)`
-> `RetrievalEngine.executeMcpAndMerge(...)`
-> `RetrievalEngine.executeMcpTools(...)`
-> `RemoteMCPToolExecutor.execute(...)`
-> `HttpMCPClient.callTool(...)`
-> MCP Server `POST /mcp`
-> `MCPDispatcher.handleToolsCall(...)`
-> 具体 `MCPToolExecutor.execute(...)`
-> 返回文本结果
-> `DefaultContextFormatter.formatMcpContext(...)`
-> `RAGPromptService.buildStructuredMessages(...)`
-> `LLMService.streamChat(...)`

### MCP 工具从注册到调用的完整链路

#### 1. MCP Server 注册工具

MCP Server 启动时，`mcp-server` 模块中的 `DefaultMCPToolRegistry` 自动注入 `List<MCPToolExecutor>`。

在 `@PostConstruct init()` 中：

- 如果没有工具执行器，记录日志后返回。
- 如果存在执行器，逐个调用 `register(executor)`。
- 注册表使用 `ConcurrentHashMap<String, MCPToolExecutor>` 保存，key 是 `toolId`。

本轮代表性工具是 `WeatherMCPExecutor`：

- 它是 Spring `@Component`。
- `getToolDefinition()` 返回 `toolId=weather_query`。
- 参数包括 `city`、`queryType`、`days`。
- `execute(...)` 从请求参数中读取城市、查询类型和天数，返回天气文本。
- 该工具当前使用内置城市列表和本地生成逻辑，不是外部天气 API。

#### 2. MCP Server 暴露工具发现和调用

`MCPEndpoint` 暴露 `POST /mcp`，请求体是 JSON-RPC。

`MCPDispatcher` 处理三个方法：

- `initialize`：返回协议版本、tools capability 和 serverInfo。
- `tools/list`：从服务端 registry 取出所有 `MCPToolDefinition`，转换为 `MCPToolSchema`，返回 `name`、`description`、`inputSchema`。
- `tools/call`：从 params 中读取 `name` 作为工具 ID，从 `arguments` 中读取参数，找到 executor 后执行。

`tools/call` 的成功响应结构中，结果放在：

- `result.content[0].type = text`
- `result.content[0].text = toolResponse.textResult`
- `result.isError = !toolResponse.success`

#### 3. 主服务启动时发现远程工具

主服务 `application.yaml` 中配置：

- `rag.mcp.servers[0].name = default`
- `rag.mcp.servers[0].url = http://localhost:9099`

`MCPClientAutoConfiguration` 在 `@PostConstruct init()` 中读取 `rag.mcp.servers`：

1. 为每个 server 创建 `HttpMCPClient`。
2. 调用 `initialize()`。
3. 初始化成功后调用 `listTools()`。
4. `HttpMCPClient` 把 MCP Server 返回的 `MCPToolSchema` 转为主服务侧 `MCPTool`。
5. 每个远程工具被包装成 `RemoteMCPToolExecutor`。
6. `RemoteMCPToolExecutor` 注册到主服务 `MCPToolRegistry`。

如果 MCP Server 连接失败、初始化失败或没有工具，当前代码会记录日志并跳过远程工具注册。

#### 4. 主流程中选择 MCP 工具

工具选择不是由 `tools/list` 临时决定，而是由意图树决定。

`DefaultIntentClassifier` 构造意图分类 Prompt 时，会列出叶子节点：

- `id`
- `path`
- `description`
- `type`
- 如果是 MCP 节点，还会附带 `toolId`

LLM 返回命中的意图节点 ID 和分数后，`IntentResolver` 过滤低于阈值的意图。

`RetrievalEngine.filterMCPIntents(...)` 再筛选：

- 分数大于等于 `INTENT_MIN_SCORE`。
- 节点不为空。
- `node.kind == IntentKind.MCP`。
- `node.mcpToolId` 不为空。

因此，MCP 工具是否被调用，取决于意图树中是否存在 MCP 节点，并且该节点的 `mcpToolId` 能匹配主服务 registry 中已注册的工具 ID。

#### 5. 主流程中构造 MCP 请求

`RetrievalEngine.buildMcpRequest(...)` 会：

1. 从 `IntentNode.getMcpToolId()` 取出 toolId。
2. 用 toolId 从主服务 `MCPToolRegistry` 查找 executor。
3. 从 executor 中取出 `MCPTool` 定义。
4. 读取意图节点上的 `paramPromptTemplate` 作为可选自定义参数抽取 Prompt。
5. 调用 `MCPParameterExtractor.extractParameters(question, tool, customParamPrompt)`。
6. 构造 `MCPRequest`，设置 `toolId`、`userQuestion`、`parameters`。

`LLMMCPParameterExtractor` 会把工具定义和用户问题发给 LLM，要求严格输出 JSON 对象，只保留工具定义中声明的参数，并补默认值。

如果 LLM 返回解析失败或调用异常，会回退为默认参数。

#### 6. 主流程中并行调用 MCP 工具

`RetrievalEngine.executeMcpTools(...)` 会把 MCP 请求列表提交到 `mcpBatchThreadPoolExecutor`，通过 `CompletableFuture.supplyAsync(...)` 并行执行。

单个工具调用由 `executeSingleMcpTool(...)` 完成：

- 如果主服务 registry 找不到 toolId，返回 `TOOL_NOT_FOUND`。
- 如果 executor 执行抛异常，返回 `EXECUTION_ERROR`。
- 如果执行成功，返回 executor 的 `MCPResponse`。

远程工具场景下，executor 是 `RemoteMCPToolExecutor`：

- 它调用 `mcpClient.callTool(toolId, parameters)`。
- `HttpMCPClient.callTool(...)` 发送 JSON-RPC `tools/call`。
- 如果服务端返回 `isError=true`，客户端记录 warning 并返回 `null`。
- `RemoteMCPToolExecutor` 收到 `null` 后返回 `REMOTE_CALL_FAILED`。

#### 7. MCP 结果进入 LLM 上下文

`RetrievalEngine.executeMcpAndMerge(...)` 拿到工具响应后：

- 如果没有任何成功响应，返回空字符串。
- 如果有成功响应，调用 `contextFormatter.formatMcpContext(responses, mcpIntents)`。

`DefaultContextFormatter.formatMcpContext(...)` 会：

1. 以 `mcpToolId` 把意图节点和工具响应对应起来。
2. 对每个成功工具结果拼接文本。
3. 如果意图节点有 `promptSnippet`，先加入 `#### 意图规则`。
4. 再加入 `#### 动态数据片段` 和工具文本结果。

随后：

- `RetrievalContext.mcpContext` 承载格式化后的 MCP 上下文。
- `PromptContext.mcpContext` 把它传给 Prompt 构建阶段。
- `RAGPromptService.buildStructuredMessages(...)` 会把 MCP 上下文作为 system message 加入，header 是 `## 动态数据片段`。
- 如果只有 MCP 上下文，走 `answer-chat-mcp.st`。
- 如果 MCP 和 KB 上下文都有，走 `answer-chat-mcp-kb-mixed.st`。

最终 LLM 的自然语言回答不是 MCP Server 直接返回给用户，而是 MCP 工具文本结果进入 Prompt 后，由最终 Chat 模型生成回答。

### Configuration And Dependencies

- MCP Server 端口：Phase 2 已确认是 `9099`。
- 主服务默认 MCP Server URL：`http://localhost:9099`。
- HTTP 客户端：主服务全局 `OkHttpClient`。
- OkHttp 超时：
  - connect timeout: 30 秒。
  - write timeout: 60 秒。
  - read timeout: 0。
  - call timeout: 0。
  - `retryOnConnectionFailure(true)`。
- MCP 批处理线程池：
  - Bean 名称：`mcpBatchThreadPoolExecutor`。
  - core size: `CPU_COUNT`。
  - max size: `CPU_COUNT << 1`。
  - 队列：`SynchronousQueue`。
  - 拒绝策略：`CallerRunsPolicy`。
  - 通过 TTL executor 包装。
- MCP 工具选择依赖意图树数据库字段：
  - `t_intent_node.kind`
  - `t_intent_node.mcp_tool_id`
  - `t_intent_node.param_prompt_template`
  - `t_intent_node.prompt_snippet`
  - `t_intent_node.prompt_template`
- MCP 参数抽取和最终回答依赖 LLM。

### Facts

- MCP 工具注册有两层：MCP Server 注册本服务工具，主服务启动时再通过 `tools/list` 把远程工具注册为 `RemoteMCPToolExecutor`。
- 主服务的远程工具发现只在 `MCPClientAutoConfiguration.@PostConstruct` 中看到，没有在本轮已读文件中看到定时刷新或失败重试注册。
- `/rag/v3/chat` 主流程中，MCP 调用发生在 `RetrievalEngine.retrieve(...)` 阶段。
- MCP 工具选择依赖意图识别结果中的 `IntentNode.kind == MCP` 和 `IntentNode.mcpToolId`。
- MCP 参数由 `LLMMCPParameterExtractor` 调用 LLM 从用户问题中提取，不是硬编码解析。
- MCP 工具调用通过 `mcpBatchThreadPoolExecutor` 并行执行。
- MCP Server 暴露的是 JSON-RPC over HTTP，HTTP 路径为 `POST /mcp`。
- `WeatherMCPExecutor` 是本轮确认的代表性工具，工具 ID 是 `weather_query`。
- MCP 工具结果会进入 `RetrievalContext.mcpContext`，再作为 `RAGPromptService` 构造的模型上下文，而不是直接绕过 LLM 返回给用户。
- 当前已读链路中，`MCPTool.requireUserId`、`MCPRequest.userId`、`MCPToolRequest.userId` 字段存在，但主流程构造 MCP 请求和 MCP Server dispatcher 构造工具请求时没有看到 userId 注入。

### Assumptions

- 如果意图树中的 MCP 节点配置了 `mcpToolId=weather_query`，并且用户问题被意图分类命中该 MCP 节点，则主流程会调用 `WeatherMCPExecutor`。这条链路在代码上成立，但本轮没有读取数据库初始化数据或运行接口验证。
- 当前 MCP Server 中的示例工具更像演示工具，不一定代表生产外部系统集成方式；该判断来自 `WeatherMCPExecutor` 使用内置数据生成天气结果。

### Open Questions

- 意图树默认数据是否已经配置 MCP 节点，以及 `mcp_tool_id` 是否能稳定匹配 `weather_query`、`sales_query`、`ticket_query` 等已注册工具，仍需读取初始化数据或运行验证。
- `IntentNodeDO.kind` 是 Integer，`IntentNode.kind` 是 `IntentKind` 枚举；本轮读到的 loader 使用 `BeanUtil.toBean(...)`，没有看到显式调用 `IntentKind.fromCode(...)`，需要后续确认枚举映射是否稳定。
- MCP Server 启动失败时，主服务启动阶段会跳过远程工具注册；本轮没有看到后续自动重连和重新注册机制，需要确认是否需要运维或代码层兜底。
- MCP 工具定义里有 `requireUserId`，请求结构也有 `userId`，但本轮主链路没有看到用户身份传递到 MCP Server；如果后续工具需要查个人数据，需要确认设计是否完整。
- `HttpMCPClient` 的 read timeout 和 call timeout 都是 0，单个远程 MCP 调用如果卡住，实际影响范围需要后续压测或补充超时设计确认。
- `DefaultContextFormatter.formatMcpContext(...)` 在有 MCP 意图时只分组成功响应；失败工具结果不会进入 LLM 上下文。是否要向用户暴露部分失败，需要后续判断产品预期。

### Next Reading Target

下一轮如果继续 Phase 4，建议二选一：

1. 读取 `RAG 检索模块`：把 KB 检索、pgvector、多通道检索、rerank 和 MCP 的关系讲清楚。
2. 读取 `流式任务和模型 fallback 模块`：把 SSE、取消、模型路由、首包探测、失败切换和线程池治理讲清楚。

如果目标是简历和面试讲法，建议下一轮读 `RAG 检索模块`。这样可以把“工具调用”与“知识库检索”两条能力合并成完整 Agent/RAG 架构叙事。

## Phase 4 Module 2: Knowledge Base Retrieval / RAG Retrieval

### Reading Scope

本轮只阅读 `/rag/v3/chat` 主流程中知识库检索直接相关的文件，没有继续深入 MCP 工具实现，也没有阅读知识库管理后台、上传、权限、用户体系等旁路功能。

### Files Read

#### 已存在阅读文档

- `docs/reading/00-reading-plan.md`：确认分阶段阅读协议和“事实 / 推测 / 开放问题”边界。
- `docs/reading/01-project-map.md`：确认项目模块结构和主服务位置。
- `docs/reading/02-startup-and-config.md`：复用 PostgreSQL、Redis、模型服务、MCP、Milvus 可选后端等启动结论。
- `docs/reading/03-main-flow.md`：确认 `/rag/v3/chat` 的主链路位置。
- `docs/reading/04-module-notes.md`：承接上一节 MCP 模块结论，避免重复展开 MCP。
- `docs/reading/99-open-questions.md`：确认已有未解问题，避免重复记录。

#### 主流程触发与上下文合并

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`：确认检索发生在查询改写、意图解析、歧义引导之后，并确认 `RetrievalContext` 如何进入 `PromptContext`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/RetrievalContext.java`：确认 KB 和 MCP 的统一承载结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java`：确认 Prompt 阶段接收的 KB/MCP 字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/constant/RAGConstant.java`：确认默认 `DEFAULT_TOP_K=10`、意图过滤阈值 `INTENT_MIN_SCORE=0.35`、最大意图数 `MAX_INTENT_COUNT=3`。

#### 查询改写、拆分、意图节点

- `QueryRewriteService.java`、`MultiQuestionRewriteService.java`、`RewriteResult.java`：确认问题改写、子问题拆分、失败兜底逻辑。
- `QueryTermMappingService.java`、`QueryTermMappingUtil.java`：确认改写前会做术语归一化，规则来自数据库并缓存到内存。
- `IntentResolver.java`、`IntentClassifier.java`、`DefaultIntentClassifier.java`：确认意图识别如何生成 `SubQuestionIntent` 和 `NodeScore`，以及只保留分数达标的意图。
- `IntentNode.java`、`NodeScore.java`、`IntentKind.java`、`IntentNodeDO.java`、`IntentTreeCacheManager.java`：确认 KB 节点字段、节点类型、collectionName、topK、promptSnippet、promptTemplate、Redis 意图树缓存。

#### KB 检索编排

- `RetrievalEngine.java`：确认 KB 检索触发、每个子问题并行处理、KB 与 MCP 合并到 `RetrievalContext` 的位置。
- `MultiChannelRetrievalEngine.java`：确认多通道并行召回和后置处理器链。
- `SearchChannel.java`、`SearchContext.java`、`SearchChannelResult.java`、`SearchChannelType.java`：确认通道抽象、检索上下文、通道结果结构和预留通道类型。
- `IntentDirectedSearchChannel.java`、`VectorGlobalSearchChannel.java`：确认意图定向检索和全局向量兜底检索。
- `AbstractParallelRetriever.java`、`IntentParallelRetriever.java`、`CollectionParallelRetriever.java`：确认 collection / intent 级并行检索方式。
- `SearchResultPostProcessor.java`、`DeduplicationPostProcessor.java`、`RerankPostProcessor.java`：确认去重和 rerank 处理链。
- `RetrieveRequest.java`、`RetrieverService.java`、`RetrievedChunk.java`：确认检索请求和命中结果的数据结构。

#### 向量检索、存储与模型服务

- `PgRetrieverService.java`、`MilvusRetrieverService.java`：确认 pgvector 与 Milvus 检索实现和条件装配。
- `PgVectorStoreService.java`、`MilvusVectorStoreService.java`：确认文档分块向量写入 pgvector / Milvus 的字段。
- `PgVectorStoreAdmin.java`、`MilvusVectorStoreAdmin.java`：确认 pgvector HNSW 索引与 Milvus collection/schema 创建逻辑。
- `RAGDefaultProperties.java`、`SearchChannelProperties.java`、`application.yaml`：确认默认向量后端、维度、通道阈值、embedding/rerank 模型配置。
- `EmbeddingService.java`、`RoutingEmbeddingService.java`：确认 query embedding 使用模型路由和 fallback。
- `RerankService.java`、`RoutingRerankService.java`、`BaiLianRerankClient.java`、`NoopRerankClient.java`：确认 rerank 调用、失败降级和 noop 兜底。
- `resources/database/schema_pg.sql`：确认 PostgreSQL 表结构、pgvector 扩展、HNSW 索引和知识库相关表。

#### Context 与 Prompt

- `DefaultContextFormatter.java`、`ContextFormatter.java`：确认 KB 命中片段如何格式化为 `kbContext`。
- `RAGPromptService.java`：确认 `kbContext`、`mcpContext` 如何作为 message 注入最终 LLM 请求。
- `answer-chat-kb.st`、`answer-chat-mcp-kb-mixed.st`：确认 KB-only 和 MCP+KB 混合场景的系统提示词约束。

### Why This Module Matters

这条链路是 `/rag/v3/chat` 中最能支撑简历表达的 RAG 核心能力：用户问题经过改写和意图识别后，系统可以按意图定向检索指定知识库，也可以在意图不明确时做全局向量兜底；召回结果经过去重和 rerank 后拼装为 Prompt 上下文，最后由 LLM 基于上下文生成回答。

### Retrieval Flow

#### 1. 触发

`RAGChatServiceImpl.streamChat(...)` 中，主流程顺序是：

1. `memoryService.loadAndAppend(...)` 加载并追加用户消息。
2. `queryRewriteService.rewriteWithSplit(question, history)` 做问题归一化、改写和子问题拆分。
3. `intentResolver.resolve(rewriteResult)` 对每个子问题做意图识别。
4. `guidanceService.detectAmbiguity(...)` 判断是否需要先让用户澄清。
5. 如果不是 system-only，则调用 `retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K)`。

因此 KB 检索不是 Controller 直接触发，而是在 Chat Service 的主流程中，在“改写 / 意图识别 / 歧义判断”之后触发。

如果 `RetrievalContext.isEmpty()` 为 true，主流程直接向 SSE 写出 `未检索到与问题相关的文档内容。` 并结束。

#### 2. 查询改写 / 拆分

`MultiQuestionRewriteService.rewriteWithSplit(...)` 先调用 `QueryTermMappingService.normalize(...)` 做术语归一化。归一化规则由 `QueryTermMappingService.loadMappings()` 在启动后从 `t_query_term_mapping` 中读取 `enabled=1` 的记录，排序后缓存在内存。

当 `rag.query-rewrite.enabled=true` 时，系统会加载 `prompt/user-question-rewrite.st`，调用 `LLMService.chat(...)`，要求模型返回 JSON，字段包括：

- `rewrite`
- `sub_questions`

解析失败或 LLM 调用异常时，会回退为归一化后的原问题，并把它作为唯一子问题。配置关闭时，则使用规则分隔符按 `?？。；;\n` 做兜底拆分。

#### 3. 意图识别与 KB 节点选择

`IntentResolver.resolve(...)` 会对每个子问题并行调用 `DefaultIntentClassifier.classifyTargets(...)`。

`DefaultIntentClassifier` 的意图树来源是：

1. 先从 Redis key `ragent:intent:tree` 读取。
2. Redis 不存在时，从 `t_intent_node` 读取 `deleted=0` 的节点。
3. 组装树和叶子节点后写回 Redis，缓存 7 天。

意图识别由 LLM 完成：系统把所有叶子节点的 `id`、`path`、`description`、`type`、`toolId`、`examples` 渲染进 Prompt，让 LLM 返回节点 ID 和 score。

`IntentResolver.classifyIntents(...)` 会过滤 `score >= 0.35` 的结果，并限制最多 3 个意图。进入检索阶段后，`RetrievalEngine.filterKbIntents(...)` 仍要求：

- score >= `INTENT_MIN_SCORE`，即 0.35。
- 节点不为空。
- `node.kind == null` 或 `node.kind == KB`。

`IntentDirectedSearchChannel` 还有自己的通道阈值，默认 `rag.search.channels.intent-directed.min-intent-score=0.4`。所以进入 `RetrievalEngine` 的 KB 意图阈值是 0.35，而真正启用意图定向检索时默认要求 KB 意图分数至少 0.4。

#### 4. Embedding / 向量召回

KB 检索由 `RetrievalEngine.retrieveAndRerank(...)` 交给 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels(...)`。

`MultiChannelRetrievalEngine` 会构建 `SearchContext`，其中主问题是当前子问题，然后筛选启用的 `SearchChannel` 并通过 `ragRetrievalThreadPoolExecutor` 并行执行。

本轮在 `retrieve/channel` 包中确认到两个实际通道实现：

- `IntentDirectedSearchChannel`
- `VectorGlobalSearchChannel`

`SearchChannelType` 中还有 `KEYWORD_ES` 和 `HYBRID` 枚举值，但本轮已读通道实现中没有看到对应实现类。

##### 意图定向检索

`IntentDirectedSearchChannel` 在存在 KB 意图时启用，优先级是 1。它提取 `node.isKB()` 且 score >= 0.4 的意图，然后调用 `IntentParallelRetriever.executeParallelRetrieval(...)`。

每个 KB 意图会形成一个 `RetrieveRequest`：

- `collectionName = node.collectionName`
- `query = 当前子问题`
- `topK = 节点 topK 或 fallbackTopK，再乘以 intent-directed 的 topKMultiplier`

默认情况下：

- 主流程传入 `DEFAULT_TOP_K=10`。
- `RetrievalEngine.resolveSubQuestionTopK(...)` 如果 KB 节点配置了 `topK`，会取最大节点 topK。
- `IntentParallelRetriever` 再乘以 `rag.search.channels.intent-directed.top-k-multiplier=2`。

##### 全局向量检索

`VectorGlobalSearchChannel` 优先级是 10，是兜底通道。启用条件是：

- 没有任何意图识别结果。
- 或所有意图的最高 score < `rag.search.channels.vector-global.confidence-threshold`，默认 0.6。

全局检索会从 `t_knowledge_base` 读取所有 `deleted=0` 的 `collectionName`，然后通过 `CollectionParallelRetriever` 在所有 collection 中并行检索。默认会把 topK 乘以 `rag.search.channels.vector-global.top-k-multiplier=3`。

本轮已读代码中，未看到全局检索按用户、组织、知识库权限、文档 enabled 状态过滤；它只按知识库 `deleted=0` 取 collection。

##### Query Embedding

无论走 pgvector 还是 Milvus，检索前都会调用 `EmbeddingService.embed(query)` 生成 query embedding。

当前主实现是 `RoutingEmbeddingService`，通过 `ModelRoutingExecutor.executeWithFallback(...)` 选择 embedding 模型候选。当前配置中：

- 默认 embedding model 是 `qwen-emb-8b`。
- provider 是 `siliconflow`。
- model 是 `Qwen/Qwen3-Embedding-8B`。
- dimension 使用 `${rag.default.dimension}`，即 1536。
- fallback 候选是 Ollama 本地 `qwen3-embedding:8b-fp16`。

#### 5. pgvector / Milvus 实际分工

当前 `application.yaml` 明确配置：

```yaml
rag:
  vector:
    type: pg
```

所以当前默认运行链路使用 PostgreSQL + pgvector。

`PgRetrieverService` 只有在 `rag.vector.type=pg` 时装配。它的查询逻辑是：

1. 调用 embedding 服务生成 query 向量。
2. 对 query 向量做归一化。
3. 执行 `SET hnsw.ef_search = 200`。
4. 查询 `t_knowledge_vector`：
   - 用 `metadata->>'collection_name' = ?` 限定 collection。
   - 用 `embedding <=> ?::vector` 做 cosine 距离排序。
   - 用 `1 - distance` 作为 score。
   - 按 `LIMIT topK` 返回。

`MilvusRetrieverService` 只有在 `rag.vector.type=milvus` 时装配，并且带 `matchIfMissing=true`。也就是说，如果配置缺失，Milvus 实现会成为默认候选；但当前配置没有缺失，当前默认是 `pg`。

Milvus 检索逻辑是：

1. 调用 embedding 服务生成 query 向量。
2. 归一化。
3. 用 `MilvusClientV2.search(...)` 搜索 collection。
4. `annsField=embedding`。
5. `outputFields=id,content,metadata`。
6. `metric_type` 来自 `rag.default.metric-type`，当前是 `COSINE`。
7. search params 中 `ef=128`。

本轮结论：检索后端是按 `rag.vector.type` 二选一切换，不是 PostgreSQL 和 Milvus 同时召回。当前默认实际使用 PostgreSQL + pgvector；Milvus 是可切换实现。

#### 6. 过滤 / 排序 / Rerank

多通道检索返回 `SearchChannelResult` 后，`MultiChannelRetrievalEngine.executePostProcessors(...)` 按 `order` 顺序执行后置处理器。

当前已读到两个后置处理器：

1. `DeduplicationPostProcessor`，order=1。
2. `RerankPostProcessor`，order=10。

`DeduplicationPostProcessor` 会按通道优先级处理结果：

- `INTENT_DIRECTED` 优先级最高。
- `KEYWORD_ES` 次之。
- `VECTOR_GLOBAL` 最低。

去重 key 优先用 `RetrievedChunk.id`，如果 id 为空，用文本 hash。重复 chunk 保留 score 更高的版本。

`RerankPostProcessor` 始终启用。如果 chunks 为空，会跳过。否则调用：

```java
rerankService.rerank(context.getMainQuestion(), chunks, context.getTopK())
```

当前主实现 `RoutingRerankService` 会通过模型路由选择 rerank 模型。当前配置：

- 默认 rerank model 是 `qwen3-rerank`。
- provider 是 `bailian`。
- fallback 是 `rerank-noop`。

`BaiLianRerankClient` 会先按 chunk id 去重。如果候选数量小于等于 topN，则直接返回，不发起远程 rerank 调用；否则调用百炼 rerank 接口，取返回的 `relevance_score` 作为新的 chunk score。`NoopRerankClient` 只截断 topN，不做真实重排。

本轮未看到向量检索后的 score threshold 过滤。`MilvusRetrieverService` 中有 TODO 注释提到是否要限制低分数据，例如 0.65，但当前没有实现。`RAGConstant.SCORE_MARGIN_RATIO` 存在，但本轮已读检索链路没有看到它被用于过滤。

#### 7. Context 拼装

`RetrievalEngine.retrieveAndRerank(...)` 拿到最终 chunks 后，会构造 `intentChunks`。

如果有 KB 意图，它会把同一批 chunks 分配给每一个 KB 意图节点 ID。源码注释说明，多通道检索返回的 chunks 无法精确对应到某个意图节点，所以把所有 chunks 分配给每个意图节点。

如果没有 KB 意图，则使用特殊 key：

```java
MULTI_CHANNEL_KEY = "multi_channel"
```

然后调用：

```java
contextFormatter.formatKbContext(kbIntents, intentChunks, topK)
```

`DefaultContextFormatter` 的 KB 格式化规则是：

- 没有 KB 意图：直接把 chunks 格式化为 `#### 知识库片段`。
- 单个 KB 意图：如果节点有 `promptSnippet`，先加入 `#### 回答规则`，再加入 `#### 知识库片段`。
- 多个 KB 意图：合并去重后的 `promptSnippet`，再合并 chunks 并限制 topK。

最终 KB 文本会进入 `RetrievalContext.kbContext`。

#### 8. Prompt 注入

`RAGChatServiceImpl.streamLLMResponse(...)` 会把 `RetrievalContext` 转成 `PromptContext`：

- `mcpContext = ctx.getMcpContext()`
- `kbContext = ctx.getKbContext()`
- `mcpIntents = intentGroup.mcpIntents()`
- `kbIntents = intentGroup.kbIntents()`
- `intentChunks = ctx.getIntentChunks()`

`RAGPromptService.buildStructuredMessages(...)` 再构造最终消息：

1. 先加入 system prompt。
2. 如果有 MCP 上下文，把 `## 动态数据片段` 作为 system message 加入。
3. 如果有 KB 上下文，把 `## 文档内容` 作为 user message 加入。
4. 加入 history。
5. 多子问题时，把子问题编号后作为最终 user message；否则加入改写后的问题。

模板选择规则：

- 只有 KB：使用 `answer-chat-kb.st`，但单意图且节点有 `promptTemplate` 时可以用节点模板。
- 只有 MCP：使用 `answer-chat-mcp.st`，单 MCP 意图且节点有 `promptTemplate` 时可以用节点模板。
- MCP + KB 混合：使用 `answer-chat-mcp-kb-mixed.st`。

`answer-chat-kb.st` 明确要求模型只基于 `【文档内容】` 回答；`answer-chat-mcp-kb-mixed.st` 要求只基于动态数据片段与文档内容回答，并禁止暴露 MCP、KB、检索结果等内部术语。

### Where State Is Stored

- 知识库元数据：PostgreSQL `t_knowledge_base`，包括 `name`、`embedding_model`、`collection_name`、`created_by`、`deleted`。
- 文档元数据：PostgreSQL `t_knowledge_document`，包括 `kb_id`、`doc_name`、`enabled`、`file_url`、`file_type`、`process_mode`、`status`、`chunk_strategy`、`deleted`。
- 文档 chunk 文本：PostgreSQL `t_knowledge_chunk`，包括 `kb_id`、`doc_id`、`chunk_index`、`content`、`content_hash`、`enabled`、`deleted`。
- pgvector 向量：PostgreSQL `t_knowledge_vector`，字段为 `id`、`content`、`metadata JSONB`、`embedding vector(1536)`，并有 HNSW 索引。
- pgvector metadata：`PgVectorStoreService` 写入 `collection_name`、`doc_id`、`chunk_index`，并合并 chunk 自带 metadata。
- Milvus 向量：Milvus collection 中字段为 `id`、`content`、`metadata`、`embedding`。
- 意图树：PostgreSQL `t_intent_node` 为源数据，Redis key `ragent:intent:tree` 为缓存。
- 查询术语映射：PostgreSQL `t_query_term_mapping` 为源数据，`QueryTermMappingService` 内存缓存为运行时状态。
- 检索结果：`RetrievedChunk`、`SearchChannelResult`、`RetrievalContext` 都是内存对象，不是持久化结果。

### KB And MCP Merge Point

KB 结果和 MCP 结果在 `RetrievalEngine.retrieve(...)` 中合并。

每个子问题会生成一个 `SubQuestionContext`：

- `question`
- `kbContext`
- `mcpContext`
- `intentChunks`

最后 `RetrievalEngine` 把所有子问题的 KB context 追加到一个 `kbBuilder`，把所有 MCP context 追加到一个 `mcpBuilder`，并把 `intentChunks` 合并成 `mergedIntentChunks`，返回统一的 `RetrievalContext`。

因此 KB 和 MCP 并不是在向量召回阶段合并，而是在检索编排层合并为同一个 `RetrievalContext`，再由 `RAGPromptService` 注入最终 Prompt。

### Verified Facts

- `/rag/v3/chat` 的 KB 检索入口是 `RAGChatServiceImpl.streamChat(...) -> RetrievalEngine.retrieve(...)`。
- 查询改写默认启用，先做术语归一化，再调用 LLM 输出 rewrite 和 sub_questions；异常时回退到归一化问题。
- 意图树优先从 Redis `ragent:intent:tree` 读取，缺失时从 `t_intent_node` 加载并回写 Redis。
- KB 意图节点通过 `IntentNode.kind == KB` 或 `kind == null` 判断。
- 默认全局 TopK 是 10。
- 意图定向检索和全局向量检索是当前已读到的两个实际 SearchChannel。
- 意图定向检索按意图节点的 `collectionName` 查指定知识库。
- 全局向量检索从 `t_knowledge_base` 获取所有未删除 knowledge base 的 collectionName。
- 当前默认向量后端是 `rag.vector.type=pg`，即 PostgreSQL + pgvector。
- Milvus 是可切换后端，`rag.vector.type=milvus` 时启用；如果配置缺失，Milvus 实现由于 `matchIfMissing=true` 可能成为默认。
- pgvector 检索直接查询 `t_knowledge_vector`，按 `metadata->>'collection_name'` 过滤，没有 join `t_knowledge_document` 或 `t_knowledge_chunk`。
- 本轮已读检索路径中没有看到用户级、组织级、知识库级权限过滤。
- 本轮已读检索路径中没有看到文档 `enabled` 或 chunk `enabled` 的运行时过滤。
- 多通道结果会先去重，再经过 rerank。
- 当前没有看到向量召回 score threshold 过滤。
- KB context 会作为 user message 以 `## 文档内容` 注入最终 LLM 请求。
- MCP context 会作为 system message 以 `## 动态数据片段` 注入最终 LLM 请求。

### Assumptions

- 如果文档或 chunk 被禁用，检索是否仍返回，取决于写入向量表后的同步删除或更新机制是否正确；本轮没有阅读知识库管理后台和索引构建全流程，所以这里不能写成已验证事实。
- 如果意图节点的 `collectionName` 为空，意图定向检索会构造空 collectionName 的 `RetrieveRequest`；实际运行结果取决于 retriever 对空 collection 的处理和数据情况，本轮未运行验证。
- Milvus 和 pgvector 的数据一致性需要依赖索引构建 / 文档更新链路；本轮只读检索链路，没有验证两套后端的数据同步策略。

### Open Questions

- `DefaultIntentClassifier.loadIntentTreeFromDB()` 查询 `t_intent_node` 时只过滤 `deleted=0`，本轮没有看到 `enabled=1` 过滤；禁用意图节点是否仍会参与识别，需要确认。
- `IntentNodeDO.kind` 是 Integer，`IntentNode.kind` 是 `IntentKind` 枚举；当前 loader 仍使用 `BeanUtil.toBean(...)`，需要继续确认枚举映射是否稳定。
- `RetrieveRequest.metadataFilters` 字段存在，但 pgvector 和 Milvus 当前检索实现未使用该字段；是否是预留设计，需要确认。
- pgvector 检索不 join 文档表和 chunk 表，无法在检索时按文档 enabled/deleted、chunk enabled/deleted 二次过滤；是否完全依赖向量表同步删除，需要确认。
- 当前没有 score threshold；低相关 chunk 可能进入 rerank 和 Prompt。是否应启用 `SCORE_MARGIN_RATIO` 或显式阈值，需要后续确认。
- `RAGConstant` 中存在 `SEARCH_TOP_K_MULTIPLIER`、`MIN_SEARCH_TOP_K`、`RERANK_LIMIT_MULTIPLIER`、`SCORE_MARGIN_RATIO`，本轮已读主检索链路没有看到这些常量被使用；需要确认是否为旧逻辑遗留。
- `RetrievalEngine.retrieveAndRerank(...)` 会把同一批 chunks 分配给每个 KB 意图节点，多意图下可能造成来源归属不精确；是否影响块级引用和面试讲法，需要后续评估。
- `PgRetrieverService` 每次检索执行 `SET hnsw.ef_search = 200`，在连接池复用下的作用范围和性能影响需要确认。
- 全局检索会并行查所有未删除 collection，知识库数量增长后延迟和模型 embedding/rerank 成本需要压测确认。

### Risks Worth Later Deep Dive

- 异常处理：检索通道、单 collection 检索、单 intent 检索异常都会被吞掉并返回空列表，用户最终可能只看到“未检索到”，需要结合 trace 才能定位。
- 空召回：KB 和 MCP 都为空时直接返回固定文案，不进入 LLM。
- 低相关性：当前没有显式 score threshold，依赖 rerank 截断 topN。
- 性能：一次请求可能经过改写 LLM、意图识别 LLM、query embedding、多 collection 并行检索、rerank、最终 LLM，多模型调用会放大延迟。
- 配置：`rag.vector.type` 必须明确；如果误删配置，Milvus 实现可能因 `matchIfMissing=true` 被装配。
- 权限：当前检索链路未见用户级权限过滤，企业知识库场景需要确认访问控制设计。

### Next Reading Target

下一轮建议读 `流式任务和模型 fallback 模块`，重点是：

- SSE 事件如何发送。
- `StreamTaskManager` 如何取消正在进行的 LLM 请求。
- `LLMService`、`ModelRoutingExecutor` 如何做模型 fallback。
- 多次模型调用失败时，最终用户会看到什么。
- Trace 如何串起改写、意图、检索、rerank、最终回答。

这能把“RAG 检索 + MCP 工具调用”进一步连接到“可观测、可取消、可降级”的工程亮点。

## Phase 4 Module 3: LLM 调用 + SSE 流式响应 + 消息落库

### Reading Scope

本轮只沿 `/rag/v3/chat` 主链路继续阅读 Prompt 之后的模型调用、SSE 流式事件、取消机制、并发控制和会话消息持久化。没有继续深入 MCP 或 KB 检索内部实现，没有阅读模型管理后台，也没有阅读无关用户、权限、文件上传模块。

### Files Read

#### 前序文档

- `docs/reading/00-reading-plan.md`：确认 Phase 4 模块阅读协议和事实/推测边界。
- `docs/reading/01-project-map.md`：确认模块位置和主服务结构。
- `docs/reading/02-startup-and-config.md`：复用模型 provider、OkHttp、线程池、Redis、PostgreSQL 配置结论。
- `docs/reading/03-main-flow.md`：确认 `/rag/v3/chat` 的主链路和已知断点。
- `docs/reading/04-module-notes.md`：承接 MCP 与 RAG Retrieval 两个模块结论，避免重复展开。
- `docs/reading/99-open-questions.md`：确认已有开放问题，避免重复记录。

#### 主入口、Prompt 构造和请求对象

- `RAGChatController.java`：确认 `GET /rag/v3/chat` 返回 `SseEmitter(0L)`，停止接口是 `POST /rag/v3/stop`。
- `RAGChatServiceImpl.java`：确认 Prompt 构造、`ChatRequest` 生成、`LLMService.streamChat(...)` 调用和取消句柄绑定。
- `RAGPromptService.java`：确认系统提示词、KB context、MCP context、历史消息和最终用户问题如何组成最终 messages。
- `PromptContext.java`：确认 Prompt 阶段承载 `question`、`mcpContext`、`kbContext`、意图和 chunks。
- `ChatRequest.java`、`ChatMessage.java`：确认模型请求结构和 system/user/assistant 三类角色。

#### 模型路由、Provider 和 HTTP 客户端

- `LLMService.java`：确认同步和流式模型调用统一接口。
- `RoutingLLMService.java`：确认 chat/stream 的模型候选选择、fallback、首包探测和最终失败处理。
- `ModelSelector.java`：确认默认模型、deepThinking 模型、priority 排序和首选模型提升逻辑。
- `ModelRoutingExecutor.java`、`ModelHealthStore.java`、`ModelTarget.java`：确认同步调用 fallback 和模型健康熔断状态。
- `AIModelProperties.java`、`ModelProvider.java`、`ModelCapability.java`：确认 `ai.*` 配置绑定和 provider/capability 枚举。
- `BaiLianChatClient.java`、`SiliconFlowChatClient.java`、`OllamaChatClient.java`：确认三个 Chat Provider 的同步/流式 HTTP 请求和响应解析。
- `OpenAIStyleSseParser.java`：确认 OpenAI 风格 `data:` SSE 行解析、`[DONE]` 和 `reasoning_content` 处理。
- `FirstPacketAwaiter.java`：确认流式首包成功、错误、超时、无内容四类状态。
- `StreamAsyncExecutor.java`、`StreamCancellationHandle.java`、`StreamCancellationHandles.java`：确认模型流式任务提交、线程池拒绝处理和 OkHttp cancel。
- `ModelUrlResolver.java`、`ModelClientException.java`、`ModelClientErrorType.java`：确认 Provider URL 解析和模型异常分类。
- `HttpClientConfig.java`：确认全局 OkHttp 超时和 retry 配置。
- `application.yaml`：确认当前默认 chat 模型、候选 provider、流式分块大小、限流和记忆配置。

#### SSE、取消、限流和线程池

- `StreamCallbackFactory.java`、`StreamChatEventHandler.java`、`StreamChatHandlerParams.java`：确认 SSE 回调创建、增量事件、完成事件、错误处理和助手消息保存。
- `SSEEventType.java`、`MessageDelta.java`、`MetaPayload.java`、`CompletionPayload.java`：确认 SSE 事件名和事件 payload。
- `SseEmitterSender.java`：确认实际 `SseEmitter.event().name(...).data(...)` 发送方式，以及异常关闭方式。
- `StreamTaskManager.java`：确认取消任务的本地缓存、Redis cancel key、Redis topic 和取消时保存部分回答。
- `ChatRateLimitAspect.java`：确认 `@ChatRateLimit` 如何生成 conversationId/taskId、进入队列并记录 trace。
- `ChatQueueLimiter.java`：确认 Redis/Redisson 全局并发、排队、超时拒绝、拒绝消息落库和 reject/done 事件。
- `ThreadPoolExecutorConfig.java`、`RAGRateLimitProperties.java`：确认 `chatEntryExecutor`、`modelStreamExecutor`、`memorySummaryThreadPoolExecutor` 和全局限流参数。

#### 会话、消息和摘要持久化

- `ConversationMemoryService.java`、`DefaultConversationMemoryService.java`：确认加载历史、追加消息和摘要压缩触发点。
- `ConversationMemoryStore.java`、`JdbcConversationMemoryStore.java`：确认历史消息读取、用户消息追加、会话创建/更新时间更新。
- `ConversationMemorySummaryService.java`、`JdbcConversationMemorySummaryService.java`：确认助手消息后异步摘要压缩、Redisson 锁和摘要落库。
- `ConversationMessageService.java`、`ConversationMessageServiceImpl.java`：确认消息和摘要写入 Mapper。
- `ConversationService.java`、`ConversationServiceImpl.java`：确认会话创建、更新、标题生成和删除事务边界。
- `ConversationGroupService.java`、`ConversationGroupServiceImpl.java`：确认会话、消息、摘要查询方法。
- `ConversationDO.java`、`ConversationMessageDO.java`、`ConversationSummaryDO.java`：确认会话、消息、摘要表映射。
- `ConversationMapper.java`、`ConversationMessageMapper.java`、`ConversationSummaryMapper.java`：确认 MyBatis Plus `BaseMapper` 持久化。
- `ConversationCreateRequest.java`、`ConversationMessageBO.java`、`ConversationSummaryBO.java`、`ConversationMessageVO.java`：确认服务层数据结构。
- `resources/database/schema_pg.sql`：确认 `t_conversation`、`t_message`、`t_conversation_summary` 表结构和索引。

### Responsibility

本模块负责 `/rag/v3/chat` 主流程中最后的工程闭环：

- 将 `RetrievalContext` 转成最终 LLM messages。
- 根据配置选择 Chat 模型和 Provider。
- 通过 HTTP 调用模型服务，并解析流式响应。
- 将模型增量转成前端可消费的 SSE 事件。
- 支持停止生成、排队拒绝、线程池隔离和模型 fallback。
- 在正常完成或取消时把助手消息写入 PostgreSQL。
- 在助手消息后异步触发会话摘要压缩。

### Flow: Prompt 构造 -> 模型选择 -> LLM 请求 -> 流式事件处理 -> SSE 返回 -> 助手消息落库

#### 1. Prompt 构造

`RAGChatServiceImpl.streamLLMResponse(...)` 把检索结果和意图结果构造成 `PromptContext`：

- `question`：改写后的问题。
- `mcpContext`：MCP 工具返回的动态数据片段。
- `kbContext`：知识库检索拼装出的文档内容。
- `mcpIntents`：MCP 意图节点列表。
- `kbIntents`：KB 意图节点列表。
- `intentChunks`：意图 ID 到命中 chunk 的映射。

然后调用：

```java
promptBuilder.buildStructuredMessages(promptContext, history, rewrittenQuestion, subQuestions)
```

`RAGPromptService.buildStructuredMessages(...)` 构造最终 messages 的顺序是：

1. system prompt：根据场景选择 KB-only、MCP-only 或 MCP+KB 混合模板。
2. MCP context：如果存在，以 system message 加入，header 是 `## 动态数据片段`。
3. KB context：如果存在，以 user message 加入，header 是 `## 文档内容`。
4. history：加入会话历史消息，包含 user/assistant 历史；如果有摘要，摘要会作为 system message 预先插入 history。
5. 最终 user message：多子问题时加入编号后的子问题列表；单问题时加入改写后的问题。

`ChatRequest` 参数：

- `messages`：上述完整消息列表。
- `thinking`：来自请求参数 `deepThinking`。
- `temperature`：如果有 MCP context 为 0.3，否则为 0。
- `topP`：如果有 MCP context 为 0.8，否则为 1。

#### 2. 模型选择

最终流式调用入口是：

```java
llmService.streamChat(chatRequest, callback)
```

实际实现是 `RoutingLLMService.streamChat(...)`。它调用 `ModelSelector.selectChatCandidates(request.getThinking())` 选择 Chat 候选模型。

当前配置中：

- `ai.chat.default-model = qwen3-max`
- `ai.chat.deep-thinking-model = qwen3-max`
- `qwen3-max` provider 是 `bailian`，model 是 `qwen3-max`。
- 其他候选包括：
  - `glm-4.7`：provider `siliconflow`，支持 thinking，priority 0。
  - `qwen-plus`：provider `bailian`，priority 1。
  - `qwen3-local`：provider `ollama`，priority 2。

`ModelSelector` 会先按 priority 排序，再把 default/deepThinking 指定的首选模型提升到第一位。因此普通模式和 deepThinking 模式都会先尝试 `qwen3-max`。deepThinking 模式还会过滤掉不支持 thinking 的候选。

Provider 支持来自 `ModelProvider`：

- `ollama`
- `bailian`
- `siliconflow`
- `noop`，当前用于 rerank，不是 ChatClient。

`ModelHealthStore` 维护模型健康状态：连续失败达到 `ai.selection.failure-threshold` 后进入 OPEN 状态，持续 `ai.selection.open-duration-ms`，之后进入 HALF_OPEN 探测。当前配置是失败阈值 2、OPEN 30000ms。

#### 3. LLM 请求

`RoutingLLMService.streamChat(...)` 对候选模型逐个尝试：

1. 根据 provider 找到 `ChatClient`。
2. 用 `FirstPacketAwaiter` 和 `ProbeBufferingCallback` 做首包探测。
3. 调用 `client.streamChat(...)`。
4. 等待首包，最长 60 秒。
5. 如果首包成功，提交缓存事件并返回取消句柄。
6. 如果启动失败、首包错误、首包超时或无内容完成，则取消当前 handle，标记失败，并尝试下一个模型。
7. 所有模型失败后，调用 `callback.onError(...)`，并抛出 `RemoteException`。

三个 ChatClient 都复用全局 `OkHttpClient`。

##### 百炼

`BaiLianChatClient`：

- provider id：`bailian`。
- URL：provider base URL + chat endpoint。
- 当前配置 endpoint：`/compatible-mode/v1/chat/completions`。
- 请求体使用 OpenAI 风格：
  - `model`
  - `stream=true`
  - `messages`
  - `temperature`
  - `top_p`
  - `top_k`
  - `max_tokens`
  - thinking 流式场景下加 `enable_thinking=true`
- header 包含 `Authorization: Bearer ${BAILIAN_API_KEY}`。
- 流式响应按行读取，交给 `OpenAIStyleSseParser` 解析。
- 如果有 reasoning，回调 `onThinking(...)`。
- 如果有 content，回调 `onContent(...)`。
- 遇到完成事件时回调 `onComplete()`。

##### SiliconFlow

`SiliconFlowChatClient`：

- provider id：`siliconflow`。
- 当前配置 endpoint：`/v1/chat/completions`。
- 请求体也是 OpenAI 风格。
- header 包含 `Authorization: Bearer ${SILICONFLOW_API_KEY}`。
- 流式响应同样用 `OpenAIStyleSseParser` 解析。
- 只要 `request.thinking=true`，请求体会加 `enable_thinking=true`。

##### Ollama

`OllamaChatClient`：

- provider id：`ollama`。
- 当前配置 endpoint：`/api/chat`。
- 请求体包括 `model`、`stream`、`messages`，以及 temperature/top_p/top_k/num_predict。
- 不需要 API Key。
- 流式响应不是 OpenAI SSE，而是逐行 JSON；遇到 `done=true` 时回调 `onComplete()`，否则从 `message.content` 取增量内容并回调 `onContent(...)`。

#### 4. 流式事件处理

模型客户端不会直接操作 `SseEmitter`，而是回调 `StreamCallback`。

主流程中 `StreamCallbackFactory.createChatEventHandler(...)` 创建的是 `StreamChatEventHandler`。初始化时它会：

1. 包装 `SseEmitterSender`。
2. 发送 `meta` 事件，payload 是 `MetaPayload(conversationId, taskId)`。
3. 调用 `taskManager.register(taskId, sender, onCancelSupplier)` 注册任务。

`StreamChatEventHandler` 对模型回调的处理：

- `onThinking(chunk)`：如果任务未取消且 chunk 非空，按 `type=think` 发送 `message` 事件。
- `onContent(chunk)`：如果任务未取消且 chunk 非空，追加到 `answer`，并按 `type=response` 发送 `message` 事件。
- `onComplete()`：如果任务未取消，把完整 `answer` 作为 assistant 消息落库，发送 `finish` 和 `done`，注销任务并关闭 SSE。
- `onError(Throwable)`：如果任务未取消，注销任务并调用 `sender.fail(t)`，最终是 `SseEmitter.completeWithError(t)`；本轮没有看到命名的 `error` SSE 事件。

增量发送使用 `sendChunked(...)`，会按 `ai.stream.message-chunk-size` 切分 Unicode code point。当前配置为 1，所以每个 `message` 事件通常只带 1 个 code point 的 delta。

#### 5. SSE 返回事件格式

`SseEmitterSender.sendEvent(eventName, data)` 使用：

```java
SseEmitter.event().name(eventName).data(data)
```

本轮确认的事件名来自 `SSEEventType`：

- `meta`
- `message`
- `finish`
- `done`
- `cancel`
- `reject`

具体 payload：

- `meta`：`MetaPayload(conversationId, taskId)`。
- `message`：`MessageDelta(type, delta)`，其中 type 是：
  - `response`：最终回答内容。
  - `think`：模型思考内容。
- `finish`：`CompletionPayload(messageId, title)`。
- `done`：字符串 `"[DONE]"`。
- `cancel`：`CompletionPayload(messageId, title)`。
- `reject`：`MessageDelta("response", "系统繁忙，请稍后再试")`。

普通模型错误或服务端异常时，当前已读代码没有发送命名 `error` 事件，而是调用 `SseEmitter.completeWithError(...)`。

#### 6. 助手消息落库

用户消息在进入主流程时保存：

```java
memoryService.loadAndAppend(conversationId, userId, ChatMessage.user(question))
```

`ConversationMemoryService.loadAndAppend(...)` 先 `load(...)` 再 `append(...)`。`JdbcConversationMemoryStore.append(...)` 写入 `t_message` 后，如果角色是 USER，还会调用 `ConversationService.createOrUpdate(...)` 创建或更新 `t_conversation`。

助手消息有三种已验证路径：

1. 正常完成：`StreamChatEventHandler.onComplete()` 把累积的 `answer` 写成 assistant 消息，并把 messageId 放进 `finish` 事件。
2. 用户取消：`StreamTaskManager.cancelLocal(...)` 调用 `StreamChatEventHandler` 注册的 `onCancelSupplier`；如果当前已累积 answer 非空，则写入 assistant 消息，并发送 `cancel` 和 `done`。
3. 排队超时拒绝：`ChatQueueLimiter.recordRejectedConversation(...)` 会写入用户问题和一条 assistant 消息，内容是 `系统繁忙，请稍后再试`，然后发送 `reject`、`finish`、`done`。

普通错误路径：

- `StreamChatEventHandler.onError(...)` 只注销任务并 `completeWithError`。
- 本轮已读代码中，普通错误不会把已累积的 answer 保存为 assistant 消息。

摘要压缩：

- `DefaultConversationMemoryService.append(...)` 每次写入后都会调用 `summaryService.compressIfNeeded(...)`。
- `JdbcConversationMemorySummaryService.compressIfNeeded(...)` 只有在 `summaryEnabled=true` 且消息角色是 ASSISTANT 时才异步压缩。
- 当前配置 `summary-enabled: true`，`summary-start-turns: 5`，`history-keep-turns: 4`。
- 摘要任务使用 `memorySummaryThreadPoolExecutor`。
- 摘要写入 `t_conversation_summary`，并用 Redisson lock `ragent:memory:summary:lock:{userId}:{conversationId}` 避免并发压缩。

### Core Classes And Methods

- `RAGChatController.chat(...)`：SSE HTTP 入口。
- `RAGChatServiceImpl.streamLLMResponse(...)`：主流程最终 Prompt 和 `ChatRequest` 构造点。
- `RAGPromptService.buildStructuredMessages(...)`：构造最终 LLM messages。
- `RoutingLLMService.streamChat(...)`：流式模型路由、首包探测和 fallback。
- `ModelSelector.selectChatCandidates(...)`：模型候选选择。
- `ModelHealthStore`：模型熔断状态。
- `BaiLianChatClient.streamChat(...)`、`SiliconFlowChatClient.streamChat(...)`、`OllamaChatClient.streamChat(...)`：Provider 流式 HTTP 调用。
- `StreamAsyncExecutor.submit(...)`：模型流式任务提交和线程池拒绝兜底。
- `StreamChatEventHandler.onContent(...)`：发送回答增量并累积答案。
- `StreamChatEventHandler.onComplete(...)`：助手消息落库、发送 `finish`/`done`。
- `StreamChatEventHandler.onError(...)`：异常关闭 SSE。
- `StreamTaskManager.cancel(...)` / `cancelLocal(...)`：跨节点取消和本地取消执行。
- `ChatQueueLimiter.enqueue(...)`：Redis/Redisson 全局并发与排队。
- `ConversationMemoryService.loadAndAppend(...)`：加载历史并写入用户消息。
- `JdbcConversationMemoryStore.append(...)`：消息写入 `t_message`，用户消息触发会话创建/更新。
- `ConversationMessageServiceImpl.addMessage(...)`：底层消息 insert。
- `ConversationServiceImpl.createOrUpdate(...)`：创建或更新 `t_conversation`，新会话标题由 LLM 生成。
- `JdbcConversationMemorySummaryService.compressIfNeeded(...)`：助手消息后异步摘要压缩。

### Key Data Structures

- `ChatRequest`：模型请求，包含 messages、temperature、topP、topK、maxTokens、thinking、enableTools。
- `ChatMessage`：模型消息，角色为 SYSTEM、USER、ASSISTANT。
- `PromptContext`：Prompt 构造输入，包含 question、mcpContext、kbContext、意图和 chunks。
- `ModelTarget`：模型目标，包含候选配置和 provider 配置。
- `MessageDelta`：SSE 增量消息，包含 `type` 和 `delta`。
- `MetaPayload`：SSE 元信息，包含 `conversationId` 和 `taskId`。
- `CompletionPayload`：SSE 完成/取消 payload，包含 `messageId` 和可选 `title`。
- `ConversationDO`：`t_conversation` 映射。
- `ConversationMessageDO`：`t_message` 映射。
- `ConversationSummaryDO`：`t_conversation_summary` 映射。

### Storage

- `t_conversation`：保存会话列表，唯一约束是 `(conversation_id, user_id)`，字段包括 title、last_time、deleted。
- `t_message`：保存 user/assistant 消息，字段包括 conversation_id、user_id、role、content、deleted。
- `t_conversation_summary`：保存摘要内容和摘要覆盖的最后消息 ID。
- Redis：
  - `rag:global:chat`：全局并发 semaphore。
  - `rag:global:chat:queue`：排队 sorted set。
  - `rag:global:chat:queue:notify`：队列通知 topic。
  - `ragent:stream:cancel:{taskId}`：取消标记，TTL 30 分钟。
  - `ragent:stream:cancel`：取消广播 topic。
  - `ragent:memory:summary:lock:{userId}:{conversationId}`：摘要压缩锁。
- JVM 本地：
  - `StreamTaskManager.tasks`：Guava Cache 保存 taskId 对应的 sender、handle、cancel 状态和 onCancel 回调，30 分钟过期。

### Concurrency And Control

- `@ChatRateLimit` 通过 `ChatRateLimitAspect` 把实际对话执行交给 `ChatQueueLimiter`。
- 当前配置全局并发为 1、最大等待 3 秒、permit lease 30 秒、轮询间隔 200ms。
- `chatEntryExecutor` 负责排队成功后的主流程入口。
- `modelStreamExecutor` 负责模型流式 HTTP 调用。
- `StreamAsyncExecutor` 遇到 `modelStreamExecutor` 拒绝时，会 cancel OkHttp call，并回调 `onError("流式线程池繁忙")`。
- `RoutingLLMService` 首包等待最长 60 秒，首包失败会 fallback 到下一个模型。
- `OkHttpClient` 配置 connect timeout 30 秒、write timeout 60 秒、read timeout 0、call timeout 0，并启用 `retryOnConnectionFailure(true)`。
- `StreamTaskManager.cancel(taskId)` 先写 Redis cancel key，再发布 topic；本地监听到后 cancel handle、发送 `cancel` 和 `done`。

### Verified Facts

- `/rag/v3/chat` 使用 `SseEmitter(0L)`，入口返回 `text/event-stream;charset=UTF-8`。
- 最终 LLM messages 包含 system prompt、可选 MCP context、可选 KB context、历史消息和最终 user message。
- 当前 Chat Provider 实现包括百炼、SiliconFlow、Ollama。
- 当前普通模式和 deepThinking 模式的首选 Chat 模型都是 `qwen3-max`，provider 是百炼。
- 模型流式调用使用候选 fallback 和 60 秒首包探测。
- 百炼和 SiliconFlow 使用 OpenAI 风格 SSE；Ollama 使用逐行 JSON。
- SSE 命名事件包括 `meta`、`message`、`finish`、`done`、`cancel`、`reject`。
- 当前已读代码中没有命名 `error` SSE 事件；错误通过 `completeWithError` 结束连接。
- 正常完成时助手消息保存到 `t_message`，并发送 `finish(messageId,title)` 和 `done("[DONE]")`。
- 取消时如果已有回答内容，会先把部分回答保存为 assistant 消息，再发送 `cancel(messageId,title)` 和 `done("[DONE]")`。
- 排队超时拒绝时会保存用户问题和 assistant 拒绝消息。
- 普通模型错误路径中，当前已读代码没有保存已累积的 assistant 部分回答。
- 助手消息写入后会异步触发摘要压缩，摘要写入 `t_conversation_summary`。

### Assumptions

- 前端对 `completeWithError` 的具体展示方式需要前端代码或联调验证；本轮只确认后端没有发送命名 `error` 事件。
- `ChatQueueLimiter` 的全局并发配置当前为 1，可能是开发环境或演示配置；是否适合生产，需要结合部署配置确认。
- 默认模型 `qwen3-max`、`glm-4.7` 依赖外部 API Key；若环境变量为空，实际 fallback 顺序和最终体验需要运行验证。

### Open Questions

- 普通错误路径是否应该保存已累积的部分回答，目前代码没有保存；产品预期需要确认。
- `StreamTaskManager.cancelLocal(...)` 发送 cancel/done 后没有立即 `unregister`，本地 cache 依赖 TTL 过期；是否会造成短期任务状态残留需要评估。
- 用户消息写入、会话创建/更新、助手消息写入不在一个明确单事务内；失败场景下可能出现只有用户消息、无助手消息的会话，需要确认是否符合预期。
- `DefaultConversationMemoryService.load(...)` 并行加载摘要和历史时使用默认 `CompletableFuture.supplyAsync(...)`，没有显式业务线程池；是否符合线程治理需要确认。
- `onComplete()` 即使 `answer` 为空也会尝试写 assistant 消息；空回答是否应该落库，需要确认。
- 全局 OkHttp `readTimeout=0`、`callTimeout=0` 对长时间卡住的模型流式连接有风险，虽然 `RoutingLLMService` 有 60 秒首包探测，但首包之后的长时间停顿仍需验证。

### Next Reading Target

下一轮建议继续 Phase 4 的“可观测性 / Trace 模块”或“并发限流与取消模块”二选一：

1. 如果目标是面试讲工程稳定性，建议读 `Trace + 异常观测`，把 RAG 每阶段耗时、错误记录和 run/node 表讲清楚。
2. 如果目标是生产化能力，建议读 `限流排队 + 取消`，专门确认 Redis Lua、permit lease、跨节点取消和 SSE 前端契约。

基于当前简历叙事，优先建议读 `Trace + 异常观测`，因为它能把“多次 LLM 调用 + RAG 检索 + 工具调用 + 流式输出”串成可诊断的工程能力。

## Phase 4 Module 4: Trace + 异常观测模块

### Reading Scope

本轮只围绕 RAG 对话链路的 trace/run/node 记录、异常关闭、管理端指标查询和相关配置展开。

具体范围：

- `framework/trace` 中的 Trace 注解和上下文。
- `bootstrap/rag/aop` 中对 `/rag/v3/chat` 主流程的 trace 接入。
- `bootstrap/rag/service`、`dao/entity`、`dao/mapper`、`controller/vo` 中的 trace 记录和查询。
- `resources/database/schema_pg.sql` 中 `t_rag_trace_run`、`t_rag_trace_node` 表结构。
- 与 trace 指标直接相关的 dashboard 查询片段。
- 与普通 REST 异常、SSE 流式异常直接相关的异常处理类。

本轮没有展开 ingestion pipeline 的节点日志模块；它也有 node/status/error/duration 记录，但属于文档入库流程，不是本轮 RAG 对话 Trace 主目标。

### Files Read

- `docs/reading/00-reading-plan.md`：确认 Phase 4 模块阅读规则和事实/推测边界。
- `docs/reading/01-project-map.md`：确认主服务、framework、infra-ai、admin 所在模块。
- `docs/reading/03-main-flow.md`：确认 `/rag/v3/chat` 主流程和 `ChatRateLimitAspect` 的位置。
- `docs/reading/04-module-notes.md`：确认已有 MCP、RAG 检索、LLM SSE 模块笔记，避免重复总结。
- `docs/reading/99-open-questions.md`：承接已有未解问题。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/trace/RagTraceContext.java`：确认 traceId、taskId、节点栈的上下文传递方式。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/trace/RagTraceRoot.java`：确认根 trace 注解定义。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/trace/RagTraceNode.java`：确认节点 trace 注解定义。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatRateLimitAspect.java`：确认 SSE 对话入口如何创建 run、设置 trace 上下文、记录成功/失败。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/RagTraceAspect.java`：确认注解式 root/node 采集逻辑、节点父子关系、耗时和错误记录。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagTraceProperties.java`：确认 `rag.trace` 配置绑定。
- `bootstrap/src/main/resources/application.yaml`：确认 `rag.trace.enabled` 和 `max-error-length` 默认值。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordService.java`：确认 run/node 记录接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java`：确认 MyBatis insert/update 持久化实现。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/RagTraceRunDO.java`：确认 run 表映射字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/RagTraceNodeDO.java`：确认 node 表映射字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/mapper/RagTraceRunMapper.java`：确认 run mapper。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/mapper/RagTraceNodeMapper.java`：确认 node mapper。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RagTraceController.java`：确认 trace 查询接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceQueryServiceImpl.java`：确认分页、详情、节点列表查询逻辑。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/request/RagTraceRunPageRequest.java`：确认 run 查询条件。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/RagTraceRunVO.java`：确认 run 返回字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/RagTraceNodeVO.java`：确认 node 返回字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/RagTraceDetailVO.java`：确认详情返回结构。
- `resources/database/schema_pg.sql`：确认 PostgreSQL trace 表结构、索引和字段注释。
- `resources/database/backups/schema_table.sql`：对照旧 MySQL trace 表结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`：确认 taskId 复用 `RagTraceContext`，以及流式回答启动后返回。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/MultiQuestionRewriteService.java`：确认 query rewrite trace 节点。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentResolver.java`：确认 intent resolve trace 节点。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`：确认 retrieval engine trace 节点。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`：确认 multi-channel retrieval trace 节点。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`：确认同步/流式 LLM routing trace 节点和首包探测。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/BaiLianChatClient.java`：确认百炼 provider trace 节点。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/SiliconFlowChatClient.java`：确认 SiliconFlow provider trace 节点。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/OllamaChatClient.java`：确认 Ollama provider trace 节点。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamAsyncExecutor.java`：确认流式模型调用被提交到异步线程池。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`：确认相关线程池通过 TTL executor 包装。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`：确认流式完成和错误回调如何处理。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/web/SseEmitterSender.java`：确认 SSE 发送失败和异常关闭行为。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/web/GlobalExceptionHandler.java`：确认普通 REST 异常的统一处理。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/exception/AbstractException.java`、`ClientException.java`、`ServiceException.java`、`RemoteException.java`：确认项目业务异常体系。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/errorcode/BaseErrorCode.java`、`IErrorCode.java`：确认基础错误码分类。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/http/ModelClientException.java`、`ModelClientErrorType.java`：确认模型客户端错误分类。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java`、`DashboardService.java`、`DashboardServiceImpl.java` 和相关 VO：确认管理端 performance/trends 如何复用 trace run 数据做指标。

### Responsibility

Trace + 异常观测模块负责给 RAG 对话主流程补一层工程可诊断能力：

- 为一次对话创建 run 记录，关联 `traceId`、`conversationId`、`taskId`、`userId`、入口方法、状态、错误信息和耗时。
- 为关键阶段创建 node 记录，保存节点名称、类型、类名、方法名、父节点、深度、状态、错误信息和耗时。
- 通过查询接口向管理端暴露 run 列表、run 详情和节点列表。
- 管理端 dashboard 使用 run 状态和耗时计算成功率、错误率、平均响应时间、P95、慢请求率等指标。
- 普通 REST 异常由全局异常处理器统一转成 `Result`；SSE 流式异常通过 `SseEmitter.completeWithError(...)` 结束连接。

### Core Classes And Methods

- `RagTraceContext`：基于 `TransmittableThreadLocal` 保存 `traceId`、`taskId` 和节点栈。
- `RagTraceNode`：方法级节点采集注解，声明 `name` 和 `type`。
- `RagTraceRoot`：方法级根链路注解；本轮搜索未发现业务方法使用该注解。
- `ChatRateLimitAspect.limitStreamChat(...)`：拦截 `@ChatRateLimit` 的 SSE 对话入口，补齐 conversationId，并把实际执行交给队列。
- `ChatRateLimitAspect.invokeWithTrace(...)`：排队成功后创建 `rag-stream-chat` run，设置 trace/task 上下文，执行主流程并更新 run 状态。
- `RagTraceAspect.aroundNode(...)`：拦截 `@RagTraceNode` 方法，创建 node，维护父子栈，成功或异常时更新状态和耗时。
- `RagTraceRecordServiceImpl.startRun/finishRun/startNode/finishNode`：直接通过 MyBatis mapper 写入或更新 trace 表。
- `RagTraceQueryServiceImpl.pageRuns/detail/listNodes`：按 traceId、conversationId、taskId、status 查询 run，并按 startTime/id 查询节点。
- `RagTraceController`：暴露 `GET /rag/traces/runs`、`GET /rag/traces/runs/{traceId}`、`GET /rag/traces/runs/{traceId}/nodes`。
- `DashboardServiceImpl.loadPerformance(...)`：基于 `t_rag_trace_run` 的 SUCCESS/ERROR 和 duration 计算质量与延迟指标。
- `GlobalExceptionHandler`：处理参数校验、业务异常、未登录、权限、上传大小和未捕获异常。
- `StreamChatEventHandler.onError(...)` 与 `SseEmitterSender.fail(...)`：流式链路异常时注销任务并 `completeWithError`。

当前已读到的 trace 节点包括：

- `query-rewrite` / `query-rewrite-and-split`，类型 `REWRITE`。
- `intent-resolve`，类型 `INTENT`。
- `retrieval-engine`，类型 `RETRIEVE`。
- `multi-channel-retrieval`，类型 `RETRIEVE_CHANNEL`。
- `llm-chat-routing` / `llm-stream-routing`，类型 `LLM_ROUTING`。
- `bailian-chat` / `bailian-stream-chat`，类型 `LLM_PROVIDER`。
- `siliconflow-chat` / `siliconflow-stream-chat`，类型 `LLM_PROVIDER`。
- `ollama-chat` / `ollama-stream-chat`，类型 `LLM_PROVIDER`。

### Flow Position

在 `/rag/v3/chat` 主流程中，Trace 的位置是：

1. `RAGChatController.chat(...)` 创建 `SseEmitter` 并调用 `RAGChatServiceImpl.streamChat(...)`。
2. `RAGChatServiceImpl.streamChat(...)` 带 `@ChatRateLimit`，先被 `ChatRateLimitAspect.limitStreamChat(...)` 拦截。
3. `ChatRateLimitAspect` 补齐 `conversationId`，将实际调用提交给 `ChatQueueLimiter.enqueue(...)`。
4. 排队成功后，`chatEntryExecutor` 执行 `invokeWithTrace(...)`。
5. `invokeWithTrace(...)` 创建 `t_rag_trace_run`，设置 `RagTraceContext.traceId/taskId`，再反射调用 `RAGChatServiceImpl.streamChat(...)`。
6. 主流程中的改写、意图识别、检索、LLM 路由和 Provider 方法被 `RagTraceAspect.aroundNode(...)` 按 `@RagTraceNode` 自动记录到 `t_rag_trace_node`。
7. `RAGChatServiceImpl` 调用 `llmService.streamChat(...)` 后绑定取消句柄并返回；`invokeWithTrace(...)` 随后把 run 标记为 SUCCESS 或 ERROR。
8. 模型后续流式输出、`StreamChatEventHandler.onComplete(...)`、`onError(...)` 发生在流式回调阶段，不由 `RagTraceRecordService` 再更新 run/node。

因此，当前 trace 更接近“请求进入后到模型流式首包/启动阶段”的诊断记录，不等价于完整 SSE 回答生命周期记录。

### Configuration And Dependencies

- 配置：
  - `rag.trace.enabled: true`
  - `rag.trace.max-error-length: 1000`
- 数据库表：
  - `t_rag_trace_run`
  - `t_rag_trace_node`
  - 查询时还会读取 `t_user` 来补 `username`。
- PostgreSQL schema：
  - `t_rag_trace_run.trace_id` 有唯一约束 `uk_run_id`。
  - `t_rag_trace_run` 有 `idx_task_id`、`idx_user_id_trace`。
  - `t_rag_trace_node` 有唯一约束 `(trace_id, node_id)`。
- Mapper：
  - `RagTraceRunMapper`
  - `RagTraceNodeMapper`
  - `UserMapper`
- 线程池：
  - `chatEntryExecutor`：排队成功后执行主对话和创建 trace run。
  - `intentClassifyThreadPoolExecutor`、`ragContextThreadPoolExecutor`、`ragRetrievalThreadPoolExecutor`、`mcpBatchThreadPoolExecutor`：RAG 内部并行阶段，均通过 TTL executor 包装。
  - `modelStreamExecutor`：模型流式 HTTP 调用线程池，也通过 TTL executor 包装。
- 外部服务：
  - PostgreSQL：trace 持久化和 dashboard 查询。
  - Redis/Redisson：不直接保存 trace，但主流程先经过限流排队和取消广播。
  - LLM Provider：相关调用被部分 `@RagTraceNode` 包裹。
- 普通异常返回：
  - `GlobalExceptionHandler` 对非 SSE REST 请求返回 `Result.failure(...)`。
- 流式异常返回：
  - `StreamChatEventHandler.onError(...)` -> `SseEmitterSender.fail(...)` -> `SseEmitter.completeWithError(...)`。

### Verified Facts

- `RagTraceContext` 使用 `TransmittableThreadLocal` 保存 traceId、taskId 和 `Deque<String>` 节点栈。
- 当前主 SSE 对话没有使用 `@RagTraceRoot`；run 创建逻辑直接写在 `ChatRateLimitAspect.invokeWithTrace(...)`。
- `ChatRateLimitAspect` 为每次排队成功的对话创建 `traceId` 和 `taskId`，并把 `taskId` 写入 `RagTraceContext`。
- `RAGChatServiceImpl.streamChat(...)` 会优先使用 `RagTraceContext.getTaskId()` 作为 SSE meta 事件里的 taskId。
- trace run 默认名称是 `rag-stream-chat`，`extraData` 当前只记录 `questionLength`。
- run 和 node 的状态字符串是 `RUNNING`、`SUCCESS`、`ERROR`。
- 错误信息会截断到 `rag.trace.max-error-length`，默认 1000。
- `RagTraceAspect.aroundNode(...)` 会记录父节点 ID 和 depth，并在 finally 中 pop 节点栈。
- 如果没有 traceId，`@RagTraceNode` 方法直接执行，不会创建孤立节点。
- 查询接口支持按 `traceId`、`conversationId`、`taskId`、`status` 过滤 run。
- trace detail 返回一个 run 加其 node 列表；node 按 `startTime` 和 `id` 升序返回。
- dashboard performance 使用 SUCCESS run 的 `duration_ms` 计算平均延迟、P95 和慢请求率，使用 SUCCESS/ERROR run 数计算成功率和错误率。
- dashboard trends 的 `avglatency` 和 `quality` 会按小时或天从 `t_rag_trace_run` 聚合。
- `GlobalExceptionHandler` 处理普通 REST 异常，但 SSE 已经开始后主要依赖 `SseEmitterSender.fail(...)` 关闭连接。
- `StreamChatEventHandler.onError(...)` 不更新 trace 表，只注销任务并异常关闭 SSE。

### Assumptions

- 这里的 trace 目标更偏应用级 RAG 阶段观测，不是 OpenTelemetry 那类跨服务分布式 trace；本轮没有看到 traceId 通过 HTTP header 传给 MCP Server 或外部模型服务。
- 管理端 dashboard 的“响应时间”当前依赖 `t_rag_trace_run.duration_ms`，从代码看更接近主流程启动到流式首包阶段的耗时，而不是完整回答生成耗时。
- `RagTraceRoot` 可能是为非 SSE 或后续入口预留的通用注解；本轮没有看到实际使用点，所以不能确认是否为遗留代码。

### Open Questions

- `ChatRateLimitAspect.invokeWithTrace(...)` 在 `RAGChatServiceImpl.streamChat(...)` 返回后就把 run 标记为 SUCCESS；而流式输出完整完成发生在之后的 callback。是否需要把完整 SSE 生命周期也纳入 trace？
- 首包之后如果 `modelStreamExecutor` 中的异步流式任务失败，`StreamChatEventHandler.onError(...)` 不会更新 `t_rag_trace_run` 或相关 node；dashboard 是否会把这类失败误算为成功？
- `@RagTraceRoot` 当前没有业务使用点，根链路逻辑由 `ChatRateLimitAspect` 特化实现；这是否是有意设计，还是旧方案迁移后的残留？
- `RagTraceRecordServiceImpl` 直接执行 MyBatis insert/update，没有 fail-open 保护；trace 表写入失败是否会影响正常对话请求，需要确认产品取舍。
- `RagTraceContext` 的节点栈是 TTL 中的可变 `ArrayDeque`；并行子任务下父子节点和 depth 是否稳定，需要通过单元测试或运行 trace 验证。
- `schema_pg.sql` 中 `t_rag_trace_node.trace_id` 是 `VARCHAR(20)`，`t_rag_trace_run.trace_id` 是 `VARCHAR(64)`，备份 MySQL schema 中 node trace_id 也是 `varchar(64)`；当前 Snowflake traceId 长度通常能放下，但 schema 一致性需要确认。
- dashboard 的 `avgLatencyMs`、`p95LatencyMs`、`slowRate` 只基于 SUCCESS run 的 `duration_ms`，如果 run duration 不是完整回答耗时，指标名称和用户感知延迟可能不一致。
- trace node 目前不记录输入、输出摘要或 token/模型名等 extra_data；排查质量问题时是否够用，需要结合实际管理后台页面验证。

### Next Reading Target

下一轮建议读 `限流排队 + 取消模块`：它和本轮 Trace 共用 `ChatRateLimitAspect`、`ChatQueueLimiter`、`StreamTaskManager`、Redis key、`chatEntryExecutor` 和 SSE 事件，可以把“排队、取消、异常、trace 状态、前端展示”连成一条更完整的生产化稳定性讲法。

## Phase 4 Module 5: 限流排队 + 取消模块

### Reading Scope

本轮只围绕 `/rag/v3/chat` 的全局限流、等待队列、permit 生命周期、跨节点取消、SSE 事件结果和相关前端消费代码展开。

本轮没有重复展开模型 Provider、Prompt 构造、RAG 检索、MCP 工具调用和 Trace 表结构；只在说明限流/取消对 trace 状态和 dashboard 口径的影响时引用上一轮已读 Trace 结论。

### Files Read

- `docs/reading/00-reading-plan.md`：确认只读、单目标、事实/推测分离规则。
- `docs/reading/01-project-map.md`：确认主服务、framework、infra-ai、frontend 所在模块。
- `docs/reading/03-main-flow.md`：确认 `/rag/v3/chat` 入口、`@ChatRateLimit`、`ChatQueueLimiter`、`StreamTaskManager` 在主流程中的位置。
- `docs/reading/04-module-notes.md`：确认已有 MCP、RAG 检索、LLM SSE、Trace 模块结论，避免重复展开。
- `docs/reading/99-open-questions.md`：承接已有开放问题。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`：确认 `GET /rag/v3/chat` 和 `POST /rag/v3/stop`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java`：确认 `streamChat` 与 `stopTask` 接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`：确认 taskId 获取、SSE callback 创建、用户消息写入、取消句柄绑定和 stop 入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatRateLimit.java`：确认限流注解。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatRateLimitAspect.java`：确认 service 方法如何进入队列、如何创建 trace run 和 taskId。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatQueueLimiter.java`：确认全局并发、等待队列、超时拒绝、permit 获取/释放、Lua claim 和通知机制。
- `bootstrap/src/main/resources/lua/queue_claim_atomic.lua`：确认队列原子 claim 规则。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RAGRateLimitProperties.java`：确认 `rag.rate-limit.global` 配置绑定。
- `bootstrap/src/main/resources/application.yaml`：确认 Redis 和 rate-limit 默认配置。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`：确认 `chatEntryExecutor`、`modelStreamExecutor` 等线程池和拒绝策略。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`：确认 callback 创建时注入 `StreamTaskManager`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`：确认 handler 构造参数。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`：确认 meta/message/finish/done/cancel/error 处理和部分回答保存。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java`：确认本地任务缓存、Redis cancel key、Redis topic、跨节点取消和本地取消执行。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/SSEEventType.java`：确认 SSE 事件名。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/web/SseEmitterSender.java`：确认 SSE 发送、完成和失败关闭行为。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamAsyncExecutor.java`：确认模型流式任务提交、线程池拒绝和回调错误。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamCancellationHandle.java`、`StreamCancellationHandles.java`：确认 OkHttp call cancel 和取消幂等。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamCallback.java`：确认流式回调契约。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`、`FirstPacketAwaiter.java`：确认首包探测、fallback 和首包前/后错误边界。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/BaiLianChatClient.java`、`SiliconFlowChatClient.java`、`OllamaChatClient.java` 的流式 loop 片段：只确认 cancelled flag、`call.execute()`、`callback.onError(...)`，不展开 Provider 细节。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/ConversationMemoryService.java`、`DefaultConversationMemoryService.java`、`JdbcConversationMemoryStore.java`：确认用户/助手消息写入入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`：确认消息最终 insert。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/idempotent/IdempotentSubmit.java`、`IdempotentSubmitAspect.java`：确认 Controller 层还有 Redisson lock 防重复提交。
- `frontend/src/services/chatService.ts`：确认前端取消调用 `POST /rag/v3/stop`。
- `frontend/src/hooks/useStreamResponse.ts`：确认前端 SSE event 解析和事件分发。
- `frontend/src/stores/chatStore.ts`：确认前端保存 taskId、取消请求、finish/cancel/reject/error 处理。
- `frontend/src/components/chat/ChatInput.tsx`、`WelcomeScreen.tsx`：确认流式中点击提交按钮会触发取消。
- `frontend/src/types/index.ts`：确认 `StreamMetaPayload`、`MessageDeltaPayload`、`CompletionPayload` 字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGSettingsController.java`、`SystemSettingsVO.java`：确认管理端可读取 rate-limit 配置。

### Responsibility

限流排队 + 取消模块负责在 RAG 流式对话入口外包一层生产化控制：

- 对 `/rag/v3/chat` 做全局并发限制，避免多个节点同时放行过多流式任务。
- 对超出并发的请求按 Redis sorted set 排队，按队头窗口和可用 permit 原子 claim。
- 对等待超过配置时间的请求直接拒绝，并通过 SSE 返回可展示结果，同时落库用户问题和 assistant 拒绝消息。
- 在排队成功后，把真实对话逻辑提交到 `chatEntryExecutor`。
- 通过 SSE `meta` 把 taskId 返回前端，再由 `POST /rag/v3/stop` 触发跨节点取消。
- 取消时尝试停止底层模型 OkHttp call，并把已累积的部分回答保存为 assistant 消息。

### Flow Position

入口链路：

`RAGChatController.chat(...)`
-> 创建 `SseEmitter(0L)`
-> `ragChatService.streamChat(...)`
-> `RAGChatServiceImpl.streamChat(...)` 上的 `@ChatRateLimit`
-> `ChatRateLimitAspect.limitStreamChat(...)`
-> `ChatQueueLimiter.enqueue(...)`
-> 成功获得 permit 后提交 `chatEntryExecutor`
-> `ChatRateLimitAspect.invokeWithTrace(...)`
-> 反射调用真实 `RAGChatServiceImpl.streamChat(...)`
-> `StreamCallbackFactory.createChatEventHandler(...)`
-> `StreamChatEventHandler.initialize()` 发送 `meta(conversationId, taskId)` 并注册本地任务
-> 后续主流程返回模型 `StreamCancellationHandle`
-> `StreamTaskManager.bindHandle(taskId, handle)`

取消链路：

前端 `cancelGeneration()`
-> 如果已有 `streamTaskId`，调用 `stopTask(taskId)`
-> 后端 `POST /rag/v3/stop`
-> `RAGChatServiceImpl.stopTask(...)`
-> `StreamTaskManager.cancel(taskId)`
-> Redis 写入 cancel bucket 并发布 cancel topic
-> 所有节点监听 topic 后执行 `cancelLocal(taskId)`
-> 命中本地任务的节点调用 `handle.cancel()`、发送 `cancel` 和 `done`、关闭 SSE。

### Rate Limit And Queue Flow

全局限流开启时，`ChatQueueLimiter.enqueue(...)` 的状态机是：

1. 生成 `requestId`。
2. 从 Redis `rag:global:chat:queue:seq` 递增获取排序序号。
3. 把 `requestId` 加入 Redis sorted set `rag:global:chat:queue`，score 是序号。
4. 给当前 `SseEmitter` 注册 `onCompletion`、`onTimeout`、`onError`，统一执行 `releaseOnce`。
5. 先尝试立即进入：如果 `RPermitExpirableSemaphore.availablePermits() > 0`，执行 Lua claim。
6. Lua 只允许 `ZRANK(requestId) < availablePermits` 的请求出队，也就是只允许队头窗口内请求 claim。
7. claim 成功后再调用 `tryAcquire(0, leaseSeconds)` 获取 expirable permit。
8. 获取 permit 成功后提交 `chatEntryExecutor.execute(() -> runOnAcquire(onAcquire))`。
9. 如果提交线程池失败，释放 permit、重新入队、发布通知。
10. 如果暂时不能进入，则启动定时 poller，按 `poll-interval-ms` 轮询直到获得 permit 或超过 `max-wait-seconds`。
11. 超过等待时间后，从队列移除 requestId，发送排队拒绝 SSE，并写入拒绝对话记录。

permit 释放：

- 正常完成、异常关闭、连接超时都会触发 `SseEmitter` 生命周期回调中的 `releaseOnce`。
- `releaseOnce` 会设置本地 `cancelled=true`，从等待队列移除 requestId，并在 `permitRef` 非空时释放 Redisson permit。
- 释放 permit 后会发布 `rag:global:chat:queue:notify`，唤醒其他等待 poller。
- `lease-seconds` 是 permit 自动过期兜底；当前默认配置为 30 秒。

通知机制：

- `ChatQueueLimiter` 启动时订阅 `rag:global:chat:queue:notify`。
- 释放 permit、claim 失败后重新入队、等待超时移除队列都会 publish。
- `PollNotifier` 收到通知后，如果存在可用 permit，会遍历已注册 poller 立即尝试推进队列。
- `PollNotifier` 还有清理任务，移除注册超过 5 分钟的 poller。

### Cancel Flow

taskId 生成与传递：

1. `ChatRateLimitAspect.invokeWithTrace(...)` 创建 trace run 时生成 `taskId`，写入 `RagTraceContext.setTaskId(taskId)`。
2. 真实 `RAGChatServiceImpl.streamChat(...)` 优先读取 `RagTraceContext.getTaskId()`，没有则自己生成 taskId。
3. `StreamChatEventHandler.initialize()` 先发送 `meta` 事件，payload 是 `MetaPayload(conversationId, taskId)`。
4. 前端 `chatStore.onMeta(...)` 把 `payload.taskId` 保存为 `streamTaskId`。
5. 用户点击停止时，前端调用 `POST /rag/v3/stop?taskId=...`。

跨节点取消：

- `StreamTaskManager.cancel(taskId)` 先写 Redis bucket：`ragent:stream:cancel:{taskId}=true`，TTL 30 分钟。
- 然后发布 Redis topic：`ragent:stream:cancel`，消息体是 taskId。
- 所有节点在 `@PostConstruct` 中订阅该 topic，收到消息后调用 `cancelLocal(taskId)`。
- 只有持有该 taskId 本地缓存的节点会继续执行取消；其他节点查不到 taskInfo 直接返回。

本地取消：

- `cancelLocal(...)` 用 CAS 把本地 taskInfo 标记为 cancelled，保证只执行一次。
- 如果此时已有 `StreamCancellationHandle`，调用 `handle.cancel()`。
- OkHttp 取消句柄会设置 cancelled flag，并调用 `Call.cancel()`。
- 如果 sender 存在，会调用 `onCancelSupplier` 构造 `CompletionPayload`，再发送 `cancel` 和 `done`，最后 `sender.complete()`。
- `StreamChatEventHandler.buildCompletionPayloadOnCancel()` 只有在已累积回答非空时才写入 assistant 部分回答。

取消时机边界：

- `StreamTaskManager.register(...)` 在发送 `meta` 后立即注册 sender 和 cancel 回调。
- 模型 handle 要等 `llmService.streamChat(...)` 返回后才 `bindHandle(...)`。
- 因此前端拿到 taskId 后立即取消时，可能出现 sender 已注册但模型 handle 尚未绑定的窗口。
- 这个窗口内取消会先发送 `cancel/done` 并关闭 SSE；后续主流程如果继续走到 `bindHandle(...)`，`bindHandle` 发现 task 已 cancelled 后再调用 handle.cancel()。

### SSE Events And Persistence Outcomes

#### 正常完成

- 是否释放 permit：是。`StreamChatEventHandler.onComplete()` 调用 `sender.complete()`，触发 emitter completion 回调释放 permit。
- 是否注销本地任务：是。`onComplete()` 调用 `taskManager.unregister(taskId)`，同时异步删除 Redis cancel key。
- 是否写入用户消息：是。主流程中 `memoryService.loadAndAppend(...)` 写入 user 消息。
- 是否写入 assistant 消息：是。`onComplete()` 将累积 answer 写入 assistant 消息；即使 answer 为空也会尝试写入。
- SSE 事件：`meta`、多次 `message`、`finish`、`done`。
- trace run 最可能状态：流式路径下通常在 `llmService.streamChat(...)` 返回并绑定 handle 后已标记 `SUCCESS`，早于完整 SSE 完成。
- dashboard 影响：计入 SUCCESS 和延迟统计，但当前 duration 更接近主流程启动到流式启动/首包阶段，不一定是完整回答耗时。

#### 排队拒绝

- 是否释放 permit：没有获取 permit，因此无需释放；`sender.complete()` 后 release 回调只会清理队列和本地 cancelled 标记。
- 是否注销本地任务：没有本地 `StreamTaskManager` 任务，因为真实 `RAGChatServiceImpl.streamChat(...)` 未执行。
- 是否写入用户消息：是，前提是 question 非空且 userId 可解析；`recordRejectedConversation(...)` 会写入 user 消息。
- 是否写入 assistant 消息：是，内容为 `系统繁忙，请稍后再试`。
- SSE 事件：如果成功构造 `RejectedContext`，发送 `meta`、`reject`、`finish`、`done`；如果无法构造 rejected context，只发送 `done`。
- trace run 最可能状态：没有 trace run，因为 `invokeWithTrace(...)` 只有获取 permit 后才执行。
- dashboard 影响：不计入 trace SUCCESS/ERROR、平均延迟和错误率；但会增加会话/消息数。拒绝 assistant 内容不是“未检索到与问题相关的文档内容。”，所以不计入当前 noDocRate。

#### 用户取消

- 是否释放 permit：是。`cancelLocal(...)` 最终 `sender.complete()`，触发 emitter completion 回调释放 permit。
- 是否注销本地任务：当前代码没有在 `cancelLocal(...)` 中调用 `unregister(taskId)`；本地 Guava cache 依赖 30 分钟过期，Redis cancel key 依赖 TTL，除非后续其他路径调用 unregister。
- 是否写入用户消息：通常是。用户消息在主流程早期 `memoryService.loadAndAppend(...)` 写入；如果取消发生在用户消息写入前，则代码层面不能保证已写入。
- 是否写入 assistant 消息：只有已累积 answer 非空时，取消回调才写入 assistant 部分回答。
- SSE 事件：`meta` 已发送后，取消会发送 `cancel`、`done`；取消前可能已有若干 `message`。
- trace run 最可能状态：如果取消发生在流式 handle 已返回后，trace run 多半已经是 `SUCCESS`；如果取消发生在 handle 绑定前，真实业务方法仍可能继续执行到返回后标记 `SUCCESS`，除非同步阶段抛异常。
- dashboard 影响：用户取消通常不会被区分为取消，可能仍计入 SUCCESS；如果 run duration 只覆盖到 handle 返回，无法反映取消发生后的真实用户体验。

特别风险：

- 取消会关闭 SSE 并释放 permit，但如果取消发生在模型 handle 绑定前，后端主流程可能还在继续执行改写、意图、检索等同步工作；此时全局并发 permit 已释放，可能低估真实后端工作量。
- 当没有已累积 answer 时，`buildCompletionPayloadOnCancel()` 返回 `String.valueOf(messageId)`，messageId 变量为 null 时会变成字符串 `"null"`，前端 `onCancel` 会把本地消息 id 更新为这个字符串。

#### 模型错误

- 是否释放 permit：是。错误路径最终 `SseEmitter.completeWithError(...)` 会触发 emitter error/completion 回调释放 permit。
- 是否注销本地任务：`StreamChatEventHandler.onError(...)` 会调用 `taskManager.unregister(taskId)`；但如果错误发生在任务注册前或 SSE 发送异常打断 onComplete，可能不走到该路径。
- 是否写入用户消息：通常是，因为用户消息在模型调用前已写入。
- 是否写入 assistant 消息：普通 `onError(...)` 不保存已累积的 assistant 部分回答。
- SSE 事件：后端没有发送命名 `error` SSE 事件；可能只有 `meta` 和部分 `message`，随后连接 `completeWithError`。
- trace run 最可能状态：
  - 首包前所有候选模型失败：`RoutingLLMService.streamChat(...)` 会 `callback.onError(...)` 并抛出异常，`ChatRateLimitAspect` 可能把 run 标记为 `ERROR`。
  - 首包成功后异步流式失败：真实 service 方法已返回，trace run 通常已经是 `SUCCESS`，后续错误只通过 callback 关闭 SSE。
- dashboard 影响：首包前失败可能计入 ERROR；首包后失败可能被计入 SUCCESS，导致错误率偏低、成功率偏高。

#### SSE 发送失败

- 是否释放 permit：`SseEmitterSender.fail(...)` 会 `completeWithError(...)`，通常会触发 emitter 回调释放 permit。
- 是否注销本地任务：不稳定。`SseEmitterSender.sendEvent(...)` 内部捕获发送异常后只 fail 和 log，不主动 unregister；如果后续回调进入 `StreamChatEventHandler.onError(...)` 才会 unregister。
- 是否写入用户消息：如果失败发生在 `meta` 或早期 message，用户消息可能尚未写入或已经写入，取决于具体时机。
- 是否写入 assistant 消息：如果失败发生在 `onComplete()` 写库之后、发送 `finish/done` 期间，assistant 可能已写入；如果发生在流式中途，普通错误路径不保存部分回答。
- SSE 事件：发送失败意味着客户端可能只收到前缀事件，后端不会再稳定发送 `finish/done/cancel/reject`。
- trace run 最可能状态：如果发送失败没有抛回同步主流程，trace run 仍可能是 `SUCCESS`。
- dashboard 影响：可能把客户端断连或发送失败统计为 SUCCESS，且可能留下本地任务未及时注销。

### Redis Keys And Concurrency Controls

限流排队相关：

- `rag:global:chat`：Redisson `RPermitExpirableSemaphore`，控制全局并发。
- `rag:global:chat:queue`：Redisson scored sorted set，保存等待中的 requestId，score 是递增序号。
- `rag:global:chat:queue:seq`：Redisson atomic long，用来生成队列 score。
- `rag:global:chat:queue:notify`：Redisson topic，permit 释放、队列变化时通知等待 poller。
- `lua/queue_claim_atomic.lua`：通过 `ZRANK` 判断 requestId 是否位于可进入的队头窗口，并用 `ZREM` 原子出队。

取消相关：

- `ragent:stream:cancel:{taskId}`：Redisson bucket，值为 true，TTL 30 分钟，用于取消状态跨节点可见。
- `ragent:stream:cancel`：Redisson topic，广播需要取消的 taskId。
- `StreamTaskManager.tasks`：本地 Guava cache，保存 taskId -> sender、cancel handle、cancel 状态、onCancel 回调，30 分钟过期，最大 10000。

线程池相关：

- `chatEntryExecutor`：排队成功后执行真实 RAG 主流程。当前核心线程 `max(2, CPU/2)`，最大线程 `max(4, CPU)`，队列 200，拒绝策略 `AbortPolicy`。
- `modelStreamExecutor`：执行模型流式 HTTP 任务。队列 200，拒绝策略 `AbortPolicy`；`StreamAsyncExecutor` 捕获拒绝后 cancel OkHttp call 并回调 `onError("流式线程池繁忙")`。
- 多个 RAG 内部并行线程池使用 `TtlExecutors.getTtlExecutor(...)` 包装，配合 Trace/User 上下文传递。

配置默认值：

- `rag.rate-limit.global.enabled=true`
- `rag.rate-limit.global.max-concurrent=1`
- `rag.rate-limit.global.max-wait-seconds=3`
- `rag.rate-limit.global.lease-seconds=30`
- `rag.rate-limit.global.poll-interval-ms=200`
- Redis 地址：`127.0.0.1:6379`，密码 `123456`。

### Interaction With Trace

- Trace run 不在 Controller 创建，而是在 `ChatRateLimitAspect.invokeWithTrace(...)` 创建；这意味着只有排队成功并进入 `chatEntryExecutor` 的请求才会有 run。
- taskId 由 Trace 入口生成，并通过 `RagTraceContext` 传给 `RAGChatServiceImpl` 和 SSE `meta`。
- 排队拒绝发生在 `invokeWithTrace(...)` 之前，因此不会有 trace run，也不会进入 dashboard 的 trace 成功率/错误率统计。
- 用户取消不会把 trace run 改为 CANCELLED；当前状态只有 RUNNING/SUCCESS/ERROR。取消通常仍表现为 SUCCESS。
- 首包后模型错误和 SSE 发送失败发生在异步 callback 阶段，通常晚于 trace run 标记 SUCCESS。
- permit 生命周期绑定 SSE emitter，trace run 生命周期绑定真实 service 方法返回；两者不是同一个生命周期。

### Core Classes And Methods

- `RAGChatController.chat(...)`：创建 `SseEmitter(0L)`，调用 `streamChat`。
- `RAGChatController.stop(...)`：接收 taskId，调用 `stopTask`。
- `ChatRateLimitAspect.limitStreamChat(...)`：拦截 `@ChatRateLimit`，补 conversationId，进入 `ChatQueueLimiter`。
- `ChatRateLimitAspect.invokeWithTrace(...)`：排队成功后创建 traceId/taskId，设置 `RagTraceContext` 并执行真实方法。
- `ChatQueueLimiter.enqueue(...)`：入队、注册 emitter lifecycle release、尝试立即进入或启动轮询。
- `ChatQueueLimiter.tryAcquireIfReady(...)`：按可用 permit + Lua claim + Redisson permit 获取决定是否放行。
- `ChatQueueLimiter.scheduleQueuePoll(...)`：等待、超时拒绝和通知 unregister。
- `ChatQueueLimiter.recordRejectedConversation(...)`：排队超时后写 user/assistant 拒绝消息。
- `ChatQueueLimiter.sendRejectEvents(...)`：发送拒绝场景 SSE。
- `StreamTaskManager.cancel(...)`：写 Redis cancel key 并发布取消 topic。
- `StreamTaskManager.cancelLocal(...)`：本地执行取消、取消模型 handle、发送 cancel/done。
- `StreamTaskManager.register(...)` / `bindHandle(...)` / `unregister(...)`：管理 sender、handle、cancel 状态和 Redis key 清理。
- `StreamChatEventHandler.initialize(...)`：发送 meta 并注册任务。
- `StreamChatEventHandler.onComplete(...)`：写 assistant、发送 finish/done、注销任务、关闭 SSE。
- `StreamChatEventHandler.onError(...)`：注销任务、异常关闭 SSE。
- `StreamAsyncExecutor.submit(...)`：提交模型流式任务，构造取消句柄，处理线程池拒绝。
- `StreamCancellationHandles.fromOkHttp(...)`：用 AtomicBoolean + OkHttp `Call.cancel()` 实现底层取消。
- `useStreamResponse.readSseStream(...)`：前端解析 `meta/message/finish/done/cancel/reject/error`。
- `chatStore.cancelGeneration(...)`：前端触发取消；已有 taskId 时调用 stop，没有 taskId 时先记录 `cancelRequested`，等 meta 到达再 stop。

### Verified Facts

- `/rag/v3/chat` 入口有 `@IdempotentSubmit`，service 层 `streamChat` 有 `@ChatRateLimit`。
- `ChatRateLimitAspect` 在进入队列前会把空 conversationId 替换成 Snowflake ID。
- 全局限流关闭时，`ChatQueueLimiter.enqueue(...)` 直接提交 `chatEntryExecutor.execute(onAcquire)`，不注册 permit release 逻辑。
- 全局限流开启时，每个请求都会先进入 Redis sorted set，再尝试 claim 和获取 permit。
- Lua claim 使用 `rank < availablePermits`，只允许队头窗口内请求出队。
- permit 使用 Redisson `RPermitExpirableSemaphore.tryAcquire(0, leaseSeconds, TimeUnit.SECONDS)`，当前默认 lease 为 30 秒。
- permit 释放依赖 `SseEmitter` 的 completion/timeout/error 回调，而不是 `RAGChatServiceImpl.streamChat(...)` 方法返回。
- 排队拒绝会写入 user 消息和 assistant 拒绝消息，并发送 `meta/reject/finish/done`。
- 正常流式启动时，`meta` 在用户消息写入前发送；因为 `StreamChatEventHandler` 构造时先 initialize。
- `StreamTaskManager.cancel(...)` 先写 Redis cancel key，再发布 Redis topic。
- `StreamTaskManager.cancelLocal(...)` 不调用 `unregister(taskId)`。
- 取消时只有 answer 非空才会写 assistant 部分回答。
- 模型流式取消句柄会设置 cancelled flag 并调用 OkHttp `Call.cancel()`。
- 前端取消不会直接 abort 本地 fetch；它调用后端 stop 接口，等待后端返回 `cancel` 事件。
- 前端如果在收到 `meta` 前点击取消，会设置 `cancelRequested=true`；收到 meta 后再调用 stop。
- 后端没有命名 `error` SSE 事件；前端虽然支持解析 `event: error`，但本轮已读后端错误路径使用 `completeWithError`。
- `SseEmitterSender.sendEvent(...)` 捕获发送异常后会 `completeWithError` 并记录 warn；如果 sender 已 closed，再调用 `sendEvent` 会直接抛 `ServiceException`。

### Assumptions

- Redisson expirable semaphore 的 lease 是 permit 自动过期兜底；若一次 SSE 回答超过 lease，permit 可能先于 SSE 完成而过期。这个行为需要运行验证。
- 因为 `SseEmitter` 生命周期回调触发时机由 Spring 管理，permit 释放和客户端实际收到最后事件之间可能存在细微时序差异。
- 前端当前没有主动 abort 本地 fetch，应该是为了等待后端返回 `cancel` payload；是否符合所有网络异常场景需要联调验证。

### Open Questions

- 当前默认 `global.lease-seconds=30`，但模型流式回答可能超过 30 秒；permit 自动过期后全局并发是否会被提前放大，需要压测验证。
- 用户取消会 `sender.complete()` 并释放 permit，但如果模型 handle 尚未绑定，后端同步主流程可能仍在执行；是否需要让取消状态更早中断改写、意图、检索等阶段？
- `cancelLocal(...)` 不调用 `unregister(...)`，导致本地 cache 和 Redis cancel key 依赖 TTL；是否应在发送 cancel/done 后主动清理？
- `buildCompletionPayloadOnCancel()` 在无部分回答时可能把 messageId 返回为字符串 `"null"`；前端会把本地消息 id 更新为 `"null"`，是否是预期行为需要修正或确认。
- 排队拒绝没有 trace run，因此 dashboard 的 trace 成功率、错误率和延迟不包含拒绝流量；是否需要单独的 reject 指标？
- 首包后模型错误和 SSE 发送失败大概率不会把 trace run 改为 ERROR；dashboard 是否需要增加“流式完成状态”维度？
- `SseEmitterSender.sendEvent(...)` 在 closed 状态下直接抛 `ServiceException`，可能打断 `onComplete()` 后续的 `taskManager.unregister(...)`；是否需要更强的 fail-safe 清理？
- `trySetPermits(maxConcurrent)` 每次获取/查看 permit 时调用；运行中调整 `max-concurrent` 是否能即时生效，需要验证 Redisson 语义。

### Next Reading Target

下一轮建议读“前端 SSE 消费 + 管理端 Trace/Dashboard 展示模块”：本轮已经确认后端会产生 `meta/message/finish/done/cancel/reject`，但普通错误没有命名 `error` 事件，且 dashboard 指标依赖 trace run 口径。继续读前端展示可以验证用户实际看到的取消、拒绝、错误和 trace 指标是否与后端状态机一致。

## Phase 4 Module 6: 知识库写入 + 索引构建模块

### Reading Scope

本轮只围绕知识库创建、文档上传/URL 导入、文档分块、embedding、向量写入、删除/启用/禁用/重建、ingestion pipeline 状态记录和与检索一致性直接相关的代码展开。

本轮没有重复展开 `/rag/v3/chat` 对话运行时链路、MCP、SSE、Trace、限流取消和 RAG 检索编排；只在 `Data Consistency With Retrieval` 中读取了 pgvector/Milvus 检索实现和全局 collection 枚举逻辑，用于对照写入侧是否维护向量数据。

### Files Read

- `docs/reading/00-reading-plan.md`：确认只读、单目标、事实/推测分离规则。
- `docs/reading/01-project-map.md`：确认 `knowledge`、`ingestion`、`rag/core/vector`、`infra-ai` 所在模块。
- `docs/reading/04-module-notes.md`：确认已完成模块，避免重复展开对话主链路。
- `docs/reading/99-open-questions.md`：承接前面 RAG 检索留下的数据一致性问题。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`：确认知识库 CRUD 入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`：确认文档上传、分块、删除、启用禁用和分块日志入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeChunkController.java`：确认 chunk 增删改、启用禁用、批量操作和重建入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseService.java`、`KnowledgeDocumentService.java`、`KnowledgeChunkService.java`：确认服务接口边界。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`：确认知识库创建 bucket、创建向量空间和删除限制。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`：确认上传、异步分块、chunk/pipeline 处理、落库、删除、启用禁用和分块日志。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java`：确认 chunk 手动维护、embedding、向量同步删除和重建。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java`、`KnowledgeDocumentChunkTransactionChecker.java`、`event/KnowledgeDocumentChunkEvent.java`：确认分块任务通过 RocketMQ 事务消息异步执行。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/MessageQueueProducer.java`、`RocketMQProducerAdapter.java`、`DelegatingTransactionListener.java`、`MessageWrapper.java`：确认事务消息、本地事务和回查机制。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java`、`KnowledgeDocumentDO.java`、`KnowledgeChunkDO.java`、`KnowledgeDocumentChunkLogDO.java`：确认知识库、文档、chunk、分块日志字段和逻辑删除。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBaseCreateRequest.java`、`KnowledgeDocumentUploadRequest.java`、`KnowledgeDocumentUpdateRequest.java`、`KnowledgeChunkCreateRequest.java`、`KnowledgeChunkBatchRequest.java`：确认写入请求字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/SourceType.java`、`ProcessMode.java`、`DocumentStatus.java`、`ScheduleRunStatus.java`：确认来源、处理模式和状态枚举。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/handler/RemoteFileFetcher.java`：确认 URL 导入、远程变更检测、临时文件和大小限制。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/FileStorageService.java`、`impl/S3FileStorageService.java`：确认文件写入 RustFS/S3 兼容存储和 `s3://bucket/key` URL。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/filter/UploadRateLimitFilter.java`、`config/RagSemaphoreProperties.java`、`SemaphoreInitializer.java`：确认上传接口的 Redisson semaphore 限流。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentScheduleServiceImpl.java`、`schedule/KnowledgeDocumentScheduleJob.java`、`ScheduleRefreshProcessor.java`、`ScheduleStateManager.java`、`ScheduleLockManager.java`、`DocumentStatusHelper.java`、`config/KnowledgeScheduleProperties.java`：确认 URL 文档定时刷新、锁、执行记录和 RUNNING 恢复。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentScheduleDO.java`、`KnowledgeDocumentScheduleExecDO.java`：确认定时刷新主表和执行表字段。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/DocumentParserSelector.java`：确认文档解析器选择。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkEmbeddingService.java`、`VectorChunk.java`、`ChunkingMode.java`、`ChunkingStrategyFactory.java`：确认分块策略和 embedding 填充方式。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/EmbeddingService.java`、`RoutingEmbeddingService.java`、`SiliconFlowEmbeddingClient.java`、`OllamaEmbeddingClient.java`：确认 embedding 模型路由、批量调用和失败处理。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelSelector.java`、`config/AIModelProperties.java`：确认 embedding 候选模型选择。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`、`PgVectorStoreService.java`、`MilvusVectorStoreService.java`、`VectorStoreAdmin.java`、`PgVectorStoreAdmin.java`、`MilvusVectorStoreAdmin.java`、`VectorSpaceId.java`、`VectorSpaceSpec.java`：确认 pgvector/Milvus 写入、删除、更新和空间创建。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/controller/IngestionTaskController.java`、`IngestionPipelineController.java`：确认 ingestion pipeline API。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IngestionTaskServiceImpl.java`、`IngestionPipelineServiceImpl.java`：确认 ingestion task、pipeline、节点日志持久化。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java`：确认 pipeline 执行、节点跳过/失败和日志生成。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/FetcherNode.java`、`ParserNode.java`、`ChunkerNode.java`、`EnhancerNode.java`、`EnricherNode.java`、`IndexerNode.java`：确认 ingestion 节点职责和向量写入节点行为。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/domain/context/IngestionContext.java`、`NodeLog.java`、`domain/enums/IngestionStatus.java`、`IngestionNodeType.java`、`domain/result/NodeResult.java`、`domain/pipeline/PipelineDefinition.java`：确认 ingestion 上下文、状态和结果模型。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/dao/entity/IngestionPipelineDO.java`、`IngestionPipelineNodeDO.java`、`IngestionTaskDO.java`、`IngestionTaskNodeDO.java`：确认 ingestion 表映射。
- `resources/database/schema_pg.sql`：确认知识库、文档、chunk、分块日志、定时刷新、ingestion 和 pgvector 表结构。
- `bootstrap/src/main/resources/application.yaml`：确认默认向量后端、维度、embedding 候选、RocketMQ、上传限流、定时刷新和文件大小配置。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`、`MilvusRetrieverService.java`、`rag/core/retrieve/channel/VectorGlobalSearchChannel.java`：只为对照写入侧一致性，确认检索时是否依赖向量表物理维护。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`：确认 `knowledgeChunkExecutor`。

### Responsibility

知识库写入 + 索引构建模块负责把可上传或可拉取的文档变成可检索的向量数据：

- 创建知识库元数据、对象存储 bucket 和向量空间。
- 上传本地文件或拉取 URL 文件，保存文档元数据。
- 通过 RocketMQ 异步触发文档分块任务，把文档状态从 `pending` 推进到 `running/success/failed`。
- 对文件执行文本抽取、切分、embedding，并持久化 `t_knowledge_chunk` 和向量数据。
- 维护文档、chunk 启用/禁用/删除与向量数据之间的同步。
- 提供手动 chunk 增删改、批量启用禁用、按文档重建向量索引。
- 支持可配置 ingestion pipeline，对文档做 fetch/parse/enhance/chunk/enrich/indexer 等节点化处理。
- 对 URL 文档提供定时刷新、变更检测、分布式锁和执行记录。

### Flow Position

知识库创建：

`POST /knowledge-base`
-> `KnowledgeBaseController.createKnowledgeBase(...)`
-> `KnowledgeBaseServiceImpl.create(...)`
-> 插入 `t_knowledge_base`
-> 创建 S3/RustFS bucket
-> `VectorStoreAdmin.ensureVectorSpace(...)`
-> 返回 kbId。

文档上传与普通分块：

`POST /knowledge-base/{kb-id}/docs/upload`
-> `KnowledgeDocumentServiceImpl.upload(...)`
-> 校验 KB、sourceType、schedule、processMode
-> 本地文件走 `FileStorageService.upload(...)`，URL 走 `RemoteFileFetcher.fetchAndStore(...)`
-> 插入 `t_knowledge_document`，状态 `pending`
-> 用户调用 `POST /knowledge-base/docs/{doc-id}/chunk`
-> `startChunk(...)` 发送 RocketMQ 事务消息，并在本地事务里把文档置为 `running`
-> `KnowledgeDocumentChunkConsumer.onMessage(...)`
-> `executeChunk(...)`
-> `runChunkTask(...)`
-> `runChunkProcess(...)`
-> `persistChunksAndVectorsAtomically(...)`
-> 文档状态更新为 `success` 或失败时更新为 `failed`。

文档 pipeline 分块：

`upload` 时 `processMode=pipeline`
-> `runPipelineProcess(...)`
-> 从对象存储读原始文件 bytes
-> 构造 `IngestionContext(taskId=docId, vectorSpaceId=collectionName, skipIndexerWrite=true)`
-> `IngestionEngine.execute(...)`
-> pipeline 节点产生 `VectorChunk`
-> 回到 `persistChunksAndVectorsAtomically(...)` 统一写 `t_knowledge_chunk` 和向量库。

独立 ingestion task：

`POST /ingestion/tasks` 或 `/ingestion/tasks/upload`
-> `IngestionTaskServiceImpl.execute/upload(...)`
-> 插入 `t_ingestion_task`，状态 `running`
-> `IngestionEngine.execute(...)`
-> 保存 `t_ingestion_task_node`
-> 更新 `t_ingestion_task` 为 `completed/failed`。

### Knowledge Base / Document Write Flow

知识库：

- `KnowledgeBaseCreateRequest` 提供 `name`、`embeddingModel`、`collectionName`。
- 创建时先检查未删除知识库中是否存在同名记录。
- 插入 `t_knowledge_base`，保存 `embedding_model` 和 `collection_name`。
- 之后创建同名 S3/RustFS bucket。
- 再调用 `VectorStoreAdmin.ensureVectorSpace(...)`。
- 删除知识库前会检查该 KB 下仍有未删除文档则拒绝删除。
- 删除知识库只逻辑删除 `t_knowledge_base`，本轮未读到 drop bucket 或 drop Milvus collection/pgvector 数据表的逻辑。

文档上传：

- `KnowledgeDocumentUploadRequest.sourceType` 只支持 `file/url`，空值或非法值会抛异常。
- 本地文件上传走 `S3FileStorageService.upload(...)`，返回 `s3://bucket/key`、文件类型、大小和原文件名。
- URL 导入走 `RemoteFileFetcher.fetchAndStore(...)`，先尝试 HEAD 检查大小，再流式下载到临时文件，最后上传到对象存储。
- 文档记录保存到 `t_knowledge_document`，核心字段包括 `kb_id`、`doc_name`、`enabled=1`、`chunk_count=0`、`file_url`、`file_type`、`file_size`、`status=pending`、`source_type`、`process_mode`、`chunk_strategy/chunk_config` 或 `pipeline_id`。
- URL 文档如果开启定时，会保存 `schedule_enabled/schedule_cron/source_location`，并在 `startChunk(...)` 事务里 `upsertSchedule(...)`。

文档更新：

- `KnowledgeDocumentServiceImpl.update(...)` 禁止在 `running` 状态修改。
- 可以更新文档名、处理模式、分块配置、pipelineId、URL 来源和定时配置。
- 更新处理模式或分块配置不会自动重建 chunk/vector；需要后续再次触发分块或重建。

### Chunk And Embedding Flow

普通 chunk 模式：

1. `runChunkProcess(...)` 从对象存储 `fileUrl` 打开输入流。
2. 固定使用 `parserSelector.select(ParserType.TIKA.getType())` 抽取文本。
3. 根据 `chunkStrategy` 和 `chunkConfig` 选择 `ChunkingStrategy`。
4. 生成 `List<VectorChunk>`，每个 chunk 包含 `chunkId`、`index`、`content`、metadata。
5. 通过 `ChunkEmbeddingService.embed(chunks, embeddingModel)` 批量填充 embedding。
6. `persistChunksAndVectorsAtomically(...)` 先转换成 `KnowledgeChunkCreateRequest`，再统一落库。

pipeline 模式：

1. `runPipelineProcess(...)` 读取文件 bytes。
2. 加载 `IngestionPipelineService.getDefinition(pipelineId)`。
3. 执行 `IngestionEngine.execute(...)`。
4. `FetcherNode` 负责原始 bytes 或外部源获取。
5. `ParserNode` 使用 Tika 解析 rawText。
6. `EnhancerNode` 可对整篇文档调用 Chat 模型生成增强文本、关键词、问题或 metadata。
7. `ChunkerNode` 选择分块策略并调用 `ChunkEmbeddingService.embed(chunks, null)` 使用默认 embedding 模型。
8. `EnricherNode` 可对每个 chunk 调 Chat 模型补充 metadata。
9. `IndexerNode` 校验 embedding、填充 chunkId/metadata；在知识库文档 pipeline 模式下因为 `skipIndexerWrite=true` 不直接写向量库。
10. 文档服务拿到 result.chunks 后仍通过 `persistChunksAndVectorsAtomically(...)` 统一写 `t_knowledge_chunk` 和向量库。

手动 chunk：

- `KnowledgeChunkServiceImpl.create(...)` 会插入 `t_knowledge_chunk`、递增文档 `chunk_count`，然后同步 embedding 并写向量库。
- `batchCreate(docId, chunks, false)` 只写 DB，不写向量；文档分块主流程就是这样调用，然后由上层统一写向量。
- `batchCreate(docId, chunks, true)` 会批量 embedding 后写向量。
- `update(...)` 更新 chunk 内容、hash、字符数、token 数后，重新 embedding 并 `vectorStoreService.updateChunk(...)`。

### Vector Store Write And Delete Flow

统一接口：

- `VectorStoreService.indexDocumentChunks(...)`：批量写文档 chunk 向量。
- `updateChunk(...)`：更新单个 chunk 向量。
- `deleteDocumentVectors(...)`：删除一个文档下所有向量。
- `deleteChunkById(...)`：删除单个 chunk 向量。
- 具体实现由 `rag.vector.type` 切换；当前配置默认 `pg`。

pgvector：

- `PgVectorStoreService` 只在 `rag.vector.type=pg` 时装配。
- 所有知识库共享 `t_knowledge_vector`。
- 写入时执行 `INSERT INTO t_knowledge_vector (id, content, metadata, embedding)`。
- metadata 中保存 `collection_name`、`doc_id`、`chunk_index`。
- 删除文档向量时按 `metadata->>'collection_name'` 和 `metadata->>'doc_id'` 删除。
- 删除单个 chunk 时按 `id` 删除。
- `updateChunk(...)` 使用 `ON CONFLICT (id) DO UPDATE`。
- `PgVectorStoreAdmin.ensureVectorSpace(...)` 只确保全局 HNSW index 存在，不为每个 `collectionName` 建独立表或独立 index。

Milvus：

- `MilvusVectorStoreService` 和 `MilvusVectorStoreAdmin` 在 `rag.vector.type=milvus` 或缺失该配置时装配。
- 每个知识库 `collectionName` 对应 Milvus collection。
- collection 字段包括 `id`、`content`、`metadata`、`embedding`。
- 批量写入使用 `insert`，单 chunk 更新使用 `upsert`。
- 删除文档向量时使用 filter：`metadata["doc_id"] == "<docId>"`。
- 删除单个 chunk 时使用 filter：`id == "<chunkId>"`。
- `MilvusVectorStoreAdmin.ensureVectorSpace(...)` 如果 collection 已存在会抛 `VectorCollectionAlreadyExistsException`，不是幂等返回。

删除/禁用/重建：

- 删除文档：禁止 `running` 文档删除；删除该 doc 所有 chunk、schedule、chunk log，逻辑删除文档，再调用 `deleteDocumentVectors(collectionName, docId)`，最后尝试删除对象存储文件。
- 禁用文档：禁止 `running` 状态修改；更新文档 enabled，更新所有 chunk enabled；禁用时删除该文档所有向量。
- 启用文档：更新所有 chunk enabled，然后读取该文档全部 chunk，重新 embedding 并写入向量库。
- 删除 chunk：逻辑删除 chunk、减少文档 `chunk_count`，并删除该 chunk 向量。
- 禁用 chunk：更新 chunk enabled=0，并删除该 chunk 向量。
- 启用 chunk：要求所属文档 enabled=1，重新 embedding 并写该 chunk 向量。
- 批量启用 chunk：更新 enabled 后调用 `doRebuildByDocId(...)`，先删文档向量，再只读取 `enabled=1` 的 chunk 重建。
- 批量禁用 chunk：逐个删除被禁用 chunk 的向量。
- 重建文档向量：先删除该文档所有向量，再只读取 `enabled=1` 的 chunk，重新 embedding 后写入向量库。

### Ingestion Status And Failure Handling

文档分块状态：

- `t_knowledge_document.status` 使用 `pending/running/success/failed`。
- `startChunk(...)` 通过 RocketMQ 事务消息发送分块事件，并在本地事务中把非 running 文档改成 `running`。
- `KnowledgeDocumentChunkTransactionChecker` 回查时根据 DB 中文档状态是否为 `running` 决定事务消息是否 committed。
- `KnowledgeDocumentChunkConsumer` 消费后调用 `executeChunk(...)`。
- `runChunkTask(...)` 会插入 `t_knowledge_document_chunk_log`，初始状态 `running`。
- 成功后更新 chunk log 为 `success`，记录 `extractDuration`、`chunkDuration`、`embedDuration`、`persistDuration`、`totalDuration`、`chunkCount`。
- 失败后 `markChunkFailed(...)` 用 `REQUIRES_NEW` 把文档标记 `failed`，并把 chunk log 标记 `failed`、记录 errorMessage。
- `runChunkTask(...)` 自己 catch 异常并更新状态，异常不会继续抛给 RocketMQ consumer；因此本轮未读到消费失败后由 MQ 自动重试分块的逻辑。
- 文档分块没有百分比进度字段，只有文档状态和 chunk log 阶段耗时。

半成品风险：

- 普通 chunk 模式在进入 `persistChunksAndVectorsAtomically(...)` 前，只完成解析、分块、embedding，尚未清理旧 chunk/vector；这些阶段失败时旧索引保留，文档标记 failed。
- `persistChunksAndVectorsAtomically(...)` 的代码顺序是：删除旧 chunk、插入新 chunk、删除旧向量、写新向量、更新文档 `chunk_count/status=success`。
- 当前默认 pgvector 下，chunk 表和 vector 表都在 PostgreSQL，且写入发生在 `TransactionTemplate` 中；按代码意图，它们应作为同一数据库事务提交或回滚。
- Milvus 写入发生在外部服务，不受数据库事务回滚保护；如果在删除/写入 Milvus 后数据库事务失败，可能留下 Milvus 与业务表不一致。
- 手动 chunk create/update/delete/enable/disable 同样把 DB 更新和向量库操作放在 `@Transactional` 方法内；对 pgvector 更容易同事务，对 Milvus 仍存在外部副作用无法回滚的边界。

独立 ingestion task：

- `/ingestion/tasks` 会创建 `t_ingestion_task`，状态初始 `running`。
- `IngestionEngine.execute(...)` 串行执行 pipeline 节点，遇到失败节点会把 context status 设为 `failed` 并停止。
- 每个节点执行后写入内存 `NodeLog`；任务结束后由 `IngestionTaskServiceImpl.saveNodeLogs(...)` 批量插入 `t_ingestion_task_node`。
- 任务结束后更新 `t_ingestion_task.status` 为 `completed/failed`，保存 `chunk_count`、`error_message`、`logs_json`、`metadata_json`、`completed_at`。
- `/ingestion/tasks` 是同步执行，代码里没有读到后台队列、重试或分段进度更新。
- 知识库文档的 `processMode=pipeline` 没有创建 `t_ingestion_task` 记录；它只复用 `IngestionEngine`，最终状态仍落在 `t_knowledge_document` 和 `t_knowledge_document_chunk_log`。

定时刷新：

- URL 文档可启用 schedule，持久化到 `t_knowledge_document_schedule`。
- `KnowledgeDocumentScheduleJob.scan()` 定期扫描到期 schedule，用 DB 字段 `lock_owner/lock_until` 领取锁，再提交到 `knowledgeChunkExecutor`。
- `ScheduleRefreshProcessor` 会检查文档存在、未删除、已启用、URL 和 cron 有效，远程文件未变化时记录 skipped。
- 文件变化后先把文档抢占为 running，再上传新文件并调用 `documentService.chunkDocument(runtimeDoc)` 重建 chunk/vector。
- 刷新成功后切换文档文件元数据；失败时尽量把文档从 running 改为 failed，并写 `t_knowledge_document_schedule_exec`。
- `recoverStuckRunningDocuments()` 每分钟把超时 running 文档改为 failed，默认阈值配置类为 30 分钟，实际方法至少取 10 分钟。

### Data Consistency With Retrieval

前面 RAG 检索模块看到的问题，本轮对照结论如下：

- pgvector 检索确实只查 `t_knowledge_vector`：`WHERE metadata->>'collection_name' = ?`，没有 join `t_knowledge_document` 或 `t_knowledge_chunk`。
- Milvus 检索也只查目标 collection，没有按 document/chunk enabled/deleted 过滤。
- 全局向量检索只从 `t_knowledge_base` 读取 `deleted=0` 的 `collectionName`，没有文档级或 chunk 级过滤。
- 因此禁用/删除文档或 chunk 不被召回，依赖写入侧物理删除向量，而不是检索时动态过滤。
- 本轮已读写入侧在文档删除、文档禁用、chunk 删除、chunk 禁用、批量禁用、重建时会同步删除相关向量。
- 文档启用、chunk 启用和重建时会重新 embedding 并写入向量。
- `t_knowledge_vector.metadata` 保存 `collection_name/doc_id/chunk_index`，没有保存 `kb_id`、document enabled/deleted、chunk enabled/deleted 或用户权限字段。
- 只要写入侧同步成功，pgvector 只查 `t_knowledge_vector` 也可以避免召回禁用/删除的文档；但这是应用层一致性保证，不是数据库查询约束。
- 如果向量同步失败、Milvus 外部副作用与 DB 事务不一致、或存在未走服务方法的直接 DB 修改，检索侧没有第二道过滤兜底。
- `startChunk(...)` 本轮未看到对文档 enabled=1 的显式校验；如果禁用文档仍能触发重新分块并写入向量，可能破坏“禁用文档不召回”的约定，需要验证接口或前端是否阻止。
- 知识库删除前要求无未删除文档，但不 drop 向量空间；如果此前已经存在脏向量，删除 KB 后全局检索不会枚举该 collection，但物理数据仍可能保留。
- 权限问题本轮仍未看到用户级/组织级过滤字段进入 vector metadata；写入链路没有补齐前面检索模块留下的权限边界问题。

### Core Classes And Methods

- `KnowledgeBaseServiceImpl.create(...)`：创建知识库、bucket 和向量空间。
- `KnowledgeBaseServiceImpl.delete(...)`：无未删除文档时逻辑删除知识库。
- `KnowledgeDocumentServiceImpl.upload(...)`：上传/URL 导入文件并插入文档记录。
- `KnowledgeDocumentServiceImpl.startChunk(...)`：发送分块事务消息并把文档置为 running。
- `KnowledgeDocumentServiceImpl.executeChunk(...)` / `runChunkTask(...)`：执行文档分块主任务，写分块日志和状态。
- `KnowledgeDocumentServiceImpl.runChunkProcess(...)`：Tika 抽取、分块、embedding。
- `KnowledgeDocumentServiceImpl.runPipelineProcess(...)`：执行 ingestion pipeline，返回 VectorChunk。
- `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically(...)`：清旧 chunk/vector、写新 chunk/vector、更新文档状态。
- `KnowledgeDocumentServiceImpl.delete(...)`：删除文档、chunk、日志、schedule、向量和对象存储文件。
- `KnowledgeDocumentServiceImpl.enable(...)`：文档启用/禁用并同步 chunk 与向量。
- `KnowledgeChunkServiceImpl.create/update/delete/enableChunk/batchEnable/batchDisable/rebuildByDocId(...)`：手动维护 chunk 和向量。
- `ChunkEmbeddingService.embed(...)`：根据指定 embeddingModel 或默认模型为 VectorChunk 填充 embedding。
- `RoutingEmbeddingService.embedBatch(...)`：通过模型候选和 fallback 调用具体 embedding client。
- `PgVectorStoreService`：写入和删除 `t_knowledge_vector`。
- `MilvusVectorStoreService`：写入、upsert、删除 Milvus collection 内向量。
- `PgVectorStoreAdmin.ensureVectorSpace(...)`：确保 pgvector HNSW 索引存在。
- `MilvusVectorStoreAdmin.ensureVectorSpace(...)`：创建 Milvus collection 和 HNSW 索引。
- `IngestionEngine.execute(...)`：校验 pipeline、找起点、按 `nextNodeId` 串行执行节点并生成日志。
- `FetcherNode` / `ParserNode` / `EnhancerNode` / `ChunkerNode` / `EnricherNode` / `IndexerNode`：pipeline 节点实现。
- `IngestionTaskServiceImpl.executeInternal(...)`：独立 ingestion task 的任务和节点日志持久化。
- `ScheduleRefreshProcessor.process(...)`：URL 定时刷新、变更检测、重建和调度执行状态写回。
- `DocumentStatusHelper.recoverStuckRunning(...)`：恢复卡住的 running 文档。

### Storage

关系表：

- `t_knowledge_base`：知识库元数据，保存 `embedding_model`、`collection_name`、逻辑删除。
- `t_knowledge_document`：文档元数据，保存 enabled、chunk_count、file_url、file_type、process_mode、status、source、schedule、chunk/pipeline 配置和逻辑删除。
- `t_knowledge_chunk`：chunk 正文、hash、char/token 统计、enabled 和逻辑删除。
- `t_knowledge_document_chunk_log`：每次文档分块的状态、处理模式、阶段耗时、chunk_count、错误、开始/结束时间。
- `t_knowledge_document_schedule`：URL 文档定时刷新配置、上次结果、远程文件 ETag/Last-Modified/contentHash、DB 锁字段。
- `t_knowledge_document_schedule_exec`：每次定时刷新执行记录。
- `t_ingestion_pipeline`、`t_ingestion_pipeline_node`：pipeline 定义和节点配置。
- `t_ingestion_task`、`t_ingestion_task_node`：独立 ingestion task 状态和节点运行记录。
- `t_knowledge_vector`：pgvector 向量表，字段为 `id/content/metadata/embedding`，无 enabled/deleted 字段。

外部存储和服务：

- PostgreSQL：知识库业务表、ingestion 表、pgvector 表。
- Milvus：`rag.vector.type=milvus` 时的向量 collection。
- Redis/Redisson：上传接口 semaphore。
- RocketMQ：文档分块事务消息。
- RustFS/S3 兼容对象存储：原始文件存储。
- Embedding Provider：默认 SiliconFlow，其次 Ollama，维度来自 `rag.default.dimension=1536`。
- Chat Provider：pipeline enhancer/enricher 节点会调用 Chat 模型。
- `knowledgeChunkExecutor`：定时刷新任务执行线程池；普通文档分块由 RocketMQ consumer 线程触发。

关键配置：

- `spring.servlet.multipart.max-file-size=50MB`
- `spring.servlet.multipart.max-request-size=100MB`
- `rocketmq.name-server=127.0.0.1:9876`
- `rag.vector.type=pg`
- `rag.default.collection-name=rag_default_store`
- `rag.default.dimension=1536`
- `rag.semaphore.document-upload.name=rag:document:upload`
- `rag.semaphore.document-upload.max-concurrent=10`
- `rag.semaphore.document-upload.max-wait-seconds=5`
- `rag.semaphore.document-upload.lease-seconds=300`
- `rag.knowledge.schedule.scan-delay-ms=10000`
- `rag.knowledge.schedule.lock-seconds=900`
- `rag.knowledge.schedule.batch-size=20`
- `rag.knowledge.schedule.min-interval-seconds=60`
- `ai.embedding.default-model=qwen-emb-8b`

### Verified Facts

- 文档上传不会自动分块；上传后状态是 `pending`，需要调用 `/knowledge-base/docs/{doc-id}/chunk`。
- 文档分块通过 RocketMQ 事务消息异步执行，本地事务负责把文档状态改成 `running`。
- 分块任务失败会把文档状态改成 `failed`，并记录一条 failed chunk log。
- 分块任务异常被 `runChunkTask(...)` 捕获，consumer 不会因该异常自然触发 RocketMQ 重试。
- 普通 chunk 模式固定使用 Tika parser 抽取文本。
- pipeline 模式复用 `IngestionEngine`，但在知识库文档场景下 `skipIndexerWrite=true`，不由 `IndexerNode` 直接写向量。
- 知识库文档 pipeline 模式不会创建 `t_ingestion_task` 和 `t_ingestion_task_node`，只会记录 `t_knowledge_document_chunk_log`。
- 独立 `/ingestion/tasks` 会写 `t_ingestion_task` 和 `t_ingestion_task_node`。
- `persistChunksAndVectorsAtomically(...)` 每次重跑会先删除旧 chunk，再插入新 chunk，再删除旧向量，再写新向量。
- 文档删除、文档禁用、chunk 删除、chunk 禁用都会删除对应向量。
- 文档启用、chunk 启用、批量启用和重建都会重新 embedding 并写入向量。
- 文档禁用会同时调用 `knowledgeChunkService.updateEnabledByDocId(...)` 把所有 chunk enabled 改掉。
- `updateEnabledByDocId(...)` 只更新 chunk 表，不直接处理向量；文档服务在禁用/启用分支里另行处理向量。
- 重建向量只读取 `enabled=1` 的 chunk。
- pgvector 写入表 `t_knowledge_vector` 不保存 enabled/deleted 字段。
- pgvector 检索只按 `collection_name` 过滤并按向量距离排序。
- Milvus 检索只查 collection，也没有 document/chunk enabled 过滤。
- `rag.vector.type` 默认是 `pg`；Milvus 实现带 `matchIfMissing=true`。
- `PgVectorStoreAdmin` 的空间确保逻辑是检查/创建全局 HNSW index，不检查具体 collectionName。
- `MilvusVectorStoreAdmin.ensureVectorSpace(...)` 在 collection 已存在时抛异常。
- 删除知识库前要求无未删除文档；删除知识库不会 drop 向量空间或 bucket。
- URL 定时刷新使用 DB 字段 `lock_owner/lock_until` 做分布式锁，不是 Redis 锁。
- 系统有 `recoverStuckRunningDocuments()` 定时把长时间 running 文档恢复为 failed。

### Assumptions

- 对 pgvector 当前默认链路，`TransactionTemplate` 内的 `JdbcTemplate` 向量写入应参与同一个 PostgreSQL 事务；这符合 Spring 事务常规行为，但本轮没有运行验证。
- `TableLogic` 会让 MyBatis Plus 默认查询/删除过滤逻辑删除记录；本轮以实体注解和服务层显式 `deleted=0` 为依据，没有运行 SQL 验证最终生成语句。
- 文档禁用后再次启用时，代码假设禁用分支已经删除了该文档全部向量；否则 pgvector 的批量 `INSERT` 可能遇到重复主键。

### Open Questions

- `startChunk(...)` 本轮未看到文档 enabled=1 的显式校验；禁用文档是否仍可通过接口触发分块并重新写入向量，需要前端限制或接口测试验证。
- `persistChunksAndVectorsAtomically(...)` 对 Milvus 不具备真正原子性；如果 Milvus 删除/写入成功但 DB 事务回滚，是否需要补偿任务或一致性巡检？
- pgvector 批量 `indexDocumentChunks(...)` 使用普通 INSERT，不是 upsert；文档启用、chunk 启用或重复重建时是否可能出现重复主键，需要运行覆盖。
- 知识库删除不 drop bucket 或 Milvus collection；长期使用是否会产生空 collection/bucket，需要确认运维清理策略。
- 知识库文档 pipeline 模式不写 `t_ingestion_task_node`，因此无法查看每个 pipeline 节点的持久化日志；是否符合管理端排障预期需要确认。
- 文档更新处理模式、分块策略或 pipelineId 后不会自动清理旧向量或重建；是否会造成“配置已变但索引仍旧”的用户误解，需要产品交互确认。
- `t_knowledge_vector.metadata` 不包含权限字段，写入链路也没有用户/组织级 ACL；企业知识库权限隔离仍需要后续设计或验证。
- 文档分块失败后，默认没有 MQ 自动重试；是否需要用户手动重试、失败重试次数或补偿队列，需要确认产品要求。
- `ProcessMode.normalize(...)` 对空值直接抛异常，但 DB schema 默认是 `chunk`；上传接口是否总是传 processMode，需要前端或接口联调验证。
- Milvus 和 pgvector 的 `ensureVectorSpace` 语义不等价：pg 是全局表/index，Milvus 是每 KB collection；迁移或切换后已有数据如何处理需要验证。

### Next Reading Target

下一轮建议读“知识库管理前端 + 文档处理页面交互”：本轮已经确认后端依赖写入侧物理维护向量来保证 enabled/deleted 不被召回，还留下禁用文档是否能触发分块、processMode 是否必传、失败后如何重试等问题。继续读前端可以验证这些后端边界是否被 UI 约束住，以及用户在上传、分块、失败、重建、禁用时实际能看到哪些状态和操作。

## Phase 4 Module 7: 知识库管理前端 + 文档处理交互

### Reading Scope

本轮只阅读前端中与知识库管理、文档上传/URL 导入、文档状态、分块触发、分块日志、chunk 管理、数据通道选择和 API 调用封装直接相关的文件。

本轮没有重复阅读后端 chunk、embedding、vector store 的实现细节；只基于 Module 6 已记录的后端结论，对照前端是否约束住这些后端风险。

### Files Read

- `docs/reading/00-reading-plan.md`：确认只读、单目标、事实/推测分离规则。
- `docs/reading/01-project-map.md`：确认前端目录、后端知识库和 ingestion 包位置。
- `docs/reading/04-module-notes.md`：读取 Module 6 的知识库写入和索引构建结论，避免重复总结后端实现。
- `docs/reading/99-open-questions.md`：读取 Module 6 遗留问题，逐项对照前端交互是否能兜住。
- `frontend/src/router.tsx`：确认知识库、文档、chunk 和 ingestion 页面路由入口。
- `frontend/src/pages/admin/AdminLayout.tsx`：确认后台菜单、知识库/文档全局搜索和入口跳转。
- `frontend/src/components/admin/CreateKnowledgeBaseDialog.tsx`：确认创建知识库表单、字段校验和 embedding 模型来源。
- `frontend/src/pages/admin/knowledge/KnowledgeListPage.tsx`：确认知识库列表、创建、重命名、删除确认和统计展示。
- `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx`：确认文档上传、URL 导入、状态筛选、分块触发、编辑、启用禁用、删除和分块日志交互。
- `frontend/src/pages/admin/knowledge/KnowledgeChunksPage.tsx`：确认 chunk 列表、创建、编辑、删除、启用禁用、批量启停和重建向量交互。
- `frontend/src/pages/admin/ingestion/IngestionPage.tsx`：确认数据通道 pipeline 管理、独立 ingestion task 状态和节点日志页面。
- `frontend/src/services/knowledgeService.ts`：确认前端调用的 knowledge-base、docs、chunks 和 chunk-logs API。
- `frontend/src/services/ingestionService.ts`：确认前端调用的 ingestion pipeline/task API。
- `frontend/src/services/settingsService.ts`：确认上传大小限制和 embedding 模型候选来自系统设置接口。
- `frontend/src/services/api.ts`：确认 Axios 响应解包、错误 toast 和 Authorization 注入。

### Responsibility

知识库管理前端负责把 Module 6 的后端能力暴露给管理员使用：

- 管理知识库列表、创建、重命名和删除。
- 进入某个知识库后管理文档，支持本地文件上传和远程 URL 导入。
- 在上传或编辑时选择 `processMode=chunk/pipeline`、分块策略、chunkConfig 或 pipelineId。
- 展示文档 `pending/running/success/failed` 状态，并允许按状态筛选。
- 触发文档分块或重新分块，查看最近一次分块日志。
- 管理 chunk 列表，支持手动新增、编辑、删除、启用/禁用、批量启停和重建向量。
- 管理 ingestion pipeline，并查看独立 ingestion task 的任务状态和节点日志。

### Page And Route Entry

- `/admin` 受 `RequireAdmin` 保护，非 admin 用户会被重定向。
- `/admin/knowledge` 对应 `KnowledgeListPage`，是知识库列表入口。
- `/admin/knowledge/:kbId` 对应 `KnowledgeDocumentsPage`，是文档管理入口。
- `/admin/knowledge/:kbId/docs/:docId` 对应 `KnowledgeChunksPage`，是 chunk 管理入口。
- `/admin/ingestion?tab=pipelines` 和 `/admin/ingestion?tab=tasks` 对应 `IngestionPage`，分别管理数据通道和通道任务。
- `AdminLayout` 菜单中有“知识库管理”和“数据通道”入口。
- `AdminLayout` 顶部搜索会同时调用 `getKnowledgeBases(...)` 和 `searchKnowledgeDocuments(...)`，选中文档后直接跳到 chunk 管理页。

### Knowledge Base UI Flow

创建知识库：

- `KnowledgeListPage` 点击“新建知识库”打开 `CreateKnowledgeBaseDialog`。
- 创建表单字段是 `name`、`embeddingModel`、`collectionName`。
- `embeddingModel` 来自 `/rag/settings` 返回的 `ai.embedding.candidates`，并过滤 `enabled !== false` 的候选。
- `collectionName` 前端校验为必填、最长 50、只能包含小写英文字母和数字。
- 提交时调用 `createKnowledgeBase(...) -> POST /knowledge-base`。
- 成功后 toast “创建成功”，关闭对话框并刷新列表。

列表和统计：

- `KnowledgeListPage` 使用 `getKnowledgeBasesPage(...)` 分页加载知识库。
- 页面还额外分页拉取较大页大小的数据，计算知识库总数、文档数、含文档知识库数和创建用户数。
- 点击知识库名称进入 `/admin/knowledge/:kbId`。

重命名和删除：

- 重命名只调用 `PUT /knowledge-base/{id}`，请求体只有 `name`。
- 删除有确认框，文案提示“知识库删除后当前不提供恢复入口”。
- 前端提供删除入口；按 Module 6，后端会拒绝仍有未删除文档的知识库删除。前端没有预先隐藏或禁用该按钮。

### Document Upload / Import Flow

文档上传入口：

- `KnowledgeDocumentsPage` 点击“上传文档”打开内部 `UploadDialog`。
- 上传对话框支持 `sourceType=file/url`。
- 本地文件模式要求选择文件，并用 `/rag/settings` 中的 `upload.maxFileSize` 做前端大小校验。
- URL 模式要求填写 `sourceLocation`。
- URL 模式可勾选“开启定时拉取”，勾选后要求填写 `scheduleCron`。

处理模式和字段：

- 上传表单的 `processMode` 使用 zod 默认值 `chunk`，选项为 `chunk` 和 `pipeline`。
- `chunk` 模式要求选择 `chunkStrategy`，并按策略要求填写数字配置。
- 默认上传配置是 `processMode=chunk`、`chunkStrategy=fixed_size`、`chunkSize=512`、`overlapSize=128`。
- `fixed_size` 模式有“不分块”开关，打开后把 `chunkSize` 设置为 `-1`。
- `pipeline` 模式会加载 `/ingestion/pipelines`，并要求选择 `pipelineId`。
- 提交时 `KnowledgeDocumentsPage` 构造 payload：总是带 `processMode`；chunk 模式带 `chunkStrategy/chunkConfig`；pipeline 模式带 `pipelineId`。
- `knowledgeService.uploadDocument(...)` 的类型上允许 `processMode`、`chunkStrategy`、`pipelineId` 可选，但当前上传页面实际会通过表单默认值和校验传入 `processMode`。

提交结果：

- 上传成功只 toast “上传成功”并刷新文档列表。
- 上传成功后不会自动调用 `/knowledge-base/docs/{docId}/chunk`，用户需要再点“分块”按钮。

### Chunk / Rebuild / Retry Flow

文档分块入口：

- 文档列表每行都有“分块”图标按钮，点击后设置 `chunkTarget` 并打开确认框。
- 如果 `chunkCount` 存在，确认框标题为“重新分块？”，并提示会清空原有 Chunk 记录及向量数据。
- 如果没有 chunk，确认框标题为“开始分块？”，提示将开始分块并写入向量库。
- 确认后调用 `startDocumentChunk(...) -> POST /knowledge-base/docs/{docId}/chunk`。
- 成功后 toast “已开始分块”并刷新当前列表。

失败重试：

- 页面没有单独的“重试失败”按钮。
- failed 文档仍然显示同一个“分块”按钮，因此用户可以通过再次点击“分块”手动重试。
- 前端不会自动重试，也没有读取或设置重试次数。

运行中状态：

- 文档列表展示 `status` 原始值，并用圆点颜色区分：`pending` 灰、`running` 黄、`failed` 红、`success` 绿。
- 页面支持按 `pending/running/failed/success` 筛选。
- 分块开始后页面只刷新一次；本轮未读到轮询、SSE 或自动刷新 running 状态的逻辑。

分块日志：

- “分块详情”按钮调用 `getChunkLogsPage(docId, 1, 1)`。
- 日志弹窗只展示最近一条记录，包括状态、处理模式、chunk 数、阶段耗时、执行时间和错误信息。
- 独立 ingestion task 页面可以展示 task node 日志；知识库文档 pipeline 模式在文档页只展示 chunk log，不展示每个 pipeline 节点的持久化日志。

chunk 管理和重建：

- 进入文档名称链接会跳转到 `KnowledgeChunksPage`。
- chunk 页支持筛选启用状态、手动新建 chunk、编辑 chunk、删除 chunk、单个启用/禁用、选中后批量启用/禁用、全量启用/禁用。
- “重建向量”有确认框，文案说明会基于当前启用的分块重新生成向量。
- 重建成功只 toast “重建完成”；本轮未读到重建中的进度展示。

### Enable / Disable / Delete Interactions

文档启用/禁用：

- 文档列表每行用 switch 直接调用 `PATCH /knowledge-base/docs/{docId}/enable?value=...`。
- 文档启用/禁用没有确认框。
- 前端没有按 `status=running` 禁用该 switch；如果后端拒绝，只会通过错误 toast 反馈。

文档删除：

- 文档删除有确认框，文案说明“文档将被删除，且向量数据会清理”。
- 前端没有按 `status=running` 隐藏或禁用删除按钮；按 Module 6，running 文档删除由后端拒绝。

文档编辑：

- 文档编辑会先调用 `GET /knowledge-base/docs/{docId}` 获取详情。
- 编辑弹窗允许修改文档名、处理模式、chunk 策略或 pipelineId。
- URL 文档还可修改来源地址、定时拉取开关和 cron。
- 弹窗描述明确写着“保存后需重新分块才会生效”。
- 保存后只调用 `PUT /knowledge-base/docs/{docId}` 并刷新列表，不自动触发重新分块。

chunk 操作：

- chunk 单个删除有确认框，文案说明向量会清理。
- 全量启用/禁用有确认框。
- 单个启用/禁用、选中后批量启用/禁用没有确认框。
- chunk 页没有根据文档 `enabled` 或文档 `status` 禁用新建、编辑、启停、删除、重建按钮。

知识库删除：

- 知识库删除有确认框。
- 前端没有展示后端实际的“有文档时不可删除”规则，只在后端返回错误时 toast。

### API Contracts Used By Frontend

知识库：

- `GET /knowledge-base`：知识库分页列表和后台搜索。
- `GET /knowledge-base/{id}`：文档页加载知识库名称和 collectionName。
- `POST /knowledge-base`：创建知识库。
- `PUT /knowledge-base/{id}`：重命名或更新知识库。
- `DELETE /knowledge-base/{id}`：删除知识库。

文档：

- `GET /knowledge-base/{kbId}/docs`：文档分页、状态筛选和关键词搜索。
- `GET /knowledge-base/docs/search`：后台顶部文档搜索。
- `POST /knowledge-base/{kbId}/docs/upload`：文件上传或 URL 导入，使用 multipart form。
- `GET /knowledge-base/docs/{docId}`：加载文档详情。
- `PUT /knowledge-base/docs/{docId}`：保存文档配置。
- `POST /knowledge-base/docs/{docId}/chunk`：开始分块或重新分块。
- `PATCH /knowledge-base/docs/{docId}/enable?value=true|false`：启用或禁用文档。
- `DELETE /knowledge-base/docs/{docId}`：删除文档。
- `GET /knowledge-base/docs/{docId}/chunk-logs`：读取分块日志。

chunk：

- `GET /knowledge-base/docs/{docId}/chunks`：chunk 分页，可按 enabled 过滤。
- `POST /knowledge-base/docs/{docId}/chunks`：新建 chunk。
- `PUT /knowledge-base/docs/{docId}/chunks/{chunkId}`：更新 chunk 内容。
- `DELETE /knowledge-base/docs/{docId}/chunks/{chunkId}`：删除 chunk。
- `POST /knowledge-base/docs/{docId}/chunks/{chunkId}/enable`：启用 chunk。
- `POST /knowledge-base/docs/{docId}/chunks/{chunkId}/disable`：禁用 chunk。
- `POST /knowledge-base/docs/{docId}/chunks/batch-enable`：批量或全量启用 chunk。
- `POST /knowledge-base/docs/{docId}/chunks/batch-disable`：批量或全量禁用 chunk。
- `POST /knowledge-base/docs/{docId}/chunks/rebuild`：基于当前启用 chunk 重建向量。

数据通道：

- `GET /ingestion/pipelines`：上传、编辑文档和 ingestion 页面选择 pipeline。
- `POST/PUT/DELETE /ingestion/pipelines`：管理 pipeline。
- `GET /ingestion/tasks`：查看独立 ingestion task。
- `GET /ingestion/tasks/{id}` 和 `/ingestion/tasks/{id}/nodes`：查看独立 task 和节点日志。
- `POST /ingestion/tasks`、`POST /ingestion/tasks/upload`：创建独立 ingestion task。

### Frontend Constraints Over Backend Risks

对 Module 6 开放问题的前端约束结论：

- 禁用文档是否仍可触发分块：前端没有约束住。文档即使 disabled，仍然能看到并点击“分块”按钮；如果后端 `startChunk(...)` 无 enabled 校验，直接 UI 操作也可能触发重新写向量。
- running 文档操作：前端没有按 `status=running` 禁用编辑、删除、启用/禁用或分块按钮；按 Module 6，部分操作由后端拒绝。这里是“前端有入口，但后端接口不允许部分场景”，不是“前端没有入口”。
- `processMode` 空值：上传页面通过默认值和 zod 校验保证会传 `processMode`。但 `knowledgeService.uploadDocument(...)` 的 API 封装仍把该字段建模为可选；绕过上传对话框或直接调接口时，后端仍需要兜底。
- 分块失败后重试：前端提供事实上的手动重试入口，即再次点击“分块”；但没有专门的 retry 按钮、重试次数、自动重试或失败补偿状态。
- 配置变更后索引仍旧：编辑弹窗有“保存后需重新分块才会生效”的提示；但保存后不自动分块，也没有保存后的强提示或待重建状态。因此只能降低误解，不能保证索引已更新。
- 文档/chunk enabled 与检索一致性：前端暴露启停操作，但最终是否同步向量仍完全依赖后端。前端没有检索侧兜底，也没有向量一致性检查入口。
- Milvus 与 DB 事务不一致：前端无入口检测或修复外部向量库半成功状态。
- pgvector 普通 INSERT 重复主键：前端会触发启用、批量启用、重建等操作，但没有重复执行保护；是否重复主键由后端处理。
- 知识库删除不 drop bucket/collection：前端有知识库删除入口，但没有物理存储清理入口，也没有展示残留 bucket/collection 状态。
- 知识库文档 pipeline 节点日志：前端 ingestion task 页能展示独立 task 的节点日志；知识库文档页只能看最近一次 chunk log，无法查看文档 pipeline 每个节点的日志。
- 权限/ACL：前端后台路由要求 admin，但这不等于知识库检索权限隔离。vector metadata 缺少 ACL 的后端问题没有被前端解决。
- pgvector/Milvus 切换和迁移：前端没有配置切换、迁移或重建全库向量的入口。

明确区分：

- 前端没有入口：向量库一致性巡检、Milvus 补偿、drop bucket/collection、ACL metadata 配置、全库 vector 后端迁移。
- 前端有入口但不做本地限制：禁用文档分块、running 文档分块/编辑/删除/启停、disabled 文档下进入 chunk 页重建或批量启停。
- 后端接口不允许的场景：按 Module 6，running 文档删除、更新、启用禁用由后端拒绝；知识库有未删除文档时删除由后端拒绝。前端只是发起请求并展示错误。

### Verified Facts

- 知识库管理、文档管理、chunk 管理和 ingestion 页面都只在 `/admin` 下，且路由受 `RequireAdmin` 保护。
- 创建知识库前端强制填写 embeddingModel 和 collectionName，并限制 collectionName 只能包含小写字母和数字。
- 上传页面支持本地文件和 URL 导入。
- 上传页面默认 `processMode=chunk`，并在提交 payload 中传递 `processMode`。
- chunk 模式上传会传 `chunkStrategy` 和 `chunkConfig`。
- pipeline 模式上传要求选择 `pipelineId`。
- 上传成功不会自动开始分块。
- 文档状态以原始字符串展示，并提供 `pending/running/failed/success` 筛选。
- 文档失败后仍可通过同一个“分块”按钮手动重试。
- 文档分块按钮没有根据 `enabled=false` 禁用。
- 文档分块按钮没有根据 `status=running` 禁用。
- 文档启用/禁用 switch 没有确认框，也没有根据 running 状态禁用。
- 文档删除有确认框，文案说明会清理向量数据。
- 文档编辑弹窗提示“保存后需重新分块才会生效”。
- 保存文档处理模式、分块策略或 pipelineId 后不会自动触发重新分块。
- 分块日志弹窗只请求 `page=1,size=1`，实际只展示最近一条日志。
- chunk 页暴露新建、编辑、删除、启用、禁用、批量启停、全量启停和重建向量入口。
- chunk 重建有确认框，文案说明基于当前启用分块重建。
- chunk 页没有根据文档 enabled/status 禁用操作按钮。
- ingestion 页面可以管理 pipeline，也可以查看独立 ingestion task 的状态和节点日志。
- 知识库文档 pipeline 模式的文档页没有展示 ingestion task node 级日志。

### Assumptions

- 本轮只验证了前端源码中的交互条件，没有运行浏览器实际点击页面。
- “前端能约束”只表示普通管理员通过当前 UI 操作时的约束；不能覆盖直接调用后端接口、浏览器控制台调用 service 或构造 HTTP 请求。
- 页面中的 toast 错误展示依赖后端返回错误信息和 Axios 拦截器行为，本轮没有运行接口验证实际错误文案。

### Open Questions

- 禁用文档仍可在前端点击“分块”；如果后端没有 enabled 校验，UI 本身不能阻止禁用文档重新写入向量，是否应在前后端都加保护？
- running 文档仍显示编辑、删除、启停和分块入口；是否应在前端按状态禁用这些按钮，减少依赖后端错误 toast？
- 文档编辑后只提示“需重新分块才会生效”，但没有待重建状态或一键保存并重建；是否会继续造成“配置已变但索引仍旧”的误解？
- 文档分块开始后没有轮询或自动刷新；用户是否需要手动刷新才能看到 running -> success/failed 的变化？
- 分块日志只展示最近一条；失败排障是否需要分页查看历史日志？
- chunk 页没有根据文档 disabled/running 状态限制重建、批量启用或新建 chunk；是否需要后端进一步兜底，尤其是 disabled 文档下的向量重建？
- ingestion pipeline 删除前端没有提示该 pipeline 是否被知识库文档引用；删除被引用 pipeline 后，文档重新分块会如何失败需要验证。
- `knowledgeService.uploadDocument(...)` 类型仍允许不传 `processMode`；是否应把 API 封装层也改成必填，避免未来页面绕过表单校验？
- 单个文档启用/禁用、单个 chunk 启用/禁用和批量选中启停没有确认框；是否符合危险操作交互预期？

### Next Reading Target

下一轮建议读“用户权限 + 知识库访问控制前后端闭环”。Module 6 和本轮前端阅读都没有看到 vector metadata 或知识库检索层的用户/组织 ACL 约束；下一步可以只围绕登录用户、admin 路由、知识库列表权限、RAG 检索传参和后端鉴权注解验证企业知识库隔离边界。

## Phase 4 Module 8: 用户权限 + 知识库访问控制

### Reading Scope

本轮只围绕登录认证、前端 token 注入、admin 路由保护、后端 Sa-Token 登录检查、UserContext、知识库 created_by / userId 使用、RAG 检索权限边界和 MCP userId 传递做代码阅读。不重新展开知识库写入、chunk、embedding、vector store 写入实现，也不重复总结 RAG 对话运行时细节。

### Files Read

- 既有阅读文档：`docs/reading/00-reading-plan.md`、`docs/reading/01-project-map.md`、`docs/reading/04-module-notes.md`、`docs/reading/99-open-questions.md`。
- 前端认证与路由：`frontend/src/utils/storage.ts`、`frontend/src/services/api.ts`、`frontend/src/services/authService.ts`、`frontend/src/stores/authStore.ts`、`frontend/src/router.tsx`、`frontend/src/pages/LoginPage.tsx`、`frontend/src/main.tsx`、`frontend/src/types/index.ts`、`frontend/src/components/layout/Sidebar.tsx`、`frontend/src/pages/admin/AdminLayout.tsx`。
- 前端 chat 身份传递：`frontend/src/stores/chatStore.ts`、`frontend/src/hooks/useStreamResponse.ts`、`frontend/src/services/chatService.ts`、`frontend/src/services/sessionService.ts`。
- 后端认证与用户上下文：`AuthController.java`、`AuthServiceImpl.java`、`UserController.java`、`SaTokenConfig.java`、`SaTokenStpInterfaceImpl.java`、`UserContextInterceptor.java`、`UserContext.java`。
- 知识库访问控制相关：`KnowledgeBaseController.java`、`KnowledgeDocumentController.java`、`KnowledgeChunkController.java`、`KnowledgeBaseServiceImpl.java`、`KnowledgeDocumentServiceImpl.java`、`KnowledgeChunkServiceImpl.java`、`KnowledgeDocumentChunkConsumer.java`。
- RAG 检索权限相关：`RAGChatController.java`、`RAGChatServiceImpl.java`、`RetrievalEngine.java`、`MultiChannelRetrievalEngine.java`、`SearchContext.java`、`RetrieveRequest.java`、`VectorGlobalSearchChannel.java`、`IntentDirectedSearchChannel.java`、`CollectionParallelRetriever.java`、`IntentParallelRetriever.java`、`PgRetrieverService.java`、`MilvusRetrieverService.java`。
- MCP 身份传递：`MCPRequest.java`、`MCPTool.java`、`DefaultMCPToolRegistry.java`、`MCPToolRegistry.java`、`RemoteMCPToolExecutor.java`、`HttpMCPClient.java`、`mcp-server` 下 `MCPDispatcher.java`、`MCPToolRequest.java`、`MCPToolDefinition.java`、`SalesMCPExecutor.java`、`TicketMCPExecutor.java`、`WeatherMCPExecutor.java`。
- 会话用户隔离与 schema：`ConversationController.java`、`ConversationServiceImpl.java`、`JdbcConversationMemoryStore.java`、`MessageFeedbackServiceImpl.java`、`bootstrap/src/main/resources/application.yaml`、`resources/database/schema_pg.sql`。

### Responsibility

这个模块目前承担的是“登录态识别 + 前端页面准入 + 后端请求登录校验 + 会话数据按用户隔离”的基础能力。知识库管理侧已经记录创建人、更新人，但本轮没有看到知识库、文档、chunk 或检索阶段基于 userId、role、组织或 ACL 做数据级访问控制。

更准确地说：

- 登录态：由 Sa-Token 维护，前端保存 token，后端通过 `Authorization` 请求头识别登录用户。
- 前端权限：`RequireAdmin` 根据本地/接口返回的 `user.role` 限制 `/admin` 路由。
- 后端接口保护：全局拦截器要求登录；用户管理接口额外要求 admin。
- 数据隔离：会话、消息、反馈按 `user_id` 过滤；知识库相关数据没有按用户过滤。
- 检索隔离：RAG 检索没有携带当前用户，也没有按用户可访问知识库过滤。

### Frontend Auth Flow

- `LoginPage` 调用 `authStore.login(username, password)`，默认表单值是 `admin/admin`。
- `authService.login(...)` 请求 `/auth/login`。
- 登录成功后 `authStore` 把 `token` 写入 `localStorage` 的 `ragent_token`，把 user 写入 `ragent_user`。
- `api.ts` 的 Axios request interceptor 每次从 `storage.getToken()` 读取 token，并写入 `config.headers.Authorization`。
- `authStore.checkAuth()` 在 `main.tsx` 渲染前执行：读取本地 token/user，设置 Axios 默认 Authorization，并尝试请求 `/user/me` 刷新用户信息。
- `createStreamResponse(...)` 使用浏览器 `fetch` 读取 SSE；`chatStore` 手动从 storage 读取 token，并给 `/rag/v3/chat` 请求附加 `Authorization` header。
- `stopTask(taskId)` 走 Axios `api.post('/rag/v3/stop?...')`，因此通过 Axios interceptor 注入 Authorization。
- `remember` checkbox 当前不影响实际存储策略；即使未勾选，代码也只是留下“可扩展为内存登录态”的注释。

### Backend Auth And User Context

- `application.yaml` 配置 `sa-token.token-name=Authorization`，token 样式为 `simple-uuid`，timeout 为 2592000 秒。
- `/auth/login` 校验用户名密码后调用 `StpUtil.login(loginId)`，返回 `userId`、`role`、`token`、`avatar`。
- 密码校验是明文字符串比较；本轮没有看到哈希校验。
- `SaTokenConfig` 注册全局 `SaInterceptor`，除 `/auth/**` 和 `/error` 外，默认对所有路径执行 `StpUtil.checkLogin()`；ASYNC dispatch 和 OPTIONS 会跳过。
- `UserContextInterceptor` 在请求进入时通过 `StpUtil.getLoginIdAsString()` 读取 loginId，再 `userMapper.selectById(loginId)` 查用户，写入 `UserContext`。
- `UserContext` 使用 `TransmittableThreadLocal<LoginUser>`，提供 `getUserId()`、`getUsername()`、`getRole()`、`requireUser()` 等方法。
- `SaTokenStpInterfaceImpl.getRoleList(...)` 会按 loginId 查用户角色；`getPermissionList(...)` 当前返回空列表。
- 本轮 `rg` 只看到 `UserController` 的 `/users` 增删改查调用 `StpUtil.checkRole("admin")`；未看到其它业务 Controller 使用 `@SaCheckRole`、`@SaCheckPermission` 或 `StpUtil.checkPermission`。

### Admin Route And API Protection

- 前端 `/chat` 由 `RequireAuth` 保护，只检查 `isAuthenticated`。
- 前端 `/admin` 及其子路由由 `RequireAdmin` 保护：未登录跳 `/login`，非 admin 跳 `/chat`。
- 左侧 Chat sidebar 里的“管理后台”按钮只在 `user?.role === "admin"` 时展示。
- 后端并没有把 `/admin` 页面概念映射成统一的 admin API 鉴权；后端只有“所有非 auth 接口要求登录”的全局检查。
- 读到的知识库、文档、chunk Controller 上没有 admin 角色校验。
- 因此前端 admin 路由限制不等价于后端权限限制。普通登录用户如果直接调用知识库管理 API，当前已读代码中没有看到后端 admin 兜底。

### Knowledge Base Access Control

- `KnowledgeBaseServiceImpl.create(...)` 写入 `createdBy(UserContext.getUsername())` 和 `updatedBy(UserContext.getUsername())`。
- 知识库 rename/update/delete 只更新 `updatedBy`，没有校验当前用户是否为创建人，也没有校验 role。
- `KnowledgeBaseServiceImpl.pageQuery(...)` 过滤条件是 name 和 `deleted=0`，没有 `createdBy`、`userId`、role、organization 或 ACL 条件。
- `KnowledgeBaseServiceImpl.queryById(...)` 只按 id 查询并检查 deleted。
- `KnowledgeDocumentServiceImpl.upload(...)` 写入 `createdBy/updatedBy`，但文档 page/search/get/update/delete/enable/startChunk 都没有按 createdBy 或当前用户过滤。
- `KnowledgeDocumentServiceImpl.page(kbId, ...)` 只按 `kbId`、`deleted=0`、keyword、status 过滤。
- `KnowledgeDocumentServiceImpl.search(...)` 是跨知识库的文档名搜索，只过滤 `deleted=0` 和 docName like。
- `KnowledgeChunkServiceImpl.pageQuery/create/update/delete/enable/batch/rebuild` 主要按 docId/chunkId 校验归属，没有按当前用户或 role 过滤。
- `KnowledgeDocumentChunkConsumer` 处理异步分块时只把 operator 写成 `UserContext.username`，用于审计字段，不携带 userId。
- `created_by` 在当前已读链路中更像展示/审计字段，不是访问控制字段。

### RAG Retrieval Access Control

- `/rag/v3/chat` 后端会经过 Sa-Token 登录检查，Controller 上还有以 `UserContext.getUserId()` 为 key 的幂等注解。
- `RAGChatServiceImpl.streamChat(...)` 读取 `UserContext.getUserId()`，用于会话历史、用户消息和 assistant 消息落库。
- 会话相关接口和服务按 `user_id` 查询：`/conversations`、`/conversations/{id}/messages`、rename/delete、feedback 都有 userId 边界。
- 检索阶段没有携带 userId：`RetrievalEngine.retrieve(...)`、`MultiChannelRetrievalEngine.retrieveKnowledgeChannels(...)`、`SearchContext`、`RetrieveRequest` 都没有当前用户、角色、组织或允许访问的 KB 列表字段。
- 全局检索 `VectorGlobalSearchChannel.getAllKBCollections()` 查询所有 `deleted=0` 的知识库 collection，然后并行检索所有 collection。
- 意图定向检索只根据识别到的 KB intent node 的 `collectionName` 构造 `RetrieveRequest`。
- `PgRetrieverService` 的 SQL 只用 `metadata->>'collection_name' = ?` 过滤 collection。
- `MilvusRetrieverService` 只选择 collectionName 并执行向量搜索，没有 expr 级 ACL 过滤。
- `t_knowledge_vector` schema 只有 `id/content/metadata/embedding`；本轮在检索 SQL 和 schema 中没有看到用户、组织、角色或 ACL 约束。
- 结论：当前对话和消息是用户隔离的，但知识库检索结果是登录用户共享的知识库集合，未看到用户级、组织级或知识库级权限过滤。

### MCP User Identity Flow

- bootstrap 侧 `MCPRequest` 有 `userId`、`conversationId`、`userQuestion` 字段；`MCPTool` 有 `requireUserId` 字段。
- `RetrievalEngine.buildMcpRequest(...)` 构造 MCP 请求时只设置 `toolId`、`userQuestion`、`parameters`，没有设置 `userId` 或 `conversationId`。
- `RemoteMCPToolExecutor.execute(...)` 调用 `mcpClient.callTool(toolId, request.getParameters())`，只把参数 Map 发给远程 MCP Server。
- `HttpMCPClient.callTool(...)` 的 JSON-RPC `tools/call` params 只有 `name` 和 `arguments`。
- `mcp-server` 的 `MCPDispatcher.handleToolsCall(...)` 从 `arguments` 构造 `MCPToolRequest`，只设置 `toolId` 和 `parameters`，没有设置 `userId`。
- `SalesMCPExecutor`、`TicketMCPExecutor` 的工具定义标记 `requireUserId(true)`，但 execute 方法读取的是业务参数，没有读取 `request.getUserId()`。
- `WeatherMCPExecutor` 标记 `requireUserId(false)`。
- `MCPToolDefinition.requireUserId` 没有出现在 `MCPToolSchema` 的 `tools/list` 输出中；bootstrap 从 schema 转回 `MCPTool` 时也没有恢复该字段。
- 结论：`requireUserId/userId` 字段目前存在于模型结构里，但 `/rag/v3/chat` 到 MCP Server 的实际调用链没有传递或强制校验用户身份。

### Data Model And Permission Fields

- `t_user` 有 `username/password/role/avatar/deleted`，role 注释为 `admin/user`。
- `t_conversation`、`t_conversation_summary`、`t_message`、`t_message_feedback` 都有 `user_id`，服务层也按 userId 查询。
- `t_knowledge_base`、`t_knowledge_document`、`t_knowledge_chunk` 有 `created_by/updated_by/deleted`，没有 `user_id`、`owner_id`、`org_id`、`tenant_id` 或 ACL 关系表。
- `t_knowledge_vector` 有 `metadata JSONB`，但 schema 没有强制 ACL 字段；pgvector 检索 SQL 当前只读取 `collection_name`。
- 本轮没有看到组织、租户、成员、知识库授权表或角色-权限表。

### Verified Facts

- 前端 token 保存在 localStorage，并通过 Axios interceptor 和 SSE fetch header 传给后端。
- 后端使用 Sa-Token，通过 `Authorization` 请求头识别登录态。
- 后端全局拦截器会保护除 `/auth/**`、`/error`、ASYNC dispatch、OPTIONS 外的大多数接口。
- `/users` 管理接口有后端 `StpUtil.checkRole("admin")`。
- 本轮读到的知识库、文档、chunk Controller/Service 没有 admin role 校验或 owner 校验。
- 前端 `/admin` 路由和管理入口按钮基于 `user.role === "admin"` 限制。
- 会话列表、消息列表、会话 rename/delete、反馈都按 `user_id` 隔离。
- 知识库列表、文档列表、全局文档搜索、chunk 管理没有按 userId/role/org/ACL 过滤。
- `/rag/v3/chat` 使用当前用户 ID 做幂等、会话和消息归属，但检索阶段没有传 userId。
- 全局检索会读取所有未删除知识库 collection。
- 意图定向检索按 intent node 的 collectionName 检索，没有用户可见性校验。
- pgvector 检索只按 `metadata.collection_name` 过滤。
- Milvus 检索只按 collectionName 选择集合，没有用户级 expr 过滤。
- MCP 请求结构有 userId 字段，但构造、远程调用和 server dispatcher 都没有注入 userId。
- `requireUserId` 当前没有实际强制效果。
- `created_by` 当前用于写入创建人/更新人和 VO 展示，不参与已读查询过滤或权限判断。

### Assumptions

- “普通登录用户可直接调用知识库管理 API”是基于当前已读 Controller/Service 以及全仓 `StpUtil.checkRole/@SaCheck*` 搜索结果推断，未通过接口联调验证。
- MyBatis-Plus `selectById` 对逻辑删除字段的实际行为本轮未运行验证；但业务层没有显式把“创建人/当前用户”作为访问控制条件。
- 本轮没有运行前端页面或后端接口，只基于源码静态阅读。

### Open Questions

- 后端是否应该把知识库、文档、chunk、ingestion、trace、settings 等管理 API 统一加 admin 角色校验，避免只依赖前端 `/admin` 路由？
- 如果项目目标是多用户/企业知识库，ACL 应该落在知识库表、独立授权表、向量 metadata，还是检索前的 allowed collection 计算？
- `created_by` 如果只做审计展示，是否应避免在产品语义上被解释为所有权；如果要做权限字段，是否应改为稳定 userId 而不是 username？
- MCP 的 `requireUserId` 是预留设计还是未完成实现？如果工具未来访问个人数据，需要补齐 userId 注入、JSON-RPC 传输、Server dispatcher 校验和 executor 使用。
- 用户被删除或禁用后，已有 Sa-Token token 是否会被主动失效？`UserContextInterceptor` 查不到用户时的错误处理是否需要更明确？
- 当前检索没有 ACL 后，dashboard/trace 中展示的知识库命中结果是否也可能被非 admin 通过接口间接查看，需要单独验证 trace 查询接口的后端角色保护。

### Next Reading Target

下一轮建议读“管理后台接口安全边界 + Trace/Dashboard 后端查询权限”。重点只验证 dashboard、trace、settings、intent tree、sample question、query mapping、ingestion 等后台 API 是否也只有前端路由保护，以及是否存在普通登录用户可直接调用的管理面接口。

## Phase 4 Module 9: 管理后台接口安全边界 + Trace/Dashboard 查询权限

### Reading Scope

本轮继续 Phase 4 Module Reading，只围绕管理后台入口到后端 API 的安全边界做静态阅读。重点验证 `/admin` 下 dashboard、trace、settings、intent tree / intent list、sample question、query mapping、ingestion pipeline / task 等后台功能是否有后端 admin 角色校验、权限注解、owner 校验或 userId 隔离。

本轮没有重新展开 RAG chat 主流程、知识库写入链路、检索链路或 MCP 调用链路；只在需要判断 trace/dashboard/ingestion 数据暴露时读取了必要的 Trace、Dashboard、Ingestion 查询和记录代码。没有运行前端、后端、测试或数据库。

### Files Read

- 既有阅读文档：`docs/reading/00-reading-plan.md`、`docs/reading/01-project-map.md`、`docs/reading/02-startup-and-config.md`、`docs/reading/03-main-flow.md`、`docs/reading/04-module-notes.md`、`docs/reading/99-open-questions.md`。
- 前端后台路由和菜单：`frontend/src/router.tsx`、`frontend/src/pages/admin/AdminLayout.tsx`。
- 前端 API service：`frontend/src/services/api.ts`、`frontend/src/services/dashboardService.ts`、`frontend/src/services/ragTraceService.ts`、`frontend/src/services/settingsService.ts`、`frontend/src/services/intentTreeService.ts`、`frontend/src/services/sampleQuestionService.ts`、`frontend/src/services/queryTermMappingService.ts`、`frontend/src/services/ingestionService.ts`。
- 后端认证和角色边界：`SaTokenConfig.java`、`UserContextInterceptor.java`、`SaTokenStpInterfaceImpl.java`、`UserController.java`、`UserContext.java`、`LoginUser.java`、`DemoModeInterceptor.java`。
- 后端 dashboard：`DashboardController.java`、`DashboardServiceImpl.java` 以及 dashboard VO。
- 后端 trace 查询和记录：`RagTraceController.java`、`RagTraceQueryServiceImpl.java`、`RagTraceRunVO.java`、`RagTraceNodeVO.java`、`RagTraceDetailVO.java`、`RagTraceRecordServiceImpl.java`、`ChatRateLimitAspect.java`、`RagTraceAspect.java`。
- 后端 settings：`RAGSettingsController.java`、`SystemSettingsVO.java`、`bootstrap/src/main/resources/application.yaml` 中 AI provider 配置片段。
- 后端 intent / sample question / mapping：`IntentTreeController.java`、`IntentTreeServiceImpl.java`、`SampleQuestionController.java`、`SampleQuestionServiceImpl.java`、`QueryTermMappingController.java`、`QueryTermMappingAdminServiceImpl.java`。
- 后端 ingestion：`IngestionPipelineController.java`、`IngestionTaskController.java`、`IngestionPipelineServiceImpl.java`、`IngestionTaskServiceImpl.java`、`IngestionEngine.java`、`NodeLog.java`、`NodeOutputExtractor.java`。
- 数据库片段：`resources/database/schema_pg.sql` 中 `t_sample_question`、`t_intent_node`、`t_query_term_mapping`、`t_rag_trace_run`、`t_rag_trace_node`、`t_ingestion_pipeline`、`t_ingestion_pipeline_node`、`t_ingestion_task`、`t_ingestion_task_node` 表结构。

### Responsibility

这个模块验证的是“前端 admin 页面准入”与“后端管理面 API 权限”是否一致。

当前代码形态下：

- 前端 `/admin` 是路由级保护，`RequireAdmin` 只允许 `user.role === "admin"` 进入。
- 后端 `SaTokenConfig` 对除 `/auth/**`、`/error`、ASYNC dispatch、OPTIONS 外的路径执行 `StpUtil.checkLogin()`，即大多数接口默认只要求登录。
- `SaTokenStpInterfaceImpl` 可以返回用户 role，但权限列表为空。
- 本轮再次全局搜索 `@SaCheck*`、`checkRole`、`checkPermission`，只看到 `/users` 管理接口显式调用 `StpUtil.checkRole("admin")`。
- dashboard、trace、settings、intent tree、sample question、query mapping、ingestion 等本轮已读后台 API，没有看到后端 admin 角色兜底，也没有看到 owner / userId / tenant / org / ACL 查询过滤。

### Frontend Admin API Surface

前端后台入口来自 `router.tsx` 和 `AdminLayout.tsx`：

- `/admin/dashboard`：Dashboard。
  - `GET /admin/dashboard/overview`
  - `GET /admin/dashboard/performance`
  - `GET /admin/dashboard/trends`
- `/admin/traces`、`/admin/traces/:traceId`：链路追踪。
  - `GET /rag/traces/runs`
  - `GET /rag/traces/runs/{traceId}`
  - `GET /rag/traces/runs/{traceId}/nodes`
- `/admin/settings`：系统设置。
  - `GET /rag/settings`
- `/admin/intent-tree`、`/admin/intent-list`、`/admin/intent-list/:id/edit`：意图树配置和意图列表。
  - `GET /intent-tree/trees`
  - `POST /intent-tree`
  - `PUT /intent-tree/{id}`
  - `DELETE /intent-tree/{id}`
  - `POST /intent-tree/batch/enable`
  - `POST /intent-tree/batch/disable`
  - `POST /intent-tree/batch/delete`
- `/admin/sample-questions`：示例问题管理。
  - `GET /sample-questions`
  - `POST /sample-questions`
  - `PUT /sample-questions/{id}`
  - `DELETE /sample-questions/{id}`
  - 同一个 service 还提供欢迎页用的 `GET /rag/sample-questions`。
- `/admin/mappings`：关键词映射。
  - `GET /mappings`
  - `POST /mappings`
  - `PUT /mappings/{id}`
  - `DELETE /mappings/{id}`
- `/admin/ingestion?tab=pipelines`、`/admin/ingestion?tab=tasks`：数据通道流水线和任务。
  - `GET /ingestion/pipelines`
  - `GET /ingestion/pipelines/{id}`
  - `POST /ingestion/pipelines`
  - `PUT /ingestion/pipelines/{id}`
  - `DELETE /ingestion/pipelines/{id}`
  - `GET /ingestion/tasks`
  - `GET /ingestion/tasks/{id}`
  - `GET /ingestion/tasks/{id}/nodes`
  - `POST /ingestion/tasks`
  - `POST /ingestion/tasks/upload`

补充对照：`/admin/users` 也在后台菜单里，但本轮只作为权限边界对照读取；对应 `/users` 增删改查后端有 `StpUtil.checkRole("admin")`。

### Backend Authorization Boundary

后端 Controller 路径和当前已读权限边界：

- `DashboardController`：`@RequestMapping("/admin/dashboard")`，提供 `/overview`、`/performance`、`/trends`。Controller 和 `DashboardServiceImpl` 未见 admin role、permission、owner 或 userId 过滤。
- `RagTraceController`：`/rag/traces/runs`、`/rag/traces/runs/{traceId}`、`/rag/traces/runs/{traceId}/nodes`。Controller 和 `RagTraceQueryServiceImpl` 未见 admin role；查询条件只来自 request 的 `traceId/conversationId/taskId/status`，没有自动追加当前 `UserContext.getUserId()`。
- `RAGSettingsController`：`GET /rag/settings`。未见 admin role；返回全局 upload、RAG、memory、rate limit、AI provider 和模型配置。
- `IntentTreeController`：`/intent-tree/**`。未见 admin role；`IntentTreeServiceImpl` 创建/更新时写 `createBy/updateBy`，但查询、更新、批量启停/删除没有按当前用户或 role 过滤。
- `SampleQuestionController`：`/sample-questions/**` 和 `/rag/sample-questions`。未见 admin role；`SampleQuestionServiceImpl` 操作全局 `t_sample_question`。
- `QueryTermMappingController`：`/mappings/**`。未见 admin role；`QueryTermMappingAdminServiceImpl` 创建/更新/删除后会调用 `queryTermMappingService.loadMappings()` 重载内存映射。
- `IngestionPipelineController`：`/ingestion/pipelines/**`。未见 admin role；`IngestionPipelineServiceImpl` 写 `createdBy/updatedBy`，但读取、更新、删除没有 owner 过滤。
- `IngestionTaskController`：`/ingestion/tasks/**`。未见 admin role；`IngestionTaskServiceImpl` 写 `createdBy/updatedBy`，但列表、详情、节点日志没有 owner 过滤。
- `UserController`：`/users` 增删改查有 `StpUtil.checkRole("admin")`，是本轮已读范围内唯一明确的后端 admin 兜底。

`DemoModeInterceptor` 是体验环境只读保护，不是角色权限控制；只有 `app.demo-mode=true` 时才会拦截非查询请求，不能替代 admin 鉴权。

### Trace And Dashboard Data Exposure

Dashboard：

- `DashboardServiceImpl` 查询全局用户数、会话数、消息数、活跃用户数、trace 成功/失败数、成功 run 延迟、无知识回复数量等。
- dashboard 返回的是聚合指标和趋势点，不直接返回用户问题正文、assistant 正文、chunk 内容或命中知识库详情。
- 但 dashboard 查询没有 userId 过滤；普通已登录 user 如果直接请求这些 API，按当前源码看可看到全站聚合用量、活跃用户、会话/消息量、错误率、无知识率、延迟等管理指标。

Trace：

- `ChatRateLimitAspect` 创建 `t_rag_trace_run` 时写入 `traceId`、`traceName`、`entryMethod`、`conversationId`、`taskId`、`userId`、status、start/end/duration，并在 `extraData` 里写 `questionLength`。
- `RagTraceAspect` 节点记录写入 `nodeType`、`nodeName`、`className`、`methodName`、status、errorMessage、duration 等。
- `RagTraceRunVO` 返回 `traceId`、`conversationId`、`taskId`、`userId`、`username`、status、`errorMessage`、duration、start/end。
- `RagTraceNodeVO` 返回 node id、父节点、depth、node type/name、class/method、status、`errorMessage`、duration、start/end。
- 当前已读 VO 不返回 `extraData`，因此 trace 查询接口按当前源码不会直接返回用户问题正文、命中知识库、chunk 内容、模型名、token 数或 provider fallback 细节。
- 但 trace 查询没有按当前 userId 隔离；普通已登录 user 可分页看到所有 trace run 的 userId/username、conversationId/taskId、错误信息和内部类/方法节点信息，也可按已知 traceId 拉取详情。

Ingestion：

- `IngestionTaskServiceImpl.listNodes(...)` 返回节点日志；`toNodeVO(...)` 会把 `IngestionTaskNodeDO.outputJson` 转为 `output` 返回。
- `NodeOutputExtractor` 会记录 fetcher 的 source、mimeType、rawBytesLength、`rawBytesBase64`，parser 的 `rawText` 和 document，enhancer 的 enhancedText/keywords/questions/metadata，chunker/enricher/indexer 的 chunks。
- 这不是 trace/dashboard 接口，但属于本轮后台数据通道范围。因为 ingestion task/node 查询没有 admin 或 owner 过滤，普通已登录 user 直接调用 `/ingestion/tasks/{id}/nodes` 时，按当前源码可能看到其他任务的原始文件内容、解析文本、增强文本、chunk 和节点错误信息。

### Global Configuration / Ingestion / Intent Admin Risk

- settings：`GET /rag/settings` 是只读接口，没有看到更新配置接口。但它返回 `SystemSettingsVO.AISettings.ProviderConfig.apiKey`，`RAGSettingsController.toAISettings(...)` 直接把 `AIModelProperties.ProviderConfig.getApiKey()` 写入响应；如果环境变量配置了真实 `BAILIAN_API_KEY` 或 `SILICONFLOW_API_KEY`，普通登录用户直接请求可能拿到 provider API key。
- intent tree：增删改、批量启停和删除会修改 `t_intent_node` 并清除 `IntentTreeCacheManager` 缓存，下一次意图识别会重新加载，直接影响 RAG 路由、KB collection 绑定、MCP toolId、prompt snippet/template 和参数抽取模板。
- query mapping：增删改会重载 `QueryTermMappingService` 内存映射，影响后续问题改写前的术语归一化。
- sample question：增删改影响全局欢迎页/示例问题内容。
- ingestion pipeline：增删改流水线及节点配置会影响后续 pipeline 处理方式；删除 pipeline 也会删除节点配置。
- ingestion task：`POST /ingestion/tasks` 和 `/ingestion/tasks/upload` 可直接执行 pipeline，可能产生新的解析、分块、增强、索引结果；任务详情和节点详情会暴露运行状态、错误、日志和节点输出。
- 上述接口当前已读代码中都只依赖登录态和业务参数校验，没有后端 admin 兜底。

### Verified Facts

- 前端 `/admin` 由 `RequireAdmin` 保护，非 admin 会跳转 `/chat`。
- 前端后台菜单包括 Dashboard、知识库管理、意图管理、数据通道、关键词映射、链路追踪、用户管理、示例问题、系统设置。
- 本轮目标范围内的前端 service 确认了 dashboard、trace、settings、intent tree、sample question、query mapping、ingestion 对应 API。
- 后端全局登录校验来自 `SaTokenConfig` 的 `StpUtil.checkLogin()`。
- `SaTokenStpInterfaceImpl.getPermissionList(...)` 当前返回空列表；`getRoleList(...)` 从用户表读取 role。
- 本轮全局搜索 `@SaCheck*`、`checkRole`、`checkPermission`，只看到 `UserController` 的 `/users` 增删改查调用 `StpUtil.checkRole("admin")`。
- dashboard、trace、settings、intent tree、sample question、query mapping、ingestion Controller/Service 没有看到 admin role、permission、owner、tenant、org、ACL 或当前 userId 查询过滤。
- dashboard 返回全局聚合指标，不返回消息正文或 chunk 正文。
- trace 查询返回全局 run/node，包含 userId/username、conversationId/taskId、错误信息和内部节点信息；当前 VO 不返回 trace extraData。
- settings 返回 provider `apiKey` 字段。
- ingestion task node 输出会持久化并可返回 rawBytesBase64、rawText、document、enhancedText、chunks 等数据。
- intent tree、query mapping、sample question、ingestion pipeline/task 都会影响全局 RAG 行为或全局后台数据。

### Assumptions

- “普通已登录 user 可直接调用这些后台 API”是基于当前已读源码和静态搜索结果的推断，未通过实际 HTTP 请求验证。
- MyBatis-Plus 逻辑删除、拦截器和数据库运行行为没有通过测试验证；本轮只依据 Controller/Service 查询条件和 schema 判断是否存在显式权限边界。
- settings 中 `apiKey` 是否实际非空取决于运行环境变量；源码事实是响应对象包含该字段并直接来自配置属性。
- trace 当前不直接暴露问题正文、命中知识库、chunk、模型 provider 细节，是基于本轮读取的记录切面、查询服务和 VO；如果未来其它未读接口返回 conversation/message/chunk，需单独验证。

### Open Questions

- 是否应把 dashboard、trace、settings、intent tree、sample question、query mapping、ingestion 等后台 API 统一加后端 admin role 校验，而不是只依赖前端 `/admin` 路由？
- `/rag/settings` 是否应该脱敏或移除 provider `apiKey` 字段，至少避免普通登录用户或前端页面拿到真实密钥？
- trace 查询是否应按 admin-only 管理面处理；如果普通用户需要查看自己的 trace，是否应自动加 `userId = UserContext.getUserId()` 过滤？
- dashboard 是否应该只面向 admin；如果普通用户有个人统计页，是否应拆成独立的 user-scoped dashboard API？
- ingestion task node 的 rawBytesBase64/rawText/chunks 输出是否应只对 admin 可见，或者按 createdBy/owner 过滤，并对大字段/敏感字段做脱敏？
- intent tree / query mapping / sample question / ingestion pipeline 修改是否需要审计、确认、版本管理或回滚机制？
- `createdBy/updatedBy` 是否继续只作为审计字段；如果要作为 owner 权限字段，是否需要改成稳定 userId 并补服务层过滤？

### Next Reading Target

下一轮建议读“管理后台写操作的最小后端修复方案设计”，但仍先只做设计，不直接改代码。重点可以按风险排序：`/rag/settings` 密钥脱敏、后台 API admin 兜底、trace/dashboard userId 隔离策略、ingestion node output 脱敏和 owner/admin 权限模型。
