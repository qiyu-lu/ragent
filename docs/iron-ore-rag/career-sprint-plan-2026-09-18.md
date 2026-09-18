# 秋招冲刺计划（第 4 版）：LLM 应用后端的五个生产级问题

> 状态：方向已由用户于 2026-09-18 确认，可以实施。本文件取代此前同名文件的全部旧版本（自适应 Agentic RAG、可核验引用、证据账本三版均作废），以及旧计划 §8.7 未执行的 S3/S4 与 R5 接续。P0—P8、R1—R4、S1—S2 的代码全部保留。
>
> **实施会话只读两份文件：本文件 + [`career-sprint-status.md`](career-sprint-status.md)。** 不要加载旧计划、执行记录、验证报告（合计约 7 万 token）；需要历史细节时用 `git log` / `git show` 定点查。

## 0. 目标与选题标准

- **岗位**：Java 后端 / 服务端为主，Java 向的 AI 应用开发为辅。面试官是后端工程师。
- **被否定过的讲法**：XLSX 表格处理、请求级检索预算——被评价为“前期数据清洗和一些工程改进，没有亮点”。它们不再当亮点，只作为领域适配一笔带过。
- **选题三问**（三条都满足才入选）：
  1. 做 LLM 应用的公司是不是都会遇到？
  2. 出了问题会不会真的花钱、出事故或泄露数据？
  3. 能不能用后端通用指标衡量（命中率、成功率、恢复时间、延迟、成本），而不是自己发明的指标？
- **明确不做**：Agent 能力研究（证据账本、先读后引的扩展、多 Agent 收益论证）、在 MuSiQue / QASPER 上追 F1、PLAN 产物打磨、SiliconFlow 传输根因排查、角色模型 / thinking / rerank 消融。
- **一句话定位**：企业检测资料的知识问答与研究任务平台；我负责它的后端，解决成本、可用性、稳定性、安全、数据更新五个生产问题。
- **与秒杀项目的互补**：秒杀是短请求、高并发、库存一致性；本项目是分钟级长任务、又贵又不稳定的上游、流式输出、可取消可恢复。两个项目里的分布式锁与租约、缓存命中率、限流与背压可以互相呼应。
- **背景口径**：企业方提供的真实检测资料；知识服务的预研原型；未上线、无真实用户流量。不提机器人实机；被问到只说“课题背景是检测流程自动化，我负责知识服务部分”。

## 1. 已核实的现状证据（2026-09-18，只读核对）

