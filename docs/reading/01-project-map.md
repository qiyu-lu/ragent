# Phase 1: Project Map

## 本轮目标和边界

本轮只执行 `docs/reading/00-reading-plan.md` 中的 Phase 1: Project Map，目标是先建立项目地图，不深入业务逻辑。

本轮没有阅读 Controller、Service、DAO、实体类或具体业务流程，也没有修改任何生产代码。

## 本轮读取的文件和原因

- `docs/reading/00-reading-plan.md`：先确认阅读协议、Phase 1 允许范围、输出要求和事实/推测边界。
- 顶层目录结构：确认项目外形和主要目录，不读取业务文件内容。
- `README.md`：确认项目定位、公开说明的技术栈、模块分层和核心能力描述。
- `docs` 二级文件列表：确认已有文档索引范围，不深入阅读架构或业务文档正文。
- `pom.xml`：确认 Maven 父工程、Java/Spring Boot 版本、模块列表和统一依赖版本。
- `bootstrap/pom.xml`：确认主后端应用模块依赖。
- `framework/pom.xml`：确认通用框架模块依赖。
- `infra-ai/pom.xml`：确认 AI 基础设施模块依赖。
- `mcp-server/pom.xml`：确认 MCP 服务模块依赖。
- `bootstrap/src/main/resources/application.yaml`：确认主后端应用端口、上下文路径、数据库、缓存、消息队列、向量库、模型供应商等运行配置。
- `mcp-server/src/main/resources/application.yml`：确认独立 MCP 服务端口和应用名。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`：只确认主应用启动类名称、包名、启动注解和 Mapper 扫描包。
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/MCPServerApplication.java`：只确认 MCP 服务启动类名称、包名和启动方式。
- `frontend/package.json`：确认前端项目的构建脚本和主要前端依赖。
- `frontend/vite.config.ts`：确认前端开发端口和 `/api` 代理目标。

发现但未作为事实来源读取的生成文件：

- `bootstrap/target/classes/application.yaml`
- `mcp-server/target/classes/application.yml`

## 已验证事实

### 项目定位

- 项目名为 Ragent AI。
- `README.md` 将项目定位为企业级 RAG 智能体平台，覆盖文档入库、检索、问答、会话记忆、模型路由、MCP 工具调用、链路追踪和管理后台等能力。
- `README.md` 明确说明项目基于 Java 17、Spring Boot 3 和 React 18 构建。
- 根 `pom.xml` 的 `description` 描述该项目为“RAG综合智能体”，包含智能文档处理、检索、向量数据库、智能问答、知识库、会话记忆、深度思考等功能。

### 顶层结构

本轮看到的主要顶层目录包括：

- `bootstrap`
- `framework`
- `infra-ai`
- `mcp-server`
- `frontend`
- `docs`
- `assets`
- `resources`
- `scripts`

此外还存在 `.agents`、`.claude`、`.codex`、`.idea`、`.mvn` 等工具、IDE 或构建相关目录，本轮没有展开读取。

### Maven 模块

根 `pom.xml` 是 Maven 父工程，`packaging` 为 `pom`，声明了 4 个 Maven 模块：

- `bootstrap`
- `framework`
- `infra-ai`
- `mcp-server`

根工程基础信息：

- `groupId`: `com.nageoffer.ai`
- `artifactId`: `ragent`
- `version`: `0.0.1-SNAPSHOT`
- Java 版本：17
- Spring Boot 版本：3.5.7

从 `README.md` 和模块 `pom.xml` 可确认：

- `bootstrap` 依赖 `framework`、`infra-ai`、`spring-boot-starter-web`、Milvus SDK、Apache Tika、PostgreSQL、pgvector、Spring JDBC、S3 SDK、Validation 等。
- `framework` 依赖 Redis、Hutool、Transmittable Thread Local、Redisson、Guava、AspectJ、Spring Web、MyBatis Plus、Sa-Token、Gson、RocketMQ 等。
- `infra-ai` 依赖 `framework` 和 OkHttp。
- `mcp-server` 依赖 Spring Web 和 Gson。

