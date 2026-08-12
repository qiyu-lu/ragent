# 面向铁矿检测流程的人形机器人：RAG 改造项目上下文

## 文档定位

本文记录两类已经确认的信息：

1. 课题目标、现实条件、系统边界和阶段路线；
2. 对 Ragent 1.1.0 源码的只读架构分析，以及它与目标需求的映射。

本文是课题背景和源码分析参考，不是完整系统设计，也不是实现承诺。新会话应先从[项目文档入口](iron-ore-rag/README.md)确认当前阶段，再按需阅读本文。凡是机器人接口、真实设备能力、文档规模或业务规则尚未确认的地方，均按“不确定”处理，不能从本文推导出已经具备相关能力。

分析基线如下：

| 项目 | 值 |
| --- | --- |
| 上游版本 | `1.1.0` |
| Git 提交 | `f64de341452c8998ebf64cd264e60ccad6a31631` |
| 本地分支 | `research/iron-ore-rag` |
| 分析日期 | 2026-08-12 |
| 当前动作边界 | 阶段 0 本地基线已验证；已完成一项通用 trace 取消修复，尚未修改前端或铁矿领域数据库模型 |

后续若同步上游或切换基线，应先更新本节，再重新核对文中所有“当前已支持”的判断。

## 一、课题目标与现实边界

### 1.1 课题目标

目标是在现有 Java RAG 项目上进行渐进式改造，使其成为“面向铁矿检测流程的人形机器人”课题中的文档知识与流程草案辅助模块，并尽量复用现有的文档摄取、检索、生成、引用、权限和审计基础设施。

RAG 的近期目标不是直接控制机器人，而是打通以下最小知识闭环：

> 文档上传 → 文档知识入库 → 带依据检索 → 候选任务模板草案 → 人工审核

机器人侧的近期验证目标是另一个后续闭环：

> 加载已审核模板 → 创建任务实例 → 调用模拟技能 → 搬运纸箱 → 记录执行结果

这两个闭环必须分阶段建设，不能因为 RAG 项目中已有“Agent”“MCP”或“Pipeline”等命名，就默认机器人编排、技能管理或安全控制已经存在。

### 1.2 当前现实条件

- 课题仍处于申报阶段，尚未进入真实矿石检测实验室。
- 当前没有可用于联调的真实矿石、XRF 光谱仪、熔样机、分析天平等实验对象或设备。
- 人形机器人当前大致只能识别或面向纸箱、双手拿起纸箱、把纸箱放到指定位置。
- 上述能力可能来自强化学习、示教或预定义技能，但内部实现和对外调用接口均未确认。
- 当前最多可用纸箱模拟样品容器，用桌子、标记区域或简化工位模拟实验工位，验证任务编排、技能调用和状态记录。

因此，当前不得宣称已经实现矿石检测、XRF 制样、真实设备联动或实验室全流程自动化。

## 二、RAG 的职责与明确边界

### 2.1 RAG 适合承担的工作

RAG 的输入以工作人员上传或企业文档系统同步的文档为主，包括：

- 国家标准、行业标准；
- 企业内部检测 SOP 和流程文件；
- XRF 光谱仪、熔样机、天平等设备说明书；
- 安全操作规范和异常处理手册；
- 历史故障与处理记录。

RAG 适合用于：

- 文档解析、分块、索引和检索；
- 返回与问题相关的规程内容及明确来源；
- 辅助提取前置条件、步骤、质量判据、异常处理和安全约束；
- 生成结构化“候选任务模板”草案；
- 在确定性版本差异的基础上，辅助解释变化并提出潜在影响；
- 根据设备型号和报警码，为工作人员提供带文档依据的排查说明。

### 2.2 RAG 不适合成为权威执行者的工作

以下能力不应由 RAG 或大模型作为权威实现：

