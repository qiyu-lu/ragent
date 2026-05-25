# Phase 2: Startup And Configuration

## 本轮目标和边界

本轮目标是理解主服务和 MCP 服务如何启动、配置文件如何加载、本地运行依赖有哪些，并核实 Phase 1 中发现的 PostgreSQL + pgvector 与 README 中 MySQL + Milvus 不一致的问题。

本轮没有追踪完整 Agent/RAG 请求链路，没有阅读 Controller/Service 的业务调用链，也没有修改任何生产代码。少量读取了 `@PostConstruct`、`@Scheduled`、`@RocketMQMessageListener` 所在类，只用于确认启动副作用、后台任务和监听器注册，不展开被调用的业务流程。

## 本轮读取的文件和原因

- `docs/reading/00-reading-plan.md`：确认 Phase 2 允许范围和输出要求。
- `docs/reading/01-project-map.md`：承接 Phase 1 的项目地图和开放问题。
- `docs/reading/99-open-questions.md`：检查是否已有待处理问题；本轮开始时文件不存在或无内容输出。
- `pom.xml`：确认父工程、模块列表、Java/Spring Boot 版本和统一依赖版本。
- `bootstrap/pom.xml`：确认主服务依赖，包括 Web、Milvus SDK、Tika、PostgreSQL、pgvector、JDBC、S3、Validation。
- `framework/pom.xml`：确认 Redis、Redisson、MyBatis Plus、Sa-Token、RocketMQ 等基础设施依赖。
- `infra-ai/pom.xml`：确认 AI 基础设施模块依赖 `framework` 和 OkHttp。
- `mcp-server/pom.xml`：确认 MCP 服务是独立 Spring Web 应用。
- `bootstrap/src/main/resources/application.yaml`：确认主服务端口、上下文路径、数据源、Redis、RocketMQ、向量后端、MCP、AI provider、RustFS、Sa-Token 等配置。
- `mcp-server/src/main/resources/application.yml`：确认 MCP 服务端口和应用名。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`：确认主服务启动方式、调度启用和 Mapper 扫描。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/MCPServerApplication.java`：确认 MCP 服务启动方式。
- `README.md` 中和运行依赖、技术栈关键词相关的部分：核对 README 对 MySQL/Milvus 的描述。
- `docs/quick-start.md`：文件名看似快速启动，但实际主要是多通道检索重构说明；本轮只用于确认它不是本地环境启动说明，不采用其中业务流程内容作为 Phase 2 结论。
- `resources/docker/lightweight/README.md`：确认 Docker 资源目录的用途和 Milvus 轻量方案说明。
- `resources/docker/milvus-stack-2.6.6.compose.yaml`：确认 Milvus/RustFS/etcd/Attu 容器和端口。
- `resources/docker/rocketmq-stack-5.2.0.compose.yaml`：确认 RocketMQ NameServer、Broker、Dashboard 容器和端口。
- `resources/database/schema_pg.sql`：确认 PostgreSQL schema、pgvector 扩展和向量表。
- `resources/database/backups/schema_table.sql`：确认历史备份 SQL 是 MySQL 风格 schema。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/config/DataBaseConfiguration.java`：确认 MyBatis Plus 当前按 PostgreSQL 配置分页插件。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/config/RocketMQAutoConfiguration.java`：确认 RocketMQ producer 适配器和事务监听器 Bean。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/config/WebAutoConfiguration.java`：确认全局异常处理 Bean。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenConfig.java`：确认 Web 拦截器和 Sa-Token 登录校验路径规则。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MilvusConfig.java`：确认 Milvus Client Bean 的条件装配。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RestFSS3Config.java`：确认 RustFS/S3 客户端和预签名器 Bean。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/HttpClientConfig.java`：确认 OkHttpClient 全局 Bean。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`：确认启动时创建的线程池 Bean。
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/config/AIModelProperties.java`：确认 `ai.*` 配置绑定结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/MCPClientProperties.java`：确认 `rag.mcp.*` 配置绑定结构。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/client/MCPClientAutoConfiguration.java`：确认主服务启动时会尝试连接 MCP Server 并注册远程工具。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/config/KnowledgeScheduleProperties.java`：确认知识库定时任务配置绑定。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/config/RagSemaphoreProperties.java`：确认文档上传分布式信号量配置绑定。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RAGDefaultProperties.java`、`RAGRateLimitProperties.java`、`MemoryProperties.java`、`RagTraceProperties.java`：确认 RAG 默认配置、限流、记忆和 Trace 的配置绑定。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/KnowledgeDocumentScheduleJob.java`：确认主服务启动后启用的定时任务。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java`、`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/mq/MessageFeedbackConsumer.java`：确认 RocketMQ 监听器 topic 和 consumer group。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/distributedid/SnowflakeIdInitializer.java`：确认启动时依赖 Redis 初始化 Snowflake ID。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/config/SemaphoreInitializer.java`：确认启动时依赖 Redisson 初始化文档上传信号量。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkTransactionChecker.java`：确认启动时注册 RocketMQ 事务消息回查器。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/DefaultMCPToolRegistry.java`：确认主服务本地 MCP 工具注册表的启动注册行为。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/DefaultMCPToolRegistry.java`：确认 MCP 服务自身的工具注册行为。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/aop/ChatQueueLimiter.java`、`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java`：确认启动时订阅 Redis Topic 的组件。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java`、`bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkingStrategyFactory.java`：确认启动时加载数据库映射规则和注册切分策略。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/endpoint/MCPEndpoint.java`、`MCPDispatcher.java`：确认 MCP 服务暴露的 HTTP 端点和 JSON-RPC 方法范围。

