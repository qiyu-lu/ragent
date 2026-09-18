# 项目瘦身计划（S6 之前执行）

> 状态：**方向已由用户于 2026-09-19 确认，可以执行**（含删除意图树、引导问答、知识图谱、MCP）。依据同日的只读调查（三路并行核对代码耦合、脚本与评测依赖、目录体积）。
> **执行会话只读三份文件：本文件 + [`slim-down-status.md`](slim-down-status.md) + [`career-sprint-status.md`](career-sprint-status.md)。** 不要加载旧计划、执行记录、`flow-notes/` 等即将删除的文档；本文件列出的路径与行号来自调查时点，动手前用 `grep` 复核。
> 目标：只留下“五个生产级问题（W1—W5）+ 支撑它们的最小 RAG 平台”，让复盘和 S6 的总结面对的是一个小而自洽的仓库。
> 与 [冲刺计划](career-sprint-plan-2026-09-18.md) 的关系：本计划插在 W5 与 S6 之间；完成后 S6 照原计划执行。

## 0. 原则

1. **删的是“当前配置下不运行、且简历不讲”的东西**；正在承重的（RocketMQ、审计日志、解析器）不动。
2. **Git 历史就是归档**：不建 `archive/` 目录，不留注释掉的代码。需要旧内容时 `git show slim-v0-baseline:<路径>`。
3. **一批一个提交、一批一次回归**，任何一批回归不过就 `git revert` 该批，不影响其他批。
4. W1—W5 的实验数字不重跑：瘦身不改动研究 Agent、租约、权限判定、embedding 缓存的逻辑，只改它们周边的调用签名。

## 1. Git 记录方式

| 步骤 | 命令 / 约定 |
| --- | --- |
| 基线 | `git tag slim-v0-baseline`（打在当前 `feat/llm-backend-hardening` 头部 `4d41dd0` 之后的计划提交上） |
| 分支 | `git switch -c chore/slim-down`；全部完成并回归通过后 `git switch feat/llm-backend-hardening && git merge --ff-only chore/slim-down` |
| 提交 | 每批一个提交，标题见各批；只 `git rm -r -- <明确路径>` / `git add -- <明确路径>`，提交前看 `git diff --cached --stat` |
| 标签 | 低风险批次（B1—B5）完成打 `slim-1`；功能裁剪（B6—B9）完成打 `slim-2`；意图树（B10）完成打 `slim-3` |
| 度量 | 每个标签处记录：`git ls-files \| wc -l`、`find bootstrap/src/main -name '*.java' \| xargs cat \| wc -l`、`./mvnw dependency:tree` 的 jar 数，写进状态文件 |
| 回退 | 单批 `git revert <提交>`；整体放弃则删除分支，基线标签仍在 |

基线数字（2026-09-19）：被跟踪文件 1273 个；`bootstrap/src/main` Java 704 个文件 / 约 6.0 万行，其中 `rag/` 270 个文件 / 2.8 万行。

## 2. 每批的回归（固定）

```
./mvnw -o -pl bootstrap -am -DskipTests clean package
bash scripts/validate-agentic-research-p7.sh          # 期望：Python 41/41、Java 13 + 260 减去本批删除的测试数
bash scripts/validate-agentic-research-p2-database.sh
(cd frontend && npm run build)                         # 只在动了前端的批次
```

B6 起每批结束再手动冒烟一次：启动应用 → 登录 → 上传一份文档 → 问答一次 → 发起一次研究任务（可用 `stub` profile，零接口费）。

## 3. 去留总表

