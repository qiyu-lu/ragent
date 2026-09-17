# 工业文档 RAG 工程入口

本页只用于恢复当前检查点和选择阅读路径。面向 GitHub 访客的项目定位、代表性结果和快速启动见[仓库首页](../../README.md)。

## 当前检查点

| 项目 | 当前状态 |
| --- | --- |
| 上游基线 | Ragent `1.1.0`，`f64de341452c8998ebf64cd264e60ccad6a31631` |
| 开发分支 | `research/iron-ore-rag` |
| 冻结状态 | D2 固定回放已结束；公平回填质量 gate fail，代码保留但默认关闭 |
| 最终配置 | `intent=off`、`ocr=off`、`rag.search.request-level-refill-enabled=false` |

阶段 2 的工程实现、迁移和定向测试已完成；ROS1 可选扩展另有三条本地运行时路径通过。两者的登录页面业务 E2E 均未完成，作为已知边界保留，不再列为本阶段待开发事项。提交与问题的对应关系统一见[改动索引](changes/README.md)。

2026-09-15 新增独立的规程驱动送检 Agent，不改写上述冻结实验。后端、页面和软件层定向验证已完成，真实模型页面验收尚未执行；完整改动过程集中在[送检任务 Agent 记录](changes/2026-09-15-task-agent.md)。

## 主线阅读路径

| 任务 | 文档 |
| --- | --- |
| 用中文业务描述复习主要流程，减少类名、表名和配置细节 | [面试流程精简笔记](flow-notes/00-interview-summary.md) |
| 不打开源码复习上游特色、完整业务链、重点改进、技术概念与面试追问 | [项目复习主讲义](project-study-guide.md) |
| 了解项目与量化结果 | [仓库首页](../../README.md) |
| 重建本地环境 | [阶段 0：本地基线复现](stages/00-reproduction.md) → [开发栈改动说明](changes/2026-08-12-reproducible-local-development-stack.md) |
| 了解 Java 后端 + AI 核心改造 | [工业知识闭环改动](changes/2026-08-12-industrial-knowledge-demo.md) → [部署与验收手册](stages/02-industrial-knowledge-demo.md) |
| 体验工具调用、人工确认、业务提交及任务恢复 | [规程驱动送检 Agent：过程、验证、体验步骤](changes/2026-09-15-task-agent.md) |
| 复现小型系统评测 | [评测工具与冻结结果](changes/2026-08-13-system-evaluation-harness.md) → [评测说明](../../eval/iron-ore/README.md) → [固定运行手册](../../eval/iron-ore/RUNBOOK.md) |
| 查看证据驱动优化 | [XLSX 分块](changes/2026-08-13-xlsx-structure-aware-chunking.md) → [检索纯度](changes/2026-08-13-request-level-retrieval-purity.md) → [D2 负结果](changes/2026-08-14-request-level-fair-refill.md) |
| 查找某次改动、提交和回滚方式 | [改动索引](changes/README.md) |

早期最小样本方法保留在[表格样本基线](changes/2026-08-12-table-sample-baseline.md)；ROS1 dry-run 是非求职主线的[可选执行适配案例](changes/2026-08-12-ros1-robot-mission-demo.md)。

## 文档职责

- `project-study-guide.md` 按完整业务流程解释当前实现，单列上游 Ragent 原有特色及启用边界，并总结本分支重点改进的发现证据、根因、实现、取舍、效果与边界，是日常复习主入口。
- `stages/` 只保留仍有使用价值的复现或验收手册，不记录实验流水。
- `changes/` 按问题保存发现证据、根因、方案取舍、实现、效果、限制和回滚；索引是唯一提交总表。
- `eval/iron-ore/` 定义评测协议与运行命令；原始材料、响应、评分表和数据库 dump 位于 Git 忽略目录。

## 阶段停止点

- 当前只验证 3 份文档、24 题和 15 个解析锚点；B0/C-final 的人工严格通过率均为 `79.2%`，不外推为行业基准或生成准确率提升。
- D0 是冻结索引实验；D1 的 PDF 结果受在线 MinerU 漂移影响；D2 证明机制能补位但未通过质量门槛。
- 本阶段代码与评测结论冻结，不继续调参或上线公平回填。只有新增代表性语料、提出新的排序假设并预先固定门槛时，才重新立项。
