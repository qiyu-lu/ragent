# 工业知识闭环 Demo

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | `2026-08-12` |
| 所属阶段 | 阶段 2：在原生 RAG 上建立可追溯的工业知识闭环 |
| 状态 | 工程实现、针对性测试、前端构建和数据库迁移已完成；登录页面业务 E2E 未完成 |
| Git 提交 | `a016f01`（`feat(iron-ore): complete industrial RAG and ROS1 demo`） |
| 复现与验收 | [阶段 2：工业知识闭环 Demo](../stages/02-industrial-knowledge-demo.md) |

本文记录 `a016f01` 中工业文档 RAG 主线。后续 XLSX 分块和检索纯度优化分别见 [XLSX 结构感知分块](2026-08-13-xlsx-structure-aware-chunking.md) 与 [请求级检索预算](2026-08-13-request-level-retrieval-purity.md)。ROS1 是另行记录的可选扩展，不属于本页闭环的必要条件。

## 改动目的与问题发现

阶段 1 的小型 XLSX A/B 已证明表格可以进入原生 RAG，但也暴露出四个无法支撑工业演示的缺口：

1. 来源主要停留在文档级，回答不能稳定说明来自哪个版本、工作表和单元格范围。
2. 低置信知识库意图会回退到全库，领域 Demo 可能混入无关知识库内容。
3. 同一规程的多个版本没有稳定身份，版本变化只能依赖自然语言比较，无法审计“究竟改了哪些单元格”。
4. 回答之后没有受控闭环；若直接把模型文字当作任务，事实没有绑定本次检索 chunk，也没有人工审批和可回放状态。

目标不是建设通用 Office 平台或真实设备编排，而是用现有企业工作簿打通一条边界清楚的 Java 后端 + AI 链路：文档摄取、精确来源、隔离检索、证据约束的候选任务、人工批准、确定性版本比较和本地模拟。

## 根因

- `t_knowledge_document` 没有稳定 `documentKey`、显式版本和演示数据标记，同一规程的 V1.2 与 V1.3 无法可靠关联。
- 分块来源信息没有完整持久化到关系库；检索后即使拿到正确文本，也难以恢复 Sheet、单元格和块类型。
- 原检索回退强调通用可用性：没有有效 KB 意图时会查全部活动知识库；这与领域 Demo 的隔离要求冲突。
- 原回答 grounding 按文档压缩，不能为候选任务提供足够细的证据集合；自由模型输出也没有结构、顺序或 `evidenceChunkIds` 白名单校验。
- 版本差异如果交给 LLM 从两份长文中“找变化”，结果不可重复，也不能保证只报告真实单元格变化。
- 工作簿含大量图片摆放，但只有指定工作表的 2 张图片与当前演示问题相关；全量调用 VLM 会增加成本和无关视觉描述。

## 方案取舍与选择理由

### 1. 用 Profile 隔离演示策略

`iron-ore-demo` Profile 显式开启精确引用、指定工作表图片解析，并把检索 `fallback-mode` 设为 `empty`、`supplement-ratio` 设为 `0`。选择 Profile 而非修改全局默认，是为了让上游通用行为保持兼容，也能通过停用 Profile 快速回退。

### 2. 让确定性代码负责身份、差异和状态

- 文件名由 `DocumentIdentityResolver` 解析为稳定文档键、版本和 `demoData`；模型不猜版本关系。
- `WorkbookDiffService` 使用 Apache POI 逐单元格比较两个明确版本；LLM 只允许解释已生成的差异列表，不参与发现差异。
- 候选任务状态固定为 `DRAFT -> APPROVED -> SIMULATED`，状态变更和执行事件写入 PostgreSQL；模型不能绕过人工批准。

### 3. 允许模型组织文字，但不允许扩展证据边界

候选任务只能从选定 assistant 消息已经保存的真实 retrieval chunks 中生成。每条前置条件、步骤、质量判据、异常处理和安全约束都必须带 `evidenceChunkIds`；`TaskTemplateValidator` 拒绝空证据、重复 ID、未知 ID 和不连续步骤顺序。相比把模型完全移出链路，这保留了自然语言整理能力；相比直接执行模型 JSON，它建立了可验证协议。