## 启动方式和配置加载

### 主服务

- 主服务启动类是 `com.nageoffer.ai.ragent.RagentApplication`。
- 启动入口调用 `SpringApplication.run(RagentApplication.class, args)`。
- 启动类带有 `@SpringBootApplication`，按 Spring Boot 默认规则加载 classpath 下的 `application.yaml`。
- 当前源码资源目录中只发现 `bootstrap/src/main/resources/application.yaml`，没有发现 `bootstrap.yml` 或 `application-*.yaml` 源文件。
- 启动类带有 `@EnableScheduling`，因此主服务会启用 Spring 定时任务。
- 启动类带有 `@MapperScan`，扫描以下 Mapper 包：
  - `com.nageoffer.ai.ragent.rag.dao.mapper`
  - `com.nageoffer.ai.ragent.ingestion.dao.mapper`
  - `com.nageoffer.ai.ragent.knowledge.dao.mapper`
  - `com.nageoffer.ai.ragent.user.dao.mapper`

### MCP 服务

- MCP 服务启动类是 `com.nageoffer.ai.ragent.mcp.MCPServerApplication`。
- 启动入口调用 `SpringApplication.run(MCPServerApplication.class, args)`。
- 启动类带有 `@SpringBootApplication`，按 Spring Boot 默认规则加载 classpath 下的 `application.yml`。
- 当前源码资源目录中只发现 `mcp-server/src/main/resources/application.yml`，没有发现 MCP 服务的 profile 配置文件。
- MCP 服务暴露 `POST /mcp`，由 `MCPEndpoint` 接收 JSON-RPC 请求。
- `MCPDispatcher` 处理的方法包括 `initialize`、`tools/list`、`tools/call`。
- MCP 服务启动时会通过自身的 `DefaultMCPToolRegistry` 自动注册 Spring 容器中的 `MCPToolExecutor` Bean。

## 启动时初始化的关键组件

### 主服务 Bean 和客户端

- `DataBaseConfiguration` 创建 MyBatis Plus 拦截器，分页插件当前使用 `DbType.POSTGRE_SQL`。
- `RocketMQAutoConfiguration` 创建 `DelegatingTransactionListener` 和 `MessageQueueProducer`。
- `WebAutoConfiguration` 创建全局异常处理器。
- `SaTokenConfig` 注册三个 Web 拦截器：Sa-Token 登录校验、体验环境只读模式、用户上下文。
- `RestFSS3Config` 创建 `S3Client` 和 `S3Presigner`，连接 `rustfs.*` 配置指定的 S3 兼容对象存储。
- `HttpClientConfig` 创建全局 `OkHttpClient`，供模型客户端、MCP 客户端等 HTTP 调用复用。
- `AIModelProperties` 绑定 `ai.*` 配置，包含 provider、chat、embedding、rerank、selection、stream。
- `ThreadPoolExecutorConfig` 创建多个线程池 Bean，包括 MCP 批处理、RAG 上下文、检索、意图识别、记忆摘要、模型流式输出、聊天入口、知识库文档分块等。

### 向量后端初始化

