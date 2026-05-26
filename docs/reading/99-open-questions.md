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

## Phase 4: Trace And Exception Observation Module

- `ChatRateLimitAspect.invokeWithTrace(...)` 在 `RAGChatServiceImpl.streamChat(...)` 返回后就把 run 标记为 SUCCESS；而完整 SSE 输出完成发生在后续 callback。是否需要记录完整回答生命周期，需要产品和指标口径确认。
- 首包之后如果 `modelStreamExecutor` 中的异步流式任务失败，`StreamChatEventHandler.onError(...)` 当前不更新 `t_rag_trace_run` 或 node；dashboard 是否会把这类失败误算为成功，需要运行验证。
- `@RagTraceRoot` 当前没有业务使用点，根链路逻辑由 `ChatRateLimitAspect` 特化实现；这是有意设计还是迁移遗留，需要后续确认。
- `RagTraceRecordServiceImpl` 直接执行 MyBatis insert/update，没有 fail-open 保护；trace 表写入失败是否应影响正常对话请求，需要确认设计取舍。
- `RagTraceContext` 的节点栈是 TTL 中的可变 `ArrayDeque`；并行子任务下父子节点和 depth 是否稳定，需要单元测试或真实 trace 数据验证。
- `schema_pg.sql` 中 `t_rag_trace_node.trace_id` 是 `VARCHAR(20)`，`t_rag_trace_run.trace_id` 是 `VARCHAR(64)`，备份 MySQL schema 中 node trace_id 是 `varchar(64)`；当前 Snowflake traceId 长度通常能放下，但 schema 一致性需要确认。
- dashboard 的 `avgLatencyMs`、`p95LatencyMs`、`slowRate` 只基于 SUCCESS run 的 `duration_ms`；如果 run duration 实际是到流式首包而非完整回答，指标名称和用户感知延迟可能不一致。
- trace node 目前不记录输入、输出摘要、模型名、token 数或 provider 失败链路等 extra_data；排查质量问题时是否够用，需要结合管理后台页面和真实故障样例验证。

## Phase 4: Rate Limit Queue And Cancel Module

- 当前默认 `rag.rate-limit.global.lease-seconds=30`，但模型流式回答可能超过 30 秒；Redisson expirable permit 自动过期后全局并发是否会被提前放大，需要压测验证。
- 用户取消会 `sender.complete()` 并释放 permit，但如果模型 handle 尚未绑定，后端同步主流程可能仍在执行；是否需要让取消状态更早中断问题改写、意图识别、检索等阶段？
- `StreamTaskManager.cancelLocal(...)` 不调用 `unregister(...)`，本地 cache 和 Redis cancel key 依赖 30 分钟 TTL；是否应在发送 `cancel/done` 后主动清理？
- `StreamChatEventHandler.buildCompletionPayloadOnCancel()` 在无部分回答时可能把 messageId 返回为字符串 `"null"`；前端会把本地消息 id 更新为 `"null"`，是否为预期行为需要确认。
- 排队拒绝没有 trace run，因此 dashboard 的 trace 成功率、错误率和延迟不包含拒绝流量；是否需要单独的 reject 指标？
- 首包后模型错误和 SSE 发送失败大概率不会把 trace run 改为 ERROR；dashboard 是否需要增加“流式完成状态”或“客户端完成状态”维度？
- `SseEmitterSender.sendEvent(...)` 在 closed 状态下直接抛 `ServiceException`，可能打断 `StreamChatEventHandler.onComplete()` 后续的 `taskManager.unregister(...)`；是否需要更强的 fail-safe 清理？
- `ChatQueueLimiter.trySetPermits(maxConcurrent)` 每次获取/查看 permit 时调用；运行中调整 `max-concurrent` 是否能即时生效，需要验证 Redisson 语义。

## Phase 4: Knowledge Write And Index Build Module

- `KnowledgeDocumentServiceImpl.startChunk(...)` 本轮未看到文档 enabled=1 的显式校验；禁用文档是否仍可通过接口触发分块并重新写入向量，需要前端限制或接口测试验证。
- `persistChunksAndVectorsAtomically(...)` 对 Milvus 不具备真正原子性；如果 Milvus 删除/写入成功但 DB 事务回滚，是否需要补偿任务或一致性巡检？
- `PgVectorStoreService.indexDocumentChunks(...)` 使用普通 INSERT，不是 upsert；文档启用、chunk 启用或重复重建时是否可能出现重复主键，需要运行覆盖。
- 知识库删除不 drop RustFS/S3 bucket 或 Milvus collection；长期使用是否会产生空 collection/bucket，需要确认运维清理策略。
- 知识库文档 `processMode=pipeline` 只复用 `IngestionEngine`，不写 `t_ingestion_task_node`；是否需要持久化节点级 pipeline 日志以便排障，需要确认管理端预期。
- 文档更新处理模式、分块策略或 pipelineId 后不会自动清理旧向量或重建；是否会造成“配置已变但索引仍旧”的用户误解，需要产品交互确认。
- `t_knowledge_vector.metadata` 不包含用户、组织或 ACL 字段，写入链路也没有补权限数据；企业知识库权限隔离仍需要后续设计或验证。
- 文档分块失败后，consumer 路径会捕获异常并标记 failed，默认没有 MQ 自动重试；是否需要用户手动重试、失败重试次数或补偿队列，需要确认产品要求。
- `ProcessMode.normalize(...)` 对空值直接抛异常，但 DB schema 默认是 `chunk`；上传接口是否总是传 processMode，需要前端或接口联调验证。
- pgvector 与 Milvus 的向量空间语义不同：pg 是全局表/index + metadata collection，Milvus 是每 KB collection；配置切换或迁移已有数据时如何处理需要验证。