### 后端入口

主后端应用入口：

- 类名：`RagentApplication`
- 包名：`com.nageoffer.ai.ragent`
- 文件：`bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`
- 注解：`@SpringBootApplication`、`@EnableScheduling`、`@MapperScan`
- 启动方式：`SpringApplication.run(RagentApplication.class, args)`

主应用 Mapper 扫描包包括：

- `com.nageoffer.ai.ragent.rag.dao.mapper`
- `com.nageoffer.ai.ragent.ingestion.dao.mapper`
- `com.nageoffer.ai.ragent.knowledge.dao.mapper`
- `com.nageoffer.ai.ragent.user.dao.mapper`

MCP 服务入口：

- 类名：`MCPServerApplication`
- 包名：`com.nageoffer.ai.ragent.mcp`
- 文件：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/MCPServerApplication.java`
- 注解：`@SpringBootApplication`
- 启动方式：`SpringApplication.run(MCPServerApplication.class, args)`

### 运行配置

主后端应用配置来自 `bootstrap/src/main/resources/application.yaml`：

- 服务端口：`9090`
- Servlet 上下文路径：`/api/ragent`
- Spring 应用名：`ragent${unique-name:}-service`
- 数据源驱动：`org.postgresql.Driver`
- 数据库地址：`jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8`
- Redis 地址：`127.0.0.1:6379`
- RocketMQ NameServer：`127.0.0.1:9876`
- Milvus URI：`http://localhost:19530`
- 当前向量类型配置：`rag.vector.type: pg`
- 默认向量维度：1536
- MCP 默认服务地址：`http://localhost:9099`
- RustFS / S3 兼容对象存储地址：`http://localhost:9000`
- Sa-Token token 名称：`Authorization`
- `app.demo-mode`: `false`

AI 供应商配置来自同一文件：

- `ollama`：本地地址 `http://localhost:11434`
- `bailian`：地址 `https://dashscope.aliyuncs.com`，API Key 通过 `BAILIAN_API_KEY` 环境变量注入
- `siliconflow`：地址 `https://api.siliconflow.cn`，API Key 通过 `SILICONFLOW_API_KEY` 环境变量注入

模型配置中出现：

- Chat 默认模型：`qwen3-max`
- Deep Thinking 默认模型：`qwen3-max`
- Embedding 默认模型：`qwen-emb-8b`
- Rerank 默认模型：`qwen3-rerank`
- 候选供应商包括百炼、SiliconFlow、Ollama 和 `noop` rerank。

MCP 服务配置来自 `mcp-server/src/main/resources/application.yml`：

- 服务端口：`9099`
- Spring 应用名：`ragent-mcp-server`

### 前端入口信息

前端文件没有深入业务读取，只读取了构建与开发配置：

- `frontend/package.json` 显示前端项目名为 `ragent-frontend`。
- 前端使用 Vite，脚本包括 `dev`、`build`、`preview`、`lint`、`format`。
- 主要依赖包括 React 18、React Router、Axios、Zustand、Radix UI、Lucide React、Recharts、React Markdown、Tailwind 相关工具等。
- `frontend/vite.config.ts` 显示开发端口为 `5173`。
- 前端开发代理将 `/api` 转发到 `http://localhost:9090`。

### 文档索引

`docs` 目录下当前看到的文档包括：

- `docs/architecture-overview.md`
- `docs/examples/pdf-ingestion-example.md`
- `docs/multi-channel-retrieval.md`
- `docs/quick-start.md`
- `docs/reading/00-reading-plan.md`
- `docs/refactoring-summary.md`

本轮只列出文档索引，没有阅读这些文档正文。

## 初步项目地图

从已读文件看，项目大体可以分为：