- `application.yaml` 中当前配置为 `rag.vector.type: pg`。
- `MilvusConfig` 上有 `@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)`。
- 因为当前配置显式为 `pg`，`MilvusConfig` 的 `MilvusClientV2` Bean 在当前默认配置下不会装配。
- 源码中存在 `PgVectorStoreService`、`PgVectorStoreAdmin`、`PgRetrieverService`，它们按 `rag.vector.type=pg` 条件装配。
- 源码中也存在 `MilvusVectorStoreService`、`MilvusVectorStoreAdmin`、`MilvusRetrieverService`，它们按 `rag.vector.type=milvus` 或缺省条件装配。

### MCP 相关初始化

- 主服务配置 `rag.mcp.servers` 默认包含一个服务：`name=default`、`url=http://localhost:9099`。
- `MCPClientAutoConfiguration` 在 `@PostConstruct` 中读取 MCP Server 列表，逐个创建 `HttpMCPClient`，调用 `initialize()` 和 `listTools()`，再把远程工具注册到主服务的 `MCPToolRegistry`。
- `MCPClientAutoConfiguration` 对连接失败有异常捕获；如果 MCP Server 初始化失败，会记录错误并跳过远程工具注册。
- 主服务自身的 `DefaultMCPToolRegistry` 也会在 `@PostConstruct` 中注册容器内已有的本地 `MCPToolExecutor`。

### 启动钩子、定时任务和监听器

- 本轮没有发现 `ApplicationRunner` 或 `CommandLineRunner`。
- `SnowflakeIdInitializer` 在 `@PostConstruct` 中通过 Redis 执行 `framework/src/main/resources/lua/snowflake_init.lua`，获取 workerId 和 datacenterId；失败会抛出异常。
- `SemaphoreInitializer` 在 `@PostConstruct` 中通过 Redisson 初始化文档上传分布式信号量。
- `KnowledgeDocumentChunkTransactionChecker` 在 `@PostConstruct` 中把文档分块事务消息回查器注册到 `DelegatingTransactionListener`。
- `ChatQueueLimiter` 在 `@PostConstruct` 中订阅 Redis Topic `rag:global:chat:queue:notify`。
- `StreamTaskManager` 在 `@PostConstruct` 中订阅 Redis Topic `ragent:stream:cancel`。
- `QueryTermMappingService` 在 `@PostConstruct` 中从数据库加载启用的查询归一化映射规则。
- `ChunkingStrategyFactory` 在 `@PostConstruct` 中注册所有 `ChunkingStrategy` Bean；如果同一类型重复注册会抛出异常。
- `KnowledgeDocumentScheduleJob` 有两个定时任务：
  - `recoverStuckRunningDocuments()`：`fixedDelay=60000`，`initialDelay=30000`。
  - `scan()`：`fixedDelayString=${rag.knowledge.schedule.scan-delay-ms:10000}`。
- RocketMQ 监听器包括：
  - `KnowledgeDocumentChunkConsumer`：topic 为 `knowledge-document-chunk_topic${unique-name:}`，consumer group 为 `knowledge-document-chunk_cg${unique-name:}`。
  - `MessageFeedbackConsumer`：topic 为 `message-feedback_topic${unique-name:}`，consumer group 为 `message-feedback_cg${unique-name:}`。

## 本地运行依赖清单

### 端口

- 主后端服务：`9090`
- 主后端上下文路径：`/api/ragent`
- MCP 服务：`9099`
- 前端开发服务：`5173`，`/api` 代理到 `http://localhost:9090`
- PostgreSQL：`5432`
- Redis：`6379`
- RocketMQ NameServer：`9876`
- RocketMQ Broker：`10909`、`10911`、`10912`
- RocketMQ Dashboard：`8082`
- RustFS S3 API：`9000`
- RustFS Console：`9001`
- Milvus：`19530`
- Milvus health/内部 HTTP：`9091`
- Attu：`8000`
- Ollama：`11434`

### 数据库

- 当前默认配置使用 PostgreSQL。
- 数据库 URL：`jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8`
- 用户名：`postgres`
- 密码：`postgres`
- 初始化 SQL：`resources/database/schema_pg.sql` 和 `resources/database/init_data_pg.sql`。
- `schema_pg.sql` 开头声明 `CREATE EXTENSION IF NOT EXISTS vector;`，说明 PostgreSQL 需要安装 pgvector 扩展。
- `schema_pg.sql` 中有 `t_knowledge_vector`，字段 `embedding vector(1536)`，并创建 HNSW 向量索引。