| # | 问题 | 证据 | 位置 |
| --- | --- | --- | --- |
| E1 | 提示缓存几乎全部失效 | P7 回归的调用台账：B 模式 3,486 次调用、输入 3,803 万 token、缓存命中 197 万（5.2%）；C 模式输入 4,427 万、命中 8,448（0.02%）；R3 复测混合批 9.2%。根因一：每轮把会变的提醒（剩余次数、可引用 ID、阅读覆盖）拼进**第一条**系统消息；根因二：超长时每轮从最早的工具往返开始滑动裁剪。工具 schema 指纹在研究阶段内稳定，不是原因。C 为何比 B 更低尚未查明 | `BoundedResearchModel.attempt()`（约 95—135 行）、`trim()`（226—245 行）；数据在 `local-data/agentic-research/runs/20260917T145853_P7_regression_v4/attempts/*/usage.jsonl` |
| E2 | 长任务不能跨实例、不能恢复，且多实例下会互相误杀 | `interruptOrphans()` 在**启动和关闭**时把**全库**所有 QUEUED / RUNNING 任务标为 INTERRUPTED，不区分执行者——任何实例启动或停止都会杀掉其他实例的健康任务；租约在领取时一次性设为 `maxDuration + 30 = 330 s`，没有心跳续租，也没有人扫描过期租约；调度只在收到请求的实例内存线程池里，队列满直接失败；`LoginUser` 只存在内存；重启后只能整体重新发起，已花的模型调用全部作废 | `ResearchRunStore.claim()`（106—122 行）、`interruptOrphans()`（约 259—274 行）；`ResearchRunService.onStartup / schedule / execute / close` |
| E3 | 不稳定上游的治理已做但缺后端口径的数字 | 历史事故：1200 任务中 72 个超时全部卡在检索工具 30.01 s——HTTP、检索通道、工具三层超时同为 30 s，失败被吞成“空结果”。R2 / S2 已修：embedding 单次 ≤ 12 s 且内层先于外层结束、只对可重试错误重试一次、失败与无命中分离、取消传到 HTTP、连续两次失败停止补查、按 DNS / TLS / 写请求 / 等响应头分阶段计时 | 提交 `6353eb7`、`0b39d31`、`8bbff9a`、`65c99c7`；`infra-ai/.../operation/RequestOperation.java` |
| E4 | 企业资料没有权限隔离 | 知识库与问答的 Controller 上没有任何角色 / 权限校验（全仓只有 `UserController` 与 `PooledEvalController` 用了 `StpUtil.checkRole("admin")`，可作为写法参考）；`t_knowledge_base` 只有 `created_by`，没有可见性或授权；`POST /rag/v3/stop?taskId=` 不校验任务归属；研究入口注释明确“当前知识库是全局共享” | `knowledge/controller/*`、`RAGChatController:63`、`ResearchRunService.validateScope()` |
| E5 | 文档小改动导致全量重新向量化 | 重新入库时先删后插该文档的全部块；块级 `contentHash` 已保存但没有用于比对；摄取侧只有一个 embedding 调用点 | `RelationalChunkSink:98`、`ChunkEmbeddingService:54` |

## 2. 总览

| 工作项 | 真实问题 | 主要指标 | 估时 | 完成标签 |
| --- | --- | --- | --- | --- |
| S0 | 分支、基线、状态文件 | — | 0.5 天 | `career-v0-baseline` |
| W1 | LLM 调用成本与延迟：缓存友好的上下文布局 | 缓存命中率、单任务输入成本、单次调用延迟 | 1.5 天 | `career-w1` |
| W3 | 不稳定上游的治理：故障注入基准 | 各故障率下的任务成功率、错误完成率、P95 耗时 | 1.5 天 | `career-w3` |
| W2 | 长任务持久化执行：心跳租约、跨实例接管、断点续跑、优雅停机 | 恢复时间、重复执行的模型调用数、不变量通过数 | 3 天 | `career-w2` |
| W4 | 企业资料权限隔离 | 越权矩阵通过数、召回前过滤的证明 | 1.5 天 | `career-w4` |
| W5 | 增量重建：内容寻址的 embedding 复用 | 重新入库的上游调用数、版本升级的重嵌入比例 | 1 天 | `career-w5` |
| S6 | 改动说明、简历条目 | — | 0.5 天 | `career-done` |

顺序固定为 S0 → W1 → W3 → W2 → W4 → W5 → S6：W1 最小且数字最有冲击力；W3 产出的模拟上游是 W2 的测试基础。每个工作项结束即可更新一条简历，不必等全部完成。时间不够时从后往前砍（W5、W4），**S0 + W1 + W3 + W2 是底线**，约 6.5 天。

## 3. 环境速查（避免重读旧文档）