| 能力 | 应由谁负责 | RAG 可承担的辅助作用 |
| --- | --- | --- |
| 文档版本、有效期和替代关系 | 关系数据库和版本规则 | 解释版本差异 |
| 模板审核、发布、回滚 | 确定性工作流和权限系统 | 生成待审核草案、给出依据 |
| 任务步骤推进、重试、回退 | 任务编排器和状态机 | 提供规程说明 |
| 安全联锁和强制停止 | 安全控制器与确定性规则 | 查询安全规范，不参与越权决策 |
| 视觉、点云、位姿、力和关节状态 | 感知、定位和控制模块 | 原则上不接收这些实时数据 |
| 机械臂、关节、底盘低层指令 | 导航与操作控制模块 | 不生成低层控制指令 |
| 报警码精确匹配 | 结构化表或关键词索引优先 | 检索说明书并解释处理建议 |
| 新旧文件逐字段或逐行差异 | 确定性解析与 Diff 工具 | 解释差异的业务含义 |
| 文档到模板的影响集合计算 | 版本化引用关系和规则查询 | 对已确定的影响集合做语义分析 |

尤其不能让大模型绕过安全规则、设备状态检查或人工审批去控制机器人。

### 2.3 系统模块边界

| 模块 | 主要职责 | 是否属于现有 RAG 的直接职责 |
| --- | --- | --- |
| RAG 知识模块 | 文档摄取、检索、引用、草案生成、变化解释 | 是，主要改造对象 |
| 任务模板与审核模块 | 候选模板、审核、发布、回滚、版本追溯 | 否，需要新增业务域 |
| 任务编排模块 | 从正式模板创建实例并顺序调用技能 | 否，后续新增 |
| 视觉感知模块 | 识别物体、工位、放置区域并输出位姿 | 否，外部机器人模块 |
| 导航与操作模块 | 移动、抓取、搬运和放置 | 否，外部机器人模块 |
| 状态与规则模块 | 前置条件、安全条件、结果检查、重试或接管 | 否，后续独立模块 |

相机图像、点云、机器人关节状态、力传感器数据和设备实时状态原则上不进入 RAG 知识库。结构化报警信息可以用“设备型号 + 报警码”检索文档，但处理建议仍面向工作人员或受控规则模块。

## 三、当前项目架构概览

### 3.1 总体形态

Ragent 1.1.0 是 Maven 多模块的 Java 应用，主体是一个模块化单体后端，另带一个可独立启动的 MCP 示例服务和 React 管理前端。它没有依赖 Spring AI 或 LangChain4j，而是在 `infra-ai` 中维护自己的 LLM、Embedding、Rerank 和 VLM 抽象、模型路由、降级与熔断逻辑。

主要技术栈：

| 层次 | 当前技术 |
| --- | --- |
| Java 后端 | Java 17、Spring Boot 3.5.7、Spring MVC |
| 数据访问 | MyBatis-Plus、JdbcTemplate |
| 鉴权 | Sa-Token，当前角色只有 `admin` / `user` |
| 关系数据库 | PostgreSQL |
| 默认向量存储 | PostgreSQL + PGVector，默认维度 1536 |
| 可选向量存储 | Milvus |
| 缓存与分布式协调 | Redis、Redisson |
| 消息队列 | RocketMQ |
| 文件与解析资产 | S3 兼容存储或阿里云 OSS |
| 文档解析 | Tika、CommonMark、Excel/CSV 专用解析、MinerU、VLM |
| 可选关键词检索 | Elasticsearch |
| 可选图检索 | LightRAG |
| 模型接入 | 自定义 OpenAI 风格客户端及 Ollama、百炼、AIHubMix、SiliconFlow 路由 |
| 返回方式 | Spring SSE 流式返回 |
| 前端 | React 18、TypeScript、Vite、Zustand、Radix UI |

### 3.2 模块划分

| 模块 | 当前职责 | 工业场景判断 |
| --- | --- | --- |
| `bootstrap` | 主启动程序；知识库、摄取内核、RAG、会话、管理、用户、审计及各存储适配 | 主要复用和未来改造位置 |
| `framework` | Web 约定、异常、用户上下文、数据库基础、Redis、幂等、RocketMQ、Trace | 可复用基础设施 |
| `infra-ai` | LLM、Embedding、Rerank、VLM、模型路由和容错 | 可复用模型接入层 |
| `mcp-server` | 独立 MCP 示例服务，当前是销售、工单、天气和联网搜索工具 | 不是机器人技能注册中心，不能直接视为机器人接口 |
| `frontend` | 聊天、来源预览、知识库、文档分块、摄取管理、Trace、审计、Agent Prompt、用户管理 | 可复用页面框架和部分知识管理界面 |