### 向量库

- 当前默认向量后端是 PostgreSQL + pgvector：`rag.vector.type: pg`。
- Milvus 仍保留为可选向量后端：
  - `application.yaml` 中有 `milvus.uri: http://localhost:19530`。
  - `MilvusConfig`、Milvus 存储/检索类都存在，但在当前 `rag.vector.type=pg` 下不装配。
  - 如果把 `rag.vector.type` 改为 `milvus`，会需要 Milvus 服务。
- Docker 资源中提供 Milvus 2.6.6 compose：`resources/docker/milvus-stack-2.6.6.compose.yaml`。
- 该 compose 包含 RustFS、etcd、Milvus standalone 和 Attu。

### 缓存和分布式协调

- Redis 地址：`127.0.0.1:6379`
- Redis 密码：`123456`
- 已读取代码显示 Redis/Redisson 用于：
  - Sa-Token Redis 集成。
  - Snowflake worker/datacenter 初始化。
  - 文档上传信号量。
  - 全局聊天并发限流队列和 Pub/Sub 通知。
  - 流式任务取消通知。

本轮没有发现仓库中提供 Redis Docker Compose。

### 消息队列

- RocketMQ NameServer：`127.0.0.1:9876`
- Producer group：`ragent-producer${unique-name:}_pg`
- 发送超时：`2000ms`
- Docker 资源中提供 RocketMQ 5.2.0 compose：`resources/docker/rocketmq-stack-5.2.0.compose.yaml`。
- 已确认两个 RocketMQ 消费者 topic：
  - `knowledge-document-chunk_topic${unique-name:}`
  - `message-feedback_topic${unique-name:}`

### 对象存储

- 当前配置使用 RustFS，按 S3 兼容协议访问。
- RustFS URL：`http://localhost:9000`
- Access Key：`rustfsadmin`
- Secret Key：`rustfsadmin`
- Milvus Docker compose 中也启动了 RustFS，并暴露 `9000` 和 `9001`。

### 模型服务和环境变量

- Ollama：
  - URL：`http://localhost:11434`
  - Chat endpoint：`/api/chat`
  - Embedding endpoint：`/api/embed`
  - 当前配置中候选模型包括 `qwen3:8b-fp16` 和 `qwen3-embedding:8b-fp16`。
- 百炼：
  - URL：`https://dashscope.aliyuncs.com`
  - API Key：`${BAILIAN_API_KEY:}`
  - Chat endpoint：`/compatible-mode/v1/chat/completions`
  - Rerank endpoint：`/api/v1/services/rerank/text-rerank/text-rerank`
- SiliconFlow：
  - URL：`https://api.siliconflow.cn`
  - API Key：`${SILICONFLOW_API_KEY:}`
  - Chat endpoint：`/v1/chat/completions`
  - Embedding endpoint：`/v1/embeddings`

需要关注的环境变量：

- `BAILIAN_API_KEY`
- `SILICONFLOW_API_KEY`
- `unique-name`：配置中多处使用 `${unique-name:}`，默认可为空；如果设置，会影响应用名、RocketMQ group/topic 等名称。

### MCP

- MCP 服务本地端口：`9099`
- 主服务默认 MCP Server URL：`http://localhost:9099`
- MCP HTTP 端点：`POST /mcp`
- MCP JSON-RPC 方法：`initialize`、`tools/list`、`tools/call`
- MCP 服务内置工具执行器文件名包括 `SalesMCPExecutor`、`TicketMCPExecutor`、`WeatherMCPExecutor`；本轮只确认它们是 `MCPToolExecutor` 组件，没有读取具体工具业务逻辑。

## PostgreSQL + pgvector 与 MySQL + Milvus 的矛盾核实

已核实结论：

- 当前默认源码配置和启动路径是 PostgreSQL + pgvector。
- README 当前仍写关系数据库为 MySQL、向量数据库为 Milvus 2.6。
- 当前仓库保留了 MySQL 历史备份 SQL，也保留了 Milvus 可选后端和 Docker Compose。
- 因此，这不是单纯的“看错配置”：仓库中确实同时存在旧的 MySQL/Milvus 说明或资源，以及当前默认 PostgreSQL/pgvector 配置。

支撑事实：

