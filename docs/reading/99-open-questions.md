# Open Questions

## Phase 2: Startup And Configuration

- README 中的 MySQL + Milvus 描述和当前默认配置 PostgreSQL + pgvector 不一致。当前已核实默认源码配置走 PostgreSQL + pgvector，Milvus 是可选后端，但差异产生原因仍需通过官方文档、提交历史或维护者说明确认。
- 当前仓库没有发现 PostgreSQL、Redis 的 Docker Compose；本地运行需要手动准备，还是另有未读官方文档提供，需要后续确认。
- `docs/quick-start.md` 不是本地环境启动指南，而是多通道检索重构说明；真正的本地启动流程可能在外部官方文档或其他未读材料中。
- 当前配置中 `BAILIAN_API_KEY` 和 `SILICONFLOW_API_KEY` 默认可为空，但默认 chat/rerank 使用百炼，默认 embedding 使用 SiliconFlow；没有 API Key 时具体失败方式需要后续运行或阅读错误处理路径确认。
- Redis 密码在配置中固定为 `123456`，但本轮没有发现对应 Redis Compose，需确认本地 Redis 如何配置密码。
- MCP 服务内置工具的具体业务行为尚未阅读；本轮只确认启动注册和 `POST /mcp` 端点。

## Phase 3: Main Flow

- 本轮没有运行 `GET /rag/v3/chat`，SSE 事件的完整前端消费格式仍需通过联调或接口请求确认。
- 代表性 KB + MCP 混合成功路径在代码层面成立，但真实运行时是否稳定命中，取决于意图树数据、prompt、模型输出和用户问题；当前没有读取数据库种子数据。
- 主流程中的用户消息写入、会话创建/更新、助手消息写入之间是否有足够事务边界仍需确认；已读主链路代码未显示一个覆盖这些步骤的明确单事务边界。
- `DefaultConversationMemoryService.load(...)` 中历史和摘要加载使用 `CompletableFuture.supplyAsync(...)`，本轮未看到显式业务线程池，是否符合项目线程治理需要后续确认。
- 一次对话可能触发问题改写、意图识别、标题生成、MCP 参数抽取、最终回答等多次 LLM 调用，真实延迟、成本和 trace 记录效果需要通过运行日志或压测确认。

## Phase 4: MCP Tool Module

- 意图树默认数据是否已经配置 MCP 节点，以及 `mcp_tool_id` 是否能稳定匹配远程注册工具，仍需读取初始化数据或运行验证。
- `IntentNodeDO.kind` 是 Integer，`IntentNode.kind` 是 `IntentKind` 枚举；当前已读 loader 中没有看到显式 `IntentKind.fromCode(...)` 转换，需要确认 `BeanUtil.toBean(...)` 的枚举映射是否稳定。
- MCP Server 启动失败时，主服务启动阶段会跳过远程工具注册；本轮没有看到后续自动重连和重新注册机制。
- MCP 工具定义和请求结构包含 `requireUserId`、`userId` 字段，但当前已读 `/rag/v3/chat` MCP 调用链没有看到用户身份注入到 MCP 请求或 JSON-RPC arguments。
- `HttpMCPClient` 的 read timeout 和 call timeout 配置为 0，单个远程 MCP 调用卡住时对对话线程和 SSE 的影响需要后续验证。
- `DefaultContextFormatter.formatMcpContext(...)` 在有 MCP 意图时只把成功响应分组进入上下文，失败工具结果不会进入最终 LLM 上下文；是否符合产品预期需要确认。

## Phase 4: RAG Retrieval Module

- `DefaultIntentClassifier.loadIntentTreeFromDB()` 查询 `t_intent_node` 时只过滤 `deleted=0`，当前已读代码没有看到 `enabled=1` 过滤；禁用意图节点是否仍参与识别需要确认。
- `RetrieveRequest.metadataFilters` 字段存在，但当前已读 pgvector 和 Milvus 检索实现没有使用该字段；是否为预留设计或遗漏实现需要确认。
- pgvector 检索直接查 `t_knowledge_vector`，没有 join `t_knowledge_document` 或 `t_knowledge_chunk`，因此检索时未看到文档 enabled/deleted、chunk enabled/deleted 过滤；是否完全依赖向量同步删除需要确认。
- 当前已读检索链路没有看到用户级、组织级、知识库级权限过滤；企业知识库场景下访问控制边界需要确认。
- 当前已读检索链路没有看到向量召回后的 score threshold 过滤；低相关 chunk 是否可能进入 Prompt，需要通过样例或运行日志验证。
- `RAGConstant` 中存在 `SEARCH_TOP_K_MULTIPLIER`、`MIN_SEARCH_TOP_K`、`RERANK_LIMIT_MULTIPLIER`、`SCORE_MARGIN_RATIO`，但本轮已读主检索链路没有看到使用点；需要确认是否为旧逻辑遗留。
- `RetrievalEngine.retrieveAndRerank(...)` 会把同一批 chunks 分配给每个 KB 意图节点，多意图下来源归属不精确，是否影响块级引用和回答可解释性需要评估。
- `PgRetrieverService` 每次检索执行 `SET hnsw.ef_search = 200`，在连接池复用下的作用范围和性能影响需要确认。
- 全局检索会并行查所有未删除 collection，知识库数量增长后延迟、embedding/rerank 成本和线程池压力需要压测确认。
- `rag.vector.type` 当前默认配置为 `pg`，但 Milvus 实现带 `matchIfMissing=true`；如果配置缺失，实际装配行为可能切到 Milvus，需要确认配置治理方式。

## Phase 4: LLM SSE And Message Persistence Module

- 当前已读后端代码没有命名 `error` SSE 事件，普通异常通过 `SseEmitter.completeWithError(...)` 结束连接；前端如何展示该错误需要读取前端或联调确认。
- 普通模型错误路径中，`StreamChatEventHandler.onError(...)` 没有保存已累积的 assistant 部分回答；是否符合产品预期需要确认。
- `StreamTaskManager.cancelLocal(...)` 发送 `cancel` 和 `done` 后没有立即 `unregister`，本地任务状态依赖 Guava Cache TTL 过期，是否存在短期残留或重复取消影响需要评估。
- 用户消息写入、会话创建/更新、助手消息写入不在一个明确单事务边界内；失败场景下出现只有用户消息、无助手消息的会话是否可接受需要确认。
- `DefaultConversationMemoryService.load(...)` 并行加载摘要和历史时使用默认 `CompletableFuture.supplyAsync(...)`，没有显式业务线程池；是否符合项目线程治理需要确认。
- `StreamChatEventHandler.onComplete()` 即使累积回答为空也会尝试保存 assistant 消息；空回答是否应落库需要确认。
- 全局 `OkHttpClient` 的 `readTimeout=0`、`callTimeout=0`，首包之后如果模型流式连接长时间不再输出，实际资源占用和取消能力需要运行验证。
- 当前配置 `rag.rate-limit.global.max-concurrent=1`、`max-wait-seconds=3` 可能偏演示环境；生产配置如何调整需要确认。
- 默认 Chat 模型依赖 `BAILIAN_API_KEY`，fallback 中也包含 SiliconFlow；API Key 缺失时真实失败顺序和最终用户提示需要运行验证。
