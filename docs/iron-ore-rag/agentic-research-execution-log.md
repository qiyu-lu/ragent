# 统一研究工作流实施记录

主计划：[实施计划](agentic-research-implementation-plan-2026-09-17.md)。模型与评测口径：[评测与预算](agentic-research-evaluation-and-budget-2026-09-17.md)。实际验证：[验证报告](agentic-research-validation-report.md)。

## 当前接续点

本轮边界为 P0、P1，两阶段已完成；P2—P8 未开始。后续从 P2 的数据契约、证据读取和检索作用域开始，不要把 SDK 版本准备、旧能力退役或单元测试通过写成新研究 Agent 已完成。

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

阶段起始提交：`8a9c79d`。本阶段提交标题：`refactor: retire inspection and robot execution demos`；按唯一标题从 Git log 查询 SHA，下一阶段补记即可。

## 下一阶段：P2 的直接执行顺序

1. 读取主计划第 4.2/4.3/6/7 节及预算补充，核对分支、Git 状态和本记录；不要重新接入送检工具。
2. 先实现 ResearchBrief / EvidenceRecord / SubtaskResult 与新增 run/evidence/event SQL。同步更新新建 schema；增量 SQL 手工执行方式沿用数据库说明，不假定 Flyway 自动迁移。
3. 核对真实知识库共享规则，增加 KnowledgeSearchService 的召回前 scope 限制，复用 MultiChannelRetrievalEngine；不得调用包含业务 MCP 的完整 RetrievalEngine 或聊天管道。
4. SourceReader 依据服务端 evidenceId、存储块正文/hash/version 与可靠 metadata 读取；旧元数据不足时只做可信块级读取。GroundingChunk 仍是摘录，不能作为全文。
5. 在 `eval/agentic-research/` 实现 QASPER/MuSiQue 转换、清单和分离的 corpus/questions，防止答案和 gold decomposition 入库。原始数据和清单路径已存在，固定 ID/种子与真实入库能力仍未实现。
6. P2 的实际向量化/导入需记录 usage、幂等批次和错误；P3 再接入 SDK、首期研究模型及每次模型调用记录。已有 24 个类型诊断和 lint 配置问题应作为历史基线记录，不静默宣称全仓检查通过。
