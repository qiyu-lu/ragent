# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（W4 完成；MuSiQue C 复核已补入 W1） |
| 当前工作项 | W5：内容寻址的 embedding 复用 + X5（尚未开始） |
| 分支 / 提交 | `feat/llm-backend-hardening`；标签 `career-w1`、`career-w3`、`career-w2`、`career-w4` |
| 回归通过数 | `bash scripts/validate-agentic-research-p7.sh`：W4 后 Python 41/41、Java 13 + 247（W2 后 13 + 227），约 40 s；另有 `validate-agentic-research-p2-database.sh` 结构校验 |
| 最近的运行目录 | X2：`local-data/agentic-research/runs/career_X2_v1/`；X3：`runs/career_X3_v1/`；X1：`runs/career_X1_v1_*` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [x] W1 缓存友好的上下文布局 + X1（[改动说明](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [x] W3 模拟上游、抖动退避、熔断补缺 + X3（[改动说明](changes/2026-09-18-upstream-fault-injection.md)）
- [x] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2（[改动说明](changes/2026-09-18-durable-research-execution.md)）
- [x] W4 权限隔离 + 越权矩阵（[改动说明](changes/2026-09-18-knowledge-base-access-control.md)）
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## 结果摘要（引用数字时连同条件一起说）

- W1（真实上游，同 40 题 B、C，全 Flash）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%。
- W3（模拟上游，50 任务，embedding 无响应 10/30/50%）：成功率 88/54/40% → 100/100/82%；两版错误完成均为 0。
- W2（模拟上游，独立 JVM 共享运行库，租约 6 s）：kill -9 恢复 4.94 s、SIGSTOP 4.97 s、SIGTERM 0.95 s；每个被接管任务重复 1 次模型调用（在途那次）；5 类故障下不变量全部成立；毒任务 4 次失联后 EXECUTOR_LOST。
- W1 复核（MuSiQue C，80 道新题，可答 45）：命中率 0.05%→80.1%、单任务计费输入 −72% 复现；答案 F1 0.443→0.352，配对差 −0.09，区间 [−0.21, +0.02] 含 0，“资料中没有”式回答 12→16，未处理。
- W4（JUnit + 真实 PostgreSQL/pgvector）：越权矩阵 299 项全部符合（173 允许、126 拒绝，拒绝时业务服务未被调用）；私有库放最佳匹配时，其他用户 TopK=1 仍得到公开库的块（召回前过滤）。

## 下一步（下个会话）

按计划 §5 做 W5：`t_embedding_cache(model_id, dimension, text_sha256, …)`，在 `ChunkEmbeddingService` 调 `embedBatch` 前按哈希查表；核对重新入库的删插是否在同一事务；X5 重复入库与 V1.2→V1.3。缓存只按内容寻址，不带库信息（W4 泄露清点已写明）。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交。
- 迁移 `260918_02` 已于 2026-09-18 在 `ragent`（3 个有效库，全部 PUBLIC，所有者回填为 admin）和 `research_corpus_stub` 上执行；`research_corpus_v1` 未执行（X1 不经过判定）。其他本地库运行新代码前仍需执行。
- AgentScope 自带 JVM 关闭钩子（`GracefulShutdownManager`）会在“模型已决定、工具未执行”处中断 Agent，所以 SIGTERM 仍重复 1 次模型调用；消除需持久化 tool_call 决定，未做。
- X2 只跑单 Agent 模式；多 Agent 下已完成 worker 不重跑只由测试覆盖。X3 不含熔断修复 `77ca3ae`（只由单测覆盖）。