### 3.3 默认配置与“代码支持”的区别

1.1.0 默认配置为：

- 源文件使用 S3 兼容对象存储；
- 向量使用 PGVector；
- 向量检索通道开启；
- 查询重写、Rerank、行内引用和 Trace 开启；
- Elasticsearch 关键词、LightRAG 图谱和联网搜索通道关闭。

因此，源码具备向量、关键词、图谱和联网搜索的多通道框架，但默认运行态实际是“向量召回 + Rerank”，不能直接描述为已启用混合检索。Rerank、MinerU、VLM 和外部模型的实际可用性还依赖相应服务和凭据，当前分析没有假设这些外部依赖已正确部署。

## 四、核心调用链

### 4.1 文档知识入库链路

现有上传和摄取是两个分开的操作，上传成功不等于向量化成功。

1. `POST /knowledge-base/{kb-id}/docs/upload`
   - `KnowledgeDocumentController.upload`
   - `KnowledgeDocumentServiceImpl.upload`
2. 校验知识库、来源类型、定时刷新和摄取配置。
3. 将文件写入对象存储；根据真实 MIME 检查是否有可用解析器。
4. 在 `t_knowledge_document` 建立 `pending` 文档记录并返回文档 ID。
5. 用户另行调用 `POST /knowledge-base/docs/{doc-id}/chunk`。
6. `startChunk` 在本地事务中把文档置为 `running`，并发送 RocketMQ 事务消息。
7. `KnowledgeDocumentChunkConsumer` 消费消息并调用 `executeChunk` / `runChunkTask`。
8. 根据知识库解析 `VectorTarget`：逻辑知识空间、知识库级 Embedding 模型、部署级向量维度。
9. 实际可用的直接分块路径进入 `DefaultIngestionKernel`：
   - 字节和文件名探测 MIME；
   - `ParserRegistry` 按“MIME × 解析档位”选择解析器；
   - 解析为有序的 Heading、Paragraph、Table、Image 等 Block；
   - `ChunkingService` 按 Block 类型和预算分块，或整文档单块；
   - `ChunkEmbeddingService` 使用 `embeddingText` 批量向量化并校验维度；
   - `ChunkIndexWriter` 扇出写入关系分块表和向量存储。
10. 默认 PG 模式下：
    - 展示正文和 `embedding_text` 写入 `t_knowledge_chunk`；
    - 正文、向量和 JSONB 元数据写入 `t_knowledge_vector`。
11. 更新文档状态、块数和 Parse / Chunk / Embed / Persist 分阶段耗时日志。

关键限制：

- `processMode=pipeline` 虽然有领域模型、页面和旧执行代码，但 `KnowledgeDocumentServiceImpl.runChunkTask` 在该基线明确抛出“管道模式重构中，暂不可用”。它不能计作当前文档入库能力。
- PDF、Word、PPT 的复杂版面解析默认依赖 MinerU SaaS；图片文本化依赖 VLM。工业文档能否达到页码、表格和条款级质量需要用真实样本文档验证。
- `ChunkIndexWriter` 有 Spring 事务包装。默认 PGVector 与关系表同在 PostgreSQL 时可利用同库事务；若启用 Milvus、Elasticsearch 或 LightRAG，远端副作用不能天然被本地数据库事务原子回滚，需要另行评估一致性和补偿。

### 4.2 远程文档刷新链路

URL 来源支持定时检查，按 ETag → Last-Modified → SHA-256 判断是否变化；变化后重新下载并对同一个文档执行完整摄取。

这属于“变化检测 + 全量替换”，不是工业意义上的文档版本管理或差量分块：

- 没有不可变的文档修订实体；
- 没有版本号、有效日期、替代关系或版本状态；
- 新文件成功切换后会清理旧文件；
- 原向量和分块被整体替换，块 ID 也会重建；
- 历史问答只记录文档 ID 和摘要，不能可靠重建当时使用的精确文档版本。

### 4.3 检索、重排、生成与返回链路