| 对象 | 现状证据 | 决定 |
| --- | --- | --- |
| 引导问答 `rag/core/guidance` | 意图树的附属短路步骤；yaml 里没有任何配置 | **删**（B8） |
| MCP：`mcp-server` 模块 + `rag/core/mcp` | 编译期无模块依赖 `mcp-server`；只能经意图树的 MCP 节点到达；研究 Agent 与 W4 不涉及 | **删**（B8） |
| 意图树 `rag/core/intent` + 管理端 | 无意图链路已是默认（`fallback-mode: global`，`PooledEvalController` 用空意图调用）；但改动面最大 | **删，单独成批放最后**（B10）；`RetrievalScopeResolver` 的“与可读知识库求交”必须保留 |
| 知识图谱 LightRAG / Neo4j | `rag.graph.type: none`，通道关闭 | **删**（B7） |
| Milvus | `rag.vector.type: pg`；最大的第三方依赖 | **删**（B6） |
| Elasticsearch / 关键词通道 | `rag.keyword.type: none`；库内没有 PG 全文检索实现，当前就是纯向量 + rerank | **删**（B7） |
| 上下文选择 `rag/core/retrieval/selection` | 主链路零引用，只被 `eval/context-selection` 驱动 | **删**（B9） |
| `rag/eval`（Eval / Hybrid / Pooled 控制器） | 包外零引用；只服务于将被删除的两个旧评测目录 | **删**（B9） |
| RocketMQ | **承重**：上传只落元数据，切块与向量化全在消费者里；事务消息 | **留**（也是 Java 后端可讲的点） |
| `audit/` | 被 8 个核心写路径引用 | **留** |
| `ingestion/` 流水线（61 个文件） | 与上传主路径交叉引用，`ChunkerNode` 也用 W5 的 `ChunkEmbeddingService` | **本次不动**；B10 之后耦合减少，是否再裁留给 S6 后决定 |
| `core/parser/mineru`、`core/parser/image` | PDF / Word / PPT 的唯一高保真解析；`ExcelDocumentParser` 依赖 image | **留** |
| `ironore/`（版本比较） | `WorkbookDiffServiceTest` 在 p7 回归内，且接入了 W4 权限 | **留**；只删 `IronOreVersionDiffToolExecutor`（随 MCP） |
| `admin/` 仪表盘 | 自包含、零入站引用，是管理端首页 | **留**（删了省不了多少，前端首页要重做） |
| 模型供应商客户端 | 五个供应商在 `application.yaml` 中全部被引用；研究代码按具体类型引用 SiliconFlow | **留** |
| `knowledge/eval/IngestionReuseCommand` | X5 的验证入口 | **留** |

## 4. 批次

### 第一阶段：零风险清理（标签 `slim-1`）

**B1 文档** — `docs: drop superseded notes and plans`
- 删：`docs/iron-ore-rag/` 下除下列保留项之外的全部（旧计划与执行记录、`flow-notes/`、`interview-deep-dive/`、`prompt-kit/`、`samples/`、`stages/`、`08/09/10-*.md`、`project-study-guide.md`、`context-selection-*`、`chinese-diagnostic-drafts-*`、`agentic-research-*`）；`changes/` 中 2026-09-18 之前的 14 篇；`docs/assets/`（两张送检流程图，无引用）；`resources/docs/knowledge/`、`resources/examples/`；`robot-gateway/`（只有 README 被跟踪）；`eval/agentic-research/README.md` 中指向已删内容的段落。
- **留**：`career-sprint-plan-2026-09-18.md`、`career-sprint-status.md`、本文件、`changes/` 的五篇 W1—W5 改动说明与 `changes/README.md`（索引同步删到只剩五条）——它们是 S6 的直接输入，S6 完成后再决定去留。
- 同步：根 `README.md` 有 18 处指向 `docs/iron-ore-rag/` 的链接，删掉失效的；README 的重写留给 S6。计划文件 §6“旧计划与执行记录：保留为历史”改为“已由 `slim-v0-baseline` 标签留存”。

**B2 杂物** — `chore: remove unreferenced assets and stray build output`
- `assets/`（12.4 MB，26 个文件，全仓零引用）；`frontend/@/`（shadcn 误写出的旧副本，别名 `@` 实际指向 `src`）；`frontend/vite.config.js`、`vite.config.d.ts`、被跟踪的 `*.tsbuildinfo`（并加入 `.gitignore`）。
- 本地未跟踪：空目录 `.agents/`、`.codex/`，根目录 `.vite/`，`robot-gateway/ros1_ws/`。
- **不能删**：`resources/format/copyright.txt`（`pom.xml:285` spotless 引用）。

**B3 脚本** — `chore(scripts): keep only the live regression and experiment drivers`
- 删：`validate-agentic-research-p4.sh / p5.sh / p6.sh`（p7 的子集包装）、`p1-database.sh`（见 B5）、`demo-agentic-research.sh`、`sse_queue_test.sh`。
- **留**：`p7.sh`、**`p3.sh`（p7 实际 `exec` 的是它）**、`p2-database.sh`、`career-x1/x3/x5.sh`、`career-takeover-demo.sh`、`stub-upstream.sh`、`iron-ore-rag/create_v1_3_demo.py`（X5 输入的可复现来源）。
- 可选的小整理：把 `p3.sh` 并入 `p7.sh` 并改名 `validate.sh`；会牵动计划文件与状态文件里的命令，收益小，默认不做。

