# Phase 3: Main Flow

## 本轮阅读边界

本轮按 `docs/reading/00-reading-plan.md` 的协议执行，只追一条最适合作为简历项目讲述的核心主流程，不横向展开管理后台、文件上传、用户体系或全部业务模块。

选择的代表性主流程是：

`GET /rag/v3/chat` 用户发起 AI Agent/RAG 对话请求，服务端通过 SSE 流式返回回答。

代表性成功路径定义为：

用户问题进入 RAG 对话接口后，系统完成会话记忆写入、问题改写和拆分、意图识别，随后命中知识库检索，并在同一检索编排点可同时执行 MCP 工具调用，最终组装 Prompt，调用大模型流式生成回答，再把助手回答写入会话消息表并通过 SSE 完成返回。

选择这条链路的原因：

- 它是直接面向用户的 AI/RAG 对话入口，不是管理端或配置端流程。
- 它覆盖项目最有简历价值的技术事实：SSE 流式响应、会话记忆、问题改写、意图识别、多通道检索、pgvector、MCP 工具调用、Prompt 组装、模型路由和失败切换。
- 它从 Controller 到 Service/Agent 编排，再到模型、工具、知识库和响应返回，能形成完整面试讲法。

## 本轮读取文件

### 协议和已有结论

- `docs/reading/00-reading-plan.md`：确认 Phase 3 阅读边界、事实和推测分离规则。
- `docs/reading/01-project-map.md`：确认模块地图和主服务模块位置。
- `docs/reading/02-startup-and-config.md`：复用 Phase 2 已验证的启动、配置、PostgreSQL + pgvector、模型服务和 MCP 结论。
- `docs/reading/99-open-questions.md`：读取已有开放问题，避免覆盖旧问题。

### HTTP 入口、限流、排队和 SSE 任务

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`：确认对话入口 `GET /rag/v3/chat` 和停止接口 `POST /rag/v3/stop`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java`：确认主服务接口 `streamChat` 和 `stopTask`。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`：确认主编排方法和 RAG 主流程。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatRateLimitAspect.java`：确认 `@ChatRateLimit` 切面、任务 ID、trace run 和队列入口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatQueueLimiter.java`：确认 Redis 全局并发、排队、超时拒绝和线程池执行。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`：确认 SSE 回调对象创建。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`：确认流式内容发送、最终回答落库和 SSE 完成事件。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java`：确认流式任务取消句柄和任务本地状态。

### 会话记忆和数据写入

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/ConversationMemoryService.java`：确认记忆服务接口和 `loadAndAppend` 行为。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/DefaultConversationMemoryService.java`：确认加载历史、追加消息、触发摘要压缩。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java`：确认历史消息读取和消息追加走 JDBC/MyBatis 服务。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemorySummaryService.java`：确认助手回复后可异步生成摘要，并使用 Redisson 锁。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`：确认消息表写入、消息列表读取和摘要写入。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationServiceImpl.java`：确认会话创建/更新时间，新会话标题由 LLM 生成。

### 问题改写、意图识别和缓存

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryRewriteService.java`：确认问题改写接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/MultiQuestionRewriteService.java`：确认 LLM 问题改写、子问题拆分和失败回退。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java`：确认查询词映射会在改写前做 normalize。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentResolver.java`：确认子问题并行意图识别、意图过滤和 MCP/KB 分组。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/DefaultIntentClassifier.java`：确认意图树来自 Redis 或数据库，并由 LLM 输出命中意图。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeCacheManager.java`：确认意图树 Redis 缓存 key 和过期时间。

