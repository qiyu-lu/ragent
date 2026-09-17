# 08｜按证据推进研究、形成报告或计划，再比较资料版本

当前聊天有普通问答、深入分析和生成计划三个入口。普通问答继续使用既有 RAG；后两者创建持久研究运行，共用检索、原文阅读、按需委派和最终产物生成。旧候选任务、送检、审批模拟和 ROS 入口已经移除，历史实现与验证保留在 Git 和执行记录中。本文说明当前运行；启动和复测见[交接说明](../agentic-research-handoff.md)。

## 1. 用户目标先成为持久研究运行

用户选“深度研究”或“计划生成”后提交目标，前端把目标、输出类型 `REPORT` / `PLAN`、约束和选定范围交给 `POST /rag/research/runs`。模型不负责决定资源权限。服务检查会话归属；没有会话时先创建当前用户的会话，再校验知识库、文档是否存在、启用且相互匹配，形成 `ResearchBrief`。知识库仍是项目原有共享模型，限定检索范围不能代替知识库成员权限。

创建请求带 `clientRequestId`。数据库按 owner、会话和该编号唯一约束防止重复创建；相同编号但不同内容被拒绝。创建后返回 `QUEUED` 运行，后台领取执行资格才进入 `RUNNING`。运行保存目标、原始范围、累计预算、revision、epoch、证据、子任务与事件；页面刷新可以重新读取这些记录。修改范围或对终态重新生成使用新请求编号。

## 2. 下一次查询由已经读到的资料决定

主 Agent 通过 AgentScope Java 的原生工具协议调用 `search_knowledge`、`read_source`、`conduct_research`、`ask_user` 和 `finish_research`。服务端执行工具，模型不能用纯文本或一段 JSON 冒充工具成功。搜索只产生候选；`read_source` 才交付可引用的正文、精确来源和内容 hash。读取也受当前运行范围限制，原文变更、停用或不存在会得到明确结果。

例如比较两篇论文的实验条件，可以一次委派两个独立任务。每个 worker 有自己的模型上下文，只得到子目标、允许范围和用户约束；它不读取主 Agent 或另一个 worker 的完整工具历史。worker 的压缩结果包含结论、实际阅读过的证据 ID、缺口与冲突。主 Agent 可以引用经服务端核对的 worker 阅读依据；依赖前一个结果的多跳查询由同一个研究者顺序推进。委派取决于任务，简单问题不强制创建 worker。

缺少必要用户条件时，`ask_user` 保存问题并进入 `WAITING_INPUT`。回复携带运行 revision，由 owner 提交；保存的输入作为 `user_input` 条件继续研究，已完成 worker 结果和已用额度保留。文档没有披露的参数应列为缺口，不能要求用户编造资料事实。等待用户的时间不占累计活动时长。

所有角色共用每运行 16 次模型、24 次工具、300 秒活动时长，最终生成保留 2 次模型额度。单 JVM 模型并发为 2，每运行最多累计创建 4 个 worker、同时运行 2 个，每个 worker 至多 6 次模型和 180 秒。单次模型与工具超时分别为 60、30 秒。限额是程序机制，输入 token 是保守估算，不能声称使用供应商精确 tokenizer。

## 3. 版本比较是另一条独立请求

用户比较资料版本时，重新调用 `POST /iron-ore/version-diffs`，提交 `documentKey`、`baseVersion`、`targetVersion`。它独立读取指定的两个 XLSX 原文件，不依赖研究运行、报告或计划草稿。

- Controller 把三个字符串交给 `WorkbookDiffService.compare`。Service 先要求三者都非空，并以忽略大小写的方式拒绝基准版本和目标版本相同。

- Service 分别用 `documentKey + documentVersion + enabled=1` 查询基准和目标文档。
  - 没有记录就指出未找到哪个版本；超过一条则返回“同一文档版本存在多份记录，请先清理重复上传”。文档表当前没有对稳定键和版本的唯一约束，因此同版本名重复是在读取时显式拒绝，不会任取一份。
  - 每份记录都必须是 `fileType=xlsx`。两份都取到后还要比较 `kbId`，不同知识库即拒绝。
  - 在这条 Controller/Service 调用链中没有读取 `UserContext`，也没有按当前用户或知识库成员关系做资源级权限判断；这里不能把登录拦截等同于“用户被授权比较这两份文档”。