**B4 评测目录** — `chore(eval): remove retired harnesses and phase manifests`
- 删：`eval/context-selection/`、`eval/iron-ore/`（无任何脚本、Java、测试引用；与 B9 的 `rag/eval`、`selection` 成对）。
- `eval/agentic-research/` 内删：`browser_research.py`、`smoke_research.py`、`diagnose_research.py`、`check_scorer_alignment.py`、`analysis/`、`evaluate_applications.py`、`prepare_applications.py`；配置 `applications.json`、`r3.json`、`r3-rerank.json`、`r4.json`、`r4-thinking.json`、`r5.json`；18 个 P / R / S 阶段 manifest 与零引用的 `research-r2-case-ids-*.json`。
- **留**：`evaluate_research.py`、`scoring.py`、`datasetkit.py`、`cache_report.py`、`import_corpus.py`、`stub_upstream.py`、`stub_corpus.py`、`x2_takeover.py`、`x3_upstream.py`、`prepare_dataset.py`、`verify_prepared.py`、`requirements.txt`、`tests/` 全部；配置 `p7.json`（代码里的默认值）、`career-x1.json`、`career.json`；全部 `career-*` manifest 与被它们引用的 `research-career-case-ids-*`、`research-r1-case-ids-*`。
- 执行时先 `grep -rn "<文件名>" eval scripts bootstrap/src` 逐个确认零引用，再删；`tests/` 若引用了被删脚本，则该测试一并删除并在提交说明里写明 Python 用例数的变化。

**B5 数据库与编排** — `chore(resources): drop retired migrations and unused compose stacks`
- 删：`resources/database/backups/`（MySQL 时代的备份）、`examples/`（两份意图树示例 SQL）、`upgrades/v1.1.0/260812_ros1_robot_mission.sql`、`260915_task_agent.sql`、`260917_retire_execution_demo.sql`（`schema_pg.sql` 中已无这些表；唯一使用者 `p1-database.sh` 同批删除）。
- 删：`resources/docker/milvus-stack-*.yaml`、`lightweight/`、`rocketmq-stack-*.yaml`（两份独立栈；开发用的是 `dev/` 里的那一套）、`graphrag/`。
- **留**：`dev/`（产生 `ragent-iron-ore-dev-postgres-1` 的那份）、p2 校验用到的全部迁移、`schema_pg.sql`、`init_data_pg.sql`。
- 前提：确认本机 `ragent` 库已执行过 `260917_retire_execution_demo.sql`（执行时查一次即可）。

### 第二阶段：休眠功能裁剪（标签 `slim-2`）

**B6 Milvus** — `refactor(vector): remove the Milvus backend`
- 删：`rag/core/vector/Milvus*.java`（3 个）、`rag/config/MilvusConfig.java`；测试 `vector/MilvusCollectionTests`、`rag/core/vector/MilvusVectorRetrieverServiceTest`、`index/InvoiceIndexDocumentTests`（直接用 Milvus 客户端的草稿测试）。
- 改：三个 `Pg*` 类去掉 `@ConditionalOnProperty`，`application.yaml` 删 `rag.vector.type` 与 `milvus.uri`；`RAGSettingsController:68` 的默认值；设置页的 “pg / milvus” 文案；Javadoc 里的 “Milvus Collection” 措辞。**`collectionName` 字段不是 Milvus 专属，不能删。**
- Maven：`io.milvus:milvus-sdk-java`（`bootstrap/pom.xml`、根 `pom.xml` 的 dependencyManagement 与版本属性）。
- p7 清单去掉 `MilvusVectorRetrieverServiceTest`。

