# 统一研究工作流实施记录

主计划：[实施计划](agentic-research-implementation-plan-2026-09-17.md)。模型与评测口径：[评测与预算](agentic-research-evaluation-and-budget-2026-09-17.md)。实际验证：[验证报告](agentic-research-validation-report.md)。

## 当前接续点

P0、P1 已完成；P2 进行中，已完成证据工具、Java/PG 联调、QASPER/MuSiQue 训练/开发转换、固定抽样和字段隔离/来源校验；P3—P8 未开始。第四批已接通真实来源 metadata、实际主键映射与幂等摄取，并完成小批真实 embedding/search/read；完整训练/开发批次正在执行。Java 服务尚未接入 SDK 工具、运行器或聊天入口，不能将 P2 链路联调写成新研究 Agent 已上线。

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

本批起始提交：`4b8a328`。阶段提交：`365d3e4`，标题 `feat: scope research documents and read neighboring evidence`。

## P2 第三批：数据转换与固定样本（2026-09-17，已完成；P2 仍进行中）

### 范围与实现

- 从 `feat/agentic-research@365d3e4` 的干净工作区继续；新增 [离线数据工具](../../eval/agentic-research/README.md)，不修改 Java、数据库、前端、模型配置或历史评测工具，不 push/合并。
- [prepare_dataset.py](../../eval/agentic-research/prepare_dataset.py) 分批读取 QASPER Parquet，逐行读取 MuSiQue JSONL，以临时 SQLite 去重/排序。输出不存在时才执行，成功后发布完整目录；失败仅清理自身临时目录。源文件前后核对大小与 SHA-256，保存数据版本/署名/许可、源码指纹及精确条数。
- corpus 只保留真实正文与来源；questions 保存 scorer-only gold，queries 使用白名单投影供未来运行器准备请求，不能把整行 questions 送入模型。Gold 变化不影响语料/queries/固定样本身份，额外标签字段即使重写文件指纹仍被校验拒绝。
- QASPER 保留 paper、section/paragraph 原始下标及名称、全部答案标注者、yes/no 和不可回答分歧。空段落跳过但不重排原下标；只有 caption/图片路径的表格不编造正文。Evidence 按空白归一后的完整段落匹配，未匹配原文保留 unresolved；train/validation 分别有 533/382 个未解析 evidence 标注，不把它们改配到近似段落。
- MuSiQue 标题+精确正文去重，同标题不同内容不拼接；来源均标 AVAILABLE_EXCERPT。Full 两行共用原问题 ID，转换身份加入其候选上下文，不依赖答案/answerable。每题 idx → 真实来源身份映射保存在评测文件。支持 pooled-context 和 distractor；本批完整转换使用 distractor 的原候选文档范围，pool 不称 fullwiki，且不能直接沿用原负例的不可回答标签报告成绩。
- 种子 `20260917`，全部问题按 SHA256(seed, question ID) 排序，生成 20 条 smoke 与 200 条 regression 及对应 gold-free queries；smoke 是 regression 前缀。Full regression 各 200 条分别对应 train 199 / dev 196 个原始问题 ID，清单保留这个口径。真实 test 不转换；隐藏标签的合成测试保留 gold=null。
- [verify_prepared.py](../../eval/agentic-research/verify_prepared.py) 校验文件及原始数据指纹、严格字段、来源 hash/身份、候选/gold 引用、queries 投影和固定样本。完整四个训练/开发 split 通过；[紧凑清单](../../eval/agentic-research/manifests/prepared-development-2026-09-17.json) 纳入 Git，真实语料和生成大文件留在 local-data。

### 实际验证与边界

- 16 个 Python 用例全部通过：HF 并行列表与原生 sequence、标注者分歧/yes-no、unresolved evidence、Full 成对行/去重、两种作用域、gold 修改不影响请求/语料、输入行序无关、输出保留、错误/损坏数据拒绝，以及合成隐藏标签/test 防误用。
- 实际 QASPER train：888 篇、47,770 个来源段落、2,593 条问题；validation：281 篇、13,547 个来源段落、1,005 条问题。
- 实际 MuSiQue Full train：95,125 个去重可用段落、39,876 条记录 / 19,938 个原问题 ID；dev：26,326 个去重段落、4,834 条记录 / 2,417 个原问题 ID。原始 Full 的正/负版本均保留，没有把 Ans 额外相加。
- 实际 QASPER validation 和 MuSiQue dev 再转换一次，7 个数据文件及 manifest 都逐字节一致。全量校验包含原始 SHA-256、全部记录/引用和固定样本，不能把 20/200 抽样误写成已运行模型的规模。
- 首轮 MuSiQue 因共用原问题 ID 失败，修正为上下文身份后完整复跑；首轮测试发现复用目录的同名 validate_dataset 模块遮蔽，改为 verify_prepared 且调整导入路径后 16/16 通过。成功/失败日志、原始 manifests、重跑结果和源码指纹位于本地忽略目录 `local-data/agentic-research/runs/20260917T082228_P2C/`。
- 本批没有导入公开语料、产生向量、调用 embedding/rerank/研究模型、启动应用或浏览器 E2E；模型调用为 0，无 token/cost/效果成绩。未修改 Java/SQL/前端，不重复运行上一批的后端或 PostgreSQL 检查；它们的 123/7 结果仍是第二批证据。

