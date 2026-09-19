# 瘦身状态

计划：[slim-down-plan-2026-09-19.md](slim-down-plan-2026-09-19.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-19（会话 ① B1—B5 完成，已打 `slim-1`） |
| 当前批次 | B6（会话 ②：B6、B7、B9；B6 起每批加手动冒烟） |
| 分支 / 标签 | `chore/slim-down`；`slim-v0-baseline` = `20c5951`，`slim-1` = `cc83b83` |
| 对照回归 | 基线与 B1—B5 每批相同：Python 41/41、Java 13 + 260、p2 通过；前端 build 通过（B2） |

## 进度

- [x] 开场：`slim-v0-baseline`、`chore/slim-down`、对照回归
- [x] B1 文档 `3317b82`
- [x] B2 杂物 `b6b1b72`（前端 build 通过；vite 现加载 `vite.config.ts`）
- [x] B3 脚本 `298490f`
- [x] B4 评测目录 `0a874a5`（`context-selection` 的 4 个辅助函数原样迁入 `eval/agentic-research/sourcekit.py`，用户同意）
- [x] B5 数据库与编排 `cc83b83` → 标签 `slim-1`（删前已在本机 `ragent` 执行 `260917_retire_execution_demo.sql`，用户同意）
- [ ] B6 Milvus
- [ ] B7 关键词（ES）与图谱
- [ ] B9 旧评测端点与上下文选择
- [ ] B8 引导问答、MCP → 标签 `slim-2`
- [ ] B10 意图树 → 标签 `slim-3`
- [ ] §5 本地数据清理（需用户当场确认）
- [ ] B11 X2 / X3 重复实验 → 标签 `slim-done`，合并回 `feat/llm-backend-hardening`

## 度量（每个标签处填实测值）

| 标签 | 被跟踪文件 | `bootstrap/src/main` Java 行数 | 依赖 jar 数 | 回归通过数 | 提交 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 1275（含计划两文件） | 60,349 | 407 | Py 41、Java 13 + 260 | `20c5951` |
| `slim-1` | 1052 | 60,349 | 407 | Py 41、Java 13 + 260 | `cc83b83` |
- jar 数口径：`./mvnw -o -pl bootstrap -am dependency:list` 输出去重后的 `group:artifact:jar` 个数（B1—B5 未改 pom，基线同值）。

## 遗留问题与计划外发现

- 本地未跟踪目录未删（自动审批拦截了 `rm -rf`，待用户手动或授权）：`.agents/`、`.codex/`（空）、根 `.vite/`（12 KB）、`robot-gateway/ros1_ws/`（3.7 MB，只有 catkin 构建产物，`src/` 无文件）；另有 `eval/context-selection/`、`eval/iron-ore/` 下残留的 `__pycache__/`。`ros1_ws` 删除后可去掉 `.gitignore` 里 5 行 `/robot-gateway/ros1_ws/*`。
- 根 `README.md` 与 `eval/agentic-research/README.md` 只去掉了失效链接和已删脚本的命令，留有“见执行记录”等无链接的旧说法，正文重写留给 S6。
- 新生成的 prepared 数据集清单里转换器指纹改为 `sourcekit.py`（旧清单记录的是 `cs_evalkit.py`；只记录、不校验）。