- 两份数据库记录只提供身份与 `fileUrl`。Service 依次调用 `FileStorageService.openStream(fileUrl)` 取得原文件字节流，再由 Apache POI `WorkbookFactory` 打开；任一读取或解析异常都会转成“读取 XLSX 版本失败：文档名”。

- 每个工作簿都被转换成有顺序的键值集合，键是 `(sheetName, rowIndex, columnIndex)`，返回时再把零基行列转成 A1 地址。
  - 按工作簿 Sheet 顺序遍历，但跳过 hidden 和 very hidden Sheet；只遍历 POI 实际提供的 Row/Cell。
  - `DataFormatter(Locale.SIMPLIFIED_CHINESE)` 配合 `FormulaEvaluator` 得到显示值；求值抛运行时异常时回退为不带 evaluator 的格式化结果。显示字符串 trim，并把 CRLF 统一为 LF；空白值不进入集合。
  - 普通单元格保存清理后的显示值。公式单元格保存 `=公式文本 => 显示值`；若显示值为空，则只保存 `=公式文本`。所以公式文本变化和显示结果变化都能造成 MODIFIED，而不是只比较缓存结果。

- 比较时先把基准工作簿的键按读取顺序放入 `LinkedHashSet`，再追加目标工作簿中此前没有的键；随后逐键取 before/after。
  - 两边字符串相同就跳过；基准没有而目标有是 `ADDED`，基准有而目标没有是 `REMOVED`，两边都有但字符串不同是 `MODIFIED`。
  - 输出顺序不是先按变化类型分组，也不是重新按地址全局排序，而是“基准中的非空单元格顺序在前，目标独有单元格按目标读取顺序追加”。相同输入会得到稳定的 change 列表。

- 假设可见 Sheet“流程”只有以下变化：V1.2 的 A1 为 `18.4`、C3 为“旧备注”；V1.3 的 A1 为 `18.5`、C3 变空，并新增 B2 为“复核”。结果依次可表示为 `A1 MODIFIED 18.4 -> 18.5`、`C3 REMOVED 旧备注 -> null`，最后才是目标独有的 `B2 ADDED null -> 复核`。这解释了新增地址为什么可能排在修改、删除之后。

- Service 返回 `WorkbookDiffView`：稳定文档键、两端的 docId/docName/version/demoData、changeCount 和不可变 changes 列表。这里没有 LLM；若经 `iron_ore_compare_versions` MCP 工具进入，也是同一个 Service 做事实比较，工具只把结果序列化为文本和 structuredContent，异常则返回 `isError=true`。

### 3.1 这个 diff 能说明什么，不能说明什么

- 行或列插入后，后续内容即使没变，地址也会整体移动；当前键按地址匹配，重叠地址两端都有值但内容不同就会形成大量 MODIFIED，并可能伴随 ADDED/REMOVED，不会识别“同一业务行移动了”。例如假设原来 `A1=x、A2=y`，顶部插入一行后变成 `A1=z、A2=x、A3=y`，实际返回 `A1 MODIFIED x -> z`、`A2 MODIFIED y -> x`、`A3 ADDED null -> y`，没有 REMOVED。
- Sheet 改名是另一种情况：Sheet 名属于键，若其他内容不变且新名字原先不存在，旧 Sheet 的非空键全部消失、新 Sheet 的键全部新增，因此表现为 REMOVED 与 ADDED，不会识别重命名。
- 源码只比较可见 Sheet 中非空单元格的公式文本和格式化显示值，不比较隐藏/very hidden Sheet、空白格之间的差别、样式、合并关系、批注、图片、图表等。它也不解释阈值变化是否合理、公式业务含义是否等价，更不能声称理解所有 Excel 语义。
- 因而版本 diff 的可靠结论是“这两个明确文件在当前比较口径下有哪些稳定的单元格字符串增删改”。业务解释可以在其后由人或受约束模型完成，但不能让解释反过来改写差异事实。

## 4. 报告和计划由同一个最终阶段生成

研究阶段的 `finish_research` 提交已读依据支持的 findings、gaps、conflicts，不直接写终态。`ResearchCompletionService` 收集主研究与有效 worker 结果，`ResearchArtifactGenerator` 按 `outputType` 生成报告或计划。两者都使用实际保存的证据，结构或引用校验失败最多修复一次，预留额度供初次生成与修复使用。

