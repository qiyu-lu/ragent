# 统一研究工作流验证报告

最新 R1—R5 改进状态见[会话交接](agentic-research-resume-2026-09-18.md)和[R5 清单](../../eval/agentic-research/manifests/research-r5-validation-2026-09-18.json)。本轮唯一相关后端 213 项、Python 30 项、前端流恢复 4 项与浏览器夹具 9 项通过；构建通过，app 原有 24 项类型诊断保留。R5 主批仅 22/1200、复跑与应用未开始，程序检查通过不能代替整体质量验收。以下 P0—P8 数字保留为历史快照。

日期：2026-09-18。P0—P8 已完成实现与本轮约定验证。固定 regression 1200 任务全部记录，24 个应用/85 条引用及两例复测保留负结果；P8 真实演示 4 请求/10 引用已核对。当前后端 173/173、Python 28/28、新库/重复升级、前端 build、node 类型与 9 项受控浏览器检查通过；app 24 项既有诊断保留。质量见[固定对照](agentic-research-evaluation-report.md)与[原文核对](agentic-research-application-review.md)，启动及失败边界见[交接](agentic-research-handoff.md)，下方历史批次不改写。

## P0 基线

环境与起始提交见[执行记录](agentic-research-execution-log.md)。原始日志保存在本地忽略目录 `local-data/agentic-research/runs/20260917T1429_P0_P1/`；检查记录与 SHA-256 见该目录的 `checks.json`。这批是构建/单元检查，不是数据集问答评测。

```bash
./mvnw -o -pl bootstrap -am -DskipTests package
./mvnw -o -pl bootstrap -am '-Dtest=TaskAgentServiceTest,TaskAgentControllerTest,LlmTaskAgentPlannerTest,RagTaskKnowledgeTest,RobotGatewayClientTest,RobotMissionCompilerTest,TaskTemplateValidatorTest,WorkbookDiffServiceTest,RetrievalEngineTest,MultiChannelRetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest' -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix frontend run build
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.app.json --noEmit
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.node.json --noEmit --tsBuildInfoFile /tmp/agentic-research-p0-p1/tsconfig.node.tsbuildinfo
./mvnw -N dependency:get -Dartifact=io.agentscope:agentscope-core:2.0.1:pom -Dtransitive=false
./mvnw -N dependency:get -Dartifact=io.agentscope:agentscope-extensions-model-openai:2.0.1:pom -Dtransitive=false
```

node 配置首次实际运行未指定 tsBuildInfoFile，生成文件已按 HEAD 恢复；上方命令给出避免工作区污染的复跑方式。单独检查根 tsconfig 不会覆盖 references 中的 app，不能把它的 exit 0 当作前端类型通过。

后端打包通过。相关测试首次因沙箱禁止本地端口，`RobotGatewayClientTest.dispatchesStructuredMissionAndReadsSnapshot` 报 SocketException；允许本地监听后相同集 58 个用例通过，0 失败、0 错误、0 跳过。JDBC 测试使用 H2，模型使用可控响应；未复跑 PostgreSQL 或真实供应商。

Vite 构建通过，约 14 秒；现有 bundle 超过 500 kB 的提示保留。app 严格类型检查失败，共 24 个诊断：

| 文件 | 既有诊断数 | 问题范围 |
| --- | ---: | --- |
| `frontend/src/components/chat/FeedbackButtons.tsx` | 2 | DropdownMenu 属性、隐式 any |
| `frontend/src/pages/admin/ingestion/IngestionPage.tsx` | 1 | metadata Record 类型 |
| `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx` | 12 | 表单 resolver、可选字段、Control 类型 |
| `frontend/src/stores/chatStore.ts` | 9 | Axios 返回类型、隐式 any、code 比较 |

这些诊断来自修改代码之前；P1 按诊断内容对比，不仅比较数量。node 配置类型检查通过。

两个 SDK 正式 POM 从 Maven Central 解析成功，只证明版本坐标可用。P3 还需验证实际依赖图、SDK 类/工具协议、供应商 usage 和取消行为。

## P1 实际验证

```bash
./mvnw -o -pl bootstrap -am -DskipTests clean package
./mvnw -o -pl bootstrap -am '-Dtest=TaskTemplateGeneratorTest,IronOreTaskTemplateServiceTest,TaskTemplateValidatorTest,WorkbookDiffServiceTest,RetrievalEngineTest,MultiChannelRetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,StreamTaskManagerCancelTraceTest' -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix frontend run build
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.app.json --noEmit
./frontend/node_modules/.bin/tsc -p frontend/tsconfig.node.json --noEmit --tsBuildInfoFile /tmp/agentic-research-p0-p1/p1-tsconfig.node.tsbuildinfo
bash scripts/validate-agentic-research-p1-database.sh
./frontend/node_modules/.bin/eslint frontend/src/components/chat/IronOreTaskSection.tsx frontend/src/components/layout/Sidebar.tsx frontend/src/router.tsx frontend/src/services/ironOreService.ts frontend/src/types/index.ts
```

| 检查 | 实际结果与边界 |
| --- | --- |
| 后端 clean package | 通过；清理旧 class 后重新编译/打包，约 13 秒 |
| 10 个定向测试类 | 42/42 通过，0 失败/错误/跳过；模型均为可控响应 |
| 新增草稿/生成器检查 | 11 个用例：正常生成、一次修复、非法引用最终失败、模型错误、按文档过滤、归属拒绝、无精确证据、失败不落库、重复键回查、历史草稿兼容；不检查引用语义支持 |
| 前端最终 Vite build | 通过；保留既有 bundle 大小提示 |
| app 严格类型检查 | 24 个诊断，与 P0 每条诊断完全一致；新增错误为 0 |
| node 类型检查 | 通过，生成信息保存在 `/tmp` |
| 定向 ESLint | 未运行到源码规则：现有 `plugin:react-refresh/recommended` 配置报 `Unexpected top-level property name`；配置、依赖和 lock 未改动 |
| PostgreSQL 16 SQL 检查 | 隔离库通过当前 schema + init_data、无退役表、保留草稿 CRUD；模拟既有环境后退役脚本首次 UPDATE 1、第二次 UPDATE 0，版本比较意图与历史哨兵数据不变；测试库已清理 |
| 源码/JAR/历史脚本检查 | 退役业务引用为 0；clean JAR 无旧 class/提示词，含新生成器；旧测试 SQL 不再打包，三份历史升级脚本字节不变 |
| 当前入口文档链接与 whitespace | 本地文件链接解析、`git diff --check` 通过；历史流程笔记整理在 P8 |

静态检查脚本副本、原始日志和最终检查记录保存在本报告 P0 指定的本地运行目录，`checks.json` 记录文件 hash 与命令。中途的失败输出保留：一个通用图标 import 被误删后恢复；残留扫描的首次 `/tasks` 匹配误包含保留的 `/ingestion/tasks`，已限定完整路由后重新检查，未删除摄取模块。

本轮共留存 22 份检查日志，逐项校验归档 hash 通过；`checks.json` 的 SHA-256 为 `722825f0403a556235a06e7e9f5c0bafa5af0ee4eb3d1b744b583746084be46b`。当前入口文档 68 个本地文件链接解析通过。

本轮 PostgreSQL 是实际 SQL 验证；Java mapper 与 SDK 模型均未对真实业务库/供应商联调。既有业务库的升级脚本和 Redis 缓存清理仍由部署时应用，不能把隔离验证写成已完成部署。

