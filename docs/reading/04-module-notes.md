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