REPORT 保存章节与引用；PLAN 保存准备条件、顺序步骤、资源、参数、风险和缺失信息。`PlanDraftValidator` 检查步骤顺序、必需字段、引用是否属于当前运行且有阅读证明。缺失参数保留 null 和缺口；用户给出的条件使用单独的 `user_input` 来源，不能冒充文档事实。服务器按字段汇总实际引用，并回填引用正文、文档、版本与位置。产物和终态在同一个带 epoch 条件的事务中写入，避免显示完成却没有结果。

这里有三层不同的保证：JSON 可解析，字段与引用身份合法，原文真正支持结论。前两层由程序检查；第三层仍需要实际原文核对。把一个合法证据 ID 放在无关结论上可能通过身份检查。因此当前产物是研究报告或计划草稿，不能把 `COMPLETED` 理解为语义正确或业务动作已经执行。

## 5. 页面订阅事件，恢复时读取原运行

`GET /rag/research/runs/{id}`、`/sources` 和 `/events` 都先检查 owner。事件支持分页 JSON 或只读 SSE；SSE 使用递增事件编号与 `after` / `Last-Event-ID` 游标重播。订阅和重连不会领取运行、增加模型调用或重复启动 worker，最终产物可以独立从数据库读取。

聊天页面展示工具进度、子任务、追问和报告或计划卡片；下载导出的是已保存产物。刷新后按会话恢复研究记录。浏览器断开订阅不取消后台研究；用户点停止才调用取消接口。普通聊天保留原来的流式消息与停止流程，两种取消语义不能混用。

## 6. 超时、取消和重启后如何交代结果

一个 worker 失败或超时不会删除另一个 worker 已成功的发现。研究阶段超时或额度耗尽时，有可用依据才尝试用预留额度形成 `PARTIAL`，缺口同时保存；没有可用依据或最终生成不能形成合法产物则 `FAILED`。原生工具终止失败会明确记录 `NATIVE_FINISH_REQUIRED`，不能把文本回答自动包装成工具结果。

取消幂等地改变运行 epoch，关闭父子 SDK 和 HTTP 订阅；迟到结果不能覆盖终态或写入产物。取消与完成竞态由数据库条件更新收口，供应商是否停止远端推理仍是 unknown。当前是单 JVM 执行，进程启动会把失去执行者的 `QUEUED` / `RUNNING` 标为 `INTERRUPTED`，不恢复中间 token。用户可以查看保存的依据，另建请求重新研究。

## 7. 关键源码反查

- [ResearchRunController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/controller/ResearchRunController.java)、[ResearchRunService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchRunService.java) 与 [ResearchRunStore](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchRunStore.java)：创建、owner、revision、epoch、取消与原子终态。
- [ResearchAgentFactory](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchAgentFactory.java)、[ResearchTools](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchTools.java)、[ResearchWorkerCoordinator](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/ResearchWorkerCoordinator.java) 与 [BoundedResearchModel](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/runtime/BoundedResearchModel.java)：原生工具、独立上下文、共享额度和实际 usage。
- [SourceReader](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/SourceReader.java) 与 [ResearchEvidenceStore](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchEvidenceStore.java)：精确来源、阅读证明、邻块、变更与停用。
- [ResearchArtifactGenerator](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchArtifactGenerator.java)、[PlanDraftValidator](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/PlanDraftValidator.java) 与 [ResearchCompletionService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchCompletionService.java)：统一生成、一次修复、引用回填与最终提交。
- [ResearchEventStreamService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchEventStreamService.java)、[研究状态](../../../frontend/src/stores/researchStore.ts)、[研究进度](../../../frontend/src/components/chat/ResearchProgress.tsx) 与 [计划卡片](../../../frontend/src/components/chat/PlanDraftCard.tsx)：只读重播、会话恢复与产物展示。
- [WorkbookDiffService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/WorkbookDiffService.java)：独立 XLSX 事实比较；[P7 验证脚本](../../../scripts/validate-agentic-research-p7.sh) 与[评测说明](../../../eval/agentic-research/README.md)：程序验证与真实模型结果的区分。
