# 统一研究工作流验证报告

日期：2026-09-17。P0/P1 已完成；P2 首批完成契约、知识库作用域、块级证据快照与存储 SQL，P2 仍进行中。研究接口、原生模型工具调用、数据入库与 A/B/C 评测尚未运行。

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

范围是数据契约、知识库作用域、单块证据快照和研究表 SQL；P2 的文档筛选、邻接展开、转换与导入尚未完成。本批原始日志、JUnit XML 和 checks.json 保存在本地忽略目录 `local-data/agentic-research/runs/20260917T072920_P2A/`，没有生成模型问答成绩。

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

PostgreSQL 验证使用合成存储夹具和实际 SQL，没有导入公开语料。Java store 的 SELECT/UPDATE 及结果映射由 H2 验证；Java INSERT ON CONFLICT、来源 MyBatis 读取和 Spring 事务仍需 PostgreSQL 联调。示例事件 SQL 只验证存储与序号原语，事件写入器和并发任务运行尚未实现；请求唯一约束也不能代替幂等调度测试。

本批未调用真实 embedding、rerank 或研究模型，没有生成 token/cost/EM/F1 等模型评测指标。未升级已有业务库；部署时仍需应用增量 SQL。

## 尚未验证的业务和评测

- 未启动全套服务或浏览器进行登录、普通问答、入库、版本比较、草稿生成 E2E。
- 已做 PostgreSQL 隔离库的新建/退役 SQL 检查；未升级既有业务库或清除其意图缓存，不自动 DROP 既有数据。
- 未导入 QASPER/MuSiQue、生成固定回归题目、调用研究模型或产生模型费用/效果指标。
- P2 的文档筛选、可靠邻接展开、QASPER/MuSiQue 转换与幂等导入仍未完成；Java 证据存储/来源回查未对 PostgreSQL 联调。
- REPORT / PLAN 原生工具、新运行器、并发 worker、研究 SSE 与调用记录器仍属于后续阶段。

后续每阶段追加实际结果，保留失败和未运行边界，不以单元测试替代真实模型或页面效果。
