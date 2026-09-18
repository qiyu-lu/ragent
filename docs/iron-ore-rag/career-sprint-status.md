# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（W3 收尾、W2 完成） |
| 当前工作项 | W4：权限隔离 + 越权矩阵（尚未开始） |
| 分支 / 提交 | `feat/llm-backend-hardening`；标签 `career-w1`、`career-w3`、`career-w2` |
| 回归通过数 | `bash scripts/validate-agentic-research-p7.sh`：W2 后 Python 41/41、Java 240/240（13 + 227；W3 后 13 + 212），约 35 s |
| 最近的运行目录 | X2：`local-data/agentic-research/runs/career_X2_v1/`；X3：`runs/career_X3_v1/`；X1：`runs/career_X1_v1_*` |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [x] W1 缓存友好的上下文布局 + X1（[改动说明](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [x] W3 模拟上游、抖动退避、熔断补缺 + X3（[改动说明](changes/2026-09-18-upstream-fault-injection.md)）
- [x] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2（[改动说明](changes/2026-09-18-durable-research-execution.md)）
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## 结果摘要（引用数字时连同条件一起说）

- W1（真实上游，同 40 题 B、C，全 Flash）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%。
- W3（模拟上游，50 任务，embedding 无响应 10/30/50%）：成功率 88/54/40% → 100/100/82%；两版错误完成均为 0。
- W2（模拟上游，独立 JVM 共享运行库，租约 6 s）：kill -9 恢复 4.94 s、SIGSTOP 4.97 s、SIGTERM 0.95 s；每个被接管任务重复 1 次模型调用（在途那次）；5 类故障下不变量全部成立；毒任务 4 次失联后 EXECUTOR_LOST。

## 交给用户后台运行（编码 Agent 不等待、不轮询）
- **MuSiQue C 复核**已在跑（2026-09-18 21:47 起，after_C 进行中；命令见 `e53f469`）。结果：`runs/career_X1_mq80_{before,after}_C/summary.json` 与 `runs/career_X1_mq80_cache_report.md`。

## 下一步（下个会话）

若复核已跑完，把 MuSiQue C 结论补进 W1 改动说明。然后按计划 §5 做 W4：`KnowledgeAccessService` 唯一判定入口、召回前过滤、`/rag/v3/stop` 归属校验、越权矩阵与 PostgreSQL 集成测试。接管线程的 `LoginUser` 由 `t_user` 重建，用户不存在时无角色，W4 需按最小权限处理。

## 已知事实与遗留问题

- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交。
- AgentScope 自带 JVM 关闭钩子（`GracefulShutdownManager`）会在“模型已决定、工具未执行”处中断 Agent，所以 SIGTERM 仍重复 1 次模型调用；消除需持久化 tool_call 决定，未做。
- X2 只跑单 Agent 模式；多 Agent 下已完成 worker 不重跑只由测试覆盖。X3 不含熔断修复 `77ca3ae`（只由单测覆盖）。
