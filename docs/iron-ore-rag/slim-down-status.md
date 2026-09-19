# 瘦身状态

计划：[slim-down-plan-2026-09-19.md](slim-down-plan-2026-09-19.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-19（会话 ⑤ B11 第一个提交完成，等用户后台跑 X2 / X3） |
| 当前批次 | B11 后半：用户后台跑完 X2 v2、X3 v2 后，读结果写 manifest 与改动说明（第二个提交）→ `slim-done`；§5 本地数据清理待用户确认 |
| 分支 / 标签 | `chore/slim-down`；`slim-v0-baseline` = `20c5951`，`slim-1` = `cc83b83`，`slim-2` = `af8ae06`，`slim-3` = `6120945` |
| 对照回归 | B1—B5：Py 41、Java 13 + 260；B6 后 13 + 258；B7、B9、B8 后 13 + 238；B10 后 13 + 211；B11 `a9423dd` 后 Py 46（+5，新增 `test_repeat_stats`）、Java 13 + 211；p2 每批通过；前端 build 通过（B2、B6、B7、B10） |

## 进度
- [x] 开场（基线标签、分支、对照回归）；B1 `3317b82`、B2 `b6b1b72`（vite 现加载 `vite.config.ts`）、B3 `298490f`、B4 `0a874a5`（4 个辅助函数迁入 `sourcekit.py`）、B5 `cc83b83` → `slim-1`（删前已在本机 `ragent` 执行 `260917_retire_execution_demo.sql`，用户同意）
- [x] B6 Milvus `084529a`（−2：`MilvusVectorRetrieverServiceTest`）；B7 ES 与图谱 `725efc9`（−20：`KeywordSearchChannelTest` 10、`GraphSearchChannelTest` 7、`EsKeywordRetrieverServiceTest` 3）；B9 `e2e7e5c`（p7 不变）
- [x] B8 引导 `8de1afa`；MCP `989c9ab` + `af8ae06`（前者误提交时只含删除、单独编译不过，两者须一起回退）→ `slim-2`（p7 不变；p7 外删 `YouComSearchMcpExecutor*Test` 与 `RAGPromptServiceTest` 的 2 个 MCP 用例）
- [x] B10 意图树 `6120945` → `slim-3`（−27，全是意图作用域用例：`VectorSearchChannelTest` 15→5、`RetrievalScopeResolverTest` 15→4、`RetrievalEngineTest` 9→5、`MultiChannelRetrievalEngineTest` 9→7；闲聊短路消失，`SYSTEM_CHAT` 槽位与种子行随之删除）
- [ ] §5 本地数据清理（需用户当场确认）
- [x] B11 前半 `a9423dd`：`x2_takeover.py --repeat N`（出错的重复记为 errored、不重试；报告恢复 P50/P95/最大值、不变量失败数、重复调用分布）、`x3_upstream.py --seeds`（每种子一整套格，按版本与故障率合并并附 Wilson 95% 区间）；`career-x3.sh` 改用本仓库的驱动（与 `97edcc1` 版本除新选项外无差异）。v1 结果用新报告重算，数字不变；X2 T0、T1 各 2 次的实跑通过（T1 恢复 5.11 s × 2）
- [ ] B11 后半：用户依次（不并行，互扰计时）后台跑 `X2_STAMP=career_X2_v2 X2_SCENARIOS=T0,T1,T3,T4 X2_REPEAT=20 bash scripts/career-takeover-demo.sh`（约 50 分钟）与 `X3_STAMP=career_X3_v2 X3_SEEDS=7,11,13,17 bash scripts/career-x3.sh`（约 2 小时，复用 `../ragent-x3`）；之后读 `x2-report.md`、`x3-report.md` → manifest `career-x2/x3-repeat-<日期>.json`、两篇改动说明“效果”追加、`career-sprint-status.md` 结果摘要 → 提交 `docs: record repeated X2 and X3 results` → `slim-done` → ff 合并回 `feat/llm-backend-hardening`

## 度量（每个标签处填实测值）
| 标签 | 被跟踪文件 | `bootstrap/src/main` Java 行数 | 依赖 jar 数 | 回归通过数 | 提交 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 1275（含计划两文件） | 60,349 | 407 | Py 41、Java 13 + 260 | `20c5951` |
| `slim-1` | 1052 | 60,349 | 407 | Py 41、Java 13 + 260 | `cc83b83` |
| B9 后（未打标签） | 993 | 55,770 | 394 | Py 41、Java 13 + 238 | `e2e7e5c` |
| `slim-2` | 965 | 53,773 | 393 | Py 41、Java 13 + 238 | `af8ae06` |
| `slim-3` | 925 | 49,757 | 393 | Py 41、Java 13 + 211 | `6120945` |
- jar 数口径：`./mvnw -o -pl bootstrap -am dependency:list` 输出去重后的 `group:artifact:jar` 个数（B1—B5 未改 pom，基线同值）。
- 冒烟做法（B6 起每批，全部通过）：用 `schema_pg.sql` + `init_data_pg.sql` 建临时库 `slim_smoke`、Redis 用 db 7，`stub-upstream.sh` + `--spring.profiles.active=stub` 起应用，登录 → 建库 → 上传 → 切块 → 问答 → 研究任务 → 删库，结束删临时库。本机 `ragent` 库缺研究表（`t_research_run`），不能直接做研究冒烟。

## 遗留问题与计划外发现
- B11：X2 v2 跑的是瘦身后的代码（v1 在 `cb42dc4`），同时算作 W2 在瘦身后的回归；X3 两版仍是 `3381fa9` / `97edcc1`，与 v1 同一代码。§5 实测（2026-09-19）：`local-data/eval/` 1.1 GB；`runs/` 下非 `career_*` 且非 `P7_regression_v4` 的条目 85 个、约 536 MB；`career_*` 下 `source-snapshot/` 只找到 8 个、约 8 MB（计划写的 39 个不符）；`raw/` 1.2 GB。
- B10 本地清理（用户同意）：本机 `ragent` 已执行 `260919_01_drop_intent_node.sql`（`t_intent_node` 4 行），删 Redis db 0 的 `ragent:intent:tree`，删 `t_agent_prompt` 中 `SYSTEM_CHAT`、`MCP_ANSWER`、`MIXED_ANSWER` 3 行残留。B10 计划外：`iron-ore-demo` profile 的 `fallback-mode: empty` 随配置项删除，该 profile 现检索全部可读库；`KB_ANSWER` 种子提示词里仍描述“意图补充规则 `<rules>`”（DB 可编辑内容，未改）；`RetrievalCapture` 与 `retrieve(..., capture)` 重载在 B9 后已无调用方，未删；前端 `traceUtils.ts` 的 `intent-resolve` 展示映射保留（旧链路记录仍可显示）。`rag/` 现 189 个文件 / 1.8 万行。
- **待用户决定（B8）**：MCP SDK 仍经 `agentscope-core 2.0.1` 传递引入；此前根 pom 把它钉在 1.1.2，删版本管理后研究运行时改用 agentscope 自带的 0.17.0（jar 数因此只 −1）。回归与研究冒烟通过；要保持 W1—W5 时的 classpath 就在根 pom 加回 1.1.2 的版本钉。B8 计划外：`MIXED_ANSWER`（KB + MCP 场景）随 `MCP_ANSWER` 一并删槽位与种子行；本地库残留的两行已于 B10 清理时删除。前端 `traces/traceUtils.ts` 里 `GUIDANCE` / `guidance-detect` 的展示映射未动（计划本批不动前端）。
- 本地未跟踪目录未删（自动审批拦截了 `rm -rf`，待用户手动或授权）：`.agents/`、`.codex/`（空）、根 `.vite/`（12 KB）、`robot-gateway/ros1_ws/`（3.7 MB，只有 catkin 构建产物，`src/` 无文件）；另有 `eval/context-selection/`、`eval/iron-ore/` 下残留的 `__pycache__/`。`ros1_ws` 删除后可去掉 `.gitignore` 里 5 行 `/robot-gateway/ros1_ws/*`。
- 根 `README.md` 与 `eval/agentic-research/README.md` 只去掉了失效链接和已删脚本的命令，留有“见执行记录”等无链接的旧说法，正文重写留给 S6。
- **待用户决定**：`ragent.eval.enabled: true` 未删（B9）——它同时被 `framework` 的 `IdempotentSubmitAspect` 读取，为 true 时跳过 `/rag/v3/chat`、`/rag/v3/stop` 的防重锁（上游“测评模式”遗留）；删掉即开启防重锁，属行为变化。
- B7 计划外的小改动：`SearchChannelType` 去掉两值后连带改了 `ChannelAttribution`、`FusionPostProcessor`、`RerankPostProcessor`（删图谱存活日志）及两个测试（改用 WEB_SEARCH / VECTOR）；`rag/config/validation/` 只删了 4 个检索通道类，`MemoryConfigValidator`、`ValidMemoryConfig` 仍在用，保留。前端 `tsc --noEmit` 有 24 个既有错误（`KnowledgeDocumentsPage`、`chatStore`、`FeedbackButtons`、`IngestionPage`），与瘦身无关，未动；`npm run build` 只跑 vite 不做类型检查。
- prepared 清单的转换器指纹改为 `sourcekit.py`（只记录、不校验）。首次冒烟误连本机 `ragent` 库，留下已软删的知识库 `slim-smoke-b6`（`2101170726756200448`）与一条会话 `2101170998647762944`，未清理；启动时 MCP 连接失败的 ERROR 已随 B8 消失（冒烟确认）。开发用 RocketMQ broker 容器此前已停，本会话 `docker start` 过。