**B7 关键词（ES）与图谱（LightRAG）** — `refactor(retrieval): remove dormant keyword and graph channels`
- 删（ES）：`rag/core/keyword/`、`KeywordSearchChannel`、`KeywordSyncingVectorStoreService`、`KeywordProperties`、`EsClientConfig`、`KeywordSyncVectorStorePostProcessor`、`rag/eval/HybridEvalController` 及其测试。
- 删（图谱）：`rag/core/graph/`、`GraphSearchChannel`、`GraphSyncingVectorStoreService`、`GraphProperties`、`GraphSyncVectorStorePostProcessor`、`GraphController`、`GraphViewVO`、三个测试；前端 `pages/admin/knowledge-graph/`、`services/knowledgeGraphService.ts` 及路由、导航、面包屑。
- 两者都删后：整个 `rag/config/validation/` 可删；`SearchChannelType` 去掉 `KEYWORD`、`GRAPH`；`MultiChannelRetrievalEngine:195` 的白名单简化；`KnowledgeBaseCleanupConsumer` 去掉两个可选清理块；`RAGSettingsController`、`SystemSettingsVO`、设置页去掉对应区块；`application.yaml` 删 `rag.keyword.*`、`rag.graph.*`、两个通道开关与权重。
- Maven：`co.elastic.clients:elasticsearch-java`。**`mockwebserver` 不能删**（注释写的是 LightRAG，但研究侧测试在用）。
- p7 清单去掉 `EsKeywordRetrieverServiceTest`、`KeywordSearchChannelTest`、`GraphSearchChannelTest`。

**B8 引导问答与 MCP** — 两个提交：`refactor(rag): remove guidance short-circuit`、`refactor(rag): remove MCP tool routing and the mcp-server module`
- 引导：删 `rag/core/guidance/`、`GuidanceProperties`、两个提示词模板、`RAGConstant` 两个常量；`StreamChatPipeline` 去掉注入与 `handleGuidance`；`StreamChatPipelineTest` 同步。
- MCP：删 `mcp-server/` 模块、`rag/core/mcp/`、`ironore/tool/IronOreVersionDiffToolExecutor`、两个 `mcp-parameter-extract*.st`、`mcpBatchExecutor`。
- MCP 的改动点（按工作量排序）：`RetrievalEngine`（去掉 MCP 扇出与参数澄清）→ 提示词构建（`RAGPromptService` 的场景选择，**其中 148 行在“无 MCP 且无知识库”时抛异常，改为走 `StreamChatPipeline:163-171` 已有的空检索分支**；`PromptScene` 收敛为 `KB_ONLY`；`ContextFormatter` / `DefaultContextFormatter` / `PromptContext` / `PromptBuildPlan` / `AgentPromptSlot.MCP_ANSWER`；`context-format.st` 的 5 个 MCP 段）→ `RetrievalContext.isEmpty()` → `StreamChatPipeline` 的温度分支固定为 0 / 1 → 意图侧的 `mcpToolId` 字段暂留（B10 整体删除，避免改两遍）。
- 数据：`init_data_pg.sql` 与 `260803_agent_profile.sql` 里的 `MCP_ANSWER` 提示词行；`application.yaml` 的 `rag.mcp.*`；根 `pom.xml` 的 `<module>mcp-server</module>` 与 MCP SDK 依赖。
- 前端：意图树页面里的 MCP 表单项随 B10 一起删，本批不动。

**B9 旧评测端点与上下文选择** — `refactor(rag): remove replay-only evaluation endpoints and context selection`
- 删：`rag/eval/`（其余 5 个文件）、`rag/core/retrieval/selection/`（11 个文件）及各自测试；`application.yaml` 的 `ragent.eval.*`。

### 第三阶段：意图树（标签 `slim-3`）

**B10 意图树** — `refactor(rag): retrieve over all readable knowledge bases instead of an intent tree`

这是唯一改变主链路形状的一批，单独回归、单独可回退。

- 删：`rag/core/intent/`、`SubQuestionIntent` / `IntentGroup` / `IntentCandidate`、`IntentKind` / `IntentLevel`、`IntentTreeController` 与请求 / VO、`ingestion/service/IntentTreeService*`、`IntentNodeDO` / `IntentNodeMapper`、`intent-classifier.st`、`intentClassifyExecutor`、测试目录 `rag/Intent/` 与 `rag/core/intent/`；前端 `pages/admin/intent-tree/`、`services/intentTreeService.ts`、路由与导航；`BizChangeBizType.INTENT_TREE`。
- 改：
  1. `StreamChatPipeline`：去掉 `resolveIntents`、`handleSystemOnly`；闲聊类问题不再有 SYSTEM 节点短路，走检索后落到已有的“未检索到相关内容”分支——**这是一个可见的行为变化**，接受它，或者保留一个不依赖意图的极简闲聊判断（默认接受）。
  2. `RetrievalEngine.retrieve` 的入参由 `List<SubQuestionIntent>` 改为子问题字符串列表。
  3. **`RetrievalScopeResolver` 保留并简化**：恒定返回“启用中的知识库 ∩ 当前用户可读的知识库”。这是问答检索侧唯一应用 W4 权限过滤的位置；`RetrievalScopeResolverTest` 改写为只断言这一点，`KnowledgeRetrievalIsolationPostgresIT` 必须保持通过。
  4. `RetrievalScope`、`VectorSearchChannel:148`（按意图 `topK` 取深度，改用通道默认值）、`KnowledgeRetrievalResult` 的按意图归因、提示词里的 `promptSnippet` 注入与 `eligibleIntentIds`。
  5. `research/eval/ResearchRunCommand:224`、`ResearchCorpusCommand:228` 跟随构造函数签名调整（研究逻辑不变）。
  6. 配置：`rag.search.scope.min-intent-score / confidence-threshold / fallback-mode / supplement-ratio` 全部失去意义，删除；设置页与 `SystemSettingsVO.minIntentScore` 同步。