## P2 首批实际验证

范围是数据契约、知识库作用域、单块证据快照和研究表 SQL；该批结束时文档筛选、邻接展开、转换与导入尚未完成。本批原始日志、JUnit XML 和 checks.json 保存在本地忽略目录 `local-data/agentic-research/runs/20260917T072920_P2A/`，没有生成模型问答成绩。

```bash
./mvnw -o -pl bootstrap -am -DskipTests clean package
./mvnw -o -pl bootstrap -am '-Dtest=ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,TaskTemplateGeneratorTest,IronOreTaskTemplateServiceTest,TaskTemplateValidatorTest,StreamTaskManagerCancelTraceTest' -Dsurefire.failIfNoSpecifiedTests=false test
bash -n scripts/validate-agentic-research-p2-database.sh
bash scripts/validate-agentic-research-p2-database.sh
git diff --check
```

| 检查 | 实际结果与边界 |
| --- | --- |
| 后端 clean package | 通过；包含新契约/服务和测试源码编译，未接入 SDK 或新依赖 |
| 首次定向检查 | 3 个类 28/28 通过；用于验证新工具和检索入口 |
| 最终定向回归 | 16 个类 101/101 通过，0 失败/错误/跳过；覆盖新工具与普通问答、召回通道、摄取、版本比较、草稿、取消 |
| 本批新增用例 | 共 23 个：工具 15、store 5、检索引擎 3；包括真实 H2 归属 SELECT/UPDATE，以及可控召回/来源响应 |
| 作用域 | 测试验证服务端范围传入召回上下文、无意图回退/补充扩大、空范围不查询、越界通道结果在重排前拒绝；不代表已有租户文档 ACL |
| 证据 | 检查数据库正文优先、多文档、跨 worker 稳定 ID、运行/版本/正文/块身份分离、截断和代理对、已读状态、虚构 ID、来源停用/变更和 hash 错误；不检查引用语义支持 |
| PostgreSQL 16 隔离 SQL | 当前 schema + init 成功；删除隔离库内研究表后重复增量执行两次，列/默认值/约束与新建一致；历史草稿、请求/事件唯一键、FK、状态约束、归属查询和首次快照保留通过，测试库已清理 |
| SQL 首次失败 | schema 中残留一个补丁前缀字符，修正后相同脚本通过；保留失败/清理日志，不把首次失败改写为通过 |
| 入口文档与历史文件 | 68 个本地文件链接解析、Markdown 围栏、whitespace、脚本语法与历史升级 SQL 字节不变检查通过 |
| 前端、在线 SDK、数据与模型 | 本批未修改前端，未重跑前端 build/type/lint；未注册原生工具、启动研究接口、转换/入库或调用真实供应商 |

该批 PostgreSQL 验证使用合成存储夹具和实际 SQL，没有导入公开语料。Java store 的 SELECT/UPDATE 及结果映射由 H2 验证；当时 Java INSERT ON CONFLICT、来源 MyBatis 读取和 Spring 事务仍需 PostgreSQL 联调，第二批结果见下文。示例事件 SQL 只验证存储与序号原语，事件写入器和并发任务运行尚未实现；请求唯一约束也不能代替幂等调度测试。

本批未调用真实 embedding、rerank 或研究模型，没有生成 token/cost/EM/F1 等模型评测指标。未升级已有业务库；部署时仍需应用增量 SQL。

## P2 第二批实际验证

范围是召回前文档筛选、受限邻接读取、首次展开快照复用和实际 Java/PG 联调。起始提交为 `4b8a328`；原始成功/失败日志、普通与 PostgreSQL JUnit、checks.json 和源码 hash 保存在本地忽略目录 `local-data/agentic-research/runs/20260917T075350_P2B/`。

```bash
./mvnw -o -pl bootstrap -am -DskipTests clean package
./mvnw -o -pl bootstrap -am '-Dtest=ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,MilvusVectorRetrieverServiceTest,EsKeywordRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,TaskTemplateGeneratorTest,IronOreTaskTemplateServiceTest,TaskTemplateValidatorTest,StreamTaskManagerCancelTraceTest' -Dsurefire.failIfNoSpecifiedTests=false test
bash -n scripts/validate-agentic-research-p2-database.sh
P2_RUN_JAVA_TESTS=true bash scripts/validate-agentic-research-p2-database.sh
git diff --check
```

