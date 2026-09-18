# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（X1 完成，W1 收尾中） |
| 当前工作项 | W1 收尾：X1 汇总与改动说明已提交，下一项打标签、删除 X1 工作树 |
| 分支 / 提交 | `feat/llm-backend-hardening`，自 `65c99c7`（标签 `career-v0-baseline`）拉出 |
| 回归通过数 | `LC_ALL=en_US.UTF-8 bash scripts/validate-agentic-research-p7.sh`：基线 Python 33/33、Java 217/217；W1 后 Python 36/36、Java 220/220，约 20 s |
| 最近的运行目录 | `local-data/agentic-research/runs/career_X1_v1_*`（X1 已完成）；协议探针见 `eval/agentic-research/manifests/career-cache-probe-2026-09-18.json` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [ ] W1 缓存友好的上下文布局 + X1（[x] 文档与探针 [x] 布局与压缩 [x] 台账与报告 [x] X1 [x] 改动说明 [ ] 标签）
- [ ] W3 模拟上游与故障注入基准 + X3
- [ ] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## W1 结论与实现（qwen3.7-flash / max，2026-09-18）

- 文档：隐式缓存自动、前缀 ≥ 1024 token、命中计 20%、不保证命中；显式缓存为内容块内 `cache_control`，5 分钟有效，写入 125%、命中 10%；两者单请求互斥；工具定义算系统消息。
- C 为何为 0：系统消息（含工具）内任何改动都使请求 0 命中（即使前 1,000—1,944 token 相同），旧布局每次调用都改写首条系统消息。B 的 5.2% 是恒为 1280 token 的命中（工具 714 + 静态提示 632 + 模板 17 按 128 取整），属供应商未公开的共享前缀行为，探针复现不了，不可依赖。
- 稳定前缀后隐式命中逐步仍不稳定（4 次中 1 次）；显式标记打在最新一条历史上则逐步确定命中整段旧前缀、只写增量。AgentScope 自带的消息级标记被百炼忽略，所以由传输层改写为内容块并读回写入量。
- 实现：系统消息只放静态提示；提醒改为末尾临时 user 消息、不进记忆；越线一次压到 60%（先存根后整轮移除，水位固定）；首条消息按键排序；台账新增 `durationMs`、`firstTokenMs`、`cacheCreationTokens`、`cacheType`；`cache_report.py` 对 P7 复算与 E1 一致（B 5.19%、C 0.02%）。限制：压缩后回看超过 20 个内容块时显式缓存要重写一次前缀；探针 34 次调用多于计划的 3—5 次（查明 C 的 0、重放新代码请求）。

## X1 结果（2026-09-18 16:57—17:52，真实百炼 Flash + SiliconFlow；汇总 `eval/agentic-research/manifests/career-x1-2026-09-18.json`）

- 缓存：命中率 B 9.8%→78.4%、C 0.2%→80.0%；计费输入/输入 92.2%→32.3%、99.8%→30.7%；单任务计费输入 62,678→19,361（−69%）、76,458→19,352（−75%），其中单任务输入本身也降 12%、18%（after 调用更少）。
- 延迟：单次调用 P50 B −7.8%、C −0.6%，P95 −18%、−0.9%；每个任务的首次调用慢约 0.35 s；首 token 只有 after 有（P50 687 / 771 ms）；单任务耗时 P50 45→34 s、41→36 s。
- 护栏：完成 B 38 + 1 FAILED + 1 PARTIAL → 40、C 40 → 39 + 1 PARTIAL；答案 F1 配对自助法 95% 区间均含 0，唯 MuSiQue C 可回答 6 题 3 降 0 升（2 题同实体但措辞冗长，1 题 6 次调用即给出不同实体，旧版 14 次）。未触发压缩；C 几乎不委派，worker 缓存未被 X1 覆盖。
- 改动说明：[`changes/2026-09-18-prompt-cache-stable-prefix.md`](changes/2026-09-18-prompt-cache-stable-prefix.md)（已入索引）。下一步：打标签 `career-w1` → 删除 `../ragent-x1/{before,after}` 两个工作树。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交；2026-09-18 15:42 起该目录已不在工作区，非计划会话所为。
- 回归必须在英文语言环境下跑：默认 `LANG=zh_CN.UTF-8` 时 JSON Schema 报错被本地化为中文，`ResearchNativeToolsTest` 3 个用例失败；发给模型的参数错误反馈语言也随宿主机变化，未修，待用户决定。