- 数据库：`schema_pg.sql` 删 `t_intent_node`；新增迁移 `upgrades/v1.1.0/260919_01_drop_intent_node.sql`（`DROP TABLE IF EXISTS`），并加入 `p2-database.sh` 的“新建库与升级库结构一致”校验；`260725_intent_multi_collections.sql` 保留（历史升级链）。Redis 键 `ragent:intent:tree` 手动清一次。
- 顺带核对：计划文件 W4 的“意图树绑定的知识库同样求交”在改动说明里改为“已随意图树移除，检索范围恒为可读集合”。
- 若本批回归或冒烟出现难以当天解决的问题：`git revert` 本批，瘦身在 `slim-2` 处收尾，不影响 S6。

## 5. 本地数据（不在 Git 内，**删除不可恢复，执行前逐项向用户确认**）

`local-data/` 共约 3.7 GB。

| 路径 | 体积 | 建议 |
| --- | --- | --- |
| `local-data/eval/` | 1.1 GB | 删（属于已退役的 context-selection / iron-ore 评测） |
| `agentic-research/runs/` 中非 `career_*` 的目录 | 约 660 MB | 删，**但保留 `20260917T145853_P7_regression_v4`（93 MB）**——它是 E1“命中率 5.2% / 0.02%”的原始证据 |
| `agentic-research/runs/career_*`（含 `career_X1_mq80_*` 复核） | 约 160 MB | 留；其中 39 个 `source-snapshot/` 是整份源码副本，可只删快照、保留台账与结果 |
| `agentic-research/raw/` | 1.2 GB | 可选：可重新下载；`prepared/research-data-v1` 在就不需要它 |
| `agentic-research/prepared/` | 686 MB | 留（`career-x1.sh` 的输入） |
| `source/` 两份 xlsx | 52 MB | 留（X5 输入）；pptx 与 PDF 由用户决定 |
| `backups/`、`stub-upstream/` | < 3 MB | 留 |

另：关联工作树 `../ragent-x1/{before,after}`、`../ragent-x3/{before,after}` 由实验脚本按需重建，可 `git worktree remove`；`ragent-new`、`ragent-study`、`ragent-iron-ore-baseline-1.1.0` 属于其他仓库分工，不动。

## 6. 预期结果（执行后用实测值替换）

| 指标 | 基线 | 预期 |
| --- | --- | --- |
| 被跟踪文件 | 1273 | 约 900 |
| Maven 模块 | 4 | 3（去掉 `mcp-server`） |
| 重量级依赖 | Milvus SDK、ES 客户端、MCP SDK | 全部移除 |
| `rag/` 包 | 270 个文件 / 2.8 万行 | 约 190 个文件 |
| 检索链路 | 改写 → 意图 → 引导 → 多通道（向量 / 关键词 / 图谱 / Web）+ MCP → 提示词 | 改写 → 向量检索（按权限过滤）→ rerank → 提示词 |
| `local-data/` | 3.7 GB | 约 1 GB（不删 `raw/` 则约 2.2 GB） |

## 7. 估时与顺序

B1—B5 约 2 小时（纯删除 + 链接修补）；B6、B7、B9 各约 1 小时；B8 约 2—3 小时；B10 约半天。合计 1—1.5 天。时间紧时在 `slim-2` 收尾即可，B10 可整体放弃。

## 8. 瘦身之后：实验统计加固（B11，标签 `slim-done`）

背景：企业知识库很小，检索质量不在其上报数字；工程指标的样本单位是调用、任务与故障场景。目前有两处是小样本或单次测量，用模拟上游补强，零接口费。