### RAG 检索和 MCP 工具

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`：确认 KB 检索和 MCP 工具调用的统一编排。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`：确认多检索通道并行执行和后处理链。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java`：确认基于 KB 意图的定向检索通道。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`：确认低置信或无意图时的全局向量检索通道。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java`：确认按 KB 意图 collection 并行检索。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/CollectionParallelRetriever.java`：确认按知识库 collection 并行检索。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`：确认当前 pgvector 检索实现。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java`：确认检索结果去重。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessor.java`：确认检索结果 rerank。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SearchChannelProperties.java`：确认检索通道配置项。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/RetrievalContext.java`：确认 RAG/MCP 上下文承载结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SubQuestionIntent.java`：确认子问题与意图分数结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/MCPParameterExtractor.java`：确认 MCP 参数抽取接口。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/LLMMCPParameterExtractor.java`：确认通过 LLM 从用户问题中抽取 MCP 参数。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/RemoteMCPToolExecutor.java`：确认远程 MCP 工具执行封装。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/HttpMCPClient.java`：确认 JSON-RPC `/mcp` 的 `initialize`、`tools/list` 和 `tools/call` 调用。

### Prompt 和模型调用

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`：确认 KB、MCP、混合场景的结构化消息构造。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/DefaultContextFormatter.java`：确认 KB 证据和 MCP 工具结果如何格式化进上下文。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptTemplateLoader.java`：确认 prompt 模板从 classpath 加载和缓存。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LLMService.java`：确认同步和流式模型调用统一接口。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`：确认 LLM 路由、首包探测和失败切换。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/BaiLianChatClient.java`：确认百炼 Chat 同步和 SSE 流式调用。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamAsyncExecutor.java`：确认模型流式调用在线程池中执行。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelSelector.java`：确认默认模型、深度思考模型和候选模型选择。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelRoutingExecutor.java`：确认同步模型、embedding、rerank 的 fallback 执行器。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/RoutingEmbeddingService.java`：确认 embedding 模型路由。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java`：确认 SiliconFlow embedding HTTP 调用。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/RoutingRerankService.java`：确认 rerank 模型路由。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java`：确认百炼 rerank HTTP 调用。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/ai/model/ChatMessage.java`：确认模型消息角色结构。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/ai/model/ChatRequest.java`：确认模型请求参数结构。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/ai/model/RetrievedChunk.java`：确认检索 chunk 结构。

## 主调用链

### 1. 入口

1. 用户通过 `GET /rag/v3/chat` 发起请求，参数包括 `question`、可选 `conversationId`、可选 `deepThinking`。
2. `RAGChatController.chat(...)` 创建 `SseEmitter(0L)`，调用 `ragChatService.streamChat(question, conversationId, deepThinking, emitter)`，立即返回 SSE emitter。
3. 入口方法带有 `@IdempotentSubmit`，幂等 key 使用 `UserContext.getUserId()`，提示语为“当前会话处理中，请稍后再发起新的对话”。
4. `RAGChatServiceImpl.streamChat(...)` 受 `@ChatRateLimit` 切面包裹。`ChatRateLimitAspect` 会补齐 `conversationId`、生成或传递 `taskId`、可选记录 RAG trace，然后把实际执行逻辑交给 `ChatQueueLimiter.enqueue(...)`。
5. `ChatQueueLimiter` 使用 Redis/Redisson 做全局并发和队列控制，成功拿到许可后在 `chatEntryExecutor` 中执行主流程；如果超时或拒绝，会写入拒绝消息并通过 SSE 返回 reject/finish/done。

核心类和方法：

- `RAGChatController.chat`
- `RAGChatServiceImpl.streamChat`
- `ChatRateLimitAspect.around`
- `ChatQueueLimiter.enqueue`

### 2. 编排

`RAGChatServiceImpl.streamChat(...)` 是本轮确认到的主编排点：

