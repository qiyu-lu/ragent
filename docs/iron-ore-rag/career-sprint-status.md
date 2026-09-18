# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（W1 代码完成，X1 已备好、待用户后台运行） |
| 当前工作项 | W1：第 1—3 项完成（代码 `b186852`、`73deab5`），第 4 项 X1 待运行 |
| 分支 / 提交 | `feat/llm-backend-hardening`，自 `65c99c7`（标签 `career-v0-baseline`）拉出 |
| 回归通过数 | `LC_ALL=en_US.UTF-8 bash scripts/validate-agentic-research-p7.sh`：基线 Python 33/33、Java 217/217；W1 后 Python 36/36、Java 220/220，约 20 s |
| 最近的运行目录 | X1 未开始：`local-data/agentic-research/runs/career_X1_v1_*`；协议探针 34 次调用见 `eval/agentic-research/manifests/career-cache-probe-2026-09-18.json` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [ ] W1 缓存友好的上下文布局 + X1（[x] 文档与探针 [x] 布局与压缩 [x] 台账与报告 [ ] X1）
- [ ] W3 模拟上游与故障注入基准 + X3
- [ ] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## W1 结论与实现（qwen3.7-flash / max，2026-09-18）

- 文档：隐式缓存自动、前缀 ≥ 1024 token、命中计 20%、不保证命中；显式缓存为内容块内 `cache_control`，5 分钟有效，写入 125%、命中 10%；两者单请求互斥；工具定义算系统消息。
- C 为何为 0：系统消息（含工具）内任何改动都使请求 0 命中（即使前 1,000—1,944 token 相同），旧布局每次调用都改写首条系统消息。B 的 5.2% 是恒为 1280 token 的命中（工具 714 + 静态提示 632 + 模板 17 按 128 取整），属供应商未公开的共享前缀行为，探针复现不了，不可依赖。
- 稳定前缀后隐式命中逐步仍不稳定（4 次中 1 次）；显式标记打在最新一条历史上则逐步确定命中整段旧前缀、只写增量。AgentScope 自带的消息级标记被百炼忽略，所以由传输层改写为内容块并读回写入量。
- 实现：系统消息只放静态提示；提醒改为末尾临时 user 消息、不进记忆；越线一次压到 60%（先存根后整轮移除，水位固定）；首条消息按键排序；台账新增 `durationMs`、`firstTokenMs`、`cacheCreationTokens`、`cacheType`；`cache_report.py` 对 P7 复算与 E1 一致（B 5.19%、C 0.02%）。

## 下一步：X1（由用户在终端后台运行，编码 Agent 不等待、不轮询）

- 命令：`cd /home/sd101t/IdeaProjects/ragent-iron-ore-rag && nohup bash scripts/career-x1.sh > local-data/agentic-research/runs/career_X1_v1.log 2>&1 &`；中断后原命令重跑即续跑。
- 过程：工作树 `../ragent-x1/before`（`career-v0-baseline`）与 `../ragent-x1/after`（`73deab5`）已建好并编译；先跑 smoke（固定题外 3 题 × B、C），embedding 失败率 > 10% 或任务缺失、失败 > 2 个即停；再按 before B → after B → after C → before C 各跑 40 题（`manifests/career-cache-case-ids.json`，配置 `configs/career-x1.json` 与 P7 相同）。
- 预计 60—90 分钟（P7 单任务 P50 约 35 s、并发 2；embedding 慢时更长），约 166 个任务的真实调用。
- 结果：总日志 `runs/career_X1_v1.log`，各批日志 `runs/career_X1_v1_<批次>.log`；批次目录 `runs/career_X1_v1_{smoke_B,smoke_C,before_B,after_B,after_C,before_C}/`（`summary.json` 含完成率与 F1 护栏）；缓存报告 `runs/career_X1_v1_cache_report.{md,json}`（`runs/` 即 `local-data/agentic-research/runs/`）。
- X1 之后：写精简汇总 `eval/agentic-research/manifests/career-x1-<日期>.json`、改动说明与 `changes/README.md` 索引，更新本文件，打标签 `career-w1`，`git worktree remove` 两个 X1 工作树。smoke 若因 embedding 停止，先按计划附录切本地 embedding。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交；2026-09-18 15:42 起该目录已不在工作区，非计划会话所为。
- 回归必须在英文语言环境下跑：默认 `LANG=zh_CN.UTF-8` 时 JSON Schema 报错被本地化为中文，`ResearchNativeToolsTest` 3 个用例失败；发给模型的参数错误反馈语言也随宿主机变化，未修，待用户决定。
- 协议探针共 34 次调用（29 次定位 + 5 次重放新代码请求），多于计划的 3—5 次；压缩后回看超过 20 个内容块时显式缓存需重写一次前缀，写入改动说明的限制部分。
