# 工业文档 RAG 工程入口

本页只用于恢复当前检查点和选择阅读路径。面向 GitHub 访客的项目定位、代表性结果和快速启动见[仓库首页](../../README.md)。

## 当前检查点

| 项目 | 当前状态 |
| --- | --- |
| 上游基线 | Ragent `1.1.0`，`f64de341452c8998ebf64cd264e60ccad6a31631` |
| 开发分支 | `feat/agentic-research` |
| 冻结状态 | D2 固定回放已结束；公平回填质量 gate fail，代码保留但默认关闭 |
| 历史评测配置 | `intent=off`、`ocr=off`、`rag.search.request-level-refill-enabled=false` |

当前 P0/P1 已完成，P2 已实现数据契约、知识库/文档作用域、块级与受限邻接证据快照，并完成 Java 证据读写的隔离 PostgreSQL 联调；数据转换和导入继续在 P2 推进。[统一研究工作流计划](agentic-research-implementation-plan-2026-09-17.md)、[执行记录](agentic-research-execution-log.md)保存进度、提交和接续点；测试与真实模型边界见[验证报告](agentic-research-validation-report.md)。

阶段 2、ROS1 和 2026-09-15 送检 Agent 的实现、迁移及定向验证属于历史版本记录，均未完成登录页面业务 E2E。P1 已退役送检和执行演示；旧评测及其负结果保留。对应历史提交统一见[改动索引](changes/README.md)。

## 主线阅读路径

| 任务 | 文档 |
| --- | --- |
| 用中文业务描述复习主要流程，减少类名、表名和配置细节 | [面试流程精简笔记](flow-notes/00-interview-summary.md) |
| 不打开源码复习上游特色、完整业务链、重点改进、技术概念与面试追问 | [项目复习主讲义](project-study-guide.md) |
| 了解项目与量化结果 | [仓库首页](../../README.md) |
| 重建本地环境 | [阶段 0：本地基线复现](stages/00-reproduction.md) → [开发栈改动说明](changes/2026-08-12-reproducible-local-development-stack.md) |
| 了解 Java 后端 + AI 核心改造 | [工业知识闭环改动](changes/2026-08-12-industrial-knowledge-demo.md) → [部署与验收手册](stages/02-industrial-knowledge-demo.md) |
| 接续统一研究 Agent 与计划改造 | [实施计划](agentic-research-implementation-plan-2026-09-17.md) → [执行记录](agentic-research-execution-log.md) |
| 查阅已退役送检 Agent 的设计与验证 | [送检 Agent 历史记录](changes/2026-09-15-task-agent.md) |
| 复现小型系统评测 | [评测工具与冻结结果](changes/2026-08-13-system-evaluation-harness.md) → [评测说明](../../eval/iron-ore/README.md) → [固定运行手册](../../eval/iron-ore/RUNBOOK.md) |
| 查看证据驱动优化 | [XLSX 分块](changes/2026-08-13-xlsx-structure-aware-chunking.md) → [检索纯度](changes/2026-08-13-request-level-retrieval-purity.md) → [D2 负结果](changes/2026-08-14-request-level-fair-refill.md) |
| 查找某次改动、提交和回滚方式 | [改动索引](changes/README.md) |

早期最小样本方法保留在[表格样本基线](changes/2026-08-12-table-sample-baseline.md)；已退役 ROS1 dry-run 的设计与当时验证保留在[历史案例](changes/2026-08-12-ros1-robot-mission-demo.md)。

## 文档职责

- `project-study-guide.md` 按完整业务流程解释当前实现，单列上游 Ragent 原有特色及启用边界，并总结本分支重点改进的发现证据、根因、实现、取舍、效果与边界，是日常复习主入口。
- `stages/` 只保留仍有使用价值的复现或验收手册，不记录实验流水。
- `changes/` 按问题保存发现证据、根因、方案取舍、实现、效果、限制和回滚；索引是唯一提交总表。
- `eval/iron-ore/` 定义评测协议与运行命令；原始材料、响应、评分表和数据库 dump 位于 Git 忽略目录。

## 阶段停止点

- 当前只验证 3 份文档、24 题和 15 个解析锚点；B0/C-final 的人工严格通过率均为 `79.2%`，不外推为行业基准或生成准确率提升。
- D0 是冻结索引实验；D1 的 PDF 结果受在线 MinerU 漂移影响；D2 证明机制能补位但未通过质量门槛。
- 本阶段代码与评测结论冻结，不继续调参或上线公平回填。只有新增代表性语料、提出新的排序假设并预先固定门槛时，才重新立项。
