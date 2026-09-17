# 统一研究工作流实施记录

主计划：[实施计划](agentic-research-implementation-plan-2026-09-17.md)。模型与评测口径：[评测与预算](agentic-research-evaluation-and-budget-2026-09-17.md)。实际验证：[验证报告](agentic-research-validation-report.md)。

## 当前接续点

本轮边界为 P0、P1。P0 已完成，P1 正在实施；P2—P8 未开始。后续不要把 SDK 版本准备、旧能力退役或单元测试通过写成新研究 Agent 已完成。P1 完成后从 P2 的数据契约、证据读取和检索作用域开始。

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

阶段提交标题：`docs: record agentic research implementation baseline`。提交 SHA 在 P1 记录中补记，或按唯一标题从 Git log 查询。

## P1：旧业务退役（进行中）

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