| 项 | 值 |
| --- | --- |
| 工作树 | `/home/sd101t/IdeaProjects/ragent-iron-ore-rag`（Git 公共目录在 `ragent-new/.git`，这是关联工作树） |
| 基线分支与提交 | `feat/agentic-research` @ `65c99c7` |
| 技术栈 | JDK 17、Spring Boot 3.5.7、MyBatis-Plus、PostgreSQL + pgvector、Redis、RocketMQ、Sa-Token、AgentScope Java 2.0.1、React |
| 研究代码 | `bootstrap/src/main/java/com/nageoffer/ai/ragent/research/`（约 5,000 行）；提示词在 `bootstrap/src/main/resources/prompts/` |
| 构建 | `./mvnw -o -pl bootstrap -am -DskipTests clean package` |
| 回归 | `bash scripts/validate-agentic-research-p7.sh`（用 `P7_TESTS=类名,类名` 缩小范围；需要容器 `ragent-iron-ore-dev-postgres-1` 在运行，测试自建随机库并清理） |
| 评测命令 | `python3 eval/agentic-research/evaluate_research.py --config <cfg> --profile regression --case-ids <ids.json> --mode {A,B,C,all} --run-dir local-data/agentic-research/runs/<新目录> --execute`；续跑加 `--resume`，只评分加 `--score-only` |
| 语料库 | 数据库 `research_corpus_v1`（QASPER / MuSiQue 共 18.3 万向量，SiliconFlow `Qwen3-Embedding-8B`、1536 维） |
| 调用台账 | 每个运行目录的 `attempts/<序号>_<模式>/usage.jsonl`（含 `inputTokens`、`cachedTokens`）与 `traces.jsonl`（逐事件） |
| 模型 | 百炼 `qwen3.7-flash-2026-07-15`（`research-flash`）与 `qwen3.7-max-2026-05-20`（`research-max`）；密钥取自环境变量或 IDEA 运行配置，不落盘 |
| 授权 | 用户已授权为本项目发起真实模型调用，不做金额预估与确认；如实记录 usage 与失败 |
| 不要碰 | 用户自己的未跟踪笔记（例如 `docs/current-code-notes-*`，定稿时该目录已不在工作区）；出现时不修改、不提交 |

## 4. S0：分支、基线与状态文件（0.5 天）

1. `git status --short --branch` 确认工作区；除本文件、状态文件和用户自己的未跟踪笔记外不应有改动，有则先问用户。
2. `git tag career-v0-baseline 65c99c7`；`git switch -c feat/llm-backend-hardening`。
3. 提交本文件与状态文件；在旧计划顶部加一行指向本文件（不改旧正文）。提交标题：`docs: adopt backend hardening sprint plan`。
4. 跑一次 `bash scripts/validate-agentic-research-p7.sh`，把通过数记入状态文件，作为后续回归的对照。

## 5. 工作项

每个工作项的固定收尾：受影响回归通过 → 按 `docs/iron-ore-rag/changes/` 的既有格式写一篇改动说明（发现证据、根因、方案取舍、实现、效果、限制、回滚；≤ 120 行）并更新 `changes/README.md` 索引 → 更新状态文件 → 打标签。

### W1 成本与延迟：缓存友好的上下文布局

**真实问题**：Agent 每一轮都重发整段历史。供应商对命中缓存的输入 token 按折扣计费，首 token 延迟也更低；生产环境的 Agent 把缓存命中率当成第一指标。本项目约 8,200 万输入 token 里命中率只有 5.2% 和 0.02%（E1）。

**成熟参考**：Manus《Context Engineering for AI Agents》（保持前缀稳定、上下文只追加、可恢复的压缩）；OpenAI 与 Anthropic 的 prompt caching 指南（静态内容在前、动态内容在后；显式缓存断点）；百炼上下文缓存文档；Claude Code 到阈值才整体压缩而不是每轮滑动。

**设计要点**

1. **稳定前缀**：第一条系统消息只放静态提示词，逐字节稳定，不含次数、ID、时间。会变的提醒改为**每次调用临时加在消息列表末尾**（不写入 Agent 记忆）。百炼兼容端点对第二条 system 不可靠，所以用 user 角色追加，或拼到最后一条工具结果的副本末尾——先用 3—5 次真实调用做协议探针再定。主 Agent 与 worker 同样处理。
2. **只追加的历史**：首条用户消息的序列化改为确定顺序（`ResearchAgentFactory.run` 里的 `Map.of` 换成有序 Map）；工具结果的 JSON 已是有序 Map，保持。
3. **阈值压缩取代滑动裁剪**：超过 `max-input-tokens` 时一次压到预算的约 60%，之后保持稳定直到下次越线。压缩先把最早的工具结果正文替换成可恢复的存根（保留证据 ID，提示可用 `read_source` 重读），保留 assistant 的 tool_call 消息以维持协议配对；仍不够再整轮移除。压缩水位保存在模型包装器里，保证后续每次调用得到**同一个**前缀。事件名 `CONTEXT_COMPACTED`，记录压缩前后的估算 token。
4. **度量**：调用台账增加 `durationMs` 与 `firstTokenMs`（流式首块到达）；新增 `eval/agentic-research/cache_report.py`，按模式输出调用数、输入、命中、命中率、按折扣折算的计费输入（折扣率从供应商文档读取，做成参数）、按调用序号分组的延迟 P50 / P95。
5. **可选加分**：若 SDK 能透传消息级字段，在稳定前缀末尾加显式缓存断点。

