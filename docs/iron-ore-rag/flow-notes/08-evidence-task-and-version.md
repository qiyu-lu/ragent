# 08｜回答之后：证据任务、审批模拟与版本比较

这篇从一条已经保存完成的 assistant 消息开始。上游回答流程已经把回答正文、文档级 `sources` 和有限条精确证据 `retrievedChunks`（下文简称 grounding）写入消息表；这里不重新检索，也不重新聊天。接下来有三条彼此独立的请求：把一次回答整理成待审核任务、批准并模拟这个任务、比较同一文档的两个 XLSX 版本。ROS1 mission 是批准后的可选旁路，不是普通模拟的下一步，更不是默认真实控制。

## 1. 从已保存回答创建证据约束的候选任务

用户想把回答变成可审核对象时，调用 `POST /iron-ore/task-templates`，请求只带 `sourceMessageId` 和一个 `docId`。Controller 不补业务事实，只把两个 ID 交给任务服务；服务在同一事务中完成读取、模型整理、确定性校验和 `DRAFT` 落库，最后返回候选任务视图。

- 服务先从登录上下文取得当前 `userId`，再按 `sourceMessageId + docId + ownerUserId` 查已有任务。
  - 查到时立即把已有行转换成视图返回，不再读取消息、不再调用模型，也不新建草案。这处理的是前一次请求已经成功提交后的顺序重复。
  - 转视图时会反序列化 `template_data` 和 `evidence_refs`，并按任务 ID 查一次 execution；因此返回值包含任务状态、结构化模板、证据引用快照，以及已经存在时的模拟结果。
  - 这次“先查再插”不是并发原子幂等。两个并发请求可能同时查不到，随后数据库的部分唯一索引 `source_message_id + doc_id + owner_user_id WHERE deleted=0` 会拒绝其中一次插入；当前 Service 没有捕获唯一冲突并改为回读已有对象，异常会向外传播并使该事务回滚。

- 没有旧任务时，服务才按消息主键读取 `t_message`，并同时检查三件事：消息存在、`message.user_id` 等于当前用户、`role` 忽略大小写后等于 `assistant`。
  - 任一条件不满足都抛出“来源回答不存在或无权访问”。所以拿别人的 assistant 消息、拿自己的 user 消息，都会在这里停止；后面不会读取 grounding 或调用模型。
  - 这里信任的是已保存消息，而不是客户端重新提交的回答文本。消息里的 `sources` 是文档级来源列表，grounding 则是随 assistant 消息保存的、按 chunk 标识的有限证据列表，两者用途不同。

- 服务在该 assistant 消息的 `sources` 中按 `docId` 查找所选文档。
  - 找不到就抛出“指定文档不是该回答的检索来源”。这一步防止用户随便拿另一个文档 ID 拼到本次回答上。
  - 找到后再按 `docId` 读取当前 `t_knowledge_document` 行；记录不存在则抛出“来源文档不存在”。本链没有额外按当前用户校验文档所有权，也没有检查文档当前是否 `enabled`，它主要依靠“当前用户拥有的 assistant 消息确实把该 docId 记为 source”建立关联。

- 接着只从这条消息已经保存的 grounding 中筛选所选文档的证据，顺序保持消息中的原顺序。
  - 一块证据必须非空、`chunk.docId == 请求 docId`，且 `chunkId` 与文本都非空，才能留下。由这些块的 `chunkId` 构成 `LinkedHashSet`，就是本次模型输出允许引用的 evidence ID 白名单。
  - 当前任务一次只选一个文档。即使同一回答还有另一个文档的 grounding，也会因 `docId` 不同被排除，不能未经校验混入 Prompt 或白名单。
  - `sources` 有某文档，不代表 grounding 一定还有它。上游来源按文档归并，而 grounding 对最终 chunks 去重、按分数排序后最多保存 8 块；某文档可能出现在来源面板，却在截断后没有任何块。此时 sources 检查虽然通过，筛选结果仍为空，服务明确返回“该回答没有可用于生成任务的精确检索证据，请重新提问后再生成”，不会拿 source 摘录代替证据，也不会临时重做检索。