1. `GET /rag/v3/chat` 进入 `RAGChatController`。
2. `RAGChatServiceImpl` 创建会话和任务 ID，经 Redis 公平排队、并发限制和 Trace 包装后调用 `StreamChatPipeline`。
3. `StreamChatPipeline` 顺序执行：
   - 加载会话记忆并持久化用户问题；
   - 查询重写和多问题拆分；
   - 意图识别；
   - 歧义引导或纯系统回答短路；
   - 知识库检索及意图绑定的 MCP 工具调用；
   - Prompt 组装；
   - LLM 流式生成。
4. 知识检索由 `RetrievalEngine` 和 `MultiChannelRetrievalEngine` 完成：
   - `RetrievalScopeResolver` 根据意图置信度决定定向知识库或全库；
   - 所有已启用的 `SearchChannel` 并行召回并受通道超时限制；
   - 当前默认只有向量通道，其他可选通道为 Elasticsearch、LightRAG 和联网搜索；
   - 后处理依次做跨通道去重、RRF 融合、候选预算截断、Rerank、文档元数据回填；
   - 多子问题结果合并后格式化为 LLM 上下文。
5. `SourcesAssembler` 将命中块按文档去重，以每篇文档最高分块生成来源列表。
6. 开启引用时，`CitationContextEnricher` 给模型上下文注入文档级编号，Prompt 要求模型输出 `[N](#cite-N)`。
7. `RAGPromptService` 结合 Agent Profile / Prompt Slot、检索上下文和会话历史构造普通消息列表。
8. `LLMService.streamChat` 通过模型路由和降级链调用模型。
9. `StreamChatEventHandler` 以 SSE 下发思考片段和回答片段；结束时把回答、来源和 grounding 片段写入会话消息，并在 `finish` 事件返回来源面板数据。

这里的行内引用依靠提示词约束模型，不是确定性的“每个结论—证据片段”校验器。当前 `SourceRef` 只有文档 ID、文档名、来源类型、文件类型、URL 和 100 字摘要，没有文档版本、页码、章节、条款号或块 ID。

## 五、存储、缓存和消息队列

| 组件 | 当前用途 | 默认是否参与主链 |
| --- | --- | --- |
| PostgreSQL | 知识库、文档、块、摄取日志、刷新任务、会话、消息、意图、Prompt、用户、审计、Trace | 是 |
| PGVector | `t_knowledge_vector` 中的向量与 JSONB 元数据 | 是 |
| Milvus | PGVector 的可选替代向量后端 | 否 |
| Redis / Redisson | 鉴权会话、术语/意图/Prompt 缓存、幂等、Snowflake 节点分配、分布式锁、信号量、公平排队、任务取消 | 是，多个核心功能依赖 |
| RocketMQ | 文档异步分块、知识库清理、反馈事件 | 是 |
| S3 兼容存储 / OSS | 源文件和解析资产 | S3 默认，OSS 可选 |
| Elasticsearch | 关键词索引和 BM25 召回 | 否，默认关闭 |
| LightRAG | 图谱写入、图检索和后台可视化 | 否，默认关闭 |

## 六、现有功能与目标需求映射

判断含义：

- **直接复用**：领域含义与目标基本一致；
- **改造后复用**：基础存在，但数据契约或治理粒度不满足工业场景；
- **需要新增**：当前没有对应业务域；
- **远期外部集成**：不应纳入近期 RAG MVP。