**任务清单**

- [ ] 读供应商当前文档，确认隐式 / 显式缓存的最小前缀长度、粒度、有效期与计费；做协议探针，把结论写进状态文件。顺带查明 C 模式为何接近 0。
- [ ] 改 `BoundedResearchModel.attempt()` 的提醒位置与 `trim()`；更新受影响的协议测试（`ResearchNativeToolsTest`、`ResearchWorkerNativeTest`、`ResearchBudgetTest` 等），新增：前缀逐字节稳定、压缩后前缀稳定、提醒不进入记忆、压缩保持 tool_call 配对。
- [ ] 台账字段与 `cache_report.py`，并补 Python 单测。
- [ ] 实验 X1（见 §7）。

**验收**：协议回归全绿；X1 报告给出缓存命中率、单任务计费输入、单次调用延迟的前后对比；完成率与答案 F1 作为护栏如实列出，不设门槛。

**风险**：提醒移到末尾可能改变模型行为——先跑 6 个任务的 smoke；隐式缓存命中本身有不确定性——报告命中率的分布而不只是均值。

**提交**：`perf(research): keep agent context prefix stable for prompt caching`；`feat(eval): report prompt-cache hit ratio and call latency`；改动说明；标签 `career-w1`。

### W3 不稳定上游的治理：故障注入基准

**真实问题**：第三方模型与 embedding 接口会超时、限流、无响应。治理已经做了（E3），但只有事故叙述，没有“在多大故障率下系统表现如何”的数字；也没有一个不花接口费、可反复跑的上游替身——W2 同样需要它。

**成熟参考**：Resilience4j（超时、重试、熔断、舱壁）；AWS Builders' Library《Timeouts, retries, and backoff with jitter》；Google SRE《Addressing Cascading Failures》（截止时间传播、重试预算）；《The Tail at Scale》（对冲请求）；LiteLLM Router（失败冷却与回退）。

**设计要点**

1. **模拟上游** `eval/agentic-research/stub_upstream.py`（独立进程，OpenAI 兼容）：
   - `/v1/chat/completions`：流式返回，按场景脚本吐出原生 tool_call 序列（检索、阅读、再检索、结束；含一个会委派 worker 的场景）；
   - `/v1/embeddings`：文本哈希映射为确定性的 1536 维单位向量，查询与文档标题完全相同时必然命中第一；
   - 故障旋钮：无响应比例、429 / 5xx 比例、延迟分布、流中途断开。
2. **接入方式**：新增 Spring profile `stub`（`application-stub.yaml`），只在其中声明 `stub` 供应商与候选模型，不动主配置；配一个极小的固定语料。可参考测试夹具 `ResearchBrowserFixture` 的脚本化模型写法。
3. **小补缺**：重试加带抖动的指数退避；确认研究路径的 embedding 失败进入已有的候选熔断统计。不再扩大范围。

**任务清单**

- [ ] 模拟上游、`stub` profile、固定小语料与一条命令的启动脚本。
- [ ] 实验 X3（见 §7）：治理前（P7 提交 `3381fa9`，用临时工作树构建）对 治理后（当前）。旧提交若无法接入模拟上游，退而在当前代码上用配置还原旧行为（三层同为 30 s、不重试、失败当空结果），并在报告里注明。
- [ ] 抖动退避的实现与测试。