1. 生成实际 `conversationId` 和 `taskId`。
2. 通过 `StreamCallbackFactory.createChatEventHandler(...)` 创建 SSE 回调处理器。
3. 从 `UserContext` 读取当前 `userId`。
4. 调用 `memoryService.loadAndAppend(...)`：先加载会话历史，再追加当前用户问题。
5. 调用 `queryRewriteService.rewriteWithSplit(question, history)` 做问题改写和子问题拆分。
6. 调用 `intentResolver.resolve(rewriteResult)` 对子问题做意图识别。
7. 调用 `guidanceService.detectAmbiguity(...)` 检测歧义。如果需要澄清，会直接返回引导信息；本轮选取的成功路径不进入该分支。
8. 调用 `retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K)` 执行知识库检索和 MCP 工具调用。
9. 若检索上下文为空，会返回“未检索到...”类提示；本轮选取的成功路径是检索上下文非空。
10. 调用 `streamLLMResponse(...)` 组装 Prompt，并通过 `llmService.streamChat(...)` 进行流式生成。
11. 把返回的取消句柄绑定到 `StreamTaskManager`，供 `POST /rag/v3/stop` 停止任务使用。

核心类和方法：

- `ConversationMemoryService.loadAndAppend`
- `MultiQuestionRewriteService.rewriteWithSplit`
- `IntentResolver.resolve`
- `RetrievalEngine.retrieve`
- `RAGChatServiceImpl.streamLLMResponse`
- `StreamTaskManager.bindHandle`

### 3. 模型、工具和 RAG

#### 问题改写和子问题拆分

`MultiQuestionRewriteService` 在开启改写时会：

1. 使用 `QueryTermMappingService.normalize(...)` 先做查询词映射。
2. 读取最近历史消息片段作为上下文。
3. 加载问题改写和拆分 Prompt。
4. 调用 `LLMService.chat(...)` 获取 JSON 结果。
5. 从结果中解析 `rewrite` 和 `sub_questions`。
6. 如果 LLM 调用或 JSON 解析失败，回退为单问题路径。

#### 意图识别

`IntentResolver.resolve(...)` 会对每个子问题并行调用 `DefaultIntentClassifier.classifyTargets(...)`。

`DefaultIntentClassifier` 的意图树来源：

- 优先从 Redis 读取，key 为 `ragent:intent:tree`，过期时间 7 天。
- Redis 未命中时从数据库 `t_intent_node` 查询未删除节点，并回写 Redis。

分类方式：

- 把叶子意图节点、路径、描述、类型、MCP toolId、示例等信息组装进 Prompt。
- 调用 `LLMService.chat(...)`。
- 解析 LLM 返回的意图 ID 和分数。
- 过滤低于阈值的意图，并限制最大意图数量。
- 按类型拆分为 `KB`、`MCP`、`SYSTEM` 等意图。

#### 知识库检索

