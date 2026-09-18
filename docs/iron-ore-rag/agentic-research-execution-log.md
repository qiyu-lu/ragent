# 统一研究工作流实施记录

主计划：[实施计划](agentic-research-implementation-plan-2026-09-17.md)。模型与评测口径：[评测与预算](agentic-research-evaluation-and-budget-2026-09-17.md)。实际验证：[验证报告](agentic-research-validation-report.md)。

## 当前接续点

2026-09-18 已完成计划 8.6 的 R1：工具参数契约、原生结束恢复及默认取消金额估算门槛。相关后端 114 项、Python 29 项检查通过；12 个预先固定原题 × A/B/C 的真实诊断复测为 35 COMPLETED / 1 PARTIAL / 0 FAILED，历史同题为 21/4/11。4 个运行真实触发结束修复，使用 6 次调用；语义负例与未读引用错误仍保留，不能宣称整体质量或多 Agent 收益。见文末 R1 记录和[R1 清单](../../eval/agentic-research/manifests/research-r1-reliability-2026-09-18.json)。下一阶段为 R2：检索可恢复性和连接恢复补强；R2—R5 尚未实施。以下 P0—P8 数字保留为此前验收快照。

P0—P8 已完成实现与本轮约定验证（2026-09-18）。P7 提交 `3381fa9`；P8 通过唯一标题 `docs: finalize research workflow and implementation handoff` 定位。固定 regression 的 QASPER 200 问题与 MuSiQue Full dev 200 行，A/B/C 共 1200 个任务全部记录（932 COMPLETED / 93 PARTIAL / 175 FAILED），没有未执行任务。QASPER Answer F1：A 0.3365/B 0.2338/C 0.2442；MuSiQue Answer F1：A 0.3325/B 0.3269/C 0.3918；MuSiQue 答案/支持只评分 105 行 answerable，非 200 行答案分母。C 实际 2 个运行委派、创建 4 个 worker。结果和费用见[固定对照报告](agentic-research-evaluation-report.md)。

当前后端 24 类 173/173、Python 28/28、新库/重复增量保留历史、前端 build、node 类型与 9 项受控浏览器检查通过；app 仍有与 P6 相同的 24 项诊断。24 个应用由 Codex 核对 85 条引用快照，两例 v4 复测的错误推断和失败保留；不是独立人工盲评。最终生成 v4 隔离 evidence 输入中的托管元数据，服务端引用快照保持，输入检查不等于语义可靠。

P8 已交付三个入口、当前流程、启动/手工迁移/失败边界、最终验证和具体四请求演示：2 COMPLETED/2 PARTIAL，10 条引用由 Codex 原文核对；未完成比较、一次检索缺失后续命名线索和未生成 null 参数条目均保留。full 5839 问题/17517 任务仅 dry-run 准备；已有业务库升级、生产登录和真实文档预览下载未执行。新会话从[交接](agentic-research-handoff.md)和[P8 清单](../../eval/agentic-research/manifests/research-p8-handoff-2026-09-18.json)恢复；先处理实际失败，暂不宣称整体或多 Agent 收益。

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
- 新增 TaskTemplateGenerator（P5 已退役，历史源码见 P1 提交），将模型生成、一次修复与原有 TaskTemplateValidator 分离；它不依赖数据库、审批或执行对象。旧草稿服务只管理来源/归属、持久化和过渡期人工确认。
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

用户要求直接完成 P2，沿真实链路导入可用训练/开发语料，保持实现简单；本批从干净 `943c11a` 开始，实现提交为 `4b06f21`。完整批次与最终审计已通过，P2 已完成；没有接入 P3 SDK/运行器。

### 实现

- 新增 [ResearchCorpusImporter](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchCorpusImporter.java)，处理已解析的可信来源段落，复用 ParagraphChunker / ChunkAssembler / ChunkEmbeddingService 以及关系、PG 向量落点。QASPER 按原始字段/章节/段落下标还原论文；MuSiQue 每个摘录独立成文档，保持 AVAILABLE_EXCERPT。
- 来源 metadata 随每块进入关系/向量索引，保留 source_paragraph_id、raw source hash、原始标题与 paper/section/paragraph 位置。正文可能经过现有 TextSplitter 规范化/切分，原段落 hash 与实际块 hash 分开记录；不把原始段落数当块数。
- `section_index` / `source_field` 加入 SourceReader 来源边界，重复章节名不跨原章节展开；无章节名但有可靠原下标的正文仍可在同章节展开。
- 新增显式增量 [260917_04_research_corpus.sql](../../resources/database/upgrades/v1.1.0/260917_04_research_corpus.sql)，来源文档与真实主键映射在索引短事务中一并提交。同来源/配置重试保留 docId/chunkId，不再调用 embedding；变更内容/metadata/分块预算/模型要求新语料库。失败批次回滚，网络调用不持有 DB 事务。没有增加通用工作流、模型回退或新权限体系。
- 新增 [导入命令](../../eval/agentic-research/import_corpus.py) 与独立 Java 命令入口。SQLite 分组排序，分批续入、重试与进度记录；只把 corpus 送到摄取层，gold-free queries 仅供验证。固定模型为现有 SiliconFlow Qwen/Qwen3-Embedding-8B / 1536 维；单请求最多 32 条，QASPER 默认 8 篇/批、MuSiQue 256 条/批，最多四个 split 并行，每个 split 最多 8 个独立批次在途，最多 16 个 embedding 请求在途。未启动 Web/MQ 或生成模型。
- 逐 HTTP 请求保存开始/完成与原始供应商 usage，按 call_id 合并计量。没有回包的请求保持 unknown，不填免费 0。导入后审计完整文档/段落/块/向量映射与标注字段隔离，再用三条实际 query 做 scoped search/read，检查越界和缺失证据错误。

### 当前验证

- Python 19 个转换/导入准备测试通过；普通 Java 回归 123 个和新增 usage 测试 3 个通过；实际 PostgreSQL 11 个用例通过，涵盖完整 Java 摄取写入、重复身份/配置、同名章节边界、MuSiQue 同标题独立摘录、供应商失败及索引失败后的回滚/再入库，以及非默认分块预算的标准 IngestionSpec 编解码回查。
- 数据库脚本证明 fresh schema 与重复执行 02/03/04 升级的列、约束和索引一致，历史草稿保留；后端 package 通过。临时测试库已清理。
- 两种格式已用真实 SiliconFlow 向量小批入库；QASPER 的 17 篇/801 块已完成实际搜索/邻读，重复续入 801 块没有重新向量化；MuSiQue 的 392 条摘录已入库，真实阅读返回 AVAILABLE_EXCERPT。
- 小批首次网络超时、独立命令漏注册已有时间填充器导致回滚，以及停止旧顺序命令的记录保留。时间填充接线已修正，最终命令在新库/升级库与小批实际入库上验证。
- 完整 QASPER train/validation 和 MuSiQue Full train/dev 导入完成，结果保存在独立 `research_corpus_v1`，不改现有业务库。每个 split 的三条 gold-free query 均通过真实 scoped search/read，越界及缺失证据检查通过。

### 完整批次验收

| split | 文档 | 来源段落 | 实际块 / 向量 |
| --- | ---: | ---: | ---: |
| QASPER train | 888 | 47,770 | 47,877 |
| QASPER validation | 281 | 13,547 | 13,568 |
| MuSiQue Full train | 95,125 | 95,125 | 95,125 |
| MuSiQue Full dev | 26,326 | 26,326 | 26,326 |
| 合计 | 122,620 | 182,768 | 182,896 |

最终数据库审计核对可读文档与来源映射、逐块正文 SHA-256、来源 ID/hash/标题/version/extent、对应向量和标注隔离，异常数均为 0；完整 mapping 逐行数量及去重文档/来源/块身份与库存一致。QASPER 阅读保留 CHUNK/可靠邻块，MuSiQue 保留 AVAILABLE_EXCERPT，不补造全文。