**验收**：X3 报告给出 0 / 10% / 30% / 50% 无响应率下的终态分布、任务成功率、错误完成率（上游故障却以零证据 COMPLETED）、P50 / P95 耗时、浪费的模型调用数。数字注明“基于模拟上游”。

**提交**：`test(research): add scriptable upstream stub with fault injection`；`fix(infra): jittered backoff for retryable upstream failures`；改动说明；标签 `career-w3`。

### W2 长任务的持久化执行

**真实问题**：分钟级任务遇到发版、宕机、扩缩容就丢；而当前实现在多实例下更糟——任何实例启动或停止都会把其他实例的健康任务标为中断（E2）。

**成熟参考**：db-scheduler 与 Quartz JDBC 集群（心跳、死亡执行检测）；PostgreSQL `SELECT … FOR UPDATE SKIP LOCKED` 作业队列；Kubernetes Lease 续租；Temporal 的活动心跳与事件历史；LangGraph checkpointer（按步持久化、从最近检查点恢复）；Kleppmann 的 fencing token；Spring Boot 优雅停机。

**设计要点**

1. **先止血**：删除启动与关闭时的全库 `interruptOrphans()`。启动不做破坏性动作；过期租约交给轮询者接管。
2. **执行者身份与心跳租约**：每个实例启动时生成 `executor_id`；租约改为短租约（约 30 s）加定时续租（约每 10 s，条件为 run id、epoch、lease_token 同时匹配）；续租失败说明已被接管，立即取消本地执行。现有的 epoch 与 lease_token 写保护全部保留。
3. **数据库即队列**：每个实例定时轮询 `status = 'QUEUED'` 或“RUNNING 且租约已过期”的任务，`FOR UPDATE SKIP LOCKED`，数量受本地空闲槽位限制；创建时仍先走本地快速调度。本地队列满不再让任务失败，任务留在库里等待。
4. **断点续跑**：接管后由持久事件重建上下文——按序读取主任务的 `TOOL_STARTED`（参数）与 `TOOL_ENDED`（输出）成对还原 assistant 的 tool_call 消息和工具结果消息，预载进 Agent 记忆；`ResearchSession` 的已送达证据从 `t_research_evidence` 恢复，检索计数从事件恢复，预算沿用已持久化的 usage（`ResearchBudget` 已支持），worker 结果沿用已有的 `restoreResults`。没有配对完成的那一步丢弃并重做——语义是至少一次；检索与阅读是只读的，证据保存按稳定 ID 幂等，所以安全。崩溃时在途的 worker 记为中断，由主 Agent 在预算内决定是否重派；已完成的 worker 不重跑。若 SDK 不允许预载记忆，退化为沿用“等待用户输入后恢复”的摘要式恢复，并让已读证据免重读即可引用。
5. **接管身份**：由 `owner_user_id` 从用户表重建 `LoginUser`。
6. **毒任务保护**：`attempt_count` 超过 3 次接管即 FAILED（`EXECUTOR_LOST`）。INTERRUPTED 状态只为读取历史数据保留。
7. **优雅停机**：`server.shutdown=graceful`；停止轮询；在途任务在步边界释放租约（`lease_until = now()`）让其他实例立即接管，超过宽限期再取消。
8. **迁移**：`resources/database/upgrades/v1.1.0/2609xx_01_research_durable_execution.sql`（`executor_id`、`attempt_count`、轮询索引），同步 `schema_pg.sql`；沿用“新建库与升级库结构一致”的校验脚本做法。

**任务清单**

- [ ] 第 1 项单独成一个提交，并补“实例 B 启动不影响实例 A 的运行中任务”的回归。
- [ ] 心跳续租、轮询接管、身份重建、毒任务保护；更新 `ResearchRunPostgresIT` 中依赖旧中断语义的用例。
- [ ] 事件重建上下文的续跑；先核对 AgentScope 2.0.1 是否支持预载记忆。
- [ ] 优雅停机。
- [ ] 故障场景测试 T1—T5（见 §7 的 X2），用 W3 的模拟上游，零接口费；另写 `scripts/career-takeover-demo.sh` 起两个实例做可演示的版本。