| 目标需求 | 1.1.0 当前状态 | 判断 |
| --- | --- | --- |
| 文档上传、对象存储 | 支持本地文件和 URL；有上传限流、预览和删除 | 直接复用 |
| 文档解析 | 支持常见办公、文本、表格、图片格式；富文档依赖 MinerU | 改造后复用，先验证工业文档质量 |
| 结构感知分块 | 有 Block 中间表示、章节上下文、表格/图片专用分块和预算配置 | 直接复用主干，需补溯源元数据 |
| Embedding 与向量索引 | 知识库级模型、批量嵌入、维度校验、PGVector/Milvus | 直接复用 |
| 知识空间 | `t_knowledge_base` + 唯一 `collection_name` + 意图多库路由 | 直接复用 |
| 文档处理状态 | `pending/running/failed/success`、启停、分阶段日志 | 直接复用摄取状态，但它不是审核/生效状态 |
| 工业文档分类与元数据 | 只有名称、来源、MIME、大小、定时配置和摄取配置等固定技术字段 | 需要改造 |
| 文档版本管理 | URL 变化后替换同一文档；没有修订、版本链、有效期和旧文件保留 | 需要新增 |
| 增量更新 | 有变化检测，但变化后整篇重新摄取并替换 | 改造后复用变化检测；版本化增量能力缺失 |
| 混合检索 | 多通道框架、RRF、预算和 Rerank 已有；默认仅向量通道 | 改造后复用，不能声称当前已启用 |
| 工业元数据过滤 | 检索作用域主要按知识库 Collection；无设备、标准号、版本、有效期等过滤契约 | 需要新增 |
| 来源引用 | 有来源面板和行内文档级编号，并随消息落库 | 改造后复用；工业场景需版本/页/节/条/块级锚点 |
| 结构化任务模板生成 | 当前主回答是自由文本，`ChatRequest` 无 JSON Schema / response format，且无模板 DTO 或校验 | 需要新增 |
| 候选模板持久化 | 无任务模板表、版本和来源绑定 | 需要新增 |
| 人工审核、发布、回滚 | 无工作流；用户只有 `admin/user` | 需要新增 |
| 文档—模板追溯 | 无不可变文档版本，也无模板引用关系 | 需要新增 |
| 文档更新影响分析 | 无依赖集合计算和影响分析任务 | 需要新增；依赖查询应确定性实现，RAG 只做解释 |
| 业务变更审计 | 有前后快照、差异、操作者、结果和请求上下文 | 可复用审计基础设施，不等于审核流程 |
| RAG Trace 与反馈 | 有节点 Trace、消息反馈、会话和来源持久化 | 直接复用作质量分析基础 |
| Agent Profile / Prompt | 可管理和激活 Prompt 槽位 | 可复用 Prompt 管理；它不是机器人 Agent 或任务模板 |
| 企业文档系统同步 | 正式知识文档链仅支持 file/url；旧摄取 Pipeline 虽有 Feishu Fetcher，但该模式当前不可用 | 当前缺失 |
| 机器人技能注册与模拟调用 | MCP 示例仅是销售、工单、天气、联网搜索工具 | 需要新增，且不应直接改名冒充机器人技能 |
| 机器人任务编排与执行记录 | 现有 Ingestion Pipeline 只处理知识摄取，而且正式文档入口当前禁用 | 需要新增独立业务域 |
| 视觉、导航、抓放、设备联动 | 项目内不存在对应机器人模块 | 远期外部集成 |
| 安全联锁与运行时决策 | 项目内不存在机器人安全状态机 | 远期独立模块，不能交给 RAG |

## 七、可复用能力

优先复用以下能力，而不是重新开发整套 RAG：

1. 知识库、文档、分块管理及源文件预览；
2. 对象存储抽象和本地文件 / URL 摄取入口；
3. MIME 路由、结构化解析、Block 感知分块和 `embedding_text`；
4. Embedding 路由、批量向量化、向量维度校验；
5. PGVector 默认实现及可选 Milvus 接口；
6. 多通道检索扩展点、作用域、超时、去重、RRF、预算与 Rerank；
7. 会话记忆、SSE、答案落库、来源面板和文档预览；
8. Redis 幂等、分布式协调、流量保护和 RocketMQ 异步任务；
9. 通用业务变更审计和 RAG Trace；
10. 用户、管理后台、表单和页面布局基础；
11. Agent Prompt 的配置与激活机制，可用于管理“草案生成提示词”，但不能代替模板领域模型。

## 八、已确认的缺失能力与风险

### 8.1 近期 MVP 的关键缺口

