# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-19（W6 第 1—3 步准备完成：归因、题目 ID、脚本、smoke；后台运行交给用户） |
| 当前工作项 | **W6** 进行中：等待用户后台跑完噪声基线与 400 题交错运行（S6 顺延到 W7 之后） |
| 分支 / 提交 | `feat/llm-backend-hardening`；标签 `career-w1`、`career-w3`、`career-w2`、`career-w4`、`career-w5` |
| 回归通过数 | `bash scripts/validate-agentic-research-p7.sh`：W6 准备后 Python 53/53（W5 后 41）、Java 13 + 260（W4 后 13 + 247），约 40 s；另有 `validate-agentic-research-p2-database.sh` 结构校验 |
| 最近的运行目录 | X2：`local-data/agentic-research/runs/career_X2_v2/`（重复 20 次；单次 `career_X2_v1/`）；X3：`runs/career_X3_v2/`（4 个种子；单种子 `career_X3_v1/`）；X1：`runs/career_X1_v1_*`；W6：归因 `runs/career_X1_mq80_quality_diff.{md,json}`，smoke `runs/career_W6_smoke_*`，待跑 `career_W6_noise_after_C`、`career_W6_x1b_{before,after}_C_{1..4}`；X5：`runs/career_X5_real_v1/`（模拟上游 `career_X5_stub_v1/`） |

## 进度

- [x] S0 基线；W1 缓存友好的上下文布局 + X1（[改动说明](changes/2026-09-18-prompt-cache-stable-prefix.md)）
- [x] W3 模拟上游、抖动退避、熔断补缺 + X3（[改动说明](changes/2026-09-18-upstream-fault-injection.md)）
- [x] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2（[改动说明](changes/2026-09-18-durable-research-execution.md)）
- [x] W4 权限隔离 + 越权矩阵（[改动说明](changes/2026-09-18-knowledge-base-access-control.md)）
- [x] W5 内容寻址的 embedding 复用 + X5（[改动说明](changes/2026-09-18-content-addressed-embedding-reuse.md)）
- [ ] W6 W1 的质量护栏：归因、噪声基线、400 题扩样（X1b）
- [ ] W7 容量基准：多实例排空积压（X6，可砍）
- [ ] S6 简历条目、README、面试卡（两主一副两句话的结构，见计划 §10）

## 结果摘要（引用数字时连同条件一起说）

- W1（真实上游，同 40 题 B、C，全 Flash）：命中率 B 9.8%→78.4%、C 0.2%→80.0%；单任务计费输入 −69% / −75%。
- W3（模拟上游，50 任务 × 4 个种子 = 每格 200 任务，embedding 无响应 10/30/50%）：成功率 87.5/57.5/36.0% → 100/98.5/86.0%，Wilson 95% 区间两版不重叠（如 50%：[29.7, 42.9] → [80.5, 90.1]）；错误完成 1600 任务中为 0。单种子旧数字 88/54/40 → 100/100/82%。
- W2（模拟上游，独立 JVM 共享运行库，租约 6 s / 轮询 1 s，每场景重复 20 次）：恢复时间 P50 / P95 / 最大 kill -9 5.13 / 6.12 / 6.12 s、SIGSTOP 5.12 / 6.12 / 6.12 s、SIGTERM 1.08 / 1.11 / 1.11 s；每个被接管任务重复 1 次模型调用（在途那次，118/118）；不变量 0 失败；1 次重复在注入故障前执行者卡住，原因未查明（租约发现不了“活着但卡住”）；毒任务 4 次失联后 EXECUTOR_LOST（单次）。
- W1 复核（MuSiQue C，80 道新题，可答 45）：命中率 0.05%→80.1%、单任务计费输入 −72% 复现；答案 F1 0.443→0.352，配对差 −0.09，区间 [−0.21, +0.02] 含 0，“资料中没有”式回答 12→16。
  - W6 归因（`quality_diff.py`，零接口费）：15 道下降 = 新增拒答 6、两臂都拒答只是措辞变 4、格式差异 4、答错 1、失败 0；两臂 `CONTEXT_COMPACTED` 均为 0 次，排除“压缩存根丢正文”。6 道新拒答里 3 道后臂少检索到 1 篇 gold 段落（其中 1 道提前收手：主调用 7→5，剩余 10 次），另 3 道 gold 全部读到仍拒答（其中 1 道主调用 8→5）；反向有 3 道前臂拒答、后臂作答。自算 bootstrap [−0.207, +0.017]，与记录的 [−0.21, +0.02] 仅差蒙特卡洛误差（原脚本未入库）。
- W4（JUnit + 真实 PostgreSQL/pgvector）：越权矩阵 299 项全部符合（173 允许、126 拒绝，拒绝时业务服务未被调用）；私有库放最佳匹配时，其他用户 TopK=1 仍得到公开库的块（召回前过滤）。
- W5（调研表 V1.2/V1.3 各 79 块；真实上游 SiliconFlow 单次运行，模拟上游复现同样的块数与命中）：原样重新入库、回退旧版上游调用 0；V1.2→V1.3 重嵌入 2/79 块，计费 token 45,722→1,368（2.99%）；块表与 pgvector 同事务先删后插已由集成测试证明。限制：表格按行累加分组，表中插一行会使该表后续块全部失效；ES/LightRAG/Milvus 的先删后插不在事务内。

## 下一步（下个会话）

用户后台跑（先噪声基线、后 400 题，顺序执行；命令见 W6 会话回复，树在 `../ragent-w6`，后臂固定 `61d37d8`）：`career_W6_noise_after_C`（mq80，与 `career_X1_mq80_after_C` 配对）与 `career_W6_x1b_{before,after}_C_{1..4}`（交错，结束时脚本自动出 `career_W6_x1b_quality_diff_C.{md,json}` 与缓存报告）。下个会话：读结果 → `quality_diff.py` 出噪声基线配对 → 按计划 §5 W6 第 4 步写死的判定规则下结论（新 400 题单报、与旧 45 题合并再报）→ `manifests/career-w6-<日期>.json`、W1 改动说明“限制”一节、标签 `career-w6`。

## 已知事实与遗留问题

- 迁移 `260918_03`（W5）只在 `ragent` 执行（离线语料库不经过缓存）；迁移 `260918_02` 已于 2026-09-18 在 `ragent`（3 个有效库，全部 PUBLIC，所有者回填为 admin）和 `research_corpus_stub` 上执行；`research_corpus_v1` 已于 2026-09-19 补执行（6 个库全部 PUBLIC，所有者未匹配到用户、为空；HEAD 的检索读 `visibility`，不补会 BadSqlGrammar，见 `runs/career_W6_smoke_after_C` 的 4 个 FAILED）。其他本地库运行新代码前仍需执行。
- AgentScope 自带 JVM 关闭钩子（`GracefulShutdownManager`）会在“模型已决定、工具未执行”处中断 Agent，所以 SIGTERM 仍重复 1 次模型调用；消除需持久化 tool_call 决定，未做。X2 只跑单 Agent 模式；多 Agent 下已完成 worker 不重跑只由测试覆盖。X3 不含熔断修复 `77ca3ae`（只由单测覆盖）。