- `bootstrap/src/main/resources/application.yaml` 使用 `org.postgresql.Driver` 和 `jdbc:postgresql://127.0.0.1:5432/ragent`。
- `bootstrap/pom.xml` 依赖 `org.postgresql:postgresql` 和 `com.pgvector:pgvector`。
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/config/DataBaseConfiguration.java` 使用 `DbType.POSTGRE_SQL`。
- `resources/database/schema_pg.sql` 注释为 `PostgreSQL Schema for Ragent`，并写明 `Converted from MySQL schema_table.sql`。
- `resources/database/schema_pg.sql` 创建 pgvector 扩展和 `t_knowledge_vector` 向量表。
- `resources/database/backups/schema_table.sql` 是 MySQL 风格 SQL，包含 `CREATE DATABASE IF NOT EXISTS`、反引号、`ENGINE=InnoDB`、`AUTO_INCREMENT`。
- `application.yaml` 明确配置 `rag.vector.type: pg  # 可选 milvus / pg`。
- Milvus 相关 Bean 和存储/检索服务通过 `@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)` 条件启用。

尚未完全确认的部分：

- 无法仅从本轮文件判断该差异是一次已完成的数据库迁移后 README 未更新，还是项目刻意同时维护两套部署方案但 README 没写清默认值。
- 没有读取 Git 历史、提交说明或远程官方文档，因此不判断作者意图。

当前阅读结论建议：

- 后续本地运行和学习笔记应以当前源码默认配置为准：PostgreSQL + pgvector。
- Milvus 应记录为“代码保留的可选向量后端”，不是当前默认启动路径。
- README 的 MySQL + Milvus 描述应在开放问题中保留，等待后续通过官方文档、提交历史或维护者说明确认。

## 推测

- `resources/database/backups/schema_table.sql` 很可能是旧版 MySQL schema 备份；依据是路径名 `backups`、MySQL 语法，以及 `schema_pg.sql` 的“Converted from MySQL schema_table.sql”注释。
- 当前项目可能经历过从 MySQL + Milvus 到 PostgreSQL + pgvector 默认方案的迁移；依据是 PostgreSQL 默认配置、pgvector schema、保留的 MySQL 备份和 Milvus 可选实现。但本轮没有读取 Git 历史，不能作为事实。
- MCP 服务默认可单独启动；主服务如果连接不上 MCP Server，会跳过远程工具注册但不一定启动失败。依据是 `MCPClientAutoConfiguration` 捕获异常并记录错误，但未实际运行验证。

## 开放问题

- 当前仓库没有发现 PostgreSQL、Redis 的 Docker Compose；本地运行需要手动准备，还是另有未读官方文档提供？需要后续确认。
- README 中的 MySQL + Milvus 描述和当前 PostgreSQL + pgvector 默认配置不一致，原因仍未确认。
- `docs/quick-start.md` 不是本地启动指南，而是多通道检索重构说明；真正本地启动流程可能在外部官方文档或未读脚本/文档中。
- 当前配置中 `BAILIAN_API_KEY` 和 `SILICONFLOW_API_KEY` 默认可为空，但默认 chat/rerank 使用百炼，默认 embedding 使用 SiliconFlow；如果没有配置 API Key，实际调用会如何失败，需要后续运行或阅读错误处理路径确认。
- Redis 密码在配置中固定为 `123456`，但本轮没有发现对应 Redis Compose，需确认本地 Redis 如何配置密码。
- MCP 服务内置工具的业务行为尚未阅读，本轮只确认启动注册和端点。

## 下一轮建议

建议下一轮进入 Phase 3 前，先补一个很小的“Local Run Notes”回合，目标是只验证本地运行前置条件，不读业务流程：

- 阅读是否存在官方外部启动文档的本地副本或脚本说明。
- 只读取 `resources/database/init_data_pg.sql` 的初始化入口信息，不展开业务数据。
- 确认 PostgreSQL 是否必须预装 pgvector 扩展，以及初始化 SQL 的执行顺序。
- 确认 Redis、RocketMQ、RustFS、Milvus 是否有一键启动方案缺口。

如果直接进入 Phase 3，建议选择一个代表性但边界清晰的主流程，例如：

- 用户问答入口：只从 Controller 入口开始，沿 Controller -> Service -> 直接协作者追一条最短主链。
- 或文档入库入口：从上传/入库 Controller 开始，只追到任务提交和异步边界为止。

目前不建议同时读问答和入库两条链路。