- `bootstrap`：主后端 Spring Boot 应用模块，负责启动主服务，并依赖通用框架层与 AI 基础设施层。
- `framework`：通用后端框架能力模块，依赖 Redis、Redisson、MyBatis Plus、Sa-Token、RocketMQ、AOP 等基础设施。
- `infra-ai`：AI 供应商或模型调用基础设施模块，依赖 OkHttp，并依赖 `framework`。
- `mcp-server`：独立 Spring Boot MCP 服务模块，运行在 9099 端口。
- `frontend`：React + Vite 前端项目，开发环境通过 `/api` 代理访问 9090 端口的后端服务。

主后端可能围绕以下业务包展开，但本轮只从 `@MapperScan` 中确认了包名，没有阅读包内代码：

- `rag`
- `ingestion`
- `knowledge`
- `user`

## 推测

- `bootstrap` 很可能是主业务编排层，承载 RAG 问答、文档入库、知识库和用户相关业务；依据是 README 的模块分层说明、`bootstrap` 依赖和启动类 Mapper 扫描包，但本轮没有读取业务类，所以还不能确认具体职责边界。
- `framework` 很可能封装通用基础设施能力，例如缓存、鉴权、MyBatis、消息队列、AOP 或通用上下文；依据是模块依赖，但本轮没有读取具体配置类或实现类。
- `infra-ai` 很可能封装不同模型供应商调用、模型选择或 HTTP 客户端能力；依据是 README 的分层说明、模块名、OkHttp 依赖和 `application.yaml` 中的 AI provider 配置，但本轮没有读取实现类。
- `mcp-server` 很可能作为主服务调用的本地工具服务；依据是模块名、独立启动类、9099 端口和主服务配置中的 `rag.mcp.servers.default.url`，但本轮没有读取 MCP 工具实现。

## 开放问题

- `README.md` 的技术栈表写到关系数据库是 MySQL，但当前 `bootstrap/application.yaml` 和 `bootstrap/pom.xml` 指向 PostgreSQL + pgvector。下一轮需要确认这是文档滞后、迁移中状态，还是支持多数据库但当前配置选择 PostgreSQL。
- `rag.vector.type` 当前为 `pg`，同时配置中仍有 Milvus URI，且 README 强调 Milvus 2.6。下一轮需要确认 Milvus 与 pgvector 的关系：是二选一、迁移状态，还是不同场景分别使用。
- `resources` 目录可能包含初始化 SQL、格式化规则或部署资源，本轮只看到顶层目录，没有读取内容。后续如果做运行环境梳理，需要检查。
- `scripts` 目录可能包含本地运行、初始化或部署脚本，本轮未读取。后续如果做快速启动或环境复现，需要检查。
- `docs/quick-start.md`、`docs/architecture-overview.md`、`docs/multi-channel-retrieval.md` 可能能补充架构和运行方式，但本轮未读取正文。
- 主后端入口启用了 `@EnableScheduling`，但本轮没有读取定时任务类，暂不能说明有哪些后台任务。
- Mapper 扫描暴露了 `rag`、`ingestion`、`knowledge`、`user` 四类数据访问包，但每个包对应的业务流程、表结构和职责边界尚未验证。

## 下一轮建议读取目标

建议下一轮执行 Phase 2: Startup And Configuration，目标是理解应用如何启动、需要哪些配置和本地依赖。

建议读取：

- `docs/quick-start.md`：确认官方本地启动流程和依赖准备。
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`：继续作为启动入口，但只围绕启动和配置展开。
- 主后端配置类、Bean 定义、`ApplicationRunner` / `CommandLineRunner` / Scheduler / Listener 等启动钩子：确认启动时初始化了哪些核心组件。
- `bootstrap/src/main/resources/application.yaml`：继续梳理运行时依赖和关键配置项。
- `resources` 中与数据库、初始化或格式化相关的文件：只在 Phase 2 需要时读取。
- `scripts` 中与本地启动或环境初始化相关的脚本：只在 Phase 2 需要时读取。
- 如需确认数据库选择，优先读取与数据源、向量存储选择、Milvus/pgvector 配置绑定相关的配置类。

下一轮仍不建议直接进入业务主流程。先把启动、配置、外部依赖和本地运行条件厘清，再进入 Phase 3 的代表性主流程。
