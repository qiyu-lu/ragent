# 统一研究工作流实施记录

主计划：[实施计划](agentic-research-implementation-plan-2026-09-17.md)。模型与评测口径：[评测与预算](agentic-research-evaluation-and-budget-2026-09-17.md)。实际验证：[验证报告](agentic-research-validation-report.md)。

## 当前接续点

P0、P1 已完成；P2 进行中，已完成数据契约、知识库/文档作用域、块级与受限邻接证据快照、研究表 SQL，并通过 Java 存储与来源读取的隔离 PostgreSQL 联调；P3—P8 未开始。接下来继续 P2 的 QASPER/MuSiQue 转换与幂等导入。当前提供 Java 服务，尚未接入 SDK 工具、研究运行器或聊天入口，不能把测试通过写成研究 Agent 已完成。

## P0：基线、分支与接入准备（2026-09-17，已完成）

### 分支、工作区与环境

- 原分支：`research/iron-ore-rag`；起始提交：`a7ef61810fbfa76a16b998887762575ac1265e29`，标题 `add md and png`。未刷新远端时，本地 `origin/research/iron-ore-rag` 与 HEAD 为 0/0；原计划的“领先 4 个提交”是制定时快照。
- 新分支：`feat/agentic-research`，从当前 HEAD 创建；未 push、合并或改写历史。
- 这是关联工作树，Git 公共目录为 `/home/sd101t/IdeaProjects/ragent-new/.git`；分支和阶段提交需要写该目录，已通过沙箱授权执行。
- 已有 WIP：主计划为已跟踪修改，评测预算补充为未跟踪文件；无已有代码修改。这两份文档按 P0 要求纳入阶段提交，用户正文保留，仅更新实施状态。
- 修改前文档副本位于 `/tmp/agentic-research-p0-p1/`。主计划 SHA-256：`08e19fdbb306b4b0b596b79c89d6f9d972233808f3e5d638861d8ad1838e4e8a`；预算补充：`bf84b2cc6eca6564e5a6b1aa9bcd6a1f2958cad7311318f45871cb4a248e6200`。
- JDK 17.0.15、Maven Wrapper 3.9.11、Node.js 22.21.1、npm 10.9.4；Spring Boot 3.5.7。后端仍为 Java 17。

### 模型、SDK 与数据

- 首期研究模型约定为 `qwen3.7-flash-2026-07-15`，复用 `BAILIAN_API_KEY`；P0/P1 不切换普通问答模型，不调用付费模型。Embedding 与 rerank 沿用计划中的提供方。
- 在根 `pom.xml` 的 dependencyManagement 固定 `io.agentscope:agentscope-core:2.0.1` 与 `io.agentscope:agentscope-extensions-model-openai:2.0.1`；尚未给在线模块加入实际依赖。
- 2026-09-17 核对 [v2.0.1 父 POM](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/pom.xml) 与[模型扩展 POM](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/pom.xml)：Java 17、上述坐标和模块拆分与计划一致。
- Maven Central 实际解析两份 2.0.1 正式 POM 成功。此前离线解析失败是本地未缓存，在线解析后已解决；未用缓存中的 2.0.3-SNAPSHOT 替代。完整传递依赖、冲突和原生工具协议在 P3 验证。
- 数据根目录 `local-data/agentic-research/raw/` 已存在：3 个 QASPER Parquet 分片、6 个 MuSiQue JSONL；`manifests/source-inventory-2026-09-17.json` 存在。这里只核对路径与文件数，没有重新转换、入库或评测。

### 基线检查

| 检查 | 实际结果 |
| --- | --- |
| `./mvnw -o -pl bootstrap -am -DskipTests package` | 通过；包含现有测试源码编译 |
| 13 个相关测试类，58 个用例 | 首次 57 个通过、1 个因沙箱禁止 MockWebServer 本地监听报错；允许本地测试端口后同一集 58/58 通过 |
| `npm --prefix frontend run build` | 通过；存在既有大 bundle 提示 |
| `tsc -p frontend/tsconfig.app.json --noEmit` | 已有 24 个错误，分布在 FeedbackButtons、IngestionPage、KnowledgeDocumentsPage、chatStore |
| `tsc -p frontend/tsconfig.node.json --noEmit` | 通过；生成的已跟踪 tsbuildinfo 已还原，不带入提交 |