### 4. 把视觉处理限制在可证明范围

Excel 仍解析全部 12 个可见工作表文本，但只有“浓度检测（双场景）”中的 2 张图片允许进入既有 VLM。视觉描述 Prompt 只允许报告可见设备、容器、文字和状态，不能单独推断步骤、参数、质量结论或安全规则。

设备报警排查因没有可信设备手册、型号和报警码而延期；模拟执行只写事件，不连接机器人、PLC、仪器或其他设备。

## 实现与调用链

### 文档摄取与精确来源

```text
XLSX 上传
  -> DocumentIdentityResolver：documentKey / documentVersion / demoData
  -> ExcelDocumentParser：遍历可见工作表，白名单图片交给 ImageAssetProcessor
  -> TableChunker / ImageChunker / ChunkPacker：保留 Provenance
  -> RelationalChunkSink：正文与 metadata JSONB 入库
  -> ChunkMetadataResolver：检索后补回版本、Sheet、cellRange、blockType
  -> SourcesAssembler / GroundingChunksAssembler：来源面板与真实 chunk grounding
```

文档表新增 `document_key`、`document_version`、`demo_data`；分块表新增 JSONB `metadata`。来源面板可以返回文档版本、工作表和单元格范围，块类型保留在检索 chunk 的 metadata 中；grounding 从“每文档一块”调整为最多 8 个真实 chunk，为后续候选任务保存精确证据。

### 检索作用域

```text
问题改写与意图识别
  -> RetrievalScopeResolver
  -> 有足够置信的 KB 意图：只查绑定且仍有效的知识库
  -> 无有效或低置信 KB 意图：Demo Profile 返回空作用域
  -> 各检索通道共用同一作用域
```

独立 SQL `resources/database/examples/iron_ore_demo_intents.sql` 幂等写入四个固定叶子意图，不自动执行、不删除既有教程意图；执行后需要清理 `ragent:intent:tree` 缓存。

### 候选任务与人工批准

```text
POST /iron-ore/task-templates
  -> IronOreTaskTemplateService 校验用户、assistant 消息和指定来源文档
  -> 从消息的 retrievedChunks 过滤当前 docId 的真实 chunk
  -> LLM 以 temperature=0、topP=0.2 生成结构化 JSON
  -> JSON 解析或协议校验失败时只允许一次“修复格式、不得添加事实”的重试
  -> TaskTemplateValidator 校验结构、步骤顺序和证据 ID 白名单
  -> 保存 DRAFT
  -> 人工 approve：DRAFT -> APPROVED
  -> simulate：仅 APPROVED 可执行，写入事件后变为 SIMULATED
```

创建接口按 `sourceMessageId + docId + ownerUserId` 复用已有草案；审批使用带原状态条件的更新；模拟执行复用已存在的执行记录。模型负责候选内容，不拥有审批权，也不产生外部动作。

### 确定性版本差异

`IronOreVersionController` 或 `IronOreVersionDiffToolExecutor` 调用 `WorkbookDiffService`：按 `documentKey + version` 唯一查找两个启用的 XLSX，要求属于同一知识库，从对象存储读取原文件，用 POI 计算公式展示值并按 Sheet、行、列比较非空单元格，输出 `ADDED / REMOVED / MODIFIED`。受控 `V1.3-demo` 只修改 `F17`、`F19`、`F20` 三格，并明确标识为构造数据，不代表已批准的新规程。

### API、前端与数据表

- `IronOreTaskController` 提供草案创建、查询、批准和模拟接口；`IronOreVersionController` 提供版本差异接口。
- `IronOreTaskSection.tsx` 展示候选任务证据、审批状态和模拟事件；版本比较可经独立 API 或 MCP 工具调用。
- 新增候选任务模板、模拟执行两张表；数据库升级脚本为 `resources/database/upgrades/v1.1.0/260812_iron_ore_demo.sql`。
- V1.3-demo 由 `scripts/iron-ore-rag/create_v1_3_demo.py` 生成，文件位于 Git 忽略的 `local-data/source/`。

