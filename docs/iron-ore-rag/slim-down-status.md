# 瘦身状态

计划：[slim-down-plan-2026-09-19.md](slim-down-plan-2026-09-19.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-19（会话 ② B6、B7、B9 完成；`slim-2` 待 B8 后打） |
| 当前批次 | B8（会话 ③：引导问答 + MCP，两个提交 → `slim-2`） |
| 分支 / 标签 | `chore/slim-down`；`slim-v0-baseline` = `20c5951`，`slim-1` = `cc83b83` |
| 对照回归 | B1—B5：Py 41、Java 13 + 260；B6 后 13 + 258；B7、B9 后 13 + 238；p2 每批通过；前端 build 通过（B2、B6、B7） |

## 进度
- [x] 开场：`slim-v0-baseline`、`chore/slim-down`、对照回归
- [x] B1 文档 `3317b82`；B2 杂物 `b6b1b72`（vite 现加载 `vite.config.ts`）；B3 脚本 `298490f`
- [x] B4 评测目录 `0a874a5`（`context-selection` 的 4 个辅助函数原样迁入 `eval/agentic-research/sourcekit.py`，用户同意）
- [x] B5 数据库与编排 `cc83b83` → 标签 `slim-1`（删前已在本机 `ragent` 执行 `260917_retire_execution_demo.sql`，用户同意）
- [x] B6 Milvus `084529a`（−2：`MilvusVectorRetrieverServiceTest`）
- [x] B7 关键词（ES）与图谱 `725efc9`（−20：`KeywordSearchChannelTest` 10、`GraphSearchChannelTest` 7、`EsKeywordRetrieverServiceTest` 3）
- [x] B9 旧评测端点与上下文选择 `e2e7e5c`（p7 不变）
- [ ] B8 引导问答、MCP → 标签 `slim-2`
- [ ] B10 意图树 → 标签 `slim-3`
- [ ] §5 本地数据清理（需用户当场确认）
- [ ] B11 X2 / X3 重复实验 → 标签 `slim-done`，合并回 `feat/llm-backend-hardening`

## 度量（每个标签处填实测值）
| 标签 | 被跟踪文件 | `bootstrap/src/main` Java 行数 | 依赖 jar 数 | 回归通过数 | 提交 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 1275（含计划两文件） | 60,349 | 407 | Py 41、Java 13 + 260 | `20c5951` |
| `slim-1` | 1052 | 60,349 | 407 | Py 41、Java 13 + 260 | `cc83b83` |
| B9 后（未打标签） | 993 | 55,770 | 394 | Py 41、Java 13 + 238 | `e2e7e5c` |
- jar 数口径：`./mvnw -o -pl bootstrap -am dependency:list` 输出去重后的 `group:artifact:jar` 个数（B1—B5 未改 pom，基线同值）。
- 冒烟做法（B6 起每批，全部通过）：用 `schema_pg.sql` + `init_data_pg.sql` 建临时库 `slim_smoke`、Redis 用 db 7，`stub-upstream.sh` + `--spring.profiles.active=stub` 起应用，登录 → 建库 → 上传 → 切块 → 问答 → 研究任务 → 删库，结束删临时库。本机 `ragent` 库缺研究表（`t_research_run`），不能直接做研究冒烟。

## 遗留问题与计划外发现
- 本地未跟踪目录未删（自动审批拦截了 `rm -rf`，待用户手动或授权）：`.agents/`、`.codex/`（空）、根 `.vite/`（12 KB）、`robot-gateway/ros1_ws/`（3.7 MB，只有 catkin 构建产物，`src/` 无文件）；另有 `eval/context-selection/`、`eval/iron-ore/` 下残留的 `__pycache__/`。`ros1_ws` 删除后可去掉 `.gitignore` 里 5 行 `/robot-gateway/ros1_ws/*`。
- 根 `README.md` 与 `eval/agentic-research/README.md` 只去掉了失效链接和已删脚本的命令，留有“见执行记录”等无链接的旧说法，正文重写留给 S6。
- **待用户决定**：`ragent.eval.enabled: true` 未删（B9）——它同时被 `framework` 的 `IdempotentSubmitAspect` 读取，为 true 时跳过 `/rag/v3/chat`、`/rag/v3/stop` 的防重锁（上游“测评模式”遗留）；删掉即开启防重锁，属行为变化。
- B7 计划外的小改动：`SearchChannelType` 去掉两值后连带改了 `ChannelAttribution`、`FusionPostProcessor`、`RerankPostProcessor`（删图谱存活日志）及两个测试（改用 WEB_SEARCH / VECTOR）；`rag/config/validation/` 只删了 4 个检索通道类，`MemoryConfigValidator`、`ValidMemoryConfig` 仍在用，保留。前端 `tsc --noEmit` 有 24 个既有错误（`KnowledgeDocumentsPage`、`chatStore`、`FeedbackButtons`、`IngestionPage`），与瘦身无关，未动；`npm run build` 只跑 vite 不做类型检查。
- 首次冒烟误连本机 `ragent` 库，留下已软删的知识库 `slim-smoke-b6`（`2101170726756200448`）与一条会话 `2101170998647762944`，未清理；启动时 MCP 连接失败的 ERROR 属预期，B8 后消失。开发用 RocketMQ broker 容器此前已停，本会话 `docker start` 过。
- 新生成的 prepared 数据集清单里转换器指纹改为 `sourcekit.py`（旧清单记录的是 `cs_evalkit.py`；只记录、不校验）。