- 为恢复模型要看的问题，服务查看 assistant 的 `replyToMessageId`。
  - 没有该 ID时，问题使用固定文本“根据当前检索证据生成候选任务模板”。
  - 有 ID 时再读被回复消息；只要记录存在且属于当前用户，就取其 `content`，否则仍使用固定文本。这里没有再要求被回复记录的角色一定是 `user`。

### 1.1 模型实际收到什么，返回后怎样处理

- 服务先从 classpath 加载 `prompt/iron-ore-task-template.st` 作为 system 消息。模板加载器按路径缓存原始模板；它没有为本次请求填槽，因为模板本身只有角色、证据边界和固定 JSON 形状。
  - system Prompt 说明输出只是待人工审核草案，不是控制指令；事实只能来自问题和 `<evidence>`；每个非空事实项必须引用真实 `evidenceChunkIds`；原文没写的质量、异常和安全规则应留空；图片描述不能单独证明操作步骤、参数或安全规则。
  - 所谓 schema 在这里是 Prompt 中展示的 JSON 字段形状和 Java record 反序列化目标，不是模型 API 的强制 JSON Schema 响应格式。

- 服务再构造一条 user 消息，数据来源清楚分开：
  - `<question>` 来自上一条被回复消息，或前述固定回退文本；
  - `<document version="...">文档名</document>` 的文档名与版本来自刚读到的 `KnowledgeDocumentDO`；
  - `<evidence>` 中每块写成 `<chunk id="..." sheet="..." cells="...">文本</chunk>`。ID、Sheet、单元格范围和文本来自所选文档的 grounding，每块文本在这次模型调用边界最多取前 4000 个 Java 字符；这不是 token 上限。

- 服务把两条消息按 `system -> user` 顺序放入 `ChatRequest`，设置 `temperature=0`、`topP=0.2`、`thinking=false`，并同步调用 `Tier.STANDARD`。
  - 低 temperature 只降低采样波动，不让模型变成完全确定的规则程序；供应商实现、并发、模型版本和生成本身仍可能产生差异，更不能因此认定动作一定忠于原文。
  - 模板加载失败或第一次模型调用直接失败发生在解析用的 `try` 之前，不会进入 JSON 修复分支，而是直接向外抛出；事务不会写入草案。

- 第一次模型正常返回字符串后，程序先去掉首尾空白和可选的首尾 Markdown 代码围栏，再由 Jackson 把 JSON 读成 `TaskTemplatePayload`，随后立刻运行 Java 校验器。
  - `TaskTemplatePayload` 包含 `title`、`procedureName`、`documentVersion`，以及 prerequisites、steps、qualityCriteria、exceptionHandling、safetyConstraints。步骤还含 order、action、tools、parameters 和 evidenceChunkIds。
  - record 构造器会把缺失或为 null 的各列表归一成空列表。因此 Prompt 要求“字段必须完整”，但当前 Java 并没有逐个拒绝缺失的可空数组；`steps` 缺失会因归一后为空而被拒绝，其他四类可以为空。步骤的 tools、parameters 也可为空，参数内部的 name/value/unit 当前没有额外校验。

- JSON 解析或后续协议校验第一次失败时，服务只修复一次。
  - 它沿用原 user Prompt，再追加第一次失败消息、“仅修复 JSON、不得添加新事实”，以及第一次原始输出的前 6000 个 Java 字符；仍以同一 system Prompt 和相同模型参数调用模型。
  - 第二份输出重新经历去代码围栏、Jackson 解析和完整 Java 校验。仍失败时转成 `ClientException`：“候选任务生成结果未通过结构与证据校验：...”；不保存半成品。修复调用自身失败也会被这层捕获并按同一生成失败返回。

### 1.2 Java 校验的实际顺序与三层正确性

- `TaskTemplateValidator` 按固定顺序收紧模型输出：
  - 先拒绝空 payload，再要求 `title`、`procedureName` 为非空白文本，并要求 `steps` 至少一项；
  - 依次校验 prerequisites、qualityCriteria、exceptionHandling、safetyConstraints 中的每个非空项：对象不能为 null、`text` 不能空白、evidenceChunkIds 不能为空；
  - 每个条目的 evidenceChunkIds 先检查同一列表中不能重复，再检查去重后的所有 ID 都包含在本次单文档白名单中；
  - 最后按列表下标检查 steps：第 `i` 项的 order 必须等于 `i + 1`，所以只能从 1 连续递增；action 不能为空；其 evidenceChunkIds 同样必须非空、无重复且全部在白名单内。