## 验证与前后效果

### 已完成的工程验证

- Maven 编译通过；17/17 个针对性后端测试通过，覆盖图片白名单、首块精确来源、严格检索、文档身份、版本差异和任务证据校验。
- 前端生产构建通过。
- V1.3-demo 的输入前置条件、ZIP 完整性、包内文件清单和三处目标值通过复核。
- 本地 PostgreSQL 增量迁移已实际执行：4 份现有文档完成稳定键回填，新列和两张任务表存在，原数据未删除。
- 全量 Maven 测试曾启动 264 项，其中 40 项因沙箱禁止测试开放 Socket/连接 Redis，以及仓库既有外部 Milvus/模型测试和 Mockito 严格桩问题报错；因此不能把该次运行记为“全量测试通过”。针对本改动的 17 项离线回归是单独通过的证据。
- ESLint 因仓库既有 `react-refresh/recommended` 与 ESLint 8 不兼容而未启动；全量 TypeScript 检查仍有既有页面/Store 类型错误，本改动文件未出现在错误列表中。这些结果不能替代前端业务验收。

| 能力 | 改动前 | 改动后 | 证据边界 |
| --- | --- | --- | --- |
| 文档身份 | 上传记录彼此独立 | 稳定文档键、版本、演示标记 | 单测与本地迁移已验证 |
| 来源 | 以文档级信息为主 | 可持久化版本、Sheet、单元格、块类型 | 针对性测试通过 |
| 无有效意图 | 回退全库 | Demo Profile 返回空作用域 | Resolver 测试通过 |
| 候选任务 | 无证据约束生命周期 | `DRAFT -> APPROVED -> SIMULATED`，事实绑定 chunk | Validator/Service 行为测试通过 |
| 版本差异 | 无确定性比较 | POI 逐单元格输出精确变化 | 固定三格测试与生成器复核通过 |
| 外部执行 | 无受控边界 | 仅记录本地模拟事件 | 不连接真实设备 |

### 尚未完成的业务验证

尚未从登录页面完整执行“上传 V1.2 → 真实模型检索回答 → 查看精确来源 → 生成候选任务 → 人工批准 → 模拟 → 比较 V1.3-demo”的固定流程。因此当前结论是“工程实现和分层验证完成”，不是“真实模型、数据库、前端的完整业务 E2E 已验收”。单元测试、编译和生产构建不能替代这条人工链路。

## 限制与停止状态

- 当前材料边界是一个企业 XLSX 和一个由脚本构造的 V1.3-demo，不能外推为通用 Office 解析或工业知识平台。
- 图片只作为可见内容证据；不能从图片补造步骤、参数或安全规则。
- 报警知识因资料不足明确不实现；模拟事件不是设备执行结果。
- 固定意图需要人工导入，且 Demo Profile 的空作用域策略不代表通用产品默认策略。
- 页面业务 E2E 未完成，应作为已知验收缺口保留，不用针对性测试替代。当前阶段按既定范围冻结，不继续扩展机器人或设备能力。

## 数据兼容与回滚

- 不启用 `iron-ore-demo` Profile，即恢复原有检索回退和 Excel 图片行为；已有文档、chunk 和任务记录不会自动删除。
- 意图 SQL 只在人工执行后写入四个节点。删除前须确认没有被其他测试复用，并清理 `ragent:intent:tree` 缓存。
- 数据库新增列和任务表可能已有演示记录；代码回滚不要求立即删除结构。若必须降级 Schema，应先备份并显式迁移，不能直接把删列当作普通 Git 回滚。
- V1.3-demo 可从 V1.2 重新生成，但不得反向覆盖企业原始 V1.2。
- 代码检查点为 `a016f01`；回滚前应先核对该提交同时包含工业闭环和 ROS1 可选扩展，避免整体 revert 误删仍需保留的主线能力。