## Phase 4: Knowledge Frontend And Document Interaction Module

- 前端文档列表对 disabled 文档仍显示“分块”入口；如果后端 `startChunk(...)` 没有 enabled 校验，禁用文档仍可能通过 UI 重新写入向量。
- 前端没有按 `status=running` 禁用文档编辑、删除、启停和分块按钮；是否应在前端先行禁用，减少依赖后端错误 toast？
- 上传页面会传 `processMode`，但 `knowledgeService.uploadDocument(...)` 类型仍把 `processMode` 建模为可选；后续新增入口绕过表单时仍可能触发后端空值问题。
- 文档编辑保存后只提示“需重新分块才会生效”，没有待重建状态、一键保存并重建或强提醒；用户是否仍会误以为索引已经更新？
- 文档分块开始后前端没有轮询或自动刷新 running 状态；是否需要自动刷新或任务进度提示？
- 分块日志弹窗只展示最近一条 chunk log；失败排障是否需要展示历史日志分页？
- chunk 页没有根据文档 disabled/running 状态限制新建、编辑、批量启用或重建向量；是否需要后端统一兜底，防止 disabled 文档被间接重建向量？
- ingestion pipeline 删除前端没有提示该 pipeline 是否被知识库文档引用；删除被引用 pipeline 后，文档重新分块的失败体验需要验证。
- 单个文档启停、单个 chunk 启停和批量选中启停没有确认框；是否符合“会影响召回结果”的危险操作交互预期？

## Phase 4: User Permission And Knowledge Access Control Module

- 后端知识库、文档、chunk、ingestion、trace、settings 等管理 API 是否应统一要求 admin 角色，而不是只依赖前端 `/admin` 路由？
- 如果项目要支持多用户或企业知识库 ACL，需要确定权限模型落点：知识库 owner、组织/租户、独立授权表、向量 metadata，还是检索前 allowed collection 计算。
- `created_by` 当前更像审计展示字段；如果未来要作为权限字段，是否应改为稳定 userId 并补查询过滤，而不是使用 username 字符串？
- MCP 的 `requireUserId` 是否是未完成实现？若工具会访问个人数据，需要补齐 userId 注入、JSON-RPC 传输、Server dispatcher 校验和 executor 使用。
- 用户被删除或禁用后，已有 Sa-Token token 是否会被主动失效？`UserContextInterceptor` 查不到用户时是否应返回明确未登录/无权限，而不是依赖后续异常。
- 当前 RAG 检索没有 ACL 后，trace/dashboard 中展示的命中知识库、chunk 或问题内容是否也需要后端 admin 角色保护，需要继续验证。

## Phase 4: Admin API Security Boundary Module

- dashboard、trace、settings、intent tree、sample question、query mapping、ingestion 等后台 API 是否应统一加后端 admin role 校验，避免普通登录用户绕过前端 `/admin` 直接访问？
- `/rag/settings` 当前返回 provider `apiKey` 字段；是否应默认脱敏、删除该字段，或仅允许 admin 查看脱敏后的配置摘要？
- trace 查询接口是否应该 admin-only；如果要支持用户查看自己的 trace，是否需要在 `RagTraceQueryServiceImpl` 自动追加 `userId = UserContext.getUserId()`？
- dashboard 当前查询全局用户、会话、消息、trace 指标；是否应只面向 admin，还是拆分出只看本人数据的 user-scoped dashboard？
- ingestion task node output 当前可能包含 rawBytesBase64、rawText、document、enhancedText、chunks；是否需要 admin/owner 权限、字段脱敏、按需下载或仅保留摘要？
- intent tree、query mapping、sample question、ingestion pipeline 这些会影响全局 RAG 行为的写接口，是否需要操作审计、版本记录、回滚或发布流程？
- `createdBy/updatedBy` 在后台全局配置和 ingestion 表中是否继续只做审计字段；若要作为 owner 权限字段，是否应改为稳定 userId 并补查询过滤？
- `DemoModeInterceptor` 只在 demo-mode 下限制写操作，不能替代 admin 鉴权；生产权限边界是否需要单独定义并写入代码规范？