- 全部通过后，Validator 新建 payload：只 trim `title` 和 `procedureName`，并无条件用数据库文档行的 `documentVersion` 覆盖模型自报版本。版本事实由上传时的 `DocumentIdentityResolver` 从原文件名最后一个形如 `V数字[.数字][-demo]` 的片段提取并写入文档表，任务创建时只是读取该数据库值；模型不能决定版本。
  - 当前校验没有比较 grounding 快照中的版本字段与文档行版本，也不重新读取 chunk 表确认 chunk 内容；它校验的是这条 assistant 消息保存下来的 grounding 契约。

- 到这里要区分三种“正确”，它们不能互相替代：
  - **JSON/schema 可读**：字符串能清理并反序列化成当前 record 形状；这只说明机器读得懂。
  - **引用 ID 合法**：每个需要证据的条目引用了本次选中文档白名单内的 ID；这只说明引用集合没有越界。
  - **动作语义正确**：action、工具、参数、前置条件等确实被对应原文支持。当前 Validator 不做文本蕴含、参数一致性或安全规则校验，所以这一层仍交给人工审核。

- 假设白名单只有 `c1`、`c2`，模型输出步骤 `{"order":1,"action":"记录浓度","evidenceChunkIds":["c9"]}`：JSON 能解析、order 也正确，但 evidence 校验会算出未知集合 `[c9]`，第一次触发修复；修复后仍引用 `c9` 就整体失败，不落 `DRAFT`。
- 另一种更隐蔽的情况是模型写 `{"order":1,"action":"关闭主泵","evidenceChunkIds":["c1"]}`，而 `c1` 原文只说“记录异常浓度”。ID 完全合法，当前校验仍会通过；它不证明“关闭主泵”受原文支持。这正是候选对象必须保留为草案、不能直接执行的能力边界。

### 1.3 DRAFT 怎样保存和返回

- 校验后的 payload 回到 Service，grounding 同时被映射为 `TaskEvidenceRef`：保留 chunkId、docId、文档名、grounding 自带的版本、Sheet、单元格范围，以及 trim 后最多 180 字符的摘录。
  - 模型看到的单块文本最多 4000 字符，而 `evidence_refs` 只保存 180 字符摘录。因此它是证据**引用与定位快照**，不是完整原文快照；后续审核若需要核对完整原文，仍要凭 docId/chunkId/位置回查相应资料。

- Service 构造 `IronOreTaskTemplateDO`：conversationId 和 sourceMessageId 来自 assistant 消息，docId 和 ownerUserId 来自请求/登录上下文，标题与规程名来自校验后的 payload，documentVersion 来自当前文档表，status 固定为 `DRAFT`。
  - 完整 payload 序列化进 JSONB `template_data`，上述引用快照序列化进 JSONB `evidence_refs`；createdBy、updatedBy 使用当前 username。随后 Mapper 插入 `t_iron_ore_task_template`。
  - JSON 序列化失败会抛 `IllegalStateException`；插入失败也会使整个事务回滚。模型是事务外部副作用，数据库回滚并不能“撤销”已经发生的模型调用。

- 插入成功后，Service 把刚写入的行转成 `CandidateTaskTemplateView` 返回。至此只是产生了当前 owner 可见的 `DRAFT`；批准和模拟都要由后续独立 HTTP 请求触发。

## 2. 独立的批准请求与普通模拟请求

批准和模拟不与创建请求自动串联。用户先拿任务 ID 请求批准；之后可以另发普通模拟请求。两者都先按当前用户检查 owner，但当前 demo 允许 owner 自己批准自己创建的草案，没有 reviewer 角色或四眼分离。

### 2.1 批准：条件更新收紧状态，但不增加新状态

