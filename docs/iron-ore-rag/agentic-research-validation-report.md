# 统一研究工作流验证报告

日期：2026-09-17。P0/P1 已完成；P2 已完成证据工具、Java/PG 联调、训练/开发数据转换和固定抽样，P2 仍进行中。研究接口、原生模型工具调用、公开数据入库与 A/B/C 评测尚未运行。下方按批次保留历史结果，以最新批次说明当前验证边界。

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

## 尚未验证的业务和评测

- 未启动全套服务或浏览器进行登录、普通问答、入库、版本比较、草稿生成 E2E。
- 已做 PostgreSQL 隔离库的新建/退役 SQL 检查；未升级既有业务库或清除其意图缓存，不自动 DROP 既有数据。
- 已生成训练/开发的固定样本，未导入 QASPER/MuSiQue、调用研究模型或产生模型费用/效果指标；真实 test 转换留到最终配置确定后。
- P2 的真实 metadata 摄取、来源映射、幂等导入与 usage 仍未完成；文档筛选和邻接读取尚需实际入库语料复核。Milvus/ES 未做实际服务联调。
- REPORT / PLAN 原生工具、新运行器、并发 worker、研究 SSE 与调用记录器仍属于后续阶段。

后续每阶段追加实际结果，保留失败和未运行边界，不以单元测试替代真实模型或页面效果。
