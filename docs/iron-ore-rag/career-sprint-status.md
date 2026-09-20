# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-20（**冲刺已收尾**：W1—W7 与 S6 全部完成，笔记与面试问答已入库，两条分支已推送到 `origin`） |
| 当前工作项 | **无**。冲刺结束，后续改动另起计划 |
| 分支 / 提交 | `feat/llm-backend-hardening` 与 `research/iron-ore-rag` 同为收尾提交，均已推送；标签 `career-w1`—`career-w7`、收尾标签 `career-done`（另有基线 `career-v0-baseline`） |
| 回归通过数 | `bash scripts/validate-agentic-research-p7.sh`：W7 后 Python 53/53、Java 13 + 211（记录过的 260 早于删除测试的重构 `e2e7e5c`、`6120945` 等，未逐项核对），约 40 s；另有 `validate-agentic-research-p2-database.sh` 结构校验 |
| 最近的运行目录 | X6：`runs/career_X6_v1/`（日志 `runs/career_X6_v1.log`）；X2：`local-data/agentic-research/runs/career_X2_v2/`（重复 20 次；单次 `career_X2_v1/`）；X3：`runs/career_X3_v2/`（4 个种子；单种子 `career_X3_v1/`）；X1：`runs/career_X1_v1_*`；W6：`runs/career_W6_x1b_{before,after}_C_{1..4}`、噪声基线 `career_W6_noise_after_C`、归因 `career_W6_*_quality_diff.{md,json}` 与 `career_X1_mq80_quality_diff.*`；X5：`runs/career_X5_real_v1/`（模拟上游 `career_X5_stub_v1/`） |

## 进度