`RetrievalEngine.retrieve(...)` 对子问题并行构建检索上下文。对 KB 意图，调用 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels(...)`。

已确认的两个检索通道：

- `IntentDirectedSearchChannel`：当 KB 意图分数达到配置阈值时，按意图绑定的 collection 定向检索。
- `VectorGlobalSearchChannel`：当无意图或最高意图分数低于阈值时，从 `t_knowledge_base` 读取全部 collection，做全局向量检索。

当前 pgvector 路径：

1. `PgRetrieverService.retrieve(...)` 调用 `embeddingService.embed(query)` 获取向量。
2. 对向量归一化。
3. 执行 pgvector SQL：在 `t_knowledge_vector` 中按 `metadata->>'collection_name'` 过滤 collection，并按 `embedding <=> ?::vector` 排序。
4. 返回 `RetrievedChunk`，分数为 `1 - distance`。

检索后处理：

- `DeduplicationPostProcessor` 去重。
- `RerankPostProcessor` 调用 `RerankService.rerank(...)` 做重排。

#### MCP 工具调用

在同一个 `RetrievalEngine` 编排点，如果意图结果包含 MCP 类型节点，则会执行 MCP 分支：

1. 根据意图节点找到 MCP toolId。
2. 从 MCP registry 获取对应 executor。
3. `LLMMCPParameterExtractor.extractParameters(...)` 调用 LLM，从用户问题中抽取工具参数。
4. `RemoteMCPToolExecutor.execute(...)` 调用 `HttpMCPClient.callTool(...)`。
5. `HttpMCPClient` 以 JSON-RPC 2.0 请求远端 `/mcp`，方法包括 `tools/call`。
6. 成功的 MCP 结果由 `DefaultContextFormatter.formatMcpContext(...)` 格式化为模型上下文。

本轮只确认主服务侧如何调用 MCP，没有深入 MCP 服务内置工具的业务实现。

#### Prompt 组装和最终模型调用

`RAGChatServiceImpl.streamLLMResponse(...)` 会构建 `PromptContext`，包含：

- 改写后的问题。
- MCP 上下文。
- KB 上下文。
- 意图分组。
- 检索 chunk。
- 多子问题列表。
- 历史消息。

`RAGPromptService.buildStructuredMessages(...)` 根据上下文类型选择模板：

- 只有 KB：`answer-chat-kb.st`
- 只有 MCP：`answer-chat-mcp.st`
- KB + MCP 混合：`answer-chat-mcp-kb-mixed.st`

最终调用：

- `RoutingLLMService.streamChat(...)`
- 默认配置来自 Phase 2：chat 使用百炼 `qwen3-max`，embedding 使用 SiliconFlow，rerank 默认使用百炼并带 noop fallback。

`RoutingLLMService.streamChat(...)` 会按模型候选顺序尝试流式调用，并使用首包探测避免失败模型已经输出的半截内容直接泄漏给用户。

核心类和方法：

- `PgRetrieverService.retrieve`
- `MultiChannelRetrievalEngine.retrieveKnowledgeChannels`
- `LLMMCPParameterExtractor.extractParameters`
- `HttpMCPClient.callTool`
- `RAGPromptService.buildStructuredMessages`
- `RoutingLLMService.streamChat`
- `BaiLianChatClient.streamChat`

### 4. 数据读写

本轮已确认的状态保存位置：

- PostgreSQL 普通业务表：
  - `t_conversation`：会话记录，`ConversationServiceImpl.createOrUpdate(...)` 创建或更新。
  - `t_message`：用户和助手消息，`ConversationMessageServiceImpl.addMessage(...)` 写入。
  - `t_conversation_summary`：会话摘要，`ConversationMessageServiceImpl.addMessageSummary(...)` 写入。
  - `t_intent_node`：意图树节点，`DefaultIntentClassifier` 在 Redis 未命中时读取。
  - `t_knowledge_base`：知识库 collection 列表，`VectorGlobalSearchChannel` 读取。
- PostgreSQL + pgvector：
  - `t_knowledge_vector`：向量 chunk 表，`PgRetrieverService` 使用 `embedding <=> ?::vector` 做相似度检索。
- Redis：
  - 全局对话队列、并发许可、队列序号和通知 topic。
  - 意图树缓存 `ragent:intent:tree`。
  - 会话摘要压缩锁。
  - Phase 2 已确认 Redis 还参与部分任务取消和缓存能力。
- JVM 内存：
  - `StreamTaskManager` 保存当前流式任务句柄和任务状态。
  - `StreamChatEventHandler` 在一次 SSE 过程中累积助手回答文本。
- 对象存储：
  - 本轮选择的对话主流程没有直接确认对象存储读写。对象存储更可能属于知识库文档上传或解析链路，未纳入本轮。

本轮已确认的写入时机：

1. 用户问题进入主流程后，`memoryService.loadAndAppend(...)` 追加用户消息。
2. 新会话首次创建时，`ConversationServiceImpl.createOrUpdate(...)` 会调用 LLM 生成标题并写入会话。
3. 最终 LLM 流式完成后，`StreamChatEventHandler.onComplete(...)` 会把助手完整回答追加为消息。
4. 助手消息写入后，摘要服务可能异步压缩历史消息。

### 5. 返回

返回路径由模型流式 callback 驱动：

1. `BaiLianChatClient.streamChat(...)` 读取模型 SSE 响应。
2. `RoutingLLMService.streamChat(...)` 负责模型失败切换和首包探测。
3. `StreamChatEventHandler` 接收 thinking/content/complete/error 等回调。
4. 内容增量通过 `SseEmitter` 发送给前端。
5. 完成时写入助手消息，发送 finish/done 类事件，注销任务并 complete emitter。

## 外部系统调用点

- LLM Chat：
  - 问题改写和拆分：`MultiQuestionRewriteService`
  - 意图识别：`DefaultIntentClassifier`
  - 新会话标题生成：`ConversationServiceImpl`
  - MCP 参数抽取：`LLMMCPParameterExtractor`
  - 最终流式回答：`RoutingLLMService` -> `BaiLianChatClient`
- Embedding：
  - `PgRetrieverService` -> `RoutingEmbeddingService` -> `SiliconFlowEmbeddingClient`
- Rerank：
  - `RerankPostProcessor` -> `RoutingRerankService` -> `BaiLianRerankClient`
- MCP：
  - `RetrievalEngine` -> `RemoteMCPToolExecutor` -> `HttpMCPClient` -> JSON-RPC `/mcp`
- PostgreSQL：
  - 会话、消息、摘要、意图节点、知识库元数据。
- pgvector：
  - `t_knowledge_vector.embedding` 相似度检索。
- Redis：
  - 全局对话队列、意图树缓存、摘要锁、部分任务状态能力。
- RocketMQ：
  - 本轮主流程没有读到 RocketMQ 调用点，不能写成事实。
- Milvus：
  - 本轮主流程确认的默认向量检索路径是 `PgRetrieverService`。Milvus 没有出现在本轮选定主链路中。

## 值得后续深入的点

### 异常处理和降级

- `MultiQuestionRewriteService`：LLM 改写或 JSON 解析失败时，回退为单问题。
- `DefaultIntentClassifier`：意图树 Redis 未命中时回源数据库，并回写 Redis。
- `MultiChannelRetrievalEngine`：单个检索通道异常会被记录并返回空结果，不直接中断全部检索。
- `DeduplicationPostProcessor`、`RerankPostProcessor`：后处理链异常会被记录并跳过对应处理器。
- `LLMMCPParameterExtractor`：参数抽取失败时返回默认参数。
- `RemoteMCPToolExecutor` / `HttpMCPClient`：工具调用异常会转成错误响应或空结果。
- `RoutingLLMService` / `ModelRoutingExecutor`：模型调用失败会尝试 fallback 候选模型。
- `StreamAsyncExecutor`：线程池拒绝时会取消底层 HTTP call 并回调错误。

### 异步和线程池

- `ChatQueueLimiter` 使用 `chatEntryExecutor` 执行实际对话任务。
- `IntentResolver` 使用 `intentClassifyThreadPoolExecutor` 并行识别子问题意图。
- `RetrievalEngine` 使用 `ragContextThreadPoolExecutor` 并行处理子问题上下文。
- `RetrievalEngine` 使用 `mcpBatchThreadPoolExecutor` 并行执行 MCP 工具。
- `MultiChannelRetrievalEngine` 使用 `ragRetrievalThreadPoolExecutor` 并行执行检索通道。
- `StreamAsyncExecutor` 使用 `modelStreamExecutor` 执行模型流式请求。
- `JdbcConversationMemorySummaryService` 使用 `memorySummaryThreadPoolExecutor` 异步生成摘要。
- `DefaultConversationMemoryService.load(...)` 中的历史和摘要加载使用 `CompletableFuture.supplyAsync(...)`，本轮读到的代码没有看到显式传入业务线程池。

### 事务和一致性

- 本轮读到 `ConversationServiceImpl.delete(...)` 有事务注解，但选定主流程中的“追加用户消息 + 创建/更新会话”和“追加助手消息 + 摘要压缩”没有在已读代码里形成一个明确的单事务边界。
- 这意味着对话写入一致性需要后续专门阅读 mapper/service 事务配置或运行失败场景，当前不能断言是强事务一致。

### 可作为简历亮点的技术事实

- 基于 SSE 的 AI 对话流式输出。
- Redis/Redisson 实现全局对话并发限制、排队和超时拒绝。
- 对话请求进入模型前，先做会话记忆、查询词归一化、LLM 问题改写和多子问题拆分。
- 通过 LLM 意图识别把问题路由到 KB 检索和 MCP 工具调用。
- RAG 检索采用多通道策略：意图定向检索和全局向量检索，并带去重和 rerank 后处理。
- 默认向量检索路径使用 PostgreSQL + pgvector，而不是 README 中提到的 Milvus。
- MCP 工具通过 JSON-RPC `/mcp` 远程调用，并由 LLM 抽取工具参数。
- Prompt 组装区分 KB、MCP、KB+MCP 混合场景。
- 模型调用层有路由、健康状态、fallback 和流式首包探测机制。
- 用户消息、助手消息、会话和摘要落 PostgreSQL，意图树和队列状态使用 Redis。

## 已验证事实

- `GET /rag/v3/chat` 是当前主服务中的 RAG 对话 SSE 入口。
- `RAGChatServiceImpl.streamChat(...)` 是当前对话主流程编排方法。
- 当前主流程会在最终回答前经过会话记忆、问题改写/拆分、意图识别、检索/工具上下文构建、Prompt 组装和流式 LLM 调用。
- 当前默认向量检索实现类是 `PgRetrieverService`，其 SQL 查询 `t_knowledge_vector` 并使用 pgvector 距离操作符。
- MCP 工具调用在主服务侧通过 `HttpMCPClient` 以 JSON-RPC 调用远端 `/mcp`。
- 模型调用通过 `LLMService` 抽象，当前已读实现包含路由、fallback 和百炼/SiliconFlow 等 provider client。
- 用户问题和助手回答会写入数据库消息表，会话摘要可能异步生成。

## 推测

- 这条链路适合作为最终简历项目主线，因为它同时体现后端工程能力和 AI Agent/RAG 能力；这是基于已读代码结构的项目表达判断，不是源码事实。
- 代表性“KB + MCP 混合成功路径”在代码层面成立，但真实运行时是否经常命中混合路径，取决于意图树数据、prompt、模型输出和用户问题。

## 开放问题

- 本轮没有运行接口，SSE 事件的完整前端消费格式仍需通过联调或接口请求确认。
- 混合 KB + MCP 成功路径依赖意图分类返回 KB 和 MCP 节点；当前没有读取数据库种子数据，无法确认默认数据是否能稳定触发。
- 主流程的用户消息写入、会话创建/更新、助手消息写入之间是否需要更强事务边界，仍需进一步确认。
- `DefaultConversationMemoryService.load(...)` 使用的默认 `CompletableFuture` 线程池是否符合项目线程治理预期，值得后续看配置或压测。
- 问题改写、意图识别、标题生成、参数抽取、最终回答可能触发多次 LLM 调用，真实延迟和成本需要通过 trace 或运行日志确认。

## 下一轮建议

建议 Phase 4 选择一个垂直模块深入，但仍保持单目标：

1. 若目标是面试亮点：深入 `RAG 检索模块`，重点看多通道检索、pgvector、rerank、意图路由和召回质量。
2. 若目标是 Agent 讲法：深入 `MCP 工具调用模块`，重点看工具注册、参数抽取、JSON-RPC 调用、错误处理和工具结果如何进入 Prompt。
3. 若目标是工程稳定性：深入 `流式任务、限流排队和模型 fallback`，重点看 Redis 队列、线程池、取消、首包探测和异常恢复。

我的建议是下一轮先读 `MCP 工具调用模块`。原因是它能把项目从普通 RAG 拉到 Agent 项目表达，并且本轮已经确认 MCP 是主流程中的直接分支。