1. **不可变文档版本**：标准和 SOP 无法形成可追溯版本链，旧来源会随刷新而漂移。
2. **工业元数据**：缺少文档类别、标准号、发布机构、版本号、发布日期、生效/失效日期、适用设备型号、工序、安全等级、保密级别等字段及过滤能力。
3. **精确证据锚点**：当前引用只到文档级；`Provenance` 实际只有源文件和 Excel Sheet，未承载页码、bbox 或条款号。
4. **章节元数据落地不完整**：`ChunkMetadata` 内存中有 `outlinePath`，但当前 `toMap()` 没有序列化该字段，关系块表也没有通用元数据列；章节路径主要进入了 `embedding_text`，不能作为稳定、可查询的引用事实。
5. **结构化输出契约**：模型主链只返回自由文本，没有任务模板 Schema、强校验、修复策略和失败状态。
6. **候选模板与审核域**：没有模板、模板版本、来源绑定、审核意见、发布状态、回滚和角色分离。
7. **追溯和影响分析**：没有“文档版本 → 模板版本 → 任务实例”的关系链。

### 8.2 运行和质量风险

- 源码支持不等于运行启用：默认没有关键词、图谱或联网通道。
- 标准号、设备型号、报警码等精确词项通常不应只依赖向量检索；应以结构化/关键词匹配为主，语义检索为补充。
- 目前的行内引用由模型生成，尚无“回答断言是否真的被对应证据支持”的自动验证。
- 富文档、扫描件、复杂表格、公式和图片的解析质量尚未用真实工业样本验证。
- MinerU、VLM 和云端模型可能产生文档外发、隐私、网络稳定性和成本问题，部署策略未确认。
- 默认 PG 模式的一致性较简单；启用 Milvus、Elasticsearch、LightRAG 后会出现跨系统写入与补偿问题。
- 现有 `Agent`、`MCP`、`Ingestion Pipeline` 与机器人语义不同，复用时必须保留清晰命名边界。

## 九、建议优先阅读或讨论的问题

按优先级建议先确认：

1. **首批样本文档**：选择少量真实或脱敏的标准、SOP、说明书和异常手册，确认格式、页数、扫描件比例、表格复杂度和语言。
2. **引用验收标准**：最低要求究竟是文档版本、页码、章节、条款号还是原文片段；这会决定解析与数据模型边界。
3. **文档版本语义**：企业如何标识版本、修订、替代、废止和生效时间，以及同一文件重传应创建新版本还是覆盖草稿。
4. **候选任务模板最小字段**：确认字段、参数类型、步骤顺序、异常和安全约束的表达方式，以及哪些字段必须人工填写。
5. **审核权限和状态**：谁能生成、编辑、提交审核、批准、发布和撤销；`admin/user` 两角色是否足够。
6. **数据部署边界**：文档是否允许发送到 MinerU 或云模型，是否必须离线部署。
7. **检索评测集**：用标准号、专业术语、设备型号、异常码和跨段流程问题分别测试召回、引用准确性和无答案行为。
8. **企业文档来源**：近期只做人工上传，还是必须接入某个明确的内部文档系统。
9. **机器人技能接口**：只在进入模拟执行阶段前确认；当前不要假设已有统一 API、同步语义、返回码或可取消能力。

## 十、分阶段推进计划

### 阶段 0：现有项目分析与基线复现

当前已完成的范围：

- 固定 1.1.0 分析基线；
- 建立模块和技术栈概览；
- 闭环追踪文档摄取与问答链路；
- 核对数据库、缓存、消息队列、来源、状态、元数据和治理缺口；
- 区分可复用、需改造、需新增和远期外部集成。
- 建立项目专属本地中间件栈，并用小型 Markdown fixture 验证上传、异步摄取、检索问答和来源返回；简要复现步骤见[阶段 0 文档](iron-ore-rag/stages/00-reproduction.md)，详细证据见 [`iron-ore-rag-implementation-log.md`](iron-ore-rag-implementation-log.md)。

### 阶段 1：最小样本与改造分析

下一阶段先从原始调研材料中选择一个有限样本，再产出小范围设计和验收标准，不立即扩展成完整机器人平台。重点是：

- 工业文档元数据与不可变版本的最小模型；
- 版本化证据锚点和引用粒度；
- 候选任务模板的最小结构化契约；
- 草案、人工审核及审核记录的最小状态闭环；
- 对现有上传、摄取、检索、Prompt、来源、审计和前端页面的具体复用点；
- 小样本文档上的解析、召回、引用和结构化输出验收方法。

### 阶段 2：知识闭环 MVP

仅实现：

> 文档上传 → 版本化入库 → 带精确来源的检索 → 候选模板草案 → 人工审核

