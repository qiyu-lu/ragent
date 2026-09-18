# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（W3 代码完成，等待用户跑 X3） |
| 当前工作项 | W3：模拟上游、抖动退避、熔断补缺已提交；**X3 待用户后台运行**，之后写报告、改动说明、打 `career-w3` |
| 分支 / 提交 | `feat/llm-backend-hardening`；W1 标签 `career-w1`；W3 代码截至 `97edcc1`，交接脚本截至 `00cb75e` |
| 回归通过数 | `bash scripts/validate-agentic-research-p7.sh`：W1 后 Python 36/36、Java 220/220；W3 后 Python 41/41、Java 225/225（13 + 212），`zh_CN` 下同样全绿，约 25 s |
| 最近的运行目录 | X3：`local-data/agentic-research/runs/career_X3_v1/`；X1：`runs/career_X1_v1_*`，汇总 `eval/agentic-research/manifests/career-x1-2026-09-18.json` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [x] W1 缓存友好的上下文布局 + X1（改动说明 [2026-09-18-prompt-cache-stable-prefix.md](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [ ] W3 模拟上游与故障注入基准 + X3（[x] 模拟上游、stub profile、小语料、启动脚本 `4ace8c0`；[x] 抖动退避 `7b3d86e`；[ ] X3 运行与报告）
- [ ] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## W1 结果摘要（引用数字时连同条件一起说）

- 根因：易变提醒写进首条系统消息，百炼对系统消息内任何改动都整请求不命中；改为静态前缀 + 末尾临时提醒、阈值压缩、显式缓存断点（`research.explicit-prompt-cache: false` 可关）。X1（同 40 题，B、C，全 Flash，真实上游，ABBA）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%；单次调用 P50 −7.8% / −0.6%；答案 F1 除 MuSiQue C 外在配对自助法噪声内。

## 交给用户后台运行（编码 Agent 不等待、不轮询）

1. **X3**（零接口费，约 40—60 分钟）：`nohup bash scripts/career-x3.sh > local-data/agentic-research/runs/career_X3_v1.log 2>&1 &`。建 `../ragent-x3/before`（`3381fa9`，已存在）与 `../ragent-x3/after`（`97edcc1`）并编译，50 题 × 无响应 0/10/30/50% × 前后，每格独立模拟上游与隔离库。结果：`runs/career_X3_v1/x3-report.md`、`x3-summary.json`，逐格目录 `<before|after>_embeddingNN/`（`predictions.jsonl`、`stub-requests.jsonl`、`java.log`）。中断后原命令重跑，完成的格保留。
2. **MuSiQue C 复核**（真实百炼 + SiliconFlow，约 1 小时，可与 X3 错开跑）：`X1_STAMP=career_X1_mq80 X1_CASES=$PWD/eval/agentic-research/manifests/career-x1-musique-recheck-case-ids.json X1_MODES=C X1_SMOKE=0 nohup bash scripts/career-x1.sh > local-data/agentic-research/runs/career_X1_mq80.log 2>&1 &`。80 道未用过的 MuSiQue 题，C 模式，`career-v0-baseline` 对 `73deab5`；结果 `runs/career_X1_mq80_{before,after}_C/summary.json` 与 `runs/career_X1_mq80_cache_report.md`。

## 下一步（下个会话）

读 X3 结果 → 精简汇总存 `eval/agentic-research/manifests/career-x3-2026-09-18.json` → 改动说明（模拟上游设计、三处修复、X3 数字注明“基于模拟上游”）并更新 `changes/README.md` → 打 `career-w3`。若复核已跑完，把 MuSiQue C 结论补进 W1 改动说明。之后进入 W2。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交。
- smoke 已见：50% 无响应时治理前在 30 s 处 `RESEARCH_TIMEOUT` 整体失败，治理后重试后完成。X3 的评测命令直连 `SiliconFlowEmbeddingClient`，不经 `ModelRoutingExecutor`，所以熔断修复（`77ca3ae`，研究路径失败此前不计入熔断、半开名额取消后不归还）不在 X3 数字里，只由单测覆盖。
- 工具参数错误反馈已固定英文（`97edcc1`），回归不再需要 `LC_ALL=en_US.UTF-8`。