完整命令、日志位置和限制见验证报告。P0 建立基线，不将既有类型错误伪装为通过，也不在退役阶段顺手重写无关页面。

阶段提交：`8a9c79d`，标题 `docs: record agentic research implementation baseline`。

## P1：旧业务退役（2026-09-17，已完成）

### 删除前保存的运行器机制与 P3 测试要求

以下事实已由当前代码与基线测试重新核对；删除后可用 `git show a7ef618:<路径>` 回看，路径前缀为 `bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/`。

1. `TaskAgentStore.claim` 在短事务内按 runId + owner `SELECT FOR UPDATE`，仅领取 READY 或过期 RUNNING，递增 revision/turns，分配随机 leaseToken 与 180 秒 leaseUntil。活动租约返回空，避免重复模型调用；过期租约可被新领取替代。
2. `TaskAgentService.advance` 在领取提交后调用模型和只读工具，不持有模型调用期间的数据库锁。`finish` 再开短事务锁行，仅在当前 RUNNING 且 leaseToken 与领取值一致时保存。取消或新租约后的迟到结果直接返回当前状态，不覆盖。
3. `cancel` 在持锁短事务写 CANCELLED、清除租约，重复取消/完成后取消不产生新的业务提交。旧实现只阻止本地写回，没有供应商 HTTP 调用句柄取消传播；P3 必须补齐这一点。
4. `reply` 只允许 WAITING_INPUT / WAITING_APPROVAL / FAILED；人工确认检查 revision。用户条件变化会清空草稿和检查标志。新研究运行器应按新状态契约处理追问，不继承送检审批对象。
5. 原执行上限为 16 轮，最近观察最多 12 条；新研究预算应涵盖所有角色、修复和最终生成，不能简单把旧 turns 计数分发给每个 worker。
6. 旧事件 sequence 使用串行状态 revision 分配；这只能用于被行锁串行化的旧路径。新并发事件必须按 runId 原子分配，不能把旧逻辑直接用于多个 worker。
7. 旧 `TaskAgentServiceTest` 的有效边界样例：`lateModelResultCannotOverwriteCancellation`、`activeLeasePreventsDuplicateModelCallAndExpiredLeaseCanResume`、`supersededWorkerCannotPublishItsResult`、`allTaskOperationsEnforceOwner`、`boundsRepeatedInvalidActions`、`modelFailureIsPersistedAndCanBeRetried`。基线均通过；P1 删除专用测试，P3 为新运行器重新实现对应测试，当前不保留无法编译的新类占位测试。

本节只保存机制和验证依据，不保留两套常驻运行器。原样品/工位事务及审批提交属于退役业务，不迁入新研究服务。

### 已完成的代码与数据结构变更

- 删除 `ironore/agent` 全部送检运行器、业务工具、种子初始化与专用测试，以及 `TaskAgentController`、专用提示词、前端 TaskAgentPage/taskAgentService、`/tasks` 路由和侧边栏入口。
- 删除模拟执行工具/接口/模型/DAO，以及机器人 controller/service/compiler/gateway/config/模型/DAO、专用测试和 ROS1 跟踪源码。`robot-gateway/README.md` 仅保留退役与历史设计链接。机器上的既有 ROS 构建产物继续由原 Git 忽略规则保护，不删除或提交用户本地文件。
- 新增 [TaskTemplateGenerator](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/TaskTemplateGenerator.java)，将模型生成、一次修复与原有 TaskTemplateValidator 分离；它不依赖数据库、审批或执行对象。旧草稿服务只管理来源/归属、持久化和过渡期人工确认。
- 草稿视图不再包含 execution，读取不再访问模拟执行表。已有 SIMULATED 草稿在视图映射为 APPROVED，不修改历史行、不回读执行记录。前端只展示草稿、证据位置和人工确认。
- `createDraft` 移除覆盖模型调用的 `@Transactional`，使用单次原子 insert；并发唯一键冲突时回查已保存草稿。相关测试模拟重复键异常验证返回行为，未宣称已做真实并发数据库压测。
- 创建草稿请求增加非空字段校验；普通问答、来源、文档摄取、版本比较和通用可选 MCP 保留。仅移除确定退役的业务工具注册，不按 agent/task 名称批量删除其他模块。
- 新建 schema 去掉送检/执行/机器人表，保留 `t_iron_ore_task_template`。bootstrap POM 不再打包 `260915_task_agent.sql` 测试资源；三个相关历史升级 SQL 保持字节一致。
- 新增 `260917_retire_execution_demo.sql`，已有环境手工执行后需清除实际应用 Redis 的 `ragent:intent:tree` 缓存，或通过意图管理页面停用旧模拟节点。未对已有业务库执行升级，也未清除其 Redis 缓存。
- 新增 [数据库验证脚本](../../scripts/validate-agentic-research-p1-database.sh)，默认使用开发栈 PostgreSQL 容器；`P1_POSTGRES_CONTAINER` 可覆盖容器名。随机创建隔离库，只清理本次成功创建的库。
- 更新仓库首页、文档入口、主计划状态和两份退役模块历史说明。旧评测及负结果保持；详细流程笔记的系统性整理仍在 P8。