- `POST /iron-ore/task-templates/{taskId}/approve` 先按主键读任务，再从登录上下文取当前 userId。
  - 任务不存在或 `owner_user_id` 不等于当前用户时，统一抛出“候选任务不存在或无权访问”。
  - 如果当前状态正好是 `DRAFT`，Service 发出条件更新：只有 id、ownerUserId 都匹配且数据库当前状态仍为 DRAFT，才写为 `APPROVED`；同时把当前 username 写入 approvedBy/updatedBy，把当前时间写入 approvedAt/updateTime。

- Service 没有使用更新影响行数决定返回，而是在条件更新后重新执行一次 owner 校验和查询，再返回最新对象。
  - 两个批准请求并发时，最多一个条件更新命中；另一个即使影响 0 行，也会在回读时看到 APPROVED。顺序重复批准同样直接返回已有状态。
  - 若进入方法时状态已经是 `APPROVED` 或 `SIMULATED`，代码不会更新，直接回读当前对象。当前 enum 只有 `DRAFT -> APPROVED -> SIMULATED`，没有拒绝、撤销、过期、重新打开等环节；不能把生产审批设想说成现有状态机。
  - `approvedBy` 记录的是当前登录 username，而 owner 比较使用 userId。因为没有“审批人必须不同于 owner”的判断，owner 自审在当前实现中是允许的。

### 2.2 普通模拟：从 APPROVED 生成本地事件

- `POST /iron-ore/task-templates/{taskId}/simulate` 是另一个事务。它先校验任务属于当前用户，再按 taskTemplateId 查询已有 execution。
  - 若 execution 已存在，立即反序列化并原样返回；这一步发生在模板状态检查之前，所以重复模拟不会再次生成事件。
  - 若没有 execution，模板状态必须恰好为 `APPROVED`；DRAFT、SIMULATED 或其他值都会抛出“只有已批准的候选任务可以模拟执行”。

- 状态通过后，Service 从 `template_data` 反序列化 payload，并确定性生成事件，不调用模型也不连接外部设备：
  - sequence 0 是 `SIMULATION_STARTED`，明确写“不会连接或控制真实设备”，无 evidence IDs；
  - 每个任务步骤生成一条 `STEP_COMPLETED`，sequence 使用步骤 order，message 拼成“步骤 n：action”，并复制该步骤的 evidenceChunkIds；
  - 最后一条 sequence 为 `steps.size + 1`，类型 `SIMULATION_COMPLETED`，说明结果仅用于流程演示，同样无 evidence IDs。

- Service 新建 execution：status 固定为 `SIMULATED_SUCCESS`，events 写入 JSONB，startTime 与 endTime 都使用同一个当前时间，createdBy 使用当前 username。先插入 `t_iron_ore_task_execution`，再条件更新模板 `APPROVED -> SIMULATED`，最后返回 execution 视图。
  - 两次数据库写入位于同一事务，正常异常会一起回滚；模板更新也限定旧状态为 APPROVED。不过当前代码没有检查该更新的影响行数。
  - execution 表对 `task_template_id` 有唯一索引。它是并发下最后防线：两个请求若都在插入前查不到，后插者会遇到唯一冲突。当前 Service 没有把这个冲突捕获成“读取赢家 execution”，所以只有已经提交后再来的顺序重复能稳定返回原结果，不能把并发冲突也说成已完整幂等处理。

- 普通模拟返回的是数据库中的 `TaskExecutionView`：execution ID、taskTemplateId、`SIMULATED_SUCCESS`、事件列表与起止时间。它没有启动后台执行者，也没有调用 ROS；本次 HTTP 返回时，本地事件和模板状态已经在当前事务内完成。

## 3. 版本比较是另一条独立请求

用户比较资料版本时，重新调用 `POST /iron-ore/version-diffs`，提交 `documentKey`、`baseVersion`、`targetVersion`。它不依赖前面的 taskId、消息 ID、审批状态或模拟记录，也不会重用任务中的 180 字符证据摘录。

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

## 4. 可选旁路：把任务编译成 ROS mission dry-run

ROS mission 由另一个 Controller 请求触发，不属于 `/simulate` 的内部步骤。普通模拟只是本地生成 `SIMULATION_*` 事件；ROS 分支则会创建独立的 `t_iron_ore_robot_mission` 记录并调用本机 HTTP 网关。两者可以独立存在，不能拼成“普通模拟后默认真实执行”。