完整批次执行过程中补正了摄取配置的标准编码：运行中的旧类已加载，待所有 worker 退出后，将研究库中 123,029 个 corpus 文档（含小批）的早期预算对象转为已有 IngestionSpec v2/fast 格式，正文、块、向量、来源身份均未改变；配置格式审计异常为 0。当前代码通过已有 IngestionSpecCodec 写入，非默认预算在实际 PG 用例中验证。执行时源码与最终源码的这一差异及处理留在清单中。

完整批次按 call_id 合并后记录 5,884 个 embedding 请求，供应商已知 total_tokens 为 19,958,704；4 个返回失败、24 个旧命令停止前请求缺少结束帧，合计 28 个 usage 未知。未知请求仍保留原日志，不填 0；小批和连通性探测另记，金额未经账单核对。完成语料导入不代表历史全部 HTTP 请求都有可观测结算结果。

小批原始记录在 `local-data/agentic-research/runs/20260917T084500_P2D_smoke/`，完整批次在 `local-data/agentic-research/runs/20260917T085000_P2D_full/`；其中 validation 保留构建、回归、SQL、最终审计和标准配置转换日志。[导入清单](../../eval/agentic-research/manifests/imported-development-2026-09-17.json)纳入 Git，包含实际 KB/文档/来源/块/向量数量、源码与产物 SHA-256、调用及验证摘要；原始语料和日志留在忽略目录。

最终静态检查通过：58 个本地链接及锚点、Markdown 围栏、whitespace 和全部导入清单产物 hash；15 个历史增量 SQL 与起始提交逐字节一致。本批检查及日志指纹汇总保存在完整批次的 `validation/checks.json`。

本批只证明真实数据摄取及检索阅读链路，不运行答案生成、A/B/C 或 EM/F1；真实 test 仍不转换/入库。Milvus/ES 服务、SDK 原生工具、研究取消/epoch 和页面 E2E 尚未验证，属于后续阶段。

## P3：原生工具与单研究任务运行（2026-09-17，已完成）

起始提交 `0c2ae30`，仍在 `feat/agentic-research`。本轮按用户允许的范围只实现 P3；原生协议、运行器、持久化竞争和真实联调的工作量已较大，P4 的委派与并发研究者留到下一阶段。

### 实现范围

- bootstrap 实际加入 `agentscope-core` 和 `agentscope-extensions-model-openai` 2.0.1。API 核对以正式 tag `v2.0.1`、commit `51d10ecfddadc45fb2173ff161e40e7bcf48d0be` 的源码及 Maven JAR 为准，使用 v2 的 ReActAgent、Middleware、Toolkit 和 RuntimeContext；未引入 Harness、通用 MCP 或文本 JSON 决策协议。
- [ResearchModelFactory](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchModelFactory.java) 从现有 AI 注册表读取提供方、端点和凭证，新增独立 `research-flash` 项指向 `qwen3.7-flash-2026-07-15`，显式要求 `supports-tool-calling`。普通问答各档位候选未调整；研究链没有静默回退模型。模型请求关闭 thinking，工具存在时使用 `tool_choice=required`，最后两次研究调用限定为原生 `finish_research`，可在同一额度内修复一次引用错误。
- [ResearchAgentFactory](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchAgentFactory.java) 每次领取创建独立 Agent/会话，只注册 search/read/ask/finish 四个工具，复用 P2 知识服务。模型可以观察后补查、等待用户输入或结束。finish 的每条发现必须绑定本次实际 read_source 提供的 ID；非法参数、越界/虚构来源和未读引用以原生 tool result 回传，修复调用照常计入额度。
- [ResearchRunStore](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchRunStore.java) 使用 PostgreSQL 唯一请求键与最初请求指纹处理幂等；重复 ID 配不同请求明确拒绝。领取以 owner + 行锁分配 epoch/随机租约，活动租约不重复执行；事件序号按 run 原子 UPDATE 分配。写事件、保存结束与取消各自短事务，模型/工具不在事务中。运行视图不暴露 leaseToken。
- [ResearchRunService](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchRunService.java) 用有界任务池自行推进，不需要 advance。取消先写终态、撤销租约，再传播到 SDK interrupt 和 Reactor/HTTP 订阅；旧 epoch、取消后返回及完成/取消竞争不会覆盖终态。独立记录 CANCEL_REQUESTED 与 LOCAL_EXECUTION_ENDED；远端计算是否停止保持 unknown。排队拒绝落库为 FAILED；单 JVM 重启将失去执行者的 QUEUED/RUNNING 标记 INTERRUPTED，保留证据/调用记录，重新发起需新 clientRequestId。
- ResearchBudget 同步分配模型/工具额度，并通过共享 semaphore 限制研究模型并发；默认 16/24 次、300 秒累计活动时长、模型 60 秒/工具 30 秒，预留 2 次最终生成额度。人工等待不计活动时长，恢复保留之前的消耗。上下文按 token 估算裁剪最早完整 assistant/tool 往返，保留系统指令、目标和最新观察；供应商实际 usage 与估算分开，未返回 usage 不填 0。
- [ResearchRunController](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/controller/ResearchRunController.java) 提供创建、查询、事件分页、带 revision 的输入及幂等取消。校验当前会话归属、可用知识库与文档范围；知识库仍为现有全局共享规则。P3 输入只补充条件，不修改服务器保存的知识库/文档范围。明确记录 latestUserInput 的问题/回答与 user_input 来源，恢复时不受原始目标中旧的“未提供”措辞影响。
- 当前产物是 `state.researchResult` 的结构化研究发现、缺口和冲突以及 readEvidenceIds，`artifact` 为空；COMPLETED 在此阶段表示研究摘要闭环。P5 再接统一 REPORT/PLAN 生成、结构检查和展示用引用映射；P6 再接 SSE/聊天页面。事件 GET 当前为 after/limit 分页 JSON，查询与刷新不调度执行。
- 新增 [P3 验证脚本](../../scripts/validate-agentic-research-p3.sh)和[真实 smoke 工具](../../eval/agentic-research/smoke_research.py)。真实命令仅查询已导入公开语料，运行/证据/事件写入随机研究库并清理，保存实际模型/模板、源码指纹、工具参数和结果、usage 与失败样例，不保留隐藏推理。SDK 模型请求流取消传播到 JDK HTTP future/socket；这不等于供应商已确认停止计费。

### 验证与问题修复

最新后端回归 149/149（0 失败、错误、跳过），其中新增 P3 26 个：PostgreSQL 9 个、真实 SDK/本地 HTTP 11 个、预算 3 个、接口 3 个。PostgreSQL 覆盖重复请求并发、活动/过期租约、旧 epoch、事件并发编号、revision/owner、真实完成/取消竞争及重启。HTTP 桩覆盖匹配的 tool_call_id、依赖补查、错误回传、未读引用修复、等待输入、拒绝文本仿冒、取消、超时后配额释放、强制原生结束和额度保留。它们证明程序机制，真实模型结果单列。

首次接入修正了 SDK `maxRetries` 实际含义为含首次调用的尝试次数（应为 1），以及原生工具结果消息的 TOOL role。Maven 首次因缓存目录只读无法下载，经沙箱授权解析后成功；一次并行构建造成测试发现时目标类缺失，随后改为顺序构建/测试并 clean 重跑。失败日志保留，不作为验证通过的依据。

真实联调分批留档，未覆盖或改写旧批次：