### 验证、限制与偏差

后端 clean 打包、前端最终 build 通过；42 个定向用例通过，包括新增 11 个草稿/生成器用例及保留的普通问答管道、检索、摄取、版本比较和取消检查。PostgreSQL 16 隔离库实际通过 schema/init、草稿 CRUD、脚本执行两次及旧数据保留，测试库已清理。

app 严格类型检查仍有 24 个既有诊断，与 P0 的诊断逐条一致；中途误删的通用 PlayCircle import 已恢复。node 类型检查通过。定向 ESLint 在读取源码前因现有 react-refresh 配置不兼容失败，配置/package/lock 均未改动；没有将它写为通过。

源码和 clean JAR 检查没有旧运行器、机器人或模拟类/提示词；当前入口文档本地链接解析通过。没有启动页面做真实模型 E2E，也没有调用付费模型。SQL 检查只在新建隔离库执行，不能代替已有业务环境部署验收。

范围与 P1 一致。额外的退役意图升级脚本用于防止普通问答继续路由到被删除工具；提前更新首页和历史说明是为避免留下不可用启动入口。暂存草稿与人工确认接口按计划保留到 P5。

阶段起始提交：`8a9c79d`。阶段提交：`a1b61d7`，标题 `refactor: retire inspection and robot execution demos`。

## P2 首批：契约、纯知识检索与块级证据（2026-09-17，已完成；P2 仍进行中）

### 范围与实现

- 从 `feat/agentic-research@a1b61d7` 的干净工作区继续；本批没有切换分支、push 或合并。
- 新增 ResearchBrief / EvidenceRecord / SubtaskResult，以及候选摘要、内部快照和原文读取结果契约。ResearchBrief 必须保存明确的 allowedKbIds；本批尚未实现运行创建接口，由 P3 负责按真实共享规则计算和保存初始范围。
- [KnowledgeSearchService](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/KnowledgeSearchService.java) 先检查运行归属与保存的范围，模型只能选其中的知识库。当前知识库全局共享，createdBy 是审计字段，不能据此宣称已有租户文档权限。
- MultiChannelRetrievalEngine 新增显式范围入口，在召回前设置目标 collection，不走意图回退或补充到其他库；只使用可回查持久块的向量/关键词通道，原始越界结果在重排前拒绝。研究入口不调用聊天管道、完整 RetrievalEngine、MCP 或联网通道；原普通问答入口保留原行为。
- 检索后在只读短事务中回查数据库 chunk/document/KB 的正文、启用状态、版本和实际位置，不采信索引内的摘录。摘要上限为 1024 个 Java 字符，标注 truncated；保存完整块内部快照和首次来源 taskId。
- evidenceId 为 `ev-` 加 SHA-256，身份包含 run、知识库、文档、版本、块、正文及位置 hash。同一运行的不同 worker 共用 ID；不同运行、版本、内容或源块不合并。数据库冲突时保留首次快照。
- [SourceReader](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/SourceReader.java) 只接收 evidenceId，检查它属于本运行/用户，校验快照 hash，再检查当前来源。来源停用/删除时拒绝；正文、版本或位置变化时返回旧快照并标记 CHANGED。读取正文上限为 16000 个 Java 字符，保留剩余截断说明，不切断 UTF-16 代理对。
- 检索候选初始 read=false；实际 read_source 后保存已提供文本、截断与 read=true。多文档证据共享运行命名空间；MuSiQue 等段落资料标记 AVAILABLE_EXCERPT，其余为 CHUNK，均不称为整篇全文。旧 metadata 不足时只返回真实块序号，不编造章节、页码或拼接邻块。
- 新建 schema 与 [增量 SQL](../../resources/database/upgrades/v1.1.0/260917_02_research_evidence.sql) 增加 run/evidence/event 三张表、同用户请求唯一键和 run 内事件序号主键。运行租约、epoch、usage、事件计数为 P3 预留字段，尚未实现调度、取消或事件写入器。已有环境仍需手工执行 SQL，未升级业务库。
- 新增 [P2 数据库验证脚本](../../scripts/validate-agentic-research-p2-database.sh)，随机创建隔离库，验证新建和重复升级后列、默认值、约束一致，以及历史草稿保留和存储约束；仅清理本次成功创建的测试库。