| 检查 | 实际结果与边界 |
| --- | --- |
| 后端 clean package | 通过，包含全部测试源码编译；本批未引入 SDK 或新依赖 |
| 最终定向回归 | 18 个类 123/123 通过，0 失败/错误/跳过；比首批增加 22 个普通回归用例 |
| 文档限制 | 验证空工具参数继承 Brief、不能放大范围、无效/停用/错库文档拒绝、召回前条件传递及原始越界结果拒绝；数据库来源再次校验范围 |
| PGVector 过滤 | 真实 PGVector 用较高相似度的其他文档作为干扰：不筛文档时 Top1 为干扰项，筛文档时 Top1 返回目标块；验证参数绑定及真实 docId 映射，不证明 ANN 参数或大规模检索性能 |
| Milvus / ES | SDK 请求捕获验证 JSON doc_id / bool terms 与知识库条件同时存在、TopK 设置及 Milvus 来源映射；过滤语法按[Milvus 官方 JSON 文档](https://milvus.io/docs/json-field-overview.md)核对；未访问实际 Milvus/ES 服务 |
| 邻接边界与快照 | 每侧最多一个块，章节/工作表/原始段落、资料类型和文档版本边界检查；没有可靠分组时回退原块；返回真实位置、新 evidenceId 与 requestedEvidenceId，首次展开复用，变更标记 CHANGED，停用拒绝 |
| PostgreSQL 16 结构 | schema + init、两份研究升级 SQL 各执行两次成功；新建与升级的列/默认值/约束/索引一致，历史草稿哨兵保留，测试库清理成功 |
| Java / PG 集成 | `ResearchEvidencePostgresIT` 7/7 通过，0 失败/错误/跳过：实际 INSERT/SELECT/UPDATE、owner/run 边界、两写者并发展开唯一性、实际向量过滤、MyBatis 搜索/邻接读取及变更/停用路径 |
| 来源事务 | 在实际 MyBatis StatementHandler 读取连接属性，验证 neighbors/loadAll 的只读 REPEATABLE READ；断言仅统计来源读取，不把事务外的范围列表查询混入 |
| 保留的失败 | 测试辅助方法参数、checked exception 声明、Mockito 对 final SDK API 的测试构造问题均修正；首次 PG 7 个用例中事务探针断言 1 失败，限定观察范围后相同集 7/7 通过；日志与首次 PG JUnit 保留 |
| 静态检查 | 修改入口文档的本地链接/围栏、所有本批文件 whitespace、脚本语法与旧升级 SQL 字节不变检查通过 |
| 在线业务与模型 | 未修改前端、未启动全套应用/浏览器、未注册原生工具或调用供应商；使用合成正文/向量，没有公开语料导入、模型 token/cost 或 EM/F1 等效果指标 |

真实 PostgreSQL 测试由脚本随机创建本机隔离库并最终删除，只接受该类测试 URL，凭证只通过临时子进程环境传递，日志不包含密钥。Java/PG 通过补足首批的存储与来源回查验证，但不能代替真实语料摄取、运行器取消/epoch 保护、事件并发分配、SDK 工具协议或最终引用语义测试。旧业务库未应用本批升级。

## P2 第三批实际验证

范围为离线数据转换、字段隔离与固定样本，没有模型或摄取调用。起始提交为 `365d3e4`；[工具说明](../../eval/agentic-research/README.md)、[紧凑清单](../../eval/agentic-research/manifests/prepared-development-2026-09-17.json)纳入 Git。原始成功/失败日志、完整 manifests、重跑证明及源码 hash 位于本地忽略目录 `local-data/agentic-research/runs/20260917T082228_P2C/`，真实产物在 `local-data/agentic-research/prepared/research-data-v1/`。

```bash
python3 -m unittest discover -s eval/agentic-research/tests -v
python3 eval/agentic-research/prepare_dataset.py --dataset qasper --split train --output local-data/agentic-research/prepared/research-data-v1/qasper-train
python3 eval/agentic-research/prepare_dataset.py --dataset qasper --split validation --output local-data/agentic-research/prepared/research-data-v1/qasper-validation
python3 eval/agentic-research/prepare_dataset.py --dataset musique --split train --variant full --retrieval-mode distractor --output local-data/agentic-research/prepared/research-data-v1/musique-train
python3 eval/agentic-research/prepare_dataset.py --dataset musique --split dev --variant full --retrieval-mode distractor --output local-data/agentic-research/prepared/research-data-v1/musique-dev
python3 eval/agentic-research/verify_prepared.py --prepared local-data/agentic-research/prepared/research-data-v1/qasper-train --data-root local-data/agentic-research
python3 eval/agentic-research/verify_prepared.py --prepared local-data/agentic-research/prepared/research-data-v1/qasper-validation --data-root local-data/agentic-research
python3 eval/agentic-research/verify_prepared.py --prepared local-data/agentic-research/prepared/research-data-v1/musique-train --data-root local-data/agentic-research
python3 eval/agentic-research/verify_prepared.py --prepared local-data/agentic-research/prepared/research-data-v1/musique-dev --data-root local-data/agentic-research
git diff --check
```

上述目录已生成，复跑应指定新目录，工具拒绝覆盖。真实重跑命令为：

```bash
python3 eval/agentic-research/prepare_dataset.py --dataset qasper --split validation --output local-data/agentic-research/prepared/p2c-reproduced/qasper-validation
python3 eval/agentic-research/prepare_dataset.py --dataset musique --split dev --retrieval-mode distractor --output local-data/agentic-research/prepared/p2c-reproduced/musique-dev
```

| 数据及范围 | 原资料规模 | corpus 来源段落 | 问题记录 | 原始问题 ID |
| --- | --- | --- | --- | --- |
| QASPER train / paper | 888 篇 | 47,770 | 2,593 | 2,593 |
| QASPER validation / paper | 281 篇 | 13,547 | 1,005 | 1,005 |
| MuSiQue Full train / distractor | 可用段落候选 | 95,125（去重） | 39,876 | 19,938 |
| MuSiQue Full dev / distractor | 可用段落候选 | 26,326（去重） | 4,834 | 2,417 |

| 检查 | 实际结果与边界 |
| --- | --- |
| Python 环境 | Python 3.8.10 / PyArrow 17.0.0；复用已安装依赖，未新增在线服务 |
| 单元回归 | 16/16 通过；真实 Parquet 合成夹具及 JSONL，不调用 API。涵盖 HF/native 形状、成对行、去重、gold 不影响语料/请求/样本、作用域、隐藏标签、坏数据/引用/样本/指纹和覆盖拒绝 |
| 真实完整校验 | 四个 split 全部通过，遍历完整 corpus/questions/queries，验证来源 ID/hash、引用、gold-free 投影、20/200 样本与原始文件指纹；没有只用 smoke 代替全量文件校验 |
| 固定样本 | seed=20260917，每个 split 20 条 smoke / 200 条 regression，smoke 为其前缀；MuSiQue 两个 regression 各 200 行分别对应 train 199 / dev 196 个原问题 ID，不称 200 个独立问题组；不保证完整成对样本，不能直接冒用 Full 成对指标 |
| 实际重跑 | QASPER validation 与 MuSiQue dev 另存新目录，7 个数据文件及 manifest 均逐字节相同；reproducibility.json 保留证明 |
| QASPER gold 边界 | 1,133 个空段落跳过且保留原下标；train/validation 分别有 533/382 个未解析 evidence 标注，原文保留 unresolved。保留各标注者分歧，不补造 caption 对应的表格正文 |
| MuSiQue gold 边界 | Full 正/负版本共享原始问题 ID，转换用候选上下文区分。distractor 仅使用原候选范围；pooled-context 可能补回负例支持，不直接沿用原不可回答标签声称效果 |
| 首次失败 | MuSiQue duplicate question ID 失败保留，修正后完整转换/校验通过；测试模块同名导入遮蔽失败保留，改名及路径调整后 16/16 通过 |
| 静态与来源 | 新 Python 语法、入口文档链接/围栏、whitespace、紧凑清单与当前转换源码/原始 manifests 一致检查通过；历史 SQL/Java/前端及旧评测源码保持不变 |
| 在线业务、模型与成绩 | 无知识库入库、向量生成、供应商调用或浏览器 E2E；真实 test 未转换，模型调用 0，不生成 token/cost/EM/F1 指标。未改 Java/SQL/前端，其 123 个回归与 7 个 PG 用例沿用第二批历史证据，本批不重复声称复跑 |

当前输出是来源段落，不是实际分块/向量数；离线完整 SHA-256 ID 也不是数据库 20 字符主键。下一步须补实际 metadata 摄取与 source/document → docId/chunkId 映射。直接调用现有上传接口不证明 paper/paragraph 身份已进入 chunk；重复章节名须结合 section_index，QASPER 正文还原须按原始下标，不能按 hash 行序。转换通过不代替真实入库或 SDK 工具/引用验证。

## P2 第四批：来源摄取与真实批次

本批实现及命令见[执行记录](agentic-research-execution-log.md#p2-第四批来源摄取幂等导入与真实联调2026-09-17)和[导入说明](../../eval/agentic-research/README.md#真实摄取)。实现提交 `4b06f21`；完整语料导入及最终数据库审计通过，P2 已完成。小批联调、程序回归和完整批次分别记录。

| 验证 | 实际结果 |
| --- | --- |
| 后端构建 | package 通过；没有 SDK/前端修改 |
| 普通 Java 回归 | 原有 18 类 123/123 通过；新增 EmbeddingUsageCaptureTest 3/3 通过，实际供应商用量/unknown、观察者恢复、HTTP 200 坏响应留失败 |
| Python | 19/19，通过完整源顺序重建、独立摘录和输入幂等；既有转换/字段隔离测试仍通过 |
| 实际 PostgreSQL | 11/11，实际 JdbcTemplate/MyBatis/PG sink 与短事务，包括已提交 identity 复用、配置变更拒绝、来源字段/同名章节、同标题 MuSiQue 独立摘录、provider/index 故障与回滚后重入、非默认预算的标准配置编解码；用例向量为固定夹具，真实供应商联调另列 |
| SQL | fresh 与重复 02/03/04 升级的列/约束/索引一致；旧草稿保留；仅随机测试库被清理 |
| 小批真实数据 | QASPER validation 固定 20 query 覆盖 17 篇/801 来源段落；MuSiQue Full dev 20 query 覆盖 392 摘录；实际 embedding 为现有 SiliconFlow 模型 1536 维，使用实际库 search/read 验证正文/位置/extent |
| 续入 | 已提交 QASPER 801 个块保留真实 doc/chunk ID，不重复向量化；批次完成状态来自 DB 映射，progress 不作为恢复依据 |
| 失败保留 | 初次网络超时、时间填充接线遗漏、停止旧顺序导入；修正后回归和真实续入通过，不把失败费用记为 0 |
| 完整批次 | 四个完整训练/开发 split 全部完成，共 122,620 文档 / 182,768 来源段落 / 182,896 块及向量；逐 split 三条真实 query 的 search/read 与两项拒绝检查通过 |
| 最终库存与身份审计 | 可读文档、来源映射、mapping 逐行/去重数量、正文 SHA-256、版本/extent/原定位及向量一致，答案字段隔离，异常均为 0 |
| 标准摄取配置 | 当前代码使用已有 IngestionSpecCodec；worker 全部退出后规范化旧命令写入的 123,029 个 corpus spec（含 smoke），v2/fast/1024/128 审计异常为 0，正文及索引身份不变 |
| 最终静态检查 | 58 个本地链接/锚点、Markdown 围栏、whitespace、全部清单产物 hash 通过；15 个历史升级 SQL 与起始提交逐字节一致；日志指纹见完整批次 validation/checks.json |

完整库存按 split 为 QASPER train 888 文档 / 47,770 来源段落 / 47,877 块，validation 281 / 13,547 / 13,568；MuSiQue Full train 95,125 独立摘录、dev 26,326 独立摘录，各自块数等于摘录数。源段落数、分块数与问答行数采用不同口径。

完整批次留档 5,884 个 embedding 请求，已知供应商 total_tokens 合计 19,958,704；4 个返回失败及 24 个停止旧命令留下的无结束帧请求保持 usage unknown。小批及超时连通性探测另列。没有问答生成调用、答案/引用质量评分或货币结算结果。

原始路径：`local-data/agentic-research/runs/20260917T084500_P2D_smoke/` 和 `local-data/agentic-research/runs/20260917T085000_P2D_full/`。每份 split 保留 input/job、documents、mapping、progress/complete、corpus-audit、source-probes、usage、traces 和 Java 日志；完整批次输入的源清单/代码 SHA 在 run/invocations 中保存。[导入清单](../../eval/agentic-research/manifests/imported-development-2026-09-17.json)保留数量、KB ID、逐产物 SHA 和执行源码与最终配置编码的差异处理。逐请求日志按 call_id 合并，STARTED 没有 COMPLETED 的记录保留未知状态，不重复累加 token。金额以实际账单核对，不能把 token 记录当结算账单。

## P3：原生工具与单研究运行验证

起始提交 `0c2ae30`，分支 `feat/agentic-research`。SDK 正式版本为 2.0.1，API 核对源码 tag `v2.0.1` 的 commit `51d10ecfddadc45fb2173ff161e40e7bcf48d0be`。[联调清单](../../eval/agentic-research/manifests/research-p3-smoke-2026-09-17.json)纳入 Git；长日志、JUnit XML、逐请求记录与失败样例保存在本地忽略目录。阶段提交通过标题 `feat: implement bounded research runs with native tool calls` 定位。

```bash
./mvnw -o -pl bootstrap -am -DskipTests clean package
./mvnw -o -pl bootstrap dependency:tree -Dverbose -Dincludes=io.agentscope:*,io.projectreactor:*,com.fasterxml.jackson.core:*,org.slf4j:*,org.xerial:*
P3_TESTS=ResearchRunPostgresIT,ResearchBudgetTest,ResearchNativeToolsTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,MilvusVectorRetrieverServiceTest,EsKeywordRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,TaskTemplateGeneratorTest,IronOreTaskTemplateServiceTest,TaskTemplateValidatorTest,StreamTaskManagerCancelTraceTest bash scripts/validate-agentic-research-p3.sh
python3 eval/agentic-research/smoke_research.py --run-dir local-data/agentic-research/runs/<new-id> --execute
python3 eval/agentic-research/smoke_research.py --run-dir local-data/agentic-research/runs/<new-id> --case waiting-and-resume --execute
git diff --check
```

实际供应商调用仅由后两条 Python 命令触发；程序回归使用 PostgreSQL 隔离库与本地 HTTP 桩。Maven 构建和测试顺序执行，不共享并行 clean 目标。最终构建/回归与历次失败日志位于 `local-data/agentic-research/runs/20260917T112600_P3_validation/`，文件 hash、源码 hash 和最终静态检查见其 checks.json。

| 验证 | 实际结果与边界 |
| --- | --- |
| 后端 clean package | 通过，Java 17；实际依赖 core/openai-extension 均为 2.0.1 |
| SDK 依赖兼容 | Boot 管理 Reactor 3.7.12、Jackson 2.19.2、SLF4J 2.0.17；SDK 声明的 Reactor 3.8.2 / Jackson 2.21.1 被现有管理版本覆盖。编译、实际 SDK/HTTP 工具测试及本批供应商请求通过，没有整体替换普通问答依赖 |
| 最新定向回归 | 22 个类，149/149，0 失败/错误/跳过；原有检索、摄取、草稿、版本比较与取消检查仍通过 |
| 新增 P3 用例 | 26 个：PostgreSQL 9、实际 SDK/HTTP 11、预算 3、接口 3 |
| PostgreSQL 16 / Java 短事务 | 随机研究库，JdbcTemplate 实际 INSERT/行锁/JSONB/UPDATE；20 次并发重复请求只建一条 run，活动租约不重复领取，过期租约旧 epoch 拒绝，事件并发编号连续；10 轮真实完成/取消竞争、跨用户、revision、重启状态与异步服务调度通过。模型/工具执行时无数据库事务 |
| SDK 原生协议 / 本地 HTTP | 四个 schema、tool_call_id 与 TOOL result 匹配；依赖补查、坏参数原生错误及修复、未读引用拒绝及补读、ask_user、拒绝纯文本 JSON 仿冒、整组上下文裁剪、最后两次原生结束、取消订阅与超时后模型并发额度释放通过。它们证明程序机制，不计为模型效果 |
| REST 接口 | standalone MockMvc 校验创建参数、只读查询/事件、revision 输入与幂等取消；未启动全套 Spring 应用或浏览器 |
| 预算 / 恢复 | 16 次模型 / 24 次工具、300 秒累计活动时间，模型 60 秒 / 工具 30 秒，预留 2 次最终生成；输入恢复保留调用历史/usage，人工等待不计活动时间；耗尽时有已读证据则 PARTIAL，无证据则 FAILED |
| 取消边界 | 数据库 CANCELLED 先落，epoch 撤销后再取消 SDK/HTTP；迟到事件/结果不能覆盖终态。CANCEL_REQUESTED 与 LOCAL_EXECUTION_ENDED 分开，远端计算/计费停止未获供应商确认，保持 unknown |
| schema / 前端 | P3 未改表或 SQL、未升级既有业务库；无前端改动，本批未重跑其历史构建/类型检查 |

真实联调使用 `qwen3.7-flash-2026-07-15`，thinking 关闭，无静默模型回退。语料仅查 P2 独立 `research_corpus_v1`；运行/证据/事件写入随机 `research_p3_*` 库，结束后均已清理。请求来自 gold-free queries 与明确的缺失条件探测，没有读评分答案、证据标签或 gold decomposition。检索使用现有 query embedding + PGVector，不启用 rerank。

以下 A—E 是开发修复批次名，不是计划中的 A/B/C 架构对照。前四批每批 5 个探测，最后一批只重跑受影响的输入恢复；不是 21 个独立问题。

| 开发批次 | 查找 | 依赖多跳 | 资料不足 | 等待/恢复 | 取消 | 模型请求 / 已知输入 / 输出 token / unknown |
| --- | --- | --- | --- | --- | --- | --- |
| A `20260917T111000_P3_real_A` | COMPLETED | COMPLETED，但无阅读后补查 | COMPLETED | 回复后仍 WAITING_INPUT | CANCELLED | 29 / 210,254 / 5,245 / 1 |
| B `20260917T111500_P3_real_B` | FAILED | FAILED，补查已发生 | FAILED | FAILED | CANCELLED | 24 / 127,377 / 4,474 / 1 |
| C `20260917T111900_P3_real_C` | FAILED | COMPLETED | COMPLETED | COMPLETED | CANCELLED | 34 / 276,369 / 7,702 / 1 |
| D `20260917T112200_P3_real_D` | COMPLETED | COMPLETED，依赖补查 | COMPLETED | FAILED，研究额度耗尽 | CANCELLED | 35 / 261,347 / 4,738 / 1 |
| E `20260917T112600_P3_real_E` | 未重跑 | 未重跑 | 未重跑 | COMPLETED | 未重跑 | 14 / 183,333 / 3,089 / 0 |

原始结果逐批保留：A 候选未读引用被拒绝后模型补读，输入恢复却再次追问；B 将用户回复单独标记为 user_input，但 auto 模式仍输出纯文本，被程序判为失败；C 加入原生 required，D 将额度提示合并到开头的系统消息，查找与依赖补查结束正常。D 的输入恢复读过来源后继续搜索，研究额度耗尽；当时联调命令的统一异常处理未保留部分状态，原始 FAILED 不改写。

最终实现限制最后两次研究调用为原生 finish_research，给同一额度内的引用修复留一次机会；联调命令的预算/超时退出也对齐在线服务的 PARTIAL 与来源保存。E 在最终研究源码下经过 WAITING_INPUT → INPUT_RECEIVED → 原生 finish，形成 3 个已读证据支撑的研究摘要，保留 8 项缺口，共 14 次模型/工具调用，2 次最终生成额度未动用。D 的其余四条路径未在最终源码下重跑，分别记录修复前的实际证据，不声称最终版本整批五题通过。

五批累计 136 个研究模型请求，已知输入 1,058,680 / 输出 25,248 token，4 次取消请求 usage unknown。供应商实际 usage 与输入 token 估算分开，unknown 不当作 0；金额未核对账单。完整 run/job/traces/usage/predictions/embedding-usage/summary/Java 日志与 SHA-256 由联调清单引用，可回放实际工具参数、观察与状态；未保存隐藏推理。

P3 的 COMPLETED 表示 `state.researchResult` 研究摘要经过已读引用身份检查，`artifact` 仍为空；不证明引用语义充分、最终 REPORT/PLAN 生成或业务计划可执行。事件 GET 当前分页 JSON；SSE、聊天页面和多 Agent 仍留后续阶段。

最终静态检查通过：5 份入口 Markdown 的 69 个本地链接/锚点、围栏与全部 P3 文件 whitespace；44 份源码/配置/模板指纹与清单一致，最终 E 批的研究 Java、配置和提示词 hash 与交付源码相同。P3 起始提交的 16 份现有升级 SQL 逐字节不变；其中原始 `a7ef618` 基线的 12 份历史 SQL 也未变。脚本语法与 dry-run 通过，全批/单路径分别只生成 5/1 个请求，不访问 API/数据库。长日志、JUnit 和五批模型产物逐文件 SHA-256 检查通过。

## P3 交付时的未验证项（历史快照）

- 未启动全套服务或浏览器进行登录、普通问答、文档管理、版本比较、草稿生成 E2E。
- 已做 PostgreSQL 隔离库的新建/增量检查和真实公开语料摄取；未升级既有业务库或清除其意图缓存，不自动 DROP 既有数据。
- 已生成训练/开发固定样本并完成小规模真实研究模型联调，未执行 A/B/C、EM/F1 或语义支持评分；真实 test 转换留到最终配置确定后。完整导入不等于完整问答评分。
- Milvus/ES 未做实际服务联调；本次真实索引落点为 PostgreSQL。
- P3 原生工具、单研究运行器及取消/epoch 已验证；P4 并发 worker、P5 最终 REPORT/PLAN 生成与引用映射、P6 研究 SSE/聊天整合尚未完成。

后续每阶段追加实际结果，保留失败和未运行边界，不以单元测试替代真实模型或页面效果。


## P4 实际验证

起始提交 `20b1133`，当前主/worker 模板为 `research-main-v3` / `research-worker-v2`，SDK 2.0.1 不变。实现与重现命令见[执行记录](agentic-research-execution-log.md)和 [eval README](../../eval/agentic-research/README.md)，完整指纹见 [P4 manifest](../../eval/agentic-research/manifests/research-p4-smoke-2026-09-17.json)。本批已获用户付费联调授权。

| 检查 | 实际结果与边界 |
| --- | --- |
| clean 构建、定向回归 | 后端 clean package 通过；最新 24 类 165/165，0 失败/错误/跳过，新增 16 个 P4 用例 |
| 原生 SDK / 本地 HTTP | 2 个 worker 重叠请求，各自 search→read→新 search→read→finish；不同目标/历史隔离，主只见 findings/gaps/conflicts；worker schema 仅 search/read/finish |
| 共享预算与线程池 | worker 专用池，4 个任务分两波；所有角色同一预算与模型配额，最终 2 次生成和一次主整合预留；恢复保留 worker 累计数，重复/非法任务不启动 |
| 故障、迟到与取消 | 一个 HTTP 失败不丢弃成功 findings；worker 超时返回结构化缺口、迟到 callable 不提交；父取消使两个实际本地 SDK HTTP 订阅结束；远端停止仍 unknown |
| PostgreSQL | 父 epoch/归属保护子 checkpoint、终态重复回调拒绝、取消/父提前结束/重启关闭活跃子状态且保留快照；40 个并行事件保持单调全局 usage 和连续序号；人工输入后新 epoch 保留子 findings/读证明/额度 |
| 引用与范围 | worker 的文档筛选在检索前生效，read 再校验子范围；候选未读不能引用；主接收 worker 已验证引用不等于主读过原文；不验证引用语义支持 |
| A 真实开发 probe | 比较/PLAN 各 PARTIAL 14 调用，worker 有未读引用且有限修复失败；串行多跳 COMPLETED 11 并有阅读后新搜索；父取消 CANCELLED 3，两个 worker usage unknown；2 次 embedding 超时保留 |
| B 当前比较/PLAN复测 | worker-v2：COMPLETED 13/14 调用，主分别 4/1 findings；4 个 worker 均 COMPLETED、各 3 个已读 ID。该批没有后续补查，不将它作为补查证明 |
| C 当前补查压力 | 2 个 worker 均先读、再依观察新搜索，并各保留 2 个已读 ID；一名局部额度退出、另一名返回 2 条 findings；主 PARTIAL 保留成功结果与失败 gaps，全局 14 次探索调用、生成预留未使用 |
| 付费用量 | 三批 83 个模型请求记录，已知输入 496671 / 输出 22935 token、2 个取消请求 usage unknown；25 个去重 query embedding 请求、23 个已知 total_tokens 共 196、2 个超时 unknown；未核账单或余额 |
| 构建失败历史 | 缺失 import 和不可变 List 空项检查首次失败均保留；修复后同类测试通过。源码 A 快照保存，B/C 研究源码与当前交付一致 |
| 未执行 | 无 SQL/前端修改，无完整应用/浏览器 E2E、SSE、最终报告/计划生成或 A/B/C、EM/F1、语义评分；COMPLETED 仅研究摘要形成，不表示完整产物或资料充分 |

原始目录：`local-data/agentic-research/runs/20260917T114900_P4_real_A/`、`20260917T115300_P4_real_B/`、`20260917T115900_P4_real_C/`。验证日志和最新 JUnit XML 归档于 `local-data/agentic-research/runs/20260917T120000_P4_validation/`。v1 失败批次不可覆盖；当前 v2 在有新候选时先要求一次原生 read，之后可以补查，有限修复仍可能预算退出，按 PARTIAL 和 gaps 保留真实状态。

最终静态检查通过：5 份入口 Markdown 的 81 个本地链接/锚点、围栏、whitespace、33 个 P4 变更文件范围、51 份源码/配置/模板与全部原始/归档结果指纹；B/C 当前运行源码一致，P3 原始结果与 `20b1133` 冻结源码未改写。P3 基线的 16 份升级 SQL 保持字节一致，最初基线的 12 份也不变。全批 4 请求和补查单路径 1 请求 dry-run、shell/Python 语法与最终当前源码 clean package 通过。静态程序与检查记录位于 P4 validation 归档，未为这些检查追加付费调用。


## P5 产物生成与程序验证

最终定向后端 22 类 160/160；package 和前端 build 通过。新增 6 个真实 SDK/本地 HTTP 用例与 2 个真实 PostgreSQL 隔离库用例，覆盖 REPORT/PLAN、多文档引用、未知参数、14→16 次预留生成/修复、两次 JSON 错误、未读证据拒绝、生成取消/配额释放、产物与发布事件原子性、取消后迟到结果和首次会话幂等。删除退役草稿的 13 个旧用例，因此用例总数不能直接与 P4 165 作覆盖增减指标。

运行 `P5_TESTS=<22 个类> bash scripts/validate-agentic-research-p5.sh`；完整命令和边界见[执行记录](agentic-research-execution-log.md)。所有模型响应为本地 HTTP fixture，程序引用身份通过不代表语义质量。两条 `--phase p5` dry-run 通过；真实供应商 `--execute` 被自动审批拒绝，没有发送付费模型请求。新建 schema 移除旧计划表；测试库已自动清理，未对业务数据库升级或 DROP 旧数据。原始构建/回归日志及清单见执行记录 P5，完整 Web/浏览器验证从 P6 接续。


## P6 聊天与只读 SSE 实际验证

P5 提交 `3507d9e` 后接入聊天三模式、ResearchProgress / PlanDraftCard、已读来源恢复与 SSE。最新后端 **23 类 168/168**、0 失败/错误/跳过；clean package、前端 Vite build、node 类型检查通过。app 类型检查保留 P0 的 24 个诊断，去除行号后逐条内容相同，无新增研究代码诊断。完整命令见[执行记录](agentic-research-execution-log.md)，源码与原始产物 hash 见 [P6 manifest](../../eval/agentic-research/manifests/research-p6-validation-2026-09-17.json)。

| 检查 | 实际结果与边界 |
| --- | --- |
| 研究 SSE | 固定 owner、最大游标回放、最终尾部 ARTIFACT、snapshot、内部 payload 过滤、错误归属拒绝；订阅断开/重连无调度或 cancel，断线不写 JSON |
| 来源与接口 | 会话列表/来源快照只读，sources 排除未读与其他 owner，取消后已读快照保留；regenerate 校验新 clientRequestId；finalization HTTP 400 不重试，usage unknown 且释放配额 |
| 浏览器 M | 9 项通过，真实 React/研究服务/SDK HTTP/隔离 PG；普通问答、REPORT 双文档引用、PLAN 7 ms/未知温度、来源面板、重新生成、刷新、等待输入、取消、会话列表失败和重连 |
| 请求与状态 | 49 次本地 fixture 模型调用；6 run，5 COMPLETED 有产物、1 CANCELLED 无产物；5 次创建 POST、1 次 regenerate POST，刷新不增加模型调用，断线仅 GET 恢复 |
| 受控部分 | 认证、知识搜索、原文读取、模型内容与普通问答响应，均非真实供应商/完整普通 RAG；没有登录与文档下载预览 E2E |
| 开发失败及修复 | A—H/K 失败、I/J 首轮通过、L/M 补充场景通过原始日志保留；REPORT 改 canonical 引用、研究滚动接入已有 MessageList、SSE IOException 不进入 JSON 返回；fixture/虚拟列表断言修正单列 |
| 已知日志边界 | J 无 SSE 转换异常；取消 worker 的现有 Reactor 阻塞中断 / onErrorDropped 日志保留。持久取消/HTTP 本地结束与无产物通过，不代表远端停止计算 |
| 未执行 | 本阶段交付时真实供应商产物联调被自动审批拒绝，后续授权后的开发复测见补充记录；无 A/B/C、EM/F1、引用语义评分、完整生产 RAG 或全服务 E2E；P7/P8 未实施 |

程序日志/JUnit 在 `local-data/agentic-research/runs/20260917T125800_P6_validation/`，最终浏览器日志/summary/plan.png 在 `20260917T131200_P6_browser_M/`。本批没有付费供应商请求，随机测试库与进程已清理，历史 SQL 和业务数据库未改变。COMPLETED 表示合法产物形成，不能作为计划可执行、事实正确或资料完整的保证。

最终静态验收通过：5 份入口 Markdown 的 100 个本地链接/锚点、围栏、whitespace 与 shell/Python 语法；35 个阶段文件在约定范围，79 份源码/配置/测试及原始验证产物共 192 个指纹一致。P5 按冻结提交 3507d9e 核验，16 份历史升级 SQL 不变。检查记录与脚本在 P6 validation 目录，指纹由 P6 manifest 引用。


## P5/P6 授权后真实供应商补充验证

起始提交 `f79b9e6`，仅对原有 REPORT/PLAN 开发样例执行三批运行；完整命令、修复和边界见[执行记录](agentic-research-execution-log.md)，指纹见[artifact smoke 清单](../../eval/agentic-research/manifests/research-p5-artifact-smoke-2026-09-17.json)。这批已获具体语料外发/付费授权，不是 P7 架构或质量评测。

| 检查 | 实际结果 |
| --- | --- |
| A / v1 | REPORT、PLAN 均因连续两次引用校验失败落 FAILED，无非法产物发布；31 次调用保留 |
| B / v2 | REPORT/PLAN 均经一次修复落 COMPLETED；比较仅有论文 A 的 3 条引用，论文 B 未读取；PLAN 的唯一 Conclusion 引用不支持生成的数据集、初始种子集及计算资源项，作为语义负例保留 |
| C / v3 | REPORT 因 embedding 超时落 FAILED / RESEARCH_TIMEOUT，无产物；PLAN 落 PARTIAL，4 条实际已读引用，保留 worker 失败，未使用修复调用 |
| C 原文核对 | 数据集、主动学习与 batch-size 定义、停止窗口/批次影响、无停止方法的限制分别对应真实 Introduction/Conclusion 段；引用范围、读取证明、来源段落及 hash 回查通过；batch_size/window_size 为 null；500 条为独立 user_input |
| C 验收边界 | 计划仍为概述，关键操作、评估值与预算分配不足，只有单篇论文来源；最终两条真实供应商路径没有同时通过成功验收，不声称可执行计划或真实多文档产物验收 |
| 最新程序回归 | 23 类 170/170，0 失败/错误/跳过；后端 clean package 通过；模型为本地 HTTP fixture；新增错误定位、缺口修复和受限原始输出检查，SSE 过滤通过 |
| 实际用量 | 模型 79 请求，已知输入 321707 / 输出 26002 token，0 unknown；embedding 去重 30 请求，已知 total_tokens 60，23 usage unknown；失败与修复保留，未核对金额 |
| 隔离与留档 | 只读语料、三批随机运行库已清理，无业务升级/删除；P5/P6 旧清单按各自提交冻结核验，原始日志不改写 |

原始程序/核对记录在 `local-data/agentic-research/runs/20260917T133300_P5_followup_validation/`，三批真实请求位置见执行记录。没有新增前端改动或重跑浏览器；P6 的构建、9 个受控页面检查及原有 24 个类型诊断沿用该阶段证据。未开展 A/B/C、EM/F1、正式语义支持评分或完整生产服务 E2E。

本次最终静态验收通过：5 份入口 Markdown 的 105 个本地链接/锚点、围栏、whitespace、14 个变更文件范围与 Python 语法；301 个源码/留档指纹匹配，P5/P6 分别按 3507d9e / f79b9e6 冻结核验，16 份历史升级 SQL 不变。clean JAR 仅含 research-artifact-v3；静态检查首次因旧 P5 清单没有 static_checks 字段失败，调整检查器后通过，首次日志保留。

## P7 / P8 当前程序、迁移及浏览器验证

起始提交 `1069330`，固定批次原始资料位于 `local-data/agentic-research/runs/`，程序日志位于 `20260917T140100_P7_validation/`。下面是实际执行边界；固定 regression 400 问题/1200 任务已全部记录，质量、失败、资源和费用见[固定对照报告](agentic-research-evaluation-report.md)。完整应用核对见[应用原文报告](agentic-research-application-review.md)。

| 验证 | 实际结果与范围 |
| --- | --- |
| 后端 clean package | 成功；JAR 仅含 research-artifact-v4，不含旧 v3 模板 |
| Java 回归 | 24 类 173/173，0 failure/error/skip；真实隔离 PostgreSQL 与 SDK 本地 HTTP fixture，无供应商调用 |
| Python 回归 | 28/28；数据契约、评分、状态失败分母、source IDs/邻块和 full 执行标记 |
| 作者公式 | 独立作者代码 196 对答案/49 对支持集合匹配；代码字节已保存 official-reference，SHA 在 scorer-alignment.json |
| 新库/增量 | fresh、重复 02/03/04、owner/快照/约束、历史哨兵保留通过；只作用随机测试库，库已删除 |
| 普通保留链 | StreamChatPipeline/检索范围与各后端适配器、IngestionTask/TableChunker、WorkbookDiff、取消 trace 在同批检查中通过；不代表全服务/真实摄取 E2E |
| 前端 | build、node 类型通过；app 24 项既有诊断，去掉 cwd 前缀/行列后与 P6 一致，没有宣称 app 类型全绿 |
| 受控浏览器 | v4 批次 `20260917T150225_P8_browser_v4` 9 项通过，49 个本地模型调用，0 真实供应商；真实 React/研究 HTTP/SDK/PG，鉴权/检索/读源/模型/普通 QA 为夹具 |
| 保留历史 | 16 个升级 SQL 与起始提交字节一致；08 的 XLSX 事实比较主体保持，修正独立请求的介绍 |
| 固定公开 regression | 400 问题/1200 任务全部真实记录，932 COMPLETED / 93 PARTIAL / 175 FAILED，未执行 0；72 项源码/配置冻结，随机运行库清理 |
| 固定 smoke | 120/120；A 40 完成，B 26/8/6，C 27/5/8、零委派；MuSiQue 答案分母仅 6，v3 开发批次不是独立 held-out |
| 应用原文 | 固定 12 比较/12 计划：5/15/4；20 个产物/85 引用由 Codex 检查，错误推断保留，没有独立人工盲评或裁判 API |
| 针对性 v4 复测 | comparison-03 合法完成但仍过度解释碎片；plan-02 缺必填数组失败，未发布计划；原批次不覆盖 |
| full | 5839 问题/17517 任务 dry-run 准备，模型 0；本轮未付费执行 |
| 四请求真实演示 | 2 COMPLETED/2 PARTIAL，4 合法产物/10 引用由 Codex 核对；实际 CLI/检索/供应商/隔离 PG，两个运行库删除；一次检索缺后续、比较未完成、PLAN 没生成 null 参数条目，均保留 |
| 演示资源 | 33 SDK 模型调用记录，已知输入 444152/输出 11886、unknown 0；估算生成费 0.1457 元。Embedding 17/271 tokens/unknown 0，金额及账单未知 |
| 最终文档/源码 | 当前入口链接/锚点、围栏、whitespace、shell/JSON、72 项运行源码/配置与固定回归一致、16 历史 SQL 与原 XLSX 主体保留，详见 P8 清单 |

### 10 项必须行为的检查入口

| 行为 | 已执行的代表性检查 |
| --- | --- |
| 重复创建幂等 | ResearchRunPostgresIT.concurrentDuplicateRequestsCreateOnlyOneRunAndOneQueueEvent |
| 运行/事件/证据/产物按 owner 隔离 | ResearchRunPostgresIT.everyOperationEnforcesOwnerAndInputUsesRevision、readSourcesSurviveCancellationAndUnreadCandidatesOrOtherOwnersAreExcluded；ResearchEventStreamServiceTest.invalidCursorOrWrongOwnerCannotOpenStream |
| worker 不扩大范围 | ResearchEvidenceToolsTest.workerScopeIsValidatedBeforeAdmissionAndCannotMarkAnotherDocumentRead；ResearchWorkerCoordinatorTest.workerCannotReadOtherWorkersCandidatesExpandScopeAskOrDelegate |
| worker 超时/失败保留成功结果 | ResearchWorkerCoordinatorTest.timeoutReturnsStructuredFailureAndLateCallableCannotPublishASecondResult；ResearchWorkerNativeTest.failedWorkerLeavesSuccessfulFindingsAndExplicitPartialGap；实际应用 comparison-03 一成功/一局部预算不足 |
| 最后额度竞争与最终生成预留 | ResearchBudgetTest.concurrentRequestsCannotSpendReservedFinalizationCalls、workersShareAtomicCallsAndCountAcrossResumeWhileKeepingMainAndFinalReserve |
| 取消/完成竞态与旧 epoch | ResearchRunPostgresIT.completionRacingCancellationAlwaysPublishesOneConsistentTerminalState、activeLeaseRejectsDuplicatesAndOldEpochCannotPublish、cancelledRunRejectsLateWritesAndRepeatedCancelIsIdempotent |
| 非法参数/假引用/来源变化或停用 | ResearchEvidenceToolsTest.invalidMetadataAndInvalidToolArgumentsFailClearly、missingAndForeignRunEvidenceAreRejected、sourceChangeIsReportedWithoutMixingNewTextOrVersion、disabledDocumentCannotBeReadFromSnapshot；ResearchArtifactGeneratorTest 引用校验/修复失败检查 |
| SSE 只读重连/持久结果 | ResearchEventStreamServiceTest.reconnectReplaysAfterHighestCursorWithoutStartingOrCancellingExecution；ResearchRunControllerTest.readingStateAndEventsDoesNotCreateOrResumeResearch；浏览器刷新/重连检查 |
| 普通问答/入库/版本比较 | StreamChatPipelineTest、IngestionTaskServiceImplTest、TableChunkerTest、WorkbookDiffServiceTest；普通问答浏览器请求仍为 /rag/v3/chat，但实际答案受控 |
| 清洁库与既有结构升级不删历史 | validate-agentic-research-p2-database.sh 在随机库重建历史结构、保存真实哨兵、对比研究表 catalog 并重复增量 |

这些是程序机制、隔离存储或受控页面证据，不能包装成生产故障注入。真实模型结果包含失败/超时，远端是否停止计算或计费保持 unknown。

最终交付清单见[P8 manifest](../../eval/agentic-research/manifests/research-p8-handoff-2026-09-18.json)。四例演示是完整 24 项核对之后选取的说明样例，仍有负结果，不能替代基准或包装为语义通过率。所有 PLAN 为待核对草稿，未审批/执行/下发。已有业务库、Redis 缓存清理、生产登录、来源下载预览和完整 Web/业务栈 E2E 未执行。

## R1 契约与原生结束恢复验证（2026-09-18）

[R1 清单](../../eval/agentic-research/manifests/research-r1-reliability-2026-09-18.json)记录精确配置、选题规则、源码/产物指纹、命令、失败迭代和实际结果。R1 没有修改前端、检索配置、生成模型或最终产物提示版本。

| 检查 | 实际结果 |
| --- | --- |
| 相关后端套件 | P3 随机库 103 项通过，11 项 P2 环境专属测试跳过；随后 P2 独立执行 11/11，累计 114 个不同用例通过，临时库清理 |
| 原生协议与 HTTP | 18 项通过；覆盖缺失嵌套字段、null/错误类型、字段路径、未读引用、同一上下文的两次修复上限、保留 read 工具消息、取消与最终生成预留；不是供应商网络故障注入 |
| Python | 29/29，包含关闭金额估算后的 unknown usage 保留和固定题目 ID 选择校验 |
| SQL | 新库、重复增量、证据范围与历史哨兵保留通过，仅随机临时数据库 |
| 真实原题复测 | 12 个故障分层/正常样例 × A/B/C，共 36/36；35 COMPLETED/1 PARTIAL/0 FAILED，历史同题 21/4/11；保留全部失败/部分完成，三个运行库清理 |
| 实际恢复路径 | 4 个运行、6 次修复模型调用，均返回原生工具输出后完成；ClassCast 工具错误本批 0，未读引用错误仍有 12 次 |
| 实际 usage | 模型 273 次，输入 3,269,722/输出 50,727；embedding 141 次/1,579 total tokens；两者 unknown 0；未做金额换算 |
| 引用原文 | 4 个修复产物由 Codex 核对，比较关系缺乏引用支持、误报资料缺口等负例保留；没有独立盲评或裁判模型 |
| 版本一致性 | 73 项运行源码/配置与该批源码快照一致；HTTP 历史保留断言的后续补强只改测试，18/18 复核 |

同题完成率改善是本阶段诊断结果。QASPER 每模式仅 6 题、MuSiQue 答案分母仅 3，且是按历史故障选题；分数不能外推到固定 400 题或 full。两批均没有实际 worker，不证明多 Agent 收益。新批没有检索超时，但该链路尚未修改；SSE 断流/半开连接、上游模型断流恢复、PLAN 新验收、模型/thinking 对照、交错重复及固定回归属于后续 R2—R5。


## R2：检索与连接恢复（2026-09-18）

已实现研究专用 deadline/cancel 上下文，显式传递到排队任务、embedding HTTP 和 PG statement。embedding 每次最多 12 秒、瞬时错误最多重试一次，检索内部在工具外层之前结束；原生工具明确返回失败而非空列表。取消、鉴权及参数错误不重试，成功 query embedding 按运行及模型配置缓存。研究 PG 设置和查询使用同一连接。模型整次响应确认结束后才交给 SDK，半截工具 JSON 丢弃，从已完成历史最多重试一次；逐次 usage/unknown 均保留。SSE 增加 15 秒静默检测、可取消快照请求、带退避与游标的只读恢复、终态关闭。

后端 116 项、Python 29 项、前端 4 项故障测试通过；真实浏览器桩集成 9 项通过，前端构建通过，24 项既有 app 类型诊断未增加。详见 [R2 清单](../../eval/agentic-research/manifests/research-r2-reliability-2026-09-18.json)。

两道原超时题 × ABC 的 v1 六个任务实际记录为 4 完成/0 部分/2 失败，但其中三个完成任务答案为空；65 次 embedding 请求有 63 次 usage unknown 的网络失败，不能宣称效果改善。A 的一次两次超时发生于 embedding，尚未进入 PG。后续同公开查询的原 30 秒/新设置各四次传输对照均成功，不能据此断定先前是网络、供应商还是超时设置原因。已加入 DNS、TLS、请求写出、响应头和正文阶段记录；同两题 v2 复测进行中，最终结果续记。原始失败批次不覆盖，金额计算关闭。

R2 v2 续记：同两题 × ABC 的 6 个任务全部完成并生成回答。41 次模型请求 usage 全部已知（input 346,822/output 8,709）；16 次 embedding 请求中 2 次失败 usage unknown，14 次成功共 152 tokens。两次失败均已完成 DNS/TLS 并写出 request body，等待响应头约 12 秒后取消，第二次尝试成功；排除这些样本的 DNS、建连与 PG 阶段，但不能区分供应商排队和回程网络。v1/v2 时段差异巨大，不将小样本结果作为整体质量收益。v2 的 MuSiQue B/C 答案命中固定历史金标，但引用仍只覆盖最终实体，未完整覆盖多跳链。后续保留重复对照。

## R3—R5 改进与会话交接检查

R3/R4 真实校准详见执行日志及独立清单，thinking 与重排的质量变化不一致，默认仍关闭。最后修复明确每个模型 HTTP attempt 消耗额度并保存 usage；实际断流文字和半截工具 JSON 不拼接，永久鉴权错误不另起最终生成。

最终相关后端唯一用例计数为 213：主相关套件 128、最后新增服务失败证据收尾 1、共用问答/检索/摄取/取消 73、来源 PostgreSQL IT 11。复跑的 17 项产物测试不重复累计。日志为 `20260918_R5_validation/backend-final.log` / `completion-recovery-final.log` / `shared-path-regression.log` / `p2-database.log`；数据库测试均用临时隔离库并清理。Python 30、前端 4、浏览器 9 与构建记录见 R3/R4；受控浏览器首次编译竞态启动失败已保留，稳定后 v2 通过，app 原有 24 项类型诊断未变。

R5 v2 在连续 embedding 无响应后受控中止，仅记录 22 项，临时运行库已删除，所有失败/usage 保留。随后 66 次连接探针已完成，但不能确定协议或网络根因；会话交接时没有这轮执行进程。新的离线比较脚本 `eval/agentic-research/analysis/compare_runs.py` 用完整 R1/R3 的 36 项配对记录运行通过，不用于不完整 v2 作整体比较。本次记录修改仅做链接/结构/JSON/空白检查，没有再次启动供应商或浏览器。