- A：查找、多跳答案和资料不足正常结束，取消生效；多跳从初次候选直接读到两层信息，没有依赖补查；输入后重复追问。发现候选未读引用时，程序拒绝、模型补读后成功。这批不作为追问恢复和依赖补查通过。
- B：补查确实依赖前次阅读，已记录 user_input 回复，但 auto 协议仍会直接输出文本，四个普通探测落为 FAILED；取消保留 CANCELLED。未将文本包装为原生工具成功。
- C：加入 required 工具选择后，多跳、资料不足、输入恢复和取消通过；查找仍提前结束失败。继续调整额度提示的注入位置。
- D：额度提示合并到开头的系统指令后，查找、依赖补查、资料不足和取消通过；输入恢复读过资料后继续搜索，耗尽 14 次研究额度。当时联调命令将异常统一记为 FAILED，未保存部分状态；这份原始失败保持不变。
- E：只复测受影响的等待/恢复路径，最后两次研究调用限定 finish_research，同时将联调命令的预算/超时退出对齐服务实现：有已读证据时落 PARTIAL 并保留来源及缺口。复测经过 WAITING_INPUT、INPUT_RECEIVED 与原生 finish，最终 COMPLETED，共 14 次模型/工具调用，3 个已读证据、8 项缺口，2 次最终生成额度仍保留。

当前提示词为 `research-main-v2`。模型记录响应是否包含原生调用、是否含文本及 finishReason，不保存推理内容。五批合计 136 个研究模型请求，供应商已知输入 1,058,680 / 输出 25,248 token；四次取消请求的 usage 保持 unknown。这是开发调试累计量，不是每题平均成本或结算账单。D 的四条正常/取消路径与 E 的恢复路径分别留证，未在最终源码下重新运行整批五题，不能合并声称最终全量回归。原始结果在 `local-data/agentic-research/runs/*_P3_real_A` 至 `*_P3_real_E`，构建/程序回归及失败日志在 `local-data/agentic-research/runs/20260917T112600_P3_validation/`；[P3 清单](../../eval/agentic-research/manifests/research-p3-smoke-2026-09-17.json)记录逐批状态、源码/产物 hash 和验证边界。

本批不新增表或字段，沿用 P2 schema；未对既有业务库执行升级。真实 smoke 的检索通道为现有 PGVector + query embedding，不启用 rerank；没有 A/B/C、EM/F1 或语义支持评分，也没有全套服务/浏览器 E2E。阶段提交通过标题 `feat: implement bounded research runs with native tool calls` 在 Git log 定位，不自动 push 或合并。

最终静态验收通过：69 个入口本地链接/锚点、Markdown 围栏、全部 P3 文件 whitespace、44 份源码/配置/模板及全部原始结果/检查记录指纹一致；E 批研究源码与当前交付内容一致。P3 起始提交的 16 份升级 SQL 不变，其中最初基线 `a7ef618` 的 12 份历史文件也不变。shell/Python 语法与 5/1 请求的全批/单路径 dry-run 通过，未重复调用付费模型。

下一步从 P4 接续 conduct_research：在现有 run/epoch、证据身份、预算和模型配额上增加独立 worker 上下文与专用线程池，最多 2 个并行研究者、总数 4，仅一层委派；不得分别复制全局额度，也不将子 Agent 完整历史拼回主 Agent。


## P4：主 Agent 委派与独立 worker

起始提交 `20b1133`（P3），分支仍为 `feat/agentic-research`，开始时工作区干净。用户明确要求执行 P4，并说明已充值、授权付费联调；本批完成 P4，不继续实现 P5。阶段提交标题为 `feat: orchestrate isolated research workers`，SHA 可通过 Git log 定位，不自动 push/合并。

### 本批实现

- 主 Agent 新增原生 `conduct_research(tasks)`，1—4 个子任务包含目标、1—8 个维度、预期返回和可选文档缩小范围。服务器验证存在/启用/父范围后才原子分配累计 worker 数；相同子目标重复委派返回工具错误，补查使用具体新目标。简单查询和串行依赖继续直接使用检索/阅读工具。
- worker 使用新的 ReActAgent、Toolkit、RuntimeContext、已读集合和独立取消信号，只注册 search/read/finish。不会接收父或兄弟的完整历史，不能追问用户或再次委派。自己的检索候选才能阅读；缺少用户条件以 gaps 返回。默认专用有界池 2 个线程/32 队列，父任务池独立，防止互相等待；每运行累计最多 4 个 worker，人工输入恢复不重置。
- 主与子共用同一 ResearchBudget、活动时长、工具额度和全局模型信号量。默认仍是 16 次模型/24 次工具/300 秒，预留 2 次后续生成；worker 每个最多 6 次模型请求、180 秒含排队，另保留一次主 Agent 整合。输入按估算上限裁剪整组往返，主的已验证子摘要保留在系统上下文，不增加总结调用。
- 主只接收 `SubtaskResult`。worker 结果每类最多 8 条、总文字最多 8000 字符，保留数字、单位、适用条件和读过的证据 ID。主可引用经验证 findings 中的 ID；主自行读取和 worker 引用分别记录为 `readEvidenceIds` / `acceptedWorkerEvidenceIds`，不把接收摘要伪装成主已读原文。失败 gaps 自动合并到主结果并标 PARTIAL；主提前退出仍保留其他 worker 的已验证 findings。
- `state.subtasks` 保存任务、状态、已读 ID 和压缩结果，完成即短事务提交；事件 taskId 区分主/子，usage 增加 role/taskId/workersCreated。快照与事件写入串行化，防止并行用旧计数覆盖新计数；序号仍由 run 行原子分配。父领取失效、取消、终态重复回调不可写回。人工回复恢复已提交 findings 与读证明，不重放工具历史；重启/父提前结束关闭仍在执行的子状态并保留快照。
- 父取消信号连接所有子 SDK/HTTP 订阅，worker 超时/失败只结束自身。迟到 callable 返回不经过结果提交回调；关闭已登记的中断句柄，避免取消绑定遗留。仍分别记录取消请求和本地结束，远端是否停止计算保持 unknown。

入口代码：[ResearchWorkerCoordinator](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchWorkerCoordinator.java)、[ResearchSession](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchSession.java)、[ResearchAgentFactory](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchAgentFactory.java)、[ResearchRunStore](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchRunStore.java)。主提示词为 `research-main-v3`，当前 worker 为 `research-worker-v2`；v1 保留用于 A 批回放。SDK 仍锁定 AgentScope Java 2.0.1，无依赖升级、SQL/前端变更或新权限体系。

### 实际验证

```bash
./mvnw -o -pl bootstrap -am -DskipTests clean package
P4_TESTS=ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,MilvusVectorRetrieverServiceTest,EsKeywordRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,TaskTemplateGeneratorTest,IronOreTaskTemplateServiceTest,TaskTemplateValidatorTest,StreamTaskManagerCancelTraceTest bash scripts/validate-agentic-research-p4.sh
python3 eval/agentic-research/smoke_research.py --phase p4 --run-dir local-data/agentic-research/runs/<new-id>
python3 eval/agentic-research/smoke_research.py --phase p4 --case comparison-workers --case plan-workers --run-dir local-data/agentic-research/runs/<new-id> --execute
python3 eval/agentic-research/smoke_research.py --phase p4 --case follow-up-workers --run-dir local-data/agentic-research/runs/<new-id> --execute
```

后端 clean package 通过。最终 24 个类、165 个用例，0 失败/错误/跳过；相对 P3 新增 16 个。真实 SDK + 本地 HTTP 桩证明 2 个 worker 重叠执行、各自阅读和补查、schema 无 ask/conduct、主只收到压缩结果、单 worker HTTP 失败保留另一名 findings、两个在途 HTTP 订阅随父取消而结束。专用池/预算测试覆盖 4 个任务分两波、跨恢复累计数、全局原子额度与主/生成预留、文档越界/未读引用拒绝、超时后忽略中断的迟到返回。PostgreSQL 新增子状态/重复完成/取消、单调 usage/连续序号、父提前结束、人工回复后新 epoch 复用 findings 与累计额度验证，保留 P3 的竞争/归属回归。

首次扩展测试缺少 import，修正后发现不可变 List 的 contains(null) 会抛 NPE，改为流式空项校验；失败与清理日志保留。v2 增加“一次新检索后先读一条，再允许补查”的原生 ToolChoice 和实时已读 ID 提醒，已用协议测试验证；重复已读候选不会强制再读，仍未读的其他候选不会被冒充为已读。