**验收**：X2 的五个场景全部通过，报告恢复时间、重复执行的模型调用数（期望不超过在途的那一次）、无重复产物、事件序号连续、旧执行者的迟到写入被拒。

**提交**：`fix(research): stop interrupting other instances' runs on startup and shutdown`；`feat(research): heartbeat leases and database-backed takeover`；`feat(research): resume runs from persisted tool history`；`feat(research): graceful shutdown hands runs over`；`test(research): crash, pause and shutdown takeover scenarios`；改动说明；标签 `career-w2`。

### W4 企业资料的权限隔离

**真实问题**：企业知识库的第一条安全需求是不同角色看到不同资料；一旦出错就是数据泄露。当前全局共享（E4）。

**成熟参考**：Dify 的知识库可见性（仅自己、全部成员、指定成员）；Azure AI Search 的 security trimming（查询时按身份过滤）；Glean 与 Microsoft 365 Copilot 的权限感知检索；Elasticsearch 文档级安全。

**设计要点**

1. 知识库可见性 `PUBLIC / PRIVATE / RESTRICTED` 加授权表 `t_knowledge_base_grant(kb_id, subject_type USER|ROLE, subject_id, permission READ|MANAGE)`。存量库迁移为 PUBLIC（行为不变），新建库默认 PRIVATE；admin 拥有全部权限。
2. `KnowledgeAccessService.accessibleKbIds(user, permission)` 作为唯一判定入口，落点：知识库与文档管理接口（MANAGE）；普通问答的检索范围解析——**在召回之前**与可访问集合求交，意图树绑定的知识库同样求交；研究任务的 `validateScope`；来源预览与下载。
3. 归属校验：`/rag/v3/stop` 校验任务属于当前用户。
4. 泄露通道清点并写进改动说明：Redis 缓存键、会话记忆、Trace 与评测接口、SSE 事件、W5 的 embedding 缓存（只按内容寻址，不携带也不返回访问结论）。
5. 前端只在知识库表单加可见性字段，可砍。

**验收**：越权矩阵测试（用户 A、用户 B、admin 乘以 知识库增删改查、文档上传 / 列表 / 预览、问答检索、研究创建 / 检索 / 阅读 / 来源、停止）全部符合预期；PostgreSQL 集成测试证明：最佳匹配位于不可访问知识库时，结果中没有来自该库的块，且过滤发生在召回之前。

**提交**：`feat(knowledge): knowledge-base visibility and grants`；`fix(rag): verify task ownership on stop`；`test(security): cross-user access matrix`；改动说明；标签 `career-w4`。

### W5 增量重建：内容寻址的 embedding 复用

**真实问题**：文档改一行，全量重新向量化，更新慢且重复花钱（E5）。

**成熟参考**：LangChain Indexing API 与 RecordManager（按内容哈希跳过未变内容）；LlamaIndex IngestionPipeline 的 docstore 去重；`CacheBackedEmbeddings`。

**设计要点**

1. 表 `t_embedding_cache(model_id, dimension, text_sha256, vector, create_time, last_used)`，与块写入同库同事务。在 `ChunkEmbeddingService` 调 `embedBatch` 前按哈希查表，只把未命中的送上游，返回后回写。键必须包含模型与维度——向量不能跨模型复用，与“embedding 不能跨模型降级”是同一条规则。
2. 容量上限加按 `last_used` 淘汰；命中与未命中计数进入摄取日志。
3. 核对并写明版本切换的可见性：pgvector 与块表同库，先删后插若在同一事务内则检索不会读到半成品；不是的话改为同事务。

**验收**（实验 X5）：同一文档重复入库，上游 embedding 调用为 0；调研表 V1.2 升级到 V1.3（`local-data/source/`）时报告重嵌入的块比例、耗时与节省的 token。

