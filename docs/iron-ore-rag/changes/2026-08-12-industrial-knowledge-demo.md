# 工业知识闭环 Demo

## 问题与目标

阶段 1 已证明有限 XLSX 样本可以进入原生 RAG，但来源只有文档级，低置信意图会回退全库，且没有版本比较与候选任务闭环。本改动以“尽快形成完整 Demo”为目标，不建设通用文档平台或真实设备编排。

## 范围

- 全量解析 V1.2 的 12 个可见工作表文本。
- 仅允许“浓度检测（双场景）”内的 2 张图片调用既有 VLM。
- 持久化文档键、版本、演示数据标记和分块的 Sheet/单元格/块类型。
- Demo Profile 将无有效 KB 意图的回退改为空作用域，避免全库补充检索。
- 回答来源展示精确位置；回答 grounding 从“每文档一块”调整为“最多 8 个真实 chunk”。
- 候选任务实行 `DRAFT -> APPROVED -> SIMULATED`，模型输出经过结构和证据 ID 校验。
- 增加 XLSX 确定性版本差异和批准后模拟两个本地 MCP 工具。
- 增加三格受控变化的 V1.3-demo 生成器。

设备报警排查因缺少可信资料明确延期。模拟执行不连接真实设备。

## 关键文件

- 解析与来源：`core/parser/excel`、`core/chunk/blockaware`、`ChunkMetadataResolver`、`SourceRef`。
- 任务与工具：`bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/`。
- 前端卡片：`frontend/src/components/chat/IronOreTaskSection.tsx`。
- 配置：`bootstrap/src/main/resources/application-iron-ore-demo.yaml`。
- 数据库：`resources/database/upgrades/v1.1.0/260812_iron_ore_demo.sql`。
- 意图：`resources/database/examples/iron_ore_demo_intents.sql`。
- 演示版本：`scripts/iron-ore-rag/create_v1_3_demo.py`。
- 运行与验收：[阶段 2 文档](../stages/02-industrial-knowledge-demo.md)。

## 数据影响

- `t_knowledge_document` 新增 `document_key`、`document_version`、`demo_data`。
- `t_knowledge_chunk` 新增 JSONB `metadata`。
- 新增候选任务模板与模拟执行两张表。
- V1.3-demo 位于 Git 忽略的 `local-data/source/`，明确属于构造演示数据。
- 意图 SQL 不自动执行，也不删除现有意图；只在人工执行后写入四个固定节点。

## 验证

- Maven 编译通过。
- 针对性后端测试 17/17 通过。
- 前端生产构建通过。
- V1.3-demo 生成和包完整性复核通过。
- 本地 PostgreSQL 增量迁移实际执行并复核通过；4 份现有文档完成稳定键回填，任务表与 JSONB 元数据列存在，原数据未删除。
- 全量 Maven 测试启动 264 项，因沙箱禁止测试开放 Socket/连接 Redis，以及仓库既有外部 Milvus/模型测试和 Mockito 严格桩问题产生 40 个环境/既有错误；本次 17 项离线回归单独执行全部通过。
- ESLint 因仓库既有 `react-refresh/recommended` 配置与 ESLint 8 不兼容而无法启动；与本改动无关。全量 TypeScript 检查仍有既有页面/Store 类型错误，本改动文件未出现在错误列表中。

运行时端到端验收尚待按阶段 2 固定流程执行，因此当前不宣称已完成真实模型与数据库联调。

## 回滚边界

- 不启用 `iron-ore-demo` Profile 即恢复原有检索回退和 Excel 图片行为。
- 删除四个固定意图节点前应先确认没有被其他测试复用，并清除 `ragent:intent:tree` 缓存。
- 数据库列和任务表可能已有演示记录；删除结构前必须备份，不能把删除列当作普通代码回滚。
- V1.3-demo 可重新由 V1.2 生成，不应反向覆盖原始 V1.2。

## Git 状态

实现提交：`a016f01`（`feat(iron-ore): complete industrial RAG and ROS1 demo`）。本次收尾只建立本地提交与标签，没有推送远端。页面登录态端到端验收仍按阶段 2 文档保留为下一次运行任务。