### 付费开发联调（不是架构 A/B/C 或质量评分）

全部使用 `qwen3.7-flash-2026-07-15`、thinking=false；真实 PGVector + SiliconFlow query embedding，语料只读，运行写随机隔离库后删除。只读 queries，不用 gold 拆任务/选支持文档。比较样例使用两份不同 QASPER 文档，docId 从来源映射替换；PLAN 与补查样例使用同一论文的不同研究维度。

| 批次 / 本地目录 | 真实结果 | 模型请求记录 | 已知输入 / 输出 token | 模型 usage unknown |
| --- | --- | --- | --- | --- |
| A：`20260917T114900_P4_real_A` | 比较 PARTIAL 14；PLAN PARTIAL 14；串行多跳 COMPLETED 11 且阅读后新查询；并行取消 CANCELLED 3，两个 worker 在途请求本地取消 | 42 | 297590 / 11627 | 2 |
| B：`20260917T115300_P4_real_B` | v2 受影响路径复测：比较 COMPLETED 13、4 findings；PLAN COMPLETED 14、1 finding。四个 worker 均 COMPLETED、各读 3 个 ID；该批没有额外补查 | 27 | 121346 / 7662 | 0 |
| C：`20260917T115900_P4_real_C` | 当前 v2 补查压力：两个 worker 均读 2 个 ID 后补查；一名因局部模型额度退出 PARTIAL，另一名 COMPLETED，主 PARTIAL、保留其 2 条 findings 和失败 gaps | 14 | 77735 / 3646 | 0 |

A 的 worker 连续检索后尝试引用未读候选，有限修复仍未闭环；两次 embedding 请求超时也保留，不归因为余额告警。v2 增加阅读节奏、schema 的 1—8 limit 描述、当前可引用 ID、用户条件无需出现在文档中的提醒，并让预算失败 gaps 明确实际已读数量。未放宽引用身份检查或追加全局额度。A 的精确 Java/配置/模板源码已留在该目录 source-snapshot；B/C 的研究源码、配置和当前模板与交付内容一致。

本批共 **83 个模型请求记录，已知输入 496671 / 输出 22935 token，2 个取消请求 usage unknown**。query embedding 按 call_id 去重后 **25 个请求**，23 个已知 total_tokens 合计 196，2 个超时 usage unknown。金额、余额、缓存折扣和未知请求是否计费未核对账单，不能把它们当作正式每题成本或效果提升。原始结果位于 `local-data/agentic-research/runs/`，精简指纹见 [P4 清单](../../eval/agentic-research/manifests/research-p4-smoke-2026-09-17.json)。

仍采用单 JVM，不恢复中间 token，不执行最终生成/页面/SSE，也未启动完整 Web 服务/浏览器 E2E 或运行 A/B/C、EM/F1、语义支持评分。COMPLETED 只表示研究摘要通过引用身份检查，B 的 PLAN 不代表完整计划 JSON 或条件已齐。下一步 P5 从 brief + 主/子 findings + 已读快照生成统一报告/计划，使用预留额度，校验结构和最终引用映射；P4 成功子结果和失败 gaps 都应进入生成输入。

最终静态检查通过：5 份入口 Markdown 的 81 个本地链接/锚点、围栏、whitespace、33 个 P4 变更文件范围、51 份源码/配置/模板与全部原始/归档结果指纹；B/C 当前运行源码一致，P3 原始结果与 `20b1133` 冻结源码未改写。P3 基线的 16 份升级 SQL 保持字节一致，最初基线的 12 份也不变。全批 4 请求和补查单路径 1 请求 dry-run、shell/Python 语法与最终当前源码 clean package 通过。静态程序与检查记录位于 P4 validation 归档，未为这些检查追加付费调用。


## P5：统一报告与计划草稿输出（2026-09-17）

起始提交 `af28188`（P4），分支 `feat/agentic-research`，开始时工作区干净。阶段提交标题 `feat: generate reports and plan drafts from shared research`；不自动 push/合并。

实现共用 [ResearchArtifactGenerator](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchArtifactGenerator.java)、[ResearchArtifact](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/model/ResearchArtifact.java) 和 [PlanDraftValidator](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/PlanDraftValidator.java)。输入包括 brief、主 findings、全部已验证 worker 结果（含失败 gaps）和本次实际已读快照。输出类型在 brief 内，不新建计划运行器；最终提示词为 `research-artifact-v1`。结构/JSON/引用错误最多修复一次，均使用 P3/P4 预留额度；主、worker、生成共享模型并发信号量、16 次调用和累计活动时限。生成输入按 token 估算裁剪最长证据，保留身份、单位和真实位置字段，来源标注截断；修复对象无法完整容纳时明确失败，不静默丢证据。

REPORT 为 sections + evidenceIds，PLAN 为前置条件、顺序步骤、材料/设备、参数、注意事项、待确认项。已填参数独立绑定证据，未知参数留空并自动加待确认。用户限制由程序从 brief 保存为 `user_input`，不能由模型编造文档来源。最终引用从 1 连续编号，正文、来源及落库映射一致；程序验证本 run 的实际读证明、范围和引用身份，**不等同于语义支持通过**。主/子失败 gaps 和冲突由程序保留。

[ResearchCompletionService](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchCompletionService.java) 供在线运行与 P5 smoke 共用：校验后的 artifact 与 ARTIFACT/终态事件在同一短事务、相同 epoch/lease 保护下提交。COMPLETED/PARTIAL 在新在线路径表示已经形成合法产物；生成失败为 FAILED，保留研究摘要及失败原因，不发布非法产物。取消信号连接生成阶段 SDK/HTTP；取消或旧 epoch 的迟到完成不能写入 artifact。供应商计算是否停止仍 unknown。

增加按会话读取运行和带新 clientRequestId 的 regenerate；再生成复用同一研究流程和范围。创建研究可不传 conversationId，服务端在首次幂等创建的短事务同时建会话；重复请求不会多建会话或再次调度模型。已有会话仍校验 owner。旧单回答/单文档草稿生成、人工审批、重复 DAO/类型/提示词和前端入口已删除；新建 schema 移除 `t_iron_ore_task_template`，既有表与 16 份历史升级 SQL 不执行删除、不改写。

验证：后端 package 通过；22 个定向类 **160/160**，0 失败/错误/跳过。相对 P4 删除 13 个旧草稿测试，新增 6 个 SDK/本地 HTTP 生成测试及 2 个 PostgreSQL 用例。覆盖双文档引用、用户条件/未知参数、14→16 次生成/修复预留、两次非法 JSON 后失败、未读快照拒绝、生成取消与配额释放、产物/事件原子提交、重复/取消后迟到回调及新会话幂等。保留普通问答、检索、摄取、版本比较和 P3/P4 取消/epoch 回归。前端 build 通过。模型 HTTP 为本地可控响应，不是供应商效果；PostgreSQL 随机库已清理。首次小集 82/82，新增数据库用例后最终扩大到 160；旧批次日志保留。

[验证脚本](../../scripts/validate-agentic-research-p5.sh) 使用原有隔离数据库 guard。smoke 新增 `--phase p5` 并与在线结束处理一致；两条 REPORT/PLAN dry-run 通过，无数据库/模型调用。尝试 `--execute` 被自动审批拒绝：理由为当前用户实现授权没有明确覆盖向外部供应商发送具体语料内容并产生费用。没有执行付费请求，也没有绕过拒绝；P5 真实供应商/多文档产物联调留待明确授权。P4 已有真实研究摘要不能冒充这次最终产物验证，未跑质量评分或 A/B/C。

程序日志位于 `local-data/agentic-research/runs/20260917T122800_P5_validation/`，dry-run 位于 `20260917T122500_P5_dryrun/`；[P5 程序验证清单](../../eval/agentic-research/manifests/research-p5-validation-2026-09-17.json)保存源码/模板指纹和检查边界。下一步 P6：在现有归属、run 和事件序号上实现只读 SSE 订阅及聊天 REPORT/PLAN 展示，刷新从数据库取回。