- **X2 恢复时间**：T1（`kill -9`）、T3（SIGTERM）、T4（SIGSTOP / SIGCONT）各重复 20 次，报告 P50 / P95 / 最大值，不变量逐次校验、任何一次失败都计入并如实报告。先看 `eval/agentic-research/x2_takeover.py` 与 `scripts/career-takeover-demo.sh` 是否已有重复参数，没有则加 `--repeat N`，并补 Python 单测。运行目录 `local-data/agentic-research/runs/career_X2_v2/`。
- **X3 成功率**：每个无响应率由 50 个任务加到 200 个（或 4 个随机种子 × 50），治理前后都跑，成功率与错误完成率附 Wilson 95% 区间。运行目录 `career_X3_v2/`。治理前的工作树由 `scripts/career-x3.sh` 按需重建。
- 留档：`eval/agentic-research/manifests/career-x2-repeat-<日期>.json`、`career-x3-repeat-<日期>.json`；在对应两篇改动说明的“效果”一节追加新数字（旧的单次数字保留并注明是单次），同步 `career-sprint-status.md` 的结果摘要。
- 超过几分钟的运行按冲刺计划 §11：由用户在终端后台启动，编码会话只读结果文件。
- 提交：`test(research): repeat takeover and fault-injection runs for distributions`；`docs: record repeated X2 and X3 results`。

## 9. 已确认的决定与仍需当场确认的事

| 事项 | 结论 |
| --- | --- |
| 意图树、引导问答、知识图谱、MCP | **删**（用户 2026-09-19 确认）。理由：(a) 知识库只有个位数，“全部可读库向量召回 + rerank”已够，意图分类每次请求多一次模型调用，与 W1 的成本主线相悖；(b) 它们是上游教学项目的标志性功能，留着会被追问却没有自己的数字可答；(c) 扩展点不丢——`SearchChannel` 接口与 `RetrievalScopeResolver` 这个唯一的范围判定点都保留，将来要做路由，是在这里加一种范围策略。意图树**在主链路上**，是行为变化而不只是删死代码 |
| 闲聊短路消失（B10） | 接受，不做替代实现 |
| 五篇 W1—W5 改动说明 | 默认**保留到 S6 结束**（S6 的直接输入） |
| B11 统计加固 | 做，放在瘦身之后、S6 之前 |
| `local-data/` 的删除（§5） | **未授权**。不可恢复；执行会话必须先列出将删除的路径与体积，得到用户当场确认后才执行，且无论如何保留 `20260917T145853_P7_regression_v4` 与全部 `career_*` 的台账和结果 |
| 计划外发现的可删内容 | 记入状态文件“遗留问题”，不擅自扩大范围 |

## 10. 执行纪律

- **会话划分**（一个会话只做一组，开始读状态文件，结束更新它）：① B1—B5 → `slim-1`；② B6、B7、B9；③ B8 → `slim-2`；④ B10 → `slim-3`；⑤ B11 → `slim-done`；之后回到冲刺计划的 S6。
- **首个会话的开场动作**：`git status --short --branch` 确认工作区干净且位于 `feat/llm-backend-hardening`；`git tag slim-v0-baseline`；`git switch -c chore/slim-down`；跑一次 §2 的完整回归并把通过数记入状态文件作为对照。
- **每批流程**：`grep` 复核本批清单 → 删除与修改 → §2 回归 → `git diff --cached --stat` → 提交 → 更新 `slim-down-status.md`（勾选、提交号、回归通过数、度量）。回归通过数只允许因“本批删除的测试”而减少，减少的类名写进提交说明。
- **停下来问用户的情形**：回归失败且 30 分钟内定位不了；发现某个“待删”对象被本文件未列出的代码引用且解耦超过约 50 行；要动 `research/`、租约、`KnowledgeAccessService`、`core/ingest/embed` 的逻辑（签名跟随调整除外）；任何 `local-data/` 删除；任何数据库 `DROP` 在本机 `ragent` 库上的执行（写迁移文件不需要问，执行需要）。
- **不做**：不 push、不 rebase、不 amend；不用裸 `git stash`；不用 `git add .`；不顺手重构与瘦身无关的代码；不重写 README 正文（S6 的事），只修失效链接。
- **全部完成后**：`git switch feat/llm-backend-hardening && git merge --ff-only chore/slim-down`；把 `career-sprint-status.md` 的“当前工作项”改回 S6，并在“已知事实”里加一行指向 `slim-v0-baseline`。