- 用户调用 `POST /iron-ore/task-templates/{taskId}/robot-missions`，可选提交 missionType、robotId、containerId、起止/返航工位、returnHome 和 dryRun。
  - Service 先按 taskId 读取当前用户拥有的模板。它只拒绝 `DRAFT`，所以当前 `APPROVED` 和已经普通模拟后的 `SIMULATED` 都可进入 ROS 分支；方法名虽叫 `requireApprovedOwnedTask`，实际条件不是只允许 APPROVED。
  - 已有 mission 时，Service 用同一个 missionId 和本次参数重新编译并比较 plan hash：不同 hash 拒绝改参数重复派发；活动状态会向网关刷新，终态直接返回已存记录，READY 或 DISPATCH_FAILED 则可用相同计划继续派发。

- `RobotMissionCompiler` 不让模型把自然语言步骤翻译成命令。它只确认任务 JSON 可解析且 steps 非空，然后生成固定的 `SAMPLE_TRANSPORT` 计划；原任务 action、tools 和 parameters 不决定机器人技能。
  - missionType 为空时默认 `SAMPLE_TRANSPORT`，其他类型拒绝。dryRun 为空时默认 true，显式 false 立即拒绝。
  - robot、容器和工位标识有演示默认值，并且只能由 1～64 位字母、数字、下划线、短横线组成；起点不能等于终点，returnHome 默认 true。
  - 技能白名单是 `NAVIGATE_TO_STATION`、`TRANSPORT_CONTAINER`，以及可选的第二次 `NAVIGATE_TO_STATION` 返航；超时分别固定为 30、60、30 秒。作用域明确只做样品容器搬运演示，不做烘干、称重或设备控制。

- 编译器先构造 planHash 为空的规范化 `RobotMissionPayload`，对其 JSON UTF-8 字节计算 SHA-256，再把 64 位十六进制 hash 放回最终 payload。同一已存 missionId 和同一规范化参数会得到同一 hash；它用于检测同一候选任务的参数变化，不是审批签名或设备认证。

- 没有旧 mission 时，Service 先保存状态 `READY`、完整 mission JSON、planHash、步骤数和空网关快照，再调用 `RobotGatewayClient`。
  - mission 表对未删除的 taskTemplateId 有唯一索引，但这里同样是“先查再插”且未捕获并发唯一冲突。
  - 网关客户端默认 `enabled=false`；`iron-ore-demo` Profile 才设为 true，并配置本机 `http://127.0.0.1:18081`、3000 ms。客户端将配置 timeout 下限收紧为 250 ms，关闭连接失败自动重试。
  - 派发是 `POST /missions`。HTTP 非成功、响应 JSON 无法解析或连接失败会抛网关异常；Service 把数据库 mission 改为 `DISPATCH_FAILED` 并保留消息，然后向用户返回“任务已记录，但网关未接收”。这里先落库、后外调，没有数据库事务把网关一起回滚。

- 网关正常返回 `RobotGatewaySnapshot` 后，Service 先要求响应 missionId 与本地记录一致，再映射状态；空状态按 DISPATCHED，未知状态按 FAILED。随后保存完整 gatewayState、当前/总步骤、当前 skill、截断到 500 字符的消息及派发/完成时间，并返回 mission 视图。
  - 后续 GET 只在 DISPATCHED/RUNNING 时刷新网关；刷新失败返回最近持久化状态并附网关不可用提示。取消也只对活动状态调用网关，非活动状态直接返回本地记录。

- 这条分支验证的是“已审核的后端对象如何被确定性收口并交给 dry-run 适配器”。历史成功、取消和拒绝 `dryRun=false` 的记录只证明当时后端—HTTP 网关—ROS1 模拟服务器链路，不证明 `/cmd_vel`、控制器或真实设备效果。当前实现缺少生产所需的职责分离审批、策略引擎、设备认证、签名/防重放、联锁、急停、HIL 和现场验收；这些是与生产审批/设备控制之间的差距，不是本篇要虚构出来的现有环节或改造方案。

## 5. 把三条边界连起来复述