调用顺序是：未来运行器保存服务端 ResearchBrief → search(run/owner/task, query, narrowedKbIds, limit) → 返回候选 evidenceId → read(run/owner, evidenceId) → 返回已读正文与 CURRENT/CHANGED。run/owner/task 标识只能由执行器传入，不能开放为模型可改参数；原生工具注册在 P3 实现。

### 实际验证与边界

- 后端 clean package 通过；16 个定向测试类共 101 个用例全部通过，0 失败/错误/跳过。本批新增 23 个用例，覆盖召回前范围、归属、稳定 ID、多文档、截断、来源变更/停用、虚构来源和 hash 不一致；同时复跑普通问答、检索、摄取、版本比较、草稿及取消检查。
- PostgreSQL 16 隔离库实际通过 schema/init、两次增量执行、请求/事件唯一约束、来源快照冲突保留、归属 SQL 和历史草稿保留；测试库已清理。H2 只验证 Java store 的 SELECT/UPDATE 与结果映射；Java INSERT ON CONFLICT、MyBatis 来源读取和 Spring 事务未对 PostgreSQL 联调。
- 首次 SQL 验证发现新建 schema 中残留的补丁前缀字符，修正后复跑通过，首次失败与清理日志保留。原始日志、JUnit 结果和 checks.json 位于本地忽略目录 `local-data/agentic-research/runs/20260917T072920_P2A/`，详细命令见验证报告。
- 本批没有修改前端、接入 SDK、调用真实 embedding/rerank/研究模型、转换/导入数据或执行模型评测；不生成效果或费用成绩。前端检查沿用 P0/P1 的历史记录，本批未重跑。

本批起始提交：`a1b61d7`。阶段提交：`4b8a328`，标题 `feat: add scoped research evidence snapshots`。

## P2 第二批：文档筛选与可靠邻接快照（2026-09-17，已完成；P2 仍进行中）

### 范围与实现