## P6：聊天入口、持久进度与来源展示（2026-09-17）

起始提交 `3507d9e`（P5），分支 `feat/agentic-research`，开始时工作区干净。本阶段提交标题 `feat: integrate research and plan modes into chat`，SHA 可通过 Git log 定位；不自动 push/合并。P5/P6 的代码实现与程序验证已完成；本阶段交付时真实供应商产物联调待授权，后续用户授权后的开发复测见下方补充记录。

### 实现与交接入口

- [researchService](../../frontend/src/services/researchService.ts)、[researchStore](../../frontend/src/stores/researchStore.ts) 接入创建、会话列表、查询、补充条件、取消、重新生成和来源快照。欢迎页与 ChatInput 共用分流：普通问答继续走 `/rag/v3/chat`，REPORT/PLAN 走同一研究服务；deepThinking 仍只属于普通问答。知识库范围由用户选择，服务端再次校验。创建失败重试保留 clientRequestId，不重复提交；新研究会话采用 P5 原子创建。
- [ResearchProgress](../../frontend/src/components/chat/ResearchProgress.tsx) 展示当前阶段、主/子任务进度、已查来源和最终产物，不展示隐藏推理或系统提示。WAITING_INPUT 用当前 revision 回复；主动取消调用独立 cancel；终态可重新生成。ChatPage 按任务 create_time 关联原会话，刷新 GET 取回报告、草稿及等待问题，不重新启动模型。
- [ResearchEventStreamService](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchEventStreamService.java) 在请求线程鉴权，异步轮询固定 owner。订阅发送 progress / artifact / snapshot，after 与 Last-Event-ID 取最大游标；分页 JSON 兼容保留。终态再读尾部避免漏掉原子 ARTIFACT。订阅上限 100、超时 120 秒、750 ms 轮询；订阅关闭、重连和超时只清理连接，不调度或取消运行。前端断线先 GET 快照，再从最后 sequence 订阅，事件去重；终态只读取产物。
- read_source 新增可展示的 SOURCE_READ；GET `/{runId}/sources` 按 owner 返回实际已读快照，候选不能作为已读来源。刷新未形成产物的任务也能恢复来源；最终编号以 artifact.citations 为准。复用 [SourcesPanel](../../frontend/src/components/chat/SourcesPanel.tsx)、来源点击和原有文档预览入口，显示真实章节/页/表格位置、版本、片段和截断信息。
- [PlanDraftCard](../../frontend/src/components/chat/PlanDraftCard.tsx) 显示目标、用户提供的约束、前置条件、步骤/参数、设备材料、注意事项、待确认项、缺口与冲突。文档要求和已填参数各自引用；未知参数为待确认。REPORT 使用现有 MarkdownRenderer。研究消息独立展示，不复用普通问答的反馈/推荐问题状态。
- 浏览器发现 REPORT 的纯 `[1][2]` 标记不满足现有 MarkdownRenderer 的引用识别，生成端已改为程序生成 `[1](#cite-1)`；前端计划完成后滚动没有跟随研究状态，也已接入现有 MessageList 的 streaming 滚动逻辑。SSE 断线 IOException / AsyncRequestNotUsableException 在研究控制器内结束响应，不尝试写 JSON 错误，最终浏览器无 SSE 响应转换错误。默认开发代理保持 9090，可用 RAGENT_VITE_PROXY_TARGET 隔离浏览器测试端口。

### 实际验证

后端最终 **23 类 168/168**，0 失败/错误/跳过；后端 clean package、前端 Vite build、node 配置类型检查通过。相对 P5 新增 8 个用例：SSE 回放/游标/固定归属与只读行为、内部 payload 过滤、错误归属与游标拒绝、会话/来源读取与 regenerate 参数、断线不写 JSON/不取消、已读快照取消后保留且隔离 owner、HTTP 400 只计一次且 unknown usage/配额释放、非法创建 JSON 不作为 SSE 断线吞掉且不调度。保留 P3/P4/P5 竞争、取消、生成/有限修复、普通问答、检索、摄取和版本比较回归。

app 严格 TypeScript 检查仍有 P0 已记录的 24 个旧诊断，逐条去除行号后与 P0 内容相同；没有新增研究代码诊断。不能把 Vite build 写成全量类型检查通过。node 检查通过；tsBuildInfo 写入 /tmp。

```bash
P6_TESTS=ResearchEventStreamServiceTest,ResearchArtifactGeneratorTest,ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,MilvusVectorRetrieverServiceTest,EsKeywordRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,StreamTaskManagerCancelTraceTest bash scripts/validate-agentic-research-p6.sh
./mvnw -o -pl bootstrap -am -DskipTests clean package
npm --prefix frontend run build
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.app.json --noEmit --tsBuildInfoFile /tmp/agentic-p6-app.tsbuildinfo
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.node.json --noEmit --tsBuildInfoFile /tmp/agentic-p6-node.tsbuildinfo
./mvnw -o -pl bootstrap dependency:build-classpath -Dmdep.outputFile=/tmp/agentic-p6-classpath.txt
python3 eval/agentic-research/browser_research.py --run-dir local-data/agentic-research/runs/<new-id>
```

[浏览器 harness](../../eval/agentic-research/browser_research.py) 和仅 test-classes 的 [ResearchBrowserFixture](../../bootstrap/src/test/java/com/nageoffer/ai/ragent/research/web/ResearchBrowserFixture.java) 使用 Chrome/真实 React、研究 controller/service、AgentScope HTTP 原生协议和随机 PostgreSQL。**认证、检索、原文读取、模型内容和普通问答响应受控**，不读取或发送供应商凭证。最终 M 批 9 项检查通过，49 次本地 fixture 模型调用，6 个持久 run（5 COMPLETED 有产物、1 CANCELLED 无产物）；浏览器恰有 5 次创建 POST 和 1 次 regenerate POST。刷新恢复 REPORT/PLAN 与等待问题，调用数不增加；回复“2 小时”后继续并显示 user_input；主动取消无 artifact；同时封锁会话列表与 events，创建成功后仍能订阅进度且不恢复已提交的输入；恢复 events 后只 GET 重连，同一 run 完成。测试库与所有测试进程均已清理。

A—H 开发失败批次、I/J 首轮完整通过、K 错误的 toast 断言失败，以及 L/M 补充场景通过均保留。早期 fixture 重复 bean、Vite 根目录/工作目录、普通问答 SSE 字段和 CDP 布尔断言等 harness 问题已修正；E/F 暴露的引用/滚动实际缺陷已修复。H 需等待原有虚拟列表 1.5 秒加载定位结束后再导航；I 暴露断线通用 JSON 错误，J 已修复；新增非法 JSON 回归确认普通接口仍返回失败。K 的 toast 断言误以为 fetchSessions 会重抛错误，实际原 chatStore 已自行显示错误并处理；L/M 改为检查成功创建仍订阅且输入清空。取消 worker 时仍出现 P4 的 Reactor 阻塞订阅 InterruptedException / onErrorDropped 日志，保留原始记录；CANCELLED、HTTP 本地结束和无迟到产物验证通过，未据此声称远端供应商停止计算。

最终浏览器原始记录/截图在 `local-data/agentic-research/runs/20260917T131200_P6_browser_M/`；程序日志与 JUnit XML 在 `20260917T125800_P6_validation/`。[P6 清单](../../eval/agentic-research/manifests/research-p6-validation-2026-09-17.json)记录源码/模板/测试/日志 SHA-256、各批次与受控边界。P5 manifest 按冻结提交 3507d9e 核验，不能用 P6 引用渲染改动误判 P5 留档被改写；16 份历史升级 SQL 不变。未升级业务库或清理业务数据。

