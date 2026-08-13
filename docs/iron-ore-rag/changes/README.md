# 铁矿检测 RAG 改动索引

本页只回答“改过什么、对应哪次提交、到哪里看详情”。具体问题分析、验证和回滚方法放在单项详情中，避免形成一个越来越长的实施日志。

## 已实施改动

| 日期 | 阶段 | 改动 | Git 检查点 | 详情 |
| --- | --- | --- | --- | --- |
| 2026-08-12 | 0 | 固定 Ragent 1.1.0 项目上下文并隔离本地调研材料 | `eddeec0`、`7da3042` | [阶段 0 归档记录](../../iron-ore-rag-implementation-log.md) |
| 2026-08-12 | 0 | 建立项目专属 PostgreSQL、Redis、RustFS、RocketMQ 开发栈并完成基线验证 | `4962aad`、`b3a6085`、`bcfba62` | [阶段 0 归档记录](../../iron-ore-rag-implementation-log.md) |
| 2026-08-12 | 0 / 工程修复 | 用户取消生成后，trace run 可能永久停留在 `RUNNING` | `24bd7f8`、`e0d0871`，合并点 `c7e8da8` | [取消后 trace run 悬挂修复](2026-08-12-cancel-trace-run-hang.md) |
| 2026-08-12 | 2 | XLSX 精确来源、严格检索、候选任务、模拟执行与确定性版本差异 Demo | `a016f01` | [工业知识闭环 Demo](2026-08-12-industrial-knowledge-demo.md) |
| 2026-08-12 | 3 | 已批准候选任务确定性编译并通过 ROS1 Action 派发、反馈与取消 dry-run 搬运任务 | `a016f01` | [ROS1 机器人任务 Demo](2026-08-12-ros1-robot-mission-demo.md) |
| 2026-08-13 | 评测 | 3 类文档、24 题的冻结基线/当前版评测、OCR/意图诊断、盲评与数据库快照工具 | `318e1f3` | [小型系统评测工具](2026-08-13-system-evaluation-harness.md) |
| 2026-08-13 | 评测后优化 | 修复 XLSX 合并单元格膨胀、重复续行、双文本预算失真和跨工作表回并 | `ed2590e` | [XLSX 结构感知分块与去重](2026-08-13-xlsx-structure-aware-chunking.md) |
| 2026-08-13 | 评测后优化 | 恢复请求级 TopK 契约，执行 `should_split`，并让文档名与结构化文本进入 rerank | `fd538a8` | [请求级检索预算与结构化重排](2026-08-13-request-level-retrieval-purity.md) |

通用教程意图节点仍只是复现参考；铁矿 Demo 使用单独的幂等脚本 [`resources/database/examples/iron_ore_demo_intents.sql`](../../../resources/database/examples/iron_ore_demo_intents.sql)。

## 后续如何记录

1. 先确认改动属于哪个阶段，并在对应阶段文档中写清验收边界。
2. 实施和验证代码、配置或数据库变化，并形成可回滚的 Git 提交。
3. 对需要解释或复现的改动复制 [`TEMPLATE.md`](TEMPLATE.md)，每项改动单独建文件。
4. 在上表追加一行，链接详情和代码提交；不要把详情直接写入本索引。
5. 阶段结束时更新[项目入口](../README.md)的当前阶段和下一阶段。

建议详情文件名使用 `YYYY-MM-DD-short-topic.md`。同一问题的后续补丁继续更新原详情，并追加新的提交与验证结果；只有目标或行为边界已经变化时才建立新文件。