- 从 `feat/agentic-research@4b8a328` 的干净工作区继续。本批未接入 SDK、运行器、前端或模型调用。
- ResearchBrief 新增 `allowedDocIds`；旧四参构造与已有 JSON 兼容。空列表表示在允许知识库内不另限文档；工具的 null/空文档参数继承服务端保存的限制，不能清空它。显式文档必须属于当前选定知识库、未删除且启用；隐式限制取当前有效交集，交集为空时报错，不退回不限文档。
- [KnowledgeSearchService](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/KnowledgeSearchService.java) 增加 narrowedDocIds 参数，经 RetrieveRequest/SearchContext 传入召回。PGVector 在 ORDER/LIMIT 前使用绑定参数过滤 doc_id；Milvus 使用 JSON doc_id 过滤表达式；ES 在 bool filter 中加入 doc_id terms。三个后端返回真实 docId，原始越界结果在重排前拒绝，随后仍回查关系表的文档范围。Milvus 语法参照[官方 JSON 文档](https://milvus.io/docs/json-field-overview.md)核对；本批仅实际联调 PostgreSQL，Milvus/ES 以请求构造测试验证。
- ChunkMetadata 将实际解析的 outlinePath 写入权威 `section_path`，避免章节只存在于内存、未进入来源 metadata。旧块不回填未知结构。
- [ResearchSourceCatalog](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchSourceCatalog.java) 通过只读 REPEATABLE READ 事务读取原块及每侧最多一个相邻序号，最多返回三个块。同文档、版本、章节/工作表与资料类型边界成立才展开；AVAILABLE_EXCERPT 必须属于同一个 sourceParagraphId。缺少可靠分组、邻块序号重复或边界不符时只返回原块并说明原因；不以 chunkIndex 相邻推断章节，metadata 若声明了错误文档版本则拒绝读取。
- [SourceReader](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/SourceReader.java) 支持 CHUNK/NEIGHBORS 模式。原候选已变化时保留旧块并返回 CHANGED/BLOCK_ONLY，避免拼入新版本邻块。合法展开由 [EvidenceSnapshotFactory](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/EvidenceSnapshotFactory.java) 保存真实块顺序、正文和每块位置，得到新的 evidenceId；requestedEvidenceId 保留原候选身份。后续引用须使用返回的已读 evidenceId，不能认为未读取的原候选已经 read=true。
- 新增 [260917_03_research_neighbors.sql](../../resources/database/upgrades/v1.1.0/260917_03_research_neighbors.sql)，在 schema 增加 origin_evidence_id 自引用 FK 和运行内原候选的部分唯一索引，旧升级 SQL 保持字节不变。Java INSERT 检查原候选属于同一运行，并发展开冲突时返回首次保存的快照。重复读取复用它；来源正文/位置变化时返回旧快照与 CHANGED，来源停用/删除时仍拒绝。

### 实际验证与边界

- 后端 clean package 通过；18 个定向回归类共 123 个用例全部通过，0 失败/错误/跳过。本批新增 22 个普通回归用例，覆盖文档限制传递、越界拒绝、章节/工作表/原始段落边界、首次邻接快照复用及来源变更/停用。
- 实际 PostgreSQL 16 隔离库通过 schema/init、首批与第二批 SQL 各执行两次，以及新建/升级的列、默认值、约束和索引一致检查；历史草稿哨兵保留，测试库已清理。
- [ResearchEvidencePostgresIT](../../bootstrap/src/test/java/com/nageoffer/ai/ragent/research/service/ResearchEvidencePostgresIT.java) 7/7 通过：真实 Java INSERT/SELECT/UPDATE、归属及运行边界、并发首次展开唯一性、PGVector 文档过滤先于 Top1、MyBatis 搜索/邻接读取、来源变更快照复用和停用拒绝。拦截实际来源查询连接，验证 neighbors/loadAll 的只读 REPEATABLE READ；范围列表查询不被计入此断言。
- 初次测试源码编译暴露辅助方法参数和 checked exception 声明错误；ES 测试需以真实 SDK response 和非 final 的请求方法配合当前 Mockito mock-maker-subclass。首次 PostgreSQL 测试还发现事务探针统计了范围列表查询，调整为仅统计来源读取后复跑通过。失败日志及首次 PG JUnit 保留，不改写为一次通过。
- 原始日志、独立的普通/PG JUnit、源码 hash 和 checks.json 保存于本地忽略目录 `local-data/agentic-research/runs/20260917T075350_P2B/`。没有导入公开语料、启动完整应用或运行浏览器 E2E，没有调用真实 embedding/rerank/研究模型，没有效果或费用指标。Java 与 PG 联调使用合成正文/向量；不代表 SDK 原生工具调用或 Milvus/ES 服务已经联调。

本批起始提交：`4b8a328`。提交标题：`feat: scope research documents and read neighboring evidence`；从 Git log 按唯一标题查询 SHA，下一批补记。

## P2 剩余工作的直接接续顺序

1. 核对分支、Git 状态及本记录，继续主计划第 4.2/4.3/6/7 节；不要重新接入送检工具或直接跳到 P3。
2. 在 `eval/agentic-research/` 实现 QASPER/MuSiQue 转换、固定 ID/抽样种子、清单与 corpus/questions 分离，保留原文身份及 section_path/sourceParagraphId 等定位。答案、支持标签和 gold decomposition 不进入检索库或规划器。当前只有已下载的原始文件清单，本批未生成转换产物。
3. 实现真实摄取链的幂等批次、分批重试、进度与 usage 记录；先导入小样例，再处理真实批量。记录当前库/语料快照及错误；本批的 Java/PG 合成夹具联调不能代替真实语料入库验证。
4. 用导入后的实际文档复核 search/read 范围与邻接定位；旧 metadata 不足仍回退块级。继续保留独立的数据库联调和模型/效果证据。
5. 满足 P2 完成证据后再接入 P3 的 AgentScope 原生工具、首期研究模型、任务调度与调用记录器。本批的证据存储仍需结合 P3 的状态/epoch 写入条件处理取消和迟到结果；同用户唯一键不等于请求幂等执行已经实现。