P6 交付时真实供应商 REPORT/PLAN 产物联调因自动审批拒绝未执行（具体语料外发与付费授权不足），后续用户明确授权后的开发复测见下方补充记录；本地浏览器不能替代真实检索/模型、正式登录、文档预览下载或语义支持评分。P7/P8 未实施。下一阶段 P7 按相同语料、模型和检索设置做普通 RAG / 单研究 / 按需委派对照，以独立固定样本核对产物原文，再记录答案、证据、资源与故障结果；不把本批程序机制作为效果数字。

最终静态验收通过：5 份入口 Markdown 的 100 个本地链接/锚点、围栏、whitespace 与 shell/Python 语法；35 个阶段文件在约定范围，79 份源码/配置/测试及原始验证产物共 192 个指纹一致。P5 按冻结提交 3507d9e 核验，16 份历史升级 SQL 不变。检查记录与脚本在 P6 validation 目录，指纹由 P6 manifest 引用。


## P5/P6 补充：授权后的真实供应商产物联调（2026-09-17）

起始提交 `f79b9e6`（P6），开始时工作区干净。用户在得知具体公开 QASPER 语料外发和付费范围后明确回复“授权”。本次仅执行原有 comparison-workers / plan-workers 两条开发样例及受影响复测，不扩展 P7 批量评测，不 push/合并。后续提交标题为 `fix: improve evidence feedback for research artifacts`，SHA 可通过 Git log 定位。

### 实际结果与修复

| 批次 | 最终模板 | REPORT 比较 | PLAN 草稿 | 模型调用 |
| --- | --- | --- | --- | ---: |
| A | research-artifact-v1 | FAILED；两次引用校验失败，无产物 | FAILED；两次引用校验失败，无产物 | 31 |
| B | research-artifact-v2 | COMPLETED；一次修复，3 条引用仅来自论文 A，论文 B 无检索结果 | COMPLETED；一次修复，1 条引用；原文核对发现不支持的字段，保留为负例 | 30 |
| C | research-artifact-v3 | FAILED / RESEARCH_TIMEOUT；未读到证据，无产物 | PARTIAL；一次生成，4 条已读引用，保留 worker 超时与缺口 | 18 |

A 的历史输出只有通用错误码，不能证明具体是空引用还是错误 ID。B 增加诊断后，实际捕获报告中的无引用目标/缺口章节和计划中无引用的 500 条用户预算资源。校验现区分空引用与未知引用并给出字段位置；一次修复同时列出允许的精确 evidenceIds，要求无支持字段移入 gaps/pendingItems，不随意配上引用。内部失败事件保存最多 16000 个 Java 字符的生成输出并标注截断，不保存 thinking；SSE 仍过滤该原始草稿。引用身份、读取证明和一次修复上限没有放宽。

联调 Case 新增可选 constraints；仅 P5 plan-workers 将“最多手工标注 500 条”显式传入 Brief，产物由服务端保存为 user_input，不作为文档参数。旧 P3/P4 请求不传时仍为空。CLI embedding 原来只有 callTimeout=30 秒，实际受默认 readTimeout=10 秒限制；C 将读取超时对齐现有 30 秒工具配置及线上同步客户端，没有增加总预算。

B 的 PLAN 所引唯一 Conclusion 段没有 20Newsgroups、初始标注种子集或计算资源要求，仍生成了这些字段。这是“ID 合法但原文不支持”的真实负例，未写为质量通过。v3 将压缩 findings 限为研究线索，要求逐项核对所引正文，不从概括性结论补齐常识流程。C 的数据集引用已指向实际 Introduction 段，不再出现 B 的初始种子集细节和计算资源项；4 条引用逐项回查了真实来源段落、hash、范围及读取证明。batch_size / window_size 都为 null，500 条仍为独立用户约束。C 的步骤只构成研究概述，初始分配、标注操作、评估数值及如何满足 500 条预算尚缺，且只引用一篇论文，不能据此称为可执行方案或真实多文档 PLAN 验收。

30 秒配置下仍发生 embedding 请求超时。C 的比较没有通过成功验收；计划有一个 worker 已读两段后超时，主任务保留来源并补读，最终正确落 PARTIAL。旧 B 产物不能冒充 v3 比较通过。上游 gaps 的重复表述和预算适用性疑问仍保留在原始结果中；正式质量评测在 P7 接续。

### 复现、用量和程序边界

每批使用以下命令，run-dir 必须为新目录；A/B 回放使用各自 source-snapshot，当前源码为 C。所有运行只读既有 `research_corpus_v1`，运行/证据/事件写随机隔离库，三批测试库均已清理。

```bash
python3 eval/agentic-research/smoke_research.py --phase p5 --case comparison-workers --case plan-workers --run-dir local-data/agentic-research/runs/<new-id> --execute
```

三个开发批次累计 **79 个研究模型请求**，供应商已知输入 **321707** / 输出 **26002** token，模型 usage unknown 为 0；query embedding 按 call_id 去重 **30 请求**，已知 total_tokens **60**，另 **23 请求 usage unknown**。失败/超时及修复均计入，没有将 unknown 计作零费用；金额和余额未核对。模型始终为 `qwen3.7-flash-2026-07-15`，关闭 thinking；embedding 为 `Qwen/Qwen3-Embedding-8B`、1536 维，无 rerank。没有读取 gold，也没有跑 A/B/C、EM/F1 或正式语义评分。

修复后当前 **23 类 170/170**，0 失败/错误/跳过；后端 clean package 通过。新增 2 个本地 HTTP 用例覆盖无引用比较缺口的一次修复、参数错误字段定位及失败输出截断；扩充 SSE 过滤检查。当前未修改前端，不重复前端/浏览器检查，P6 的 build、9 个受控浏览器检查及 24 个既有类型诊断仍是该阶段证据。

A/B/C 原始目录分别为 `20260917T132700_P5_real_A/`、`20260917T133500_P5_real_B/`、`20260917T133900_P5_real_C/`，均位于 `local-data/agentic-research/runs/`。程序日志、JUnit、逐字段定性原文核对及检查脚本位于 `20260917T133300_P5_followup_validation/`；运行源码与 v1/v2/v3 模板各自冻结，既有 P5/P6 清单和原始留档不改写。[真实产物联调清单](../../eval/agentic-research/manifests/research-p5-artifact-smoke-2026-09-17.json)保存请求/状态、实际用量、源码及原始产物 SHA-256。历史升级 SQL 和业务数据库未修改；P7/P8 未开始。

本次最终静态验收通过：5 份入口 Markdown 的 105 个本地链接/锚点、围栏、whitespace、14 个变更文件范围与 Python 语法；301 个源码/留档指纹匹配，P5/P6 分别按 3507d9e / f79b9e6 冻结核验，16 份历史升级 SQL 不变。clean JAR 仅含 research-artifact-v3；静态检查首次因旧 P5 清单没有 static_checks 字段失败，调整检查器后通过，首次日志保留。

## P7：固定架构对照、公开数据评分与故障验证（2026-09-18，已完成）

起始提交 `1069330`，分支 `feat/agentic-research`，开始时工作区干净。用户已授权按主计划执行 P7/P8。新增固定配置的 A/B/C 批量运行、离线 QASPER/MuSiQue 评分和 12 个比较/12 个计划应用样例；A 是复用项目知识检索组件的一次检索路径，固定模型后与 B/C 共用产物生成，不包含生产聊天的改写、意图、MCP 和回退，不能写成完整生产 RAG E2E。

当前程序回归最终 24 类 173/173，Python 28/28，通过；首轮 6 个真实校准任务保留 B 的 NATIVE_FINISH_REQUIRED 失败。发现最终回答格式指令混入 Brief，现已将其隔离到最终生成阶段，相同规模复测 6/6 COMPLETED。失败、重试和 unknown usage 保留；尚不作为固定 regression 成绩。程序记录位于 `local-data/agentic-research/runs/20260917T140100_P7_validation/`。


