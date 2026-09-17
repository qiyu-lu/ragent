# 统一研究工作流验证报告

日期：2026-09-17。当前范围：P0 基线；P1 尚在实施。新研究接口、模型工具调用、数据入库与 A/B/C 评测均未运行。

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

## 尚未验证的业务和评测

- 未启动全套服务或浏览器进行登录、普通问答、入库、版本比较、草稿生成 E2E。
- 未运行本轮 PostgreSQL 清洁建库或已有数据库升级；P1 不对现有数据库执行 DROP。
- 未导入 QASPER/MuSiQue、生成固定回归题目、调用研究模型或产生模型费用/效果指标。
- REPORT / PLAN、新运行器、并发 worker、研究 SSE 与调用记录器仍属于后续阶段。

后续每阶段追加实际结果，保留失败和未运行边界，不以单元测试替代真实模型或页面效果。