- [x] S0 基线；W1 缓存友好的上下文布局 + X1（[改动说明](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [x] W3 模拟上游、抖动退避、熔断补缺 + X3（[改动说明](changes/2026-09-18-upstream-fault-injection.md)）
- [x] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2（[改动说明](changes/2026-09-18-durable-research-execution.md)）
- [x] W4 权限隔离 + 越权矩阵（[改动说明](changes/2026-09-18-knowledge-base-access-control.md)）
- [x] W5 内容寻址的 embedding 复用 + X5（[改动说明](changes/2026-09-18-content-addressed-embedding-reuse.md)）
- [x] W6 W1 的质量护栏：归因、噪声基线、400 题扩样（X1b，[manifest](../../eval/agentic-research/manifests/career-w6-2026-09-19.json)；结论在 W1 改动说明“限制”一节）
- [x] W7 容量基准：多实例排空积压（X6，[manifest](../../eval/agentic-research/manifests/career-x6-2026-09-19.json)；结论在 W2 改动说明“容量”一节）
- [x] S6（[计划](career-s6-plan.md)）：简历条目已定稿（§6）；**README 已完成**（会话 A，已提交）；**复习笔记已完成**（会话 B，11 篇约 1600 行）；**面试问答已完成**（会话 C，`notes/面试问答.md`，82 题约 1100 行，含 Top 15 清单、未实现之处与成熟做法的附录）。`notes/` 共 12 个文件，按计划留在工作区不提交、不加入 `.gitignore`，由用户决定

## 结果摘要（引用数字时连同条件一起说）

- W1（真实上游，同 40 题 B、C，全 Flash）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%。 W1 质量护栏（W6，MuSiQue C，400 道未见过的题、可答 196，前后 4 块交错）：命中率 0.15%→80.4%、单任务计费输入 77,113→21,436（−72%）；配对答案 F1 −0.034，95% 区间 [−0.080, +0.011]，未见显著下降、不能排除 0.08 以内的下降；EM 0.352→0.291；合并旧 45 题 −0.045 [−0.088, −0.002]（那 45 题引出了疑问，偏向下降）；同题重跑后臂的噪声 +0.059。损失主要是 15 题改为拒答（−0.056）对反向 11 题（+0.025），拒答总数 47→47；两臂均未触发压缩。
- W3（模拟上游，50 任务 × 4 个种子 = 每格 200 任务，embedding 无响应 10/30/50%）：成功率 87.5/57.5/36.0% → 100/98.5/86.0%，Wilson 95% 区间两版不重叠（如 50%：[29.7, 42.9] → [80.5, 90.1]）；错误完成 1600 任务中为 0。单种子旧数字 88/54/40 → 100/100/82%。
- W2（模拟上游，独立 JVM 共享运行库，租约 6 s / 轮询 1 s，每场景重复 20 次）：恢复时间 P50 / P95 / 最大 kill -9 5.13 / 6.12 / 6.12 s、SIGSTOP 5.12 / 6.12 / 6.12 s、SIGTERM 1.08 / 1.11 / 1.11 s；每个被接管任务重复 1 次模型调用（在途那次，118/118）；不变量 0 失败；1 次重复在注入故障前执行者卡住，原因未查明（租约发现不了“活着但卡住”）；毒任务 4 次失联后 EXECUTOR_LOST（单次）。
- W7（模拟上游，300 个积压任务 × 1/2/3 实例，每实例 2 槽位，轮询 5 s 重复 3 次 + 轮询 1 s 各 1 次，共 3600 任务）：吞吐 12.2 / 24.0 / 35.8 条每分钟，加速比 1.00 / 1.98 / 2.95（轮询 1 s 为 13.3 / 26.5 / 39.7，2.98），是槽位上限的 85%—95%；等待 P50 242 s / P95 470 s（3 实例）；提交被拒 0、不变量 0 失败；领取语句均值 4.1—5.6 ms、最大 85 ms，占排空时长不到 0.12%。瓶颈是槽位与轮询间隔，不是数据库。
- W4（JUnit + 真实 PostgreSQL/pgvector）：越权矩阵 299 项全部符合（173 允许、126 拒绝，拒绝时业务服务未被调用）；私有库放最佳匹配时，其他用户 TopK=1 仍得到公开库的块（召回前过滤）。
- W5（调研表 V1.2/V1.3 各 79 块；真实上游 SiliconFlow 单次运行，模拟上游复现同样的块数与命中）：原样重新入库、回退旧版上游调用 0；V1.2→V1.3 重嵌入 2/79 块，计费 token 45,722→1,368（2.99%）；块表与 pgvector 同事务先删后插已由集成测试证明。限制：表格按行累加分组，表中插一行会使该表后续块全部失效；ES/LightRAG/Milvus 的先删后插不在事务内。

## 收尾（2026-09-20）

七项加固（W1—W7）与三份产出（README、11 篇复习笔记、82 题面试问答）全部完成并入库，标签 `career-done` 打在收尾提交上。本文件此后只在有新结论时更新，不再逐会话维护。复现旧版本：`git worktree add` 检出 `career-v0-baseline` 得到治理前的“前”臂（W6 用过的临时工作树 `../ragent-w6` 已删除）。下面两节是长期有效的部分——引用数字时照抄“结果摘要”的条件，“已知事实与遗留问题”里的每一条都还没解决。

## 已知事实与遗留问题

- 迁移 `260918_03`（W5）只在 `ragent` 执行（离线语料库不经过缓存）；迁移 `260918_02` 已于 2026-09-18 在 `ragent`（3 个有效库，全部 PUBLIC，所有者回填为 admin）和 `research_corpus_stub` 上执行；`research_corpus_v1` 已于 2026-09-19 补执行（6 个库全部 PUBLIC，所有者未匹配到用户、为空；HEAD 的检索读 `visibility`，不补会 BadSqlGrammar，见 `runs/career_W6_smoke_after_C` 的 4 个 FAILED）。其他本地库运行新代码前仍需执行。
- AgentScope 自带 JVM 关闭钩子（`GracefulShutdownManager`）会在“模型已决定、工具未执行”处中断 Agent，所以 SIGTERM 仍重复 1 次模型调用；消除需持久化 tool_call 决定，未做。X2 只跑单 Agent 模式；多 Agent 下已完成 worker 不重跑只由测试覆盖。X3 不含熔断修复 `77ca3ae`（只由单测覆盖）。