**提交**：`feat(ingest): reuse embeddings by content hash when re-indexing`；改动说明；标签 `career-w5`。

## 6. 既有功能的处置

| 功能 | 处置 |
| --- | --- |
| 研究 Agent（检索、阅读、追问、结束） | 保留并冻结功能；作为 W1—W3 的被优化负载 |
| 多 Agent 委派 | **保留、冻结**，不论证收益。它的后端内容仍可讲：上下文隔离、父子线程池分离避免互等、共享预算原子分配、取消扇出。W1 让 worker 的上下文同样缓存友好；W2 保证已完成的 worker 在接管后不重跑 |
| 三个聊天入口、REPORT / PLAN 产物、前端 | 保留、不投入 |
| QASPER / MuSiQue 评测工具 | 保留；只作为固定负载与护栏指标，不再追分数 |
| 表格解析、检索纯度 | 保留；简历中降为领域适配的一句话 |
| 旧计划与执行记录 | 已由 `slim-v0-baseline` 标签留存（`git show slim-v0-baseline:<路径>`） |

## 7. 实验与留档

| 实验 | 负载与对照 | 产出 |
| --- | --- | --- |
| X1 缓存 | 固定 40 题（QASPER 与 MuSiQue 的 `queries.regression.jsonl` 各取前 20 条，ID 存入 `eval/agentic-research/manifests/career-cache-case-ids.json`，不读标签）；模式 B 与 C；配置以 `configs/p7.json` 为基础另存（全 Flash、预算不变，便于对照 E1 的历史数字）。前：`career-v0-baseline`（用 `git worktree add` 建临时工作树构建，`--prepared`、`--run-dir`、`--idea` 传绝对路径）；后：W1 完成的提交。同一天交错执行。embedding 失败率若在 smoke 中超过 10%，先按附录切到本地 embedding | 命中率、计费输入、调用延迟、端到端耗时；护栏：完成率、F1 |
| X3 上游 | 50 个脚本化任务 × 无响应率 0 / 10% / 30% / 50% × 治理前后 | 终态分布、成功率、错误完成率、P50 / P95、浪费调用数 |
| X2 接管 | T1 对执行实例 `kill -9`；T2 任务运行中启动新实例；T3 SIGTERM 优雅停机；T4 `SIGSTOP` 超过租约后 `SIGCONT`（验证旧执行者被 fencing）；T5 连续崩溃的毒任务 | 每个场景的不变量：恰好一个终态产物、事件序号连续、已完成步骤不重做、迟到写入被拒、恢复时间 |
| X5 增量 | 重复入库；V1.2 到 V1.3 | 上游调用数、重嵌入比例、耗时 |

留档从简：提交号、配置文件、固定题目 ID、逐题输出、评分 / 汇总 JSON。每个实验的精简汇总存 `eval/agentic-research/manifests/career-<实验>-<日期>.json` 并提交；原始数据留在被忽略的 `local-data/`。不再生成逐文件 SHA 清单和静态链接检查。**数字只写实测值；失败计入分母；模拟上游得到的数字必须注明；前后对比必须是同一负载。**

## 8. Git 规范

- 分支 `feat/llm-backend-hardening`，自 `65c99c7` 拉出；基线标签 `career-v0-baseline`；每个工作项结束打 `career-w<n>`，全部结束打 `career-done`。
- 提交信息沿用仓库现有的英文 Conventional Commits（`feat / fix / perf / test / docs`），一个提交只做一件事；数据库迁移、实现、测试可以分开提交，但每个提交都应能编译。
- 只 `git add -- <明确路径>`，不用 `git add .`；提交前看 `git diff --cached --stat`。
- 不 push、不合并、不 rebase、不 amend 既有历史，除非用户要求。
- 不使用裸 `git stash`（stash 栈与其他工作树共享）；需要暂存用临时 WIP 提交。
- 对照旧版本用 `git worktree add <临时目录> <标签或提交>`，用完 `git worktree remove`；不在主工作树来回切分支。
- 历史问题用 `git log --oneline -- <路径>`、`git show <提交>:<路径>` 定点查，不重读旧文档。