### P7 固定 smoke、应用核对与最终生成输入复测

- 固定 smoke（`20260917T140831_P7_smoke`）真实记录 120/120：A 40 COMPLETED；B 26 COMPLETED/8 PARTIAL/6 FAILED；C 27 COMPLETED/5 PARTIAL/8 FAILED。C 没有实际 worker。QASPER 20 题的 answer F1 为 A 0.3685/B 0.2017/C 0.2042；MuSiQue 20 行中仅 6 行 answerable，不能把该小分母写作 20 题答案得分。逐模式答案、证据、可回答性、资源和诊断完整保留。
- 24 个应用（`20260917T142538_P7_applications`，v3）记录 5 COMPLETED/15 PARTIAL/4 FAILED；comparison-03 真实创建两个 worker，一个完成、一个局部预算不足，保留成功结果和失败缺口。plan-05/11 真实追问和 2 小时回复均保存，随后产物失败，没有发布计划。核对者为 Codex，非独立人工盲评，不调用裁判 API；[24 项核对清单](../../eval/agentic-research/manifests/p7-application-source-review.json)记录维度、数值/条件、缺口、用户输入和具体不支持项。
- 明显问题包括语料 QASPER 版本被当作论文实验数据、残缺公式/标题被当作完整方法、运行预算失败被解释成源文没有计算预算、个别限制引用没有支持。最终生成输入改为正文、身份、extent/截断及原文章节/表格上下文；历史 v3 模板在旧批次源码快照保留，当前 clean JAR 只有 v4。
- 原题原范围两例复测（`20260917T145737_P7_application_retest_v4`）：comparison-03 COMPLETED，引用到两篇但仍把断裂公式写作完整公式；plan-02 FAILED，两次缺少 PLAN step parameters 必填数组而未发布。没有把这次修复写作语义质量提升。
- 固定 regression（`20260917T145853_P7_regression_v4`，v4）400 问题/1200 任务全部记录：932 COMPLETED / 93 PARTIAL / 175 FAILED，未执行 0；三批随机运行库均清理，只读语料不改。72 项源码/配置指纹与实际交付一致，问题/数据/预算/模型固定。QASPER Answer F1：A 0.3365/B 0.2338/C 0.2442；MuSiQue Answer F1：A 0.3325/B 0.3269/C 0.3918，分母与全部指标见[固定对照报告](agentic-research-evaluation-report.md)。C 实际委派 2 个运行/4 个 worker。
- 本批 SDK 模型调用记录 7778，已知输入 84043881/输出 1456411 token、unknown 1；已知生成费估算 21.4981 元。Embedding 3989 请求、unknown 127，金额和账单未核对。错误与已读支持覆盖提示均在 diagnostics.json；分类不构成因果或语义证明。
- full 当前仅准备 5839/17517，未付费执行；默认 30 元生成估算上限也作用于 full，未执行不计完成。所有固定任务、负结果、调用资源、日志和配置 SHA 见[P7 清单](../../eval/agentic-research/manifests/research-p7-evaluation-2026-09-17.json)。

## P8：清理与交接（2026-09-18，已完成）

运行源码/配置/前端入口引用检查未发现退役 TaskAgent、TaskTemplate、Simulation 或 Robot 依赖。保留普通摄取的任务接口、MCP、Milvus/ES 等现用能力。更新首页、文档索引、08 当前研究流程、面试口径、数据库说明与交接文档；旧工业检索数字、历史阶段日志、历史负结果和 16 份升级 SQL 保持。

新增 `scripts/demo-agentic-research.sh`，冻结两道 A 问题以及双资料比较 REPORT / AMR 摘要 PLAN（要求缺失 batch/window 保持 null/待确认），默认只准备四个请求。初版 comparison-01/plan-01 dry-run 在 `20260917T142200_P8_demo_dryrun/` 通过；最终脚本依据完整应用核对选用 comparison-05/plan-06，独立记录演示，不替换负结果。真实模式需已有公开语料和供应商凭证，逐任务状态、来源与 unknown usage 保留，不能把命令 exit 0 当作正确率。

当前前端 build、node 类型检查通过；app 24 项既有诊断按 cwd 前缀/行号归一化与 P6 相同。`20260917T142343_P8_browser/` 与 v4 变更后 `20260917T150225_P8_browser_v4/` 受控浏览器各 9 项检查通过，49 个本地 fixture 模型调用，零付费调用；受控组件和生产 E2E 边界保留。修复旧 P2 数据库脚本对已退役 fresh 表的假设，仅在随机库执行历史建表脚本；fresh、两轮增量、owner、快照与历史哨兵均通过，库已清理。

### P8 四请求真实演示与最终交付

P7 阶段提交为 `3381fa9d0a8bab051873b5ec415cc8f642da933f`。随后运行 `bash scripts/demo-agentic-research.sh --execute <new-run-directory>`，批次为 `20260917T192824_P8_demo_v4`。四个请求都在模型调用前冻结：两道 A 问题 COMPLETED，comparison-05 / plan-06 均为 PARTIAL / MODEL_CALL_BUDGET；原程序、原 24 项应用和两例复测不覆盖。两个随机运行库均删除，语料只读。

两道 A 题分别是“Do they evaluate only on English datasets?”及“After what is the body of water Partridge Lake is part of named?”。前者方向有数据段落支持，但未遵守 Yes/No 极短格式；后者只回答 Bering Sea 中间实体，未回答命名来源。comparison-05 只有 Organization/event 短句、没有读到 Paper B，不能形成方法比较。plan-06 的 JAMR/子树/Neural AMR/基线步骤有对应原文；298/33 与均值须保持 AMR Bank/训练限定，ROGUE 基线证据不能单独证明全流程评价。batch/window 仅在待确认项，step.parameters 全空，未生成请求要求的 null 条目。

四个产物的 10 条引用快照由 Codex 核对，身份、段落 ID 和正文 SHA 在 semantic-review.json / P8 清单；不是独立人工盲评，无裁判 API。SDK 模型调用记录 33、已知输入 444152/输出 11886 token，usage unknown 0；已知生成费估算 0.1457 元。Embedding 17 请求、已知 total_tokens 271、unknown 0，金额/账单未核对。这是实际公开语料—检索/模型—隔离 PostgreSQL—产物的 CLI 路径，没有生产登录或来源预览下载。

最终检查覆盖 15 份当前入口文档的链接/锚点、围栏、whitespace、三份 shell 语法、JSON 清单、72 项运行源码/配置与固定回归一致、16 份历史 SQL 字节不变及既有 XLSX 事实比较主体。后端/前端代码在最终 173/28 程序检查、clean package 和 v4 浏览器检查后未改动，不重复声称新的付费/生产服务测试。目录 docs/current-code-notes-2026-09-18/ 是工作期间新增的独立未跟踪文档，保留在阶段提交范围外。

## R1：工具契约、原生结束恢复与原题复测（2026-09-18，已完成）

分支 `feat/agentic-research`，起始提交 `243d0c0`。阶段提交通过唯一标题 `fix: repair research tool contracts and native finish recovery` 定位。用户已授权直接调用配置的供应商并使用 Git 记录；本阶段省略价格查询、金额预估和费用确认。独立未跟踪目录 `docs/current-code-notes-2026-09-18/` 保留在提交范围外。

### 实现与程序检查