本批起始提交：`365d3e4`。提交标题：`feat: prepare reproducible research datasets`；从 Git log 按唯一标题查询 SHA，下一批补记。

## P2 第四批：来源摄取、幂等导入与真实联调（2026-09-17）

用户要求直接完成 P2，沿真实链路导入可用训练/开发语料，保持实现简单；本批从干净 `943c11a` 开始，没有接入 P3 SDK/运行器。

### 实现

- 新增 [ResearchCorpusImporter](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchCorpusImporter.java)，处理已解析的可信来源段落，复用 ParagraphChunker / ChunkAssembler / ChunkEmbeddingService 以及关系、PG 向量落点。QASPER 按原始字段/章节/段落下标还原论文；MuSiQue 每个摘录独立成文档，保持 AVAILABLE_EXCERPT。
- 来源 metadata 随每块进入关系/向量索引，保留 source_paragraph_id、raw source hash、原始标题与 paper/section/paragraph 位置。正文可能经过现有 TextSplitter 规范化/切分，原段落 hash 与实际块 hash 分开记录；不把原始段落数当块数。
- `section_index` / `source_field` 加入 SourceReader 来源边界，重复章节名不跨原章节展开；无章节名但有可靠原下标的正文仍可在同章节展开。
- 新增显式增量 [260917_04_research_corpus.sql](../../resources/database/upgrades/v1.1.0/260917_04_research_corpus.sql)，来源文档与真实主键映射在索引短事务中一并提交。同来源/配置重试保留 docId/chunkId，不再调用 embedding；变更内容/metadata/分块预算/模型要求新语料库。失败批次回滚，网络调用不持有 DB 事务。没有增加通用工作流、模型回退或新权限体系。
- 新增 [导入命令](../../eval/agentic-research/import_corpus.py) 与独立 Java 命令入口。SQLite 分组排序，分批续入、重试与进度记录；只把 corpus 送到摄取层，gold-free queries 仅供验证。固定模型为现有 SiliconFlow Qwen/Qwen3-Embedding-8B / 1536 维；单请求最多 32 条，QASPER 默认 8 篇/批、MuSiQue 256 条/批，最多四个 split 并行，每个 split 最多 8 个独立批次在途，最多 16 个 embedding 请求在途。未启动 Web/MQ 或生成模型。
- 逐 HTTP 请求保存开始/完成与原始供应商 usage，按 call_id 合并计量。没有回包的请求保持 unknown，不填免费 0。导入后审计完整文档/段落/块/向量映射与标注字段隔离，再用三条实际 query 做 scoped search/read，检查越界和缺失证据错误。

### 当前验证

- Python 19 个转换/导入准备测试通过；普通 Java 回归 123 个和新增 usage 测试 3 个通过；实际 PostgreSQL 11 个用例通过，涵盖完整 Java 摄取写入、重复身份/配置、同名章节边界、MuSiQue 同标题独立摘录、供应商失败及索引失败后的回滚/再入库。
- 数据库脚本证明 fresh schema 与重复执行 02/03/04 升级的列、约束和索引一致，历史草稿保留；后端 package 通过。临时测试库已清理。
- 两种格式已用真实 SiliconFlow 向量小批入库；QASPER 的 17 篇/801 块已完成实际搜索/邻读，重复续入 801 块没有重新向量化；MuSiQue 的 392 条摘录已入库，真实阅读返回 AVAILABLE_EXCERPT。
- 小批首次网络超时、独立命令漏注册已有时间填充器导致回滚，以及停止旧顺序命令的记录保留。时间填充接线已修正，最终命令在新库/升级库与小批实际入库上验证。
- 完整 QASPER train/validation 和 MuSiQue Full train/dev 批量正在执行，结果保存在独立 `research_corpus_v1`，不改现有业务库。小批原始记录在 `local-data/agentic-research/runs/20260917T084500_P2D_smoke/`，完整批次在 `local-data/agentic-research/runs/20260917T085000_P2D_full/`。

本批只证明真实数据摄取及检索阅读链路，不运行答案生成、A/B/C 或 EM/F1；真实 test 仍不转换/入库。Milvus/ES 服务、SDK 原生工具、研究取消/epoch 和页面 E2E 尚未验证，属于后续阶段。