## 9. 风险

| 风险 | 处理 |
| --- | --- |
| 供应商隐式缓存不稳定，前后差异不明显 | 报告分布；尝试显式缓存断点；即使幅度有限，“发现、定位、修复、度量”的过程本身成立，如实写 |
| AgentScope 不支持预载记忆 | 用 W2 第 4 项写明的退化方案 |
| 旧提交无法接入模拟上游 | 用 W3 写明的配置还原方案，并在报告注明 |
| 外部 embedding 再次大面积无响应，影响 X1 | 见附录：本机 RTX 2080 跑本地 embedding，只重嵌入 X1 用到的语料 |
| 时间超支 | 从后往前砍：W5、W4；底线 S0 + W1 + W3 + W2 |
| 改动波及普通问答、摄取、版本比较 | 每个工作项收尾都跑 §3 的完整回归 |

## 10. S6：简历与改动说明（0.5 天）

五篇改动说明已随各工作项完成；S6 汇总为简历条目（尖括号只填实测值，连同口径一起说）：

- 发现 Agent 多轮调用的提示缓存命中率仅 5.2%（多 Agent 模式 0.02%），定位到易变提醒写入首条系统消息与滑动裁剪破坏前缀；改为稳定前缀、只追加历史、阈值压缩后，命中率 `<…>`，单任务输入成本 `<…>`，单次调用延迟 `<…>`。
- 长任务持久化执行：心跳租约与 epoch 写保护、数据库队列跨实例接管、由事件日志断点续跑、优雅停机；`kill -9`、进程暂停、滚动发版等 5 类故障注入下任务不丢、产物不重复，恢复时间 `<…>`，重复模型调用 `<…>`。修复了多实例下实例启停误杀其他实例任务的缺陷。
- 不稳定上游治理：分层截止时间、失败与无命中分离、带抖动的有限重试、取消传播、连续失败停止补查；模拟上游 30% 无响应时任务成功率 `<…>` 到 `<…>`，错误完成率 `<…>` 到 `<…>`。
- 知识库权限隔离：可见性与授权、召回前过滤、任务归属校验，越权矩阵 `<n>` 项全部通过。
- 内容寻址的 embedding 复用：重复入库零上游调用，版本升级重嵌入比例 `<…>`。

已有、可直接使用的条目：研究任务的幂等创建与状态机、父子线程池分离与共享预算原子分配、SSE 按事件序号断线续传、12.3 万文档 / 18.3 万向量的幂等批量导入。

同步更新仓库 `README.md` 的“工程能力”表，并按用户 Obsidian 的既有格式整理面试卡。

## 11. 会话纪律

- 一个会话只做一个工作项；开始先读状态文件，结束更新它（当前工作项、已完成的勾选项、下一步、最近的运行目录、遗留问题；≤ 40 行）。
- 超过几分钟的运行由用户在终端用 `nohup … --resume` 后台启动；编码 Agent 不等待、不轮询，只读结果文件。
- 新会话开场指令：`读 docs/iron-ore-rag/career-sprint-plan-2026-09-18.md 与 career-sprint-status.md，继续当前工作项；不要读取旧计划与执行记录。`

## 附录：本地 embedding（仅在 X1 受外部 embedding 影响时启用）

本机 RTX 2080（8 GB）。Ollama 跑 `qwen3-embedding:4b`（量化约 2.5 GB，可截到 1536 维，表结构不变）；拉取慢时用 ModelScope 的 GGUF，或用 sentence-transformers 起一个 `/v1/embeddings` 小服务。配置里已有 `qwen-emb-local` 候选与 `OllamaEmbeddingClient`，客户端会校验返回维度。只把 X1 的 40 题涉及的文档重嵌入到独立库，用 `import_corpus.py` 导入；评测必须显式固定 embedding 模型，禁止跨模型降级。限时半天。