- `Finding.statement/evidenceIds`、委派任务的 `goal/dimensions/expectedOutput` 显式声明嵌套 required，SDK 在 DTO 转换前拒绝缺字段、错误类型和 null finding。结束工具的参数错误增加 `/findings/0/evidenceIds` 等路径；未读引用业务校验继续拒绝并返回对应 finding 下标。AgentScope 2.0.1 构建 Agent 时复制 Toolkit，诊断适配器同时实现复制，避免错误反馈在构建时丢失。
- 模型提前输出普通文本时复用同一 Agent 和上下文，保留已经完成的 read 工具消息与证据，再请求原生 `finish_research`。最多消耗两次修复模型调用，仍受原有全局/worker 调用次数、最终生成预留、超时和取消约束；连续无效输出最终仍失败，不将文本或假工具 JSON 接纳为完成。
- `MODEL_STARTED` 保存 SDK 请求边界的 `requestedToolChoice`、schema SHA-256、是否修复；`MODEL_ENDED` 保存原生工具/文本出现情况、finish reason、状态、阶段和错误类型。本地 HTTP 桩验证最终发送的 tool_choice 和嵌套 schema；真实供应商批次保存 SDK 边界数据，不把它写成独立抓取的原始 HTTP 请求。
- Python 评测默认 `estimate_generation_cost=false`，Java 允许 `maxCostCny=null`，此时不计算或拦截金额；实际 usage 仍记录。新增 `--case-ids`，只接受固定 profile 中唯一的题目 ID，冻结顺序与清单指纹；标签不进入模型请求。历史批次和费用报告没有重写。

相关后端检查累计 **114/114**：P3 随机库套件实际通过 103 项，另 11 项因需要 P2 环境跳过；随后在 P2 随机库单独执行 11/11，通过新库、重复增量和历史行保留检查，临时库均清理。原生 HTTP 检查包含 18 项，补强“修复请求保留完整 read 工具消息及 tool_call_id”断言后再次 18/18；Python 29/29。此前沙箱本地套接字拒绝及字段路径适配的失败迭代均保留在日志中。这是本阶段相关测试，未重跑 P8 的全部普通业务/前端检查。

### 真实诊断复测

新批次：[运行目录](../../local-data/agentic-research/runs/20260918_R1_failure_replay_v1/run.json)。过程日志、选题脚本、同题比较与引用核对：[验证目录](../../local-data/agentic-research/runs/20260918_R1_validation/comparison.json)。可版本化的汇总、73 项运行源码指纹和输出哈希见 [R1 清单](../../eval/agentic-research/manifests/research-r1-reliability-2026-09-18.json)。

从原 regression 固定题目中预先选择 12 题：QASPER/MuSiQue 各 6；4 个原生结束失败、4 个出现 ClassCast 的工具错误样例、2 个检索超时失败、2 个正常对照。按数据集/模式及题目 ID 顺序选择，ClassCast 层优先失败、再部分完成，不使用答案得分挑题。固定 [12 个题目 ID](../../eval/agentic-research/manifests/research-r1-case-ids-2026-09-18.json)各运行 A/B/C，模型仍为 `qwen3.7-flash-2026-07-15`，PGVector、rerank 关闭、范围、提示设置和技术上限保持原约定。

| 模式 | 历史同题：完成/部分完成/失败 | R1 同题：完成/部分完成/失败 |
| --- | --- | --- |
| A | 12/0/0 | 12/0/0 |
| B | 4/2/6 | 11/1/0 |
| C | 5/2/5 | 12/0/0 |
| 合计 | 21/4/11 | 35/1/0 |

原 11 个 FAILED 中，10 个转为 COMPLETED，1 个转为 PARTIAL；后者仍有 `MODEL_CALL_BUDGET`。本批 4 个运行触发原生结束修复，共 6 次实际模型调用，均得到原生工具输出并最终完成。历史同题的 13 次 ClassCast 工具错误，本批未再出现；但未读引用错误从 6 次变为 12 次，保留逐次反馈，不能称所有工具问题已消除。没有新超时，且 141 次 embedding usage 均返回；本阶段没有修改检索超时链路，这不证明网络/超时已经修复。两批实际 worker 均为 0，不能推导并行研究收益。

| 数据集/模式 | 历史同题 Answer F1 | R1 Answer F1 | 答案分母 |
| --- | ---: | ---: | ---: |
| QASPER A | 0.3623 | 0.3932 | 6 |
| QASPER B | 0.1931 | 0.3416 | 6 |
| QASPER C | 0.0969 | 0.4401 | 6 |
| MuSiQue A | 0.3333 | 0.0000 | 3 |
| MuSiQue B | 0.3333 | 0.6667 | 3 |
| MuSiQue C | 0.3333 | 1.0000 | 3 |

这是按故障分层的诊断样本，各模式时段不同且只运行一次，MuSiQue 仅 3 个 answerable；连 A 的分数也变化，不能用这些数字替代原 400 题回归或宣称稳定的整体质量收益。逐题得分、证据覆盖、答案格式、状态和延迟仍全部留档。

实际 SDK 模型调用 273 次，已知输入 3,269,722 / 输出 50,727 token，usage unknown 0；embedding 141 次、已知 total_tokens 1,579、unknown 0。三个随机运行库均删除，只读语料未修改；金额估算与门槛实际关闭。首次执行申请因自动审批尚未核实供应商和公开语料而拒绝，未启动进程；补齐 Bailian/SiliconFlow 端点及公开 QASPER/MuSiQue 范围证据后重新审批通过，记录在 provider-scope-verification.json。

### 原文核对与接续

Codex 对全部 4 个触发修复的已完成产物核对引用原文，见 [semantic-review.json](../../local-data/agentic-research/runs/20260918_R1_validation/semantic-review.json)，没有独立人工盲评或裁判模型：

- B 的 BERT 回答有 MEDDOCAN 相对性能和 recall 的直接支持，但引用没有数值，不能据此宣称完整性能评估。
- B 的多语言训练回答主要陈述有支持；其“七种语言没有完整列出”的缺口却与已读原文不符。
- C 的比较回答引用的是前人方法介绍，引用片段不足以证明这些方法全是实际实验对照。
- C 的多跳回答名字有冻结数据快照支持；最终单条引用没有展示出生国和委员会的整条关系，也不构成对当前在任者的事实判断。

下一阶段按计划 8.6 执行 **R2**：先给检索排队、embedding HTTP、PG、工具加可对应的耗时和错误，区分成功无命中/暂时失败，安排内外 deadline 和取消传播，再做有限传输重试及查询 embedding 缓存。同时补 SSE 无数据连接检测与退避重连，验证不重复创建任务、持久事件重放和终态竞争；模型中途断流要丢弃不完整输出，从完成的工具步骤恢复，不能拼接不完整 tool JSON 或承诺第 N 个 token 原位续算。后续 R3—R5 再处理有效阅读、产物语义和固定 400 题/应用对照，本阶段没有提前标记完成。


## R2：检索与连接恢复（2026-09-18）

已实现研究专用 deadline/cancel 上下文，显式传递到排队任务、embedding HTTP 和 PG statement。embedding 每次最多 12 秒、瞬时错误最多重试一次，检索内部在工具外层之前结束；原生工具明确返回失败而非空列表。取消、鉴权及参数错误不重试，成功 query embedding 按运行及模型配置缓存。研究 PG 设置和查询使用同一连接。模型整次响应确认结束后才交给 SDK，半截工具 JSON 丢弃，从已完成历史最多重试一次；逐次 usage/unknown 均保留。SSE 增加 15 秒静默检测、可取消快照请求、带退避与游标的只读恢复、终态关闭。

后端 116 项、Python 29 项、前端 4 项故障测试通过；真实浏览器桩集成 9 项通过，前端构建通过，24 项既有 app 类型诊断未增加。详见 [R2 清单](../../eval/agentic-research/manifests/research-r2-reliability-2026-09-18.json)。

两道原超时题 × ABC 的 v1 六个任务实际记录为 4 完成/0 部分/2 失败，但其中三个完成任务答案为空；65 次 embedding 请求有 63 次 usage unknown 的网络失败，不能宣称效果改善。A 的一次两次超时发生于 embedding，尚未进入 PG。后续同公开查询的原 30 秒/新设置各四次传输对照均成功，不能据此断定先前是网络、供应商还是超时设置原因。已加入 DNS、TLS、请求写出、响应头和正文阶段记录；同两题 v2 复测进行中，最终结果续记。原始失败批次不覆盖，金额计算关闭。