- 回答结束时，系统拥有“展示来源 sources”和“有限精确 grounding”；任务请求只能选择当前用户 assistant 消息中的一个 source，再从同一消息筛出这个 docId 的 grounding，形成 evidence ID 白名单。来源存在但证据块缺失就拒绝，不回头检索。
- 模型只负责在固定 Prompt 形状下组织候选文字。程序清理 JSON、最多修复一次，按字段、步骤顺序和证据白名单校验，并用数据库版本覆盖模型版本；这仍只覆盖结构正确与引用闭合，不能证明动作受原文支持。
- 成功结果以 DRAFT、完整 payload 和短摘录证据引用快照保存。owner 可在另一个请求中自审为 APPROVED，再由普通模拟生成本地事件并变为 SIMULATED；当前没有拒绝、撤销或生产审批链。
- 版本比较从稳定文档键和两个版本名重新读取原 XLSX，按可见 Sheet 的单元格地址与“公式 + 显示值”确定性比较。它与任务审批无状态依赖，也不理解所有 Excel 语义。
- ROS mission 只有显式请求才进入：固定任务类型、受限标识符、技能白名单、plan hash 和强制 dry-run 把权限继续缩小。它既不消费普通模拟事件，也不开放真实控制。

## 6. 关键源码反查

- [IronOreTaskController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/controller/IronOreTaskController.java) 与 [IronOreTaskTemplateService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/IronOreTaskTemplateService.java)：创建、owner 检查、一次修复、批准条件更新、普通模拟和重复请求处理。
- [TaskTemplatePayload](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/model/TaskTemplatePayload.java)、[TaskTemplateValidator](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/TaskTemplateValidator.java) 与 [任务 Prompt](../../../bootstrap/src/main/resources/prompt/iron-ore-task-template.st)：输出形状、列表归一、字段/顺序/白名单校验及模型证据边界。
- [ConversationMessageDO](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java)、[GroundingChunksAssembler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/GroundingChunksAssembler.java) 与 [SourcesAssembler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourcesAssembler.java)：已保存消息中 sources 与最多 8 条 grounding 为什么可能不完全同构。
- [任务表实体](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/dao/entity/IronOreTaskTemplateDO.java)、[execution 实体](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/dao/entity/IronOreTaskExecutionDO.java) 与 [schema_pg.sql](../../../resources/database/schema_pg.sql)：JSONB 数据、状态字段和两个唯一索引。
- [IronOreVersionController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/controller/IronOreVersionController.java)、[WorkbookDiffService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/WorkbookDiffService.java) 与 [DocumentIdentityResolver](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/DocumentIdentityResolver.java)：版本请求、文档身份来源和确定性单元格比较。
- [IronOreRobotMissionController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/controller/IronOreRobotMissionController.java)、[RobotMissionCompiler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/RobotMissionCompiler.java)、[RobotMissionService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/RobotMissionService.java) 与 [RobotGatewayClient](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/gateway/RobotGatewayClient.java)：可选 dry-run 的输入门禁、固定计划、落库/外调顺序和网关快照。
- [工业知识 Demo 改动记录](../changes/2026-08-12-industrial-knowledge-demo.md) 与 [ROS1 dry-run 记录](../changes/2026-08-12-ros1-robot-mission-demo.md)：历史设计边界和当时的分层验证；这些历史记录不替代当前源码，也不证明本次做过服务、模型或真实设备验证。

## 7. 相对旧笔记的重要修正

- 唯一索引确实阻止重复行，但当前创建、普通模拟和 ROS mission 都没有捕获并发唯一冲突后回读赢家；只能把“已提交后的顺序重复返回已有对象”称为已实现幂等行为。
- 任务 `evidence_refs` 是带最多 180 字符摘录的引用定位快照，不是模型所见证据全文的不可变副本。
- 版本 diff 的稳定顺序是基准键顺序后追加目标独有键，不是按 `ADDED / REMOVED / MODIFIED` 分组；版本查询也没有当前用户级资源授权检查。
- ROS Service 实际只拒绝 DRAFT，因此 APPROVED 和 SIMULATED 都能显式创建/恢复 mission；它不是普通模拟的默认下游，且始终只允许 dry-run。

本文完成的是当前 checkout 的静态源码核对和笔记整理；没有启动服务、应用迁移、调用模型、运行测试或连接 ROS/真实设备。
