# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（W3 进行中） |
| 当前工作项 | W3：模拟上游已完成（`4ace8c0`）；下一步抖动退避，再交 X3 |
| 分支 / 提交 | `feat/llm-backend-hardening`，自 `65c99c7`（标签 `career-v0-baseline`）拉出；W1 完成于标签 `career-w1` |
| 回归通过数 | `LC_ALL=en_US.UTF-8 bash scripts/validate-agentic-research-p7.sh`：基线 Python 33/33、Java 217/217；W1 后 Python 36/36、Java 220/220，约 20 s |
| 最近的运行目录 | `local-data/agentic-research/runs/career_X1_v1_*`（X1）；汇总 `eval/agentic-research/manifests/career-x1-2026-09-18.json` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [x] W1 缓存友好的上下文布局 + X1（改动说明 [2026-09-18-prompt-cache-stable-prefix.md](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [ ] W3 模拟上游与故障注入基准 + X3（[x] 模拟上游、stub profile、小语料、启动脚本；[ ] 抖动退避；[ ] X3）
- [ ] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## W1 结果摘要（引用数字时连同条件一起说）

- 根因：易变提醒写进首条系统消息；百炼对系统消息（含工具定义）内任何改动都整请求不命中，所以 C 为 0；B 的 5.2% 是供应商未公开的 1280 token 固定命中，不可依赖。协议探针见 `eval/agentic-research/manifests/career-cache-probe-2026-09-18.json`。
- 做法：静态系统消息 + 末尾临时提醒（不进记忆）；阈值压缩到 60%（先存根后整轮移除，水位固定）；首条消息按键排序；百炼显式缓存断点打在系统消息与最新一条历史上，传输层改写为内容块并读回写入量；台账补 `durationMs`、`firstTokenMs`、`cacheCreationTokens`、`cacheType`；报告 `cache_report.py`。
- X1（同 40 题，B、C，全 Flash，真实上游，ABBA 各一次）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%（其中单任务输入本身降 12% / 18%）；单次调用 P50 −7.8% / −0.6%，每任务首次调用慢约 0.35 s；答案 F1 除 MuSiQue C 外在配对自助法噪声内。
- X1 两个临时工作树已删除；需要复算时用 `git worktree add <目录> 73deab5`（或 `career-v0-baseline`）重建。

## 下一步

W3：embedding 重试改为带抖动的指数退避（`AbstractOpenAIStyleEmbeddingClient` 现为固定 300 ms）并补测试；核对研究路径 embedding 失败是否进入候选熔断统计；然后写 X3 交接脚本。模拟上游用法：`scripts/stub-upstream.sh [--embedding-hang 0.3 …]`，语料库 `research_corpus_stub`（SRC-001…060）；治理前工作树 `../ragent-x3/before` @ `3381fa9`（已编译，经 `AI_PROVIDERS_*_URL` 环境变量接入模拟上游，无需配置还原）。smoke（4 题）：50% 无响应时治理前 3/4 在 30 s 处 `RESEARCH_TIMEOUT` 失败，治理后 4/4 完成。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交；2026-09-18 15:42 起该目录已不在工作区，非计划会话所为。
- 回归必须在英文语言环境下跑：默认 `LANG=zh_CN.UTF-8` 时 JSON Schema 报错被本地化为中文，`ResearchNativeToolsTest` 3 个用例失败；发给模型的参数错误反馈语言也随宿主机变化，未修，待用户决定。
- W1 待复核：MuSiQue C 可回答 6 题中 3 题答案 F1 下降（2 题措辞冗长、1 题换了实体），after 组单任务调用数也下降；要更大样本就用 `scripts/career-x1.sh` 换新的 `X1_STAMP` 与题集。X1 未触发压缩，C 几乎不委派。
- 显式缓存依赖百炼内容块语法；`research.explicit-prompt-cache: false` 可关闭，关闭后只剩不保证命中的隐式缓存。