暂不加入真实机器人、视觉、导航、设备控制、技能注册、复杂影响分析、图谱检索或庞大微服务拆分。关键词通道是否纳入 MVP，应由工业样本的精确词项召回测试决定；图谱和联网搜索不作为默认前提。

### 阶段 3：纸箱与模拟技能闭环

在机器人技能接口尚未明确时，新增与真实机器人解耦的技能契约和模拟适配器，验证：

> 加载正式模板 → 创建任务实例 → 调用纸箱抓取/放置技能 → 记录步骤与异常结果

这一阶段的执行器、状态机和技能注册表属于确定性业务模块，不属于 RAG。

### 阶段 4：真实机器人与实验设备集成

只有在真实技能接口、感知输出、设备协议和安全责任边界明确后，才考虑接入视觉、语义地图、导航、操作和设备状态模块。真实 XRF 检测流程仍需要实验验证、质量体系和安全评审，不能从纸箱演示直接外推。

## 十一、下一阶段建议的最小改造范围

建议主线是继续保留当前 Maven 多模块和模块化单体形态，只围绕“工业知识与候选模板审核”增加一个边界清晰的小闭环：

### 纳入范围

- 复用当前文档上传、对象存储和摄取内核；
- 为工业文档补最小分类、版本和有效性元数据；
- 让检索结果能够绑定不可变文档版本和精确证据锚点；
- 定义并校验候选任务模板的结构化输出；
- 保存候选模板、来源绑定、审核意见和审核状态；
- 复用现有用户、审计、Trace 和前端管理框架；
- 用少量样本文档建立可复现的验收集。

### 暂不纳入范围

- 真实机器人或设备接口；
- 视觉、导航、抓取和低层控制；
- 机器人运行时安全状态机；
- 技能注册、真实调用和任务执行实例；
- 文档更新影响分析的完整自动化；
- 图谱、联网搜索和大规模微服务化；
- 未经人工审核直接发布或执行模型生成结果。

这个范围可以形成申报阶段可信、可演示、可追溯的知识闭环，同时避免把尚不存在的实验室和机器人能力包装成已经完成的系统。

## 十二、仍未确定的关键事项

- 人形机器人当前技能的正式名称、请求参数、返回结果、超时、取消和错误语义；
- 首批工业文档的来源、版权、密级、格式、规模和版本命名规范；
- 企业内部文档系统及同步协议；
- 文档是否允许使用外部 MinerU、VLM 和云端 LLM；
- 引用需要达到的法务/质量体系粒度；
- 模板审核人、发布人和管理员的组织权限；
- 候选模板的最终 Schema 和可编辑范围；
- 真实实验设备的接口、安全联锁和状态码；
- 后续部署规模、并发、可用性和灾备要求；
- 用于判断 RAG 效果的问答集、期望答案、引用标准和拒答规则。

在这些事项被确认前，不应自行假设项目已经具备对应能力。

## 十三、后续源码阅读入口

建议继续沿调用链阅读，而不是无差别逐文件展开：

### 构建、启动与配置

- [`../pom.xml`](../pom.xml)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java)
- [`../bootstrap/src/main/resources/application.yaml`](../bootstrap/src/main/resources/application.yaml)
- [`../frontend/package.json`](../frontend/package.json)

### 文档摄取

- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/DefaultIngestionKernel.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/DefaultIngestionKernel.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/registry/ParserRegistry.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/registry/ParserRegistry.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkingService.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkingService.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/embed/ChunkEmbeddingService.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/embed/ChunkEmbeddingService.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/sink/ChunkIndexWriter.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/sink/ChunkIndexWriter.java)

### 检索、生成与引用

- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalEngine.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalEngine.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourcesAssembler.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourcesAssembler.java)
- [`../framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/SourceRef.java`](../framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/SourceRef.java)

### 状态、版本与治理核对

- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java)
- [`../bootstrap/src/main/java/com/nageoffer/ai/ragent/audit/dao/entity/BizChangeLogDO.java`](../bootstrap/src/main/java/com/nageoffer/ai/ragent/audit/dao/entity/BizChangeLogDO.java)
- [`../resources/database/schema_pg.sql`](../resources/database/schema_pg.sql)
