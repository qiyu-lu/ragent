# 06｜聊天流式回答与停止：从问题进入队列到留下消息、来源和 Trace

本文只讲一轮聊天怎样被接收、逐步推给浏览器，以及正常结束、主动停止、连接结束和模型错误各自留下什么。要先把三条触发关系分开：聊天主请求负责启动回答；停止是用户后来单独发出的请求，不是每次回答的固定下一步；连接完成、超时和出错则由 `SseEmitter` 或模型流在各自时刻触发回调。

贯穿示例均为假设：用户在已有会话 `conv-7` 中问“C1 的 S7 正常浓度是多少？”，最终选中规程 V1.2 的两个块和台账 V3 的一个块。示例 ID、文本和事件顺序用于理解，不是一次真实运行记录。

## 1. 先认清这一轮同时存在的对象

- `conversationId` 标识整段会话。请求可不带；服务端此时先生成雪花 ID。请求带了则沿用原值，但聊天入口本身不会先查询并证明它属于当前用户；真正读取历史、更新会话时会带 `userId` 条件。
- `taskId` 标识这次流式任务，用于 `meta`、停止请求、进程内任务表、Redis 取消信号和 Trace run。它不等于消息 ID。
- 用户消息 ID 在当前问题写入 `t_message` 后产生；助手消息 ID 要到正常完成、取消时保存了部分正文或限流拒绝消息落库后才产生。`meta` 因而没有 `messageId`。
- `replyToMessageId` 写在助手消息上，把本轮助手消息精确关联到本轮用户消息。推荐追问靠它找回原问题，而不是猜“同会话上一条就是问题”。
- SSE（Server-Sent Events）是服务器沿一个 HTTP 响应单向推事件的方式。当前连接里会出现 `meta`、若干 `message`、以及分支终态事件；用户停止仍要另发 `POST /rag/v3/stop`。
- 回调是“某件异步事情发生后继续执行哪段代码”的约定。这里至少有两组：模型回调产生思考、正文、完成或错误；`SseEmitter` 的连接回调报告连接完成、超时或发送错误。两者不能混为一组。
- 取消句柄 `StreamCancellationHandle` 是模型适配器返回的、可在以后调用 `cancel()` 的对象。当前 HTTP 模型实现会设置本地取消位并调用 OkHttp `Call.cancel()`；这是尽力中止本次网络读取，不是远端一定停止计费或推理的证明。
- CAS（compare-and-set，比较并设置）表示“只有当前值仍等于预期值，才原子改成新值”。本篇有进程内原子布尔 CAS，也有数据库 `WHERE status = 'RUNNING'` 的条件更新；它们保护的对象不同。

## 2. 触发关系一：用户发出聊天主请求

这条流程从 `GET /rag/v3/chat` 开始。Controller 接受问题、可选会话 ID 和是否深度思考，Service 建立任务与 SSE，再把后续编排交给全局队列。排队通过后才创建 Trace run、加载记忆、改写、分类、检索并生成回答。

### 2.1 Controller 创建连接，Service 先发 `meta` 再排队

- 已登录用户带 `question`、可选 `conversationId`、可选 `deepThinking` 请求 `/rag/v3/chat`。Controller 用 `rag.default.sse-timeout-ms` 创建 `SseEmitter`；当前主配置是 `300000 ms`，即 5 分钟，然后调用 `RAGChatServiceImpl.streamChat`，最后立即把 emitter 作为 HTTP 响应对象返回。
  - `SseEmitter` 不是完整答案，而是以后继续写事件的长连接句柄。Controller 返回只表示连接建立阶段结束，不表示回答结束。
  - `question` 是必填请求参数；`conversationId` 为空时，Service 生成新的雪花 ID，否则直接沿用；同一位置再生成本轮 `taskId`。
- Service 调用 `StreamCallbackFactory` 创建 `StreamChatEventHandler`。构造处理器时就完成两件事，顺序是固定的：
  - 先发送 `meta`，载荷只有 `{conversationId, taskId}`。前端据此把临时“新对话”绑定到真实会话，并保存以后停止所需的 `taskId`。
  - 再把 `taskId -> {SSE sender, 取消完成载荷生成器, 尚为空的模型句柄}` 登记到当前 JVM 的 `StreamTaskManager`。登记会检查 Redis 是否已有取消标记；这一步封住“用户收到 meta 后立刻停止，而本机任务尚未登记”的窗口。
  - 此时还没有加载历史，也没有写当前用户消息，所以 `meta` 无法携带用户消息 ID 或助手消息 ID。`taskId` 关联执行，`conversationId` 关联会话，后续 `finish.messageId` 才关联已保存的助手消息。
- 回调构造后，Service 把问题、实际会话 ID、原始 emitter 和一个“获准后执行 Trace + Pipeline”的 `Runnable` 交给 `ChatQueueLimiter.enqueue`。因此 `meta` 与本机任务登记都发生在进入全局队列之前。

#### `@IdempotentSubmit` 在这里实际管到哪里

- 聊天 Controller 的注解以当前 `userId` 作为锁键；但 `IdempotentSubmitAspect` 受 `ragent.eval.enabled` 控制：值为 `true` 时直接放行，不加锁。当前 `application.yaml` 明确配置为 `true`。
- 即使把该开关设为 `false`，切面也只在 Controller 方法执行期间持有 Redisson 锁：`joinPoint.proceed()` 返回 `SseEmitter` 后，`finally` 立即解锁。模型流仍在后台继续，所以这不是“一名用户在整段回答期间互斥”，也不是会话 active-task 约束。
- stop 接口也有同一注解，但其默认锁键还包含 servlet path、当前用户和参数摘要；同样只包住该次同步方法调用。

### 2.2 全局队列决定何时进入编排，或怎样拒绝

- 当前主配置打开 `rag.rate-limit.global.enabled`，分布式上限为 10，最多排队 15 秒。`FairDistributedRateLimiter` 用 Redis 有序集合保存排队次序、可过期信号量保存许可，并用 Lua 在有可用许可时从队首窗口原子认领 ticket。
  - 这限制的是所有实例共享的聊天入口工作，不按用户或会话分别限流。
  - ticket 在 `PENDING` 时，连接完成、超时或出错回调会把它 CAS 成 `CANCELLED` 并移出队列；排队期限到达则 CAS 成 `TIMED_OUT`，转拒绝流程。只有一个终态能赢。
  - 获得许可后，`onAcquire` 被提交到 `chatEntryExecutor`。许可由这个 Runnable 的 `finally` 释放；当前 Runnable 会执行 Trace runner 和 Pipeline，直到模型路由返回首包成功后的取消句柄并完成绑定，随后就返回。后续 token 仍在模型线程产生。因此“全局并发 10”主要限制排队后的前置编排、检索和首包探测，不覆盖整段流式生成。
  - 许可另有 30 秒租期兜底。租期和 SSE 的 300 秒不是同一个超时。
- 如果 15 秒内取得许可，执行线程先进入 `StreamChatTraceRunner.run`：
  - Trace 开启时生成 `traceId`，插入一条 `t_rag_trace_run`：`conversationId/taskId/userId` 关联本轮对象，状态为 `RUNNING`，`extraData` 保存问题和字符长度。
  - 因为 stop 可能早于这条插入，插入后马上检查本机任务是否已标记取消；若已取消，就条件写 `CANCELLED` 并不执行 Pipeline。
  - 未取消才用 Trace 装饰过的 callback 进入 `StreamChatPipeline`。该装饰器透传增量，并在第一个正文 `onContent` 记录用户首包节点；只有思考增量还不会触发这个用户首包节点。
- 如果排队超时，或获准后提交执行器被拒绝，进入独立的 reject 收尾：
  - 先保存用户消息，再保存内容为“系统繁忙，请稍后再试”、状态为 `REJECTED`、并通过 `replyToMessageId` 关联用户消息的助手消息；新会话会在保存用户消息时创建并生成标题。
  - 然后发送 `meta -> reject -> finish -> done` 并关闭 emitter。`finish` 带拒绝消息 ID、标题和 `messageStatus=REJECTED`，没有 sources。
  - 当前实现有一个需要如实记住的协议瑕疵：回调工厂在排队前已经发送过第一次 `meta` 并登记了原 `taskId`；reject 记录流程又生成一个新 `taskId` 并发送第二次 `meta`，而且没有注销第一次登记。因此拒绝分支不是理想化的单 `meta` 序列，前端最终会以第二次 `meta` 覆盖任务 ID，本机旧登记只能等缓存过期。
  - 如果拒绝消息落库本身失败，代码仍发送 `done` 并关闭连接，但没有足够字段发送 reject/finish。这是“连接不悬挂”的降级，不等于已留下拒绝消息。

### 2.3 获准后加载旧记忆，再保存本轮用户消息

- Pipeline 首先调用 `memoryService.load(conversationId, userId)`。摘要和最近历史由 `memoryLoadExecutor` 并行读取，两个 future 都结束后合并：
  - 摘要查询取该用户、该会话最新的一条摘要；有内容时用 `<conversation-summary>...</conversation-summary>` 包装成 system 消息。
  - 历史查询按 `conversationId + userId + deleted=0` 读取最近 `historyKeepTurns * 2` 条；当前配置保留 8 轮，即最多取 16 条，数据库先倒序限量，代码再恢复为正序。空内容、非 user/assistant 被过滤，开头孤立的 assistant 消息被去掉，历史 assistant 中旧的引用标记会被剥离。
  - 摘要或历史各自读取失败时分别退化为 null/空列表；外层再失败也返回空历史。因此数据库故障可能让这一轮继续但失去上下文，不会自动告知模型“历史不完整”。
  - 有摘要且历史非空时，返回顺序是 `system 摘要 -> 最近 user/assistant 历史`。若历史为空，即使查到摘要，`attachSummary` 当前也返回空列表。
- 历史加载完，Pipeline 才把当前原问题构造成 user 消息并 `append`：
  - `t_message` 插入后取得用户消息 ID，但 append 还没有返回，存储层继续维护会话记录。
  - user 消息写入后，存储层把会话 ID、用户 ID、当前原问题和当前时间交给会话 `createOrUpdate`。会话服务先拒绝空用户 ID，再按 `conversationId + userId + deleted=0` 查询；已有记录只更新 `lastTime` 并返回，查不到才拿当前问题生成标题。
    - 标题生成器读取 `titleMaxLength` 作为有效最大长度；为 0 或负数时改用 30。它准备 `title_max_chars -> 最大长度字符串`、`question -> 当前原问题` 两个槽值，把它们和 `prompt/conversation-title.st` 交给模板加载器。
    - 加载器先按路径查进程内缓存，命中直接取模板原文，未命中才从 classpath 读取 UTF-8 文本并缓存；缓存的不是上轮填好的问题。接着用本次槽值替换 `{title_max_chars}`、`{question}`，把连续三个及以上换行压成两个，再 trim 整段文本，返回完整 Prompt。
    - 模板要求只根据用户问题概括核心主题，直接输出限定长度的标题，不加引号、前缀或解释。模型只收到一条 user 消息，内容就是这份 Prompt；请求使用 `temperature=0.7`、`topP=0.3`、`thinking=false`，同步调用 `llmService.chat(request, Tier.FAST)` 等待标题字符串。
    - 请求构造或模型调用在 try/catch 内失败时返回“新对话”；模板不存在或读取失败发生在这个 try 之前，会向外抛出，不能当成同一回退。正常返回的标题直接使用，没有程序级长度截断或去引号，所以模板要求不是输出已经合规的证明。
    - 标题返回会话服务后，才把 `conversationId/userId/title/lastTime` 构成会话记录并插入；标题生成器本身不落库。会话服务返回后，存储层继续返回此前取得的用户消息 ID。这段同步工作发生在 user append 内，不是等答案结束后才生成标题；若模板或会话写入失败，append 不会正常返回，外层按同步 Pipeline 异常收尾，见 4.3。
  - 外层记忆服务接着检查摘要触发条件；当前保存的是 user，不满足 assistant 条件，直接跳过压缩并返回消息 ID。
- append 正常返回后，Pipeline 才取得 `questionMessageId` 并交给事件处理器，正常/中断/拒绝的助手消息以后用它填写 `replyToMessageId`。随后仍使用“追加当前问题之前加载出的 history”，因此最终 Prompt 的历史不重复包含本轮问题；本轮问题在最后一条 user 消息中单独出现。

### 2.4 调用 D：把问题变成可检索文本和路由结果

这里接[查询理解与路由流程 D](04-query-understanding-and-routing.md)，只保留聊天链下一步需要的数据含义，不在本篇重复分类树和候选分配算法。

- 先做问题改写与可选拆分：输入是当前原问题和上一步历史。
  - 开关关闭时只做术语映射归一化和规则拆分，返回 `RewriteResult(rewrittenQuestion, subQuestions)`。
  - 当前主配置打开改写。程序先做术语归一化，然后构造消息列表：改写规则 system Prompt，最近 user/assistant 历史，以及最后一条 user 的归一化问题；system 摘要会被过滤。代码意图是最多取最近 4 条历史，但 `skip` 基于包含摘要在内的原 history 长度，存在少取有效历史的边界。
  - 快速档模型应返回 `rewrite`、`should_split`、`sub_questions` JSON。程序去代码围栏、解析并校验：`rewrite` 为空或调用/解析失败时，退回“归一化问题 + 只含它的单项子问题”；`should_split=false` 时即便数组多写了内容也只保留 rewrite，拆分时对子问题 trim、去空、去重。
  - 例如最近一条 assistant 历史若错误写成“设备 S7”，原问题“它的阈值？”就可能被正常改写成 S7；这里没有事实校验器发现历史回答中的设备名错误。system 摘要已被过滤，不会作为本次改写的直接输入。可查原始历史、让用户纠正或以后引入结构化事实槽，但这些不是当前自动修复。
- 再为每个子问题并行做意图分类：输入是单个子问题以及当前启用意图树的叶子候选，不直接带会话历史或知识原文。
  - 没有可用叶子时直接返回空意图，不调用分类模型；有叶子时，分类模型看到的 system Prompt 列出候选的 `id/path/description/type/examples`，MCP 候选还带 `toolId`，最后一条 user 是子问题。模型返回候选 ID 和 score JSON，程序丢弃未知 ID、低于 0.35 的项，每个子问题最多保留 3 项。
  - 全部子问题汇合后，以整次请求 3 项为目标收口：先为每个有候选的子问题保留最高分，再按全局分数填剩余名额。若有候选的子问题本身超过 3 个，保底项也会超过 3，当前算法不会再删除，因此这不是所有输入下都成立的请求硬上限；具体分配由流程 D 展开。
  - 某个子问题分类调用或解析失败时，该子问题得到空意图，Pipeline 仍可走无意图的全库检索兜底；分类标签本身不是授权结论。
- 改写和分类完成后得到 `subIntents = [{subQuestion, nodeScores}]`。Pipeline 先做歧义引导判断，再决定直接回答还是交给 E 检索。

### 2.5 四种回答分支在这里分开

- **需要澄清**：引导开关当前默认开启。仅单子问题且至少两个不同知识域候选时，程序比较头两名分数比；明显歧义直接确认，边界区间才额外调用一次歧义判断模型，问题已明确写出系统名或差距足够大则跳过。确认后由程序按候选路径渲染澄清选项。
  - Pipeline 直接 `onContent(澄清文本)` 再 `onComplete()`，不进入 E，也不调用知识回答模型。完成回调仍会把这段文本当正常 assistant 消息保存、发送 `finish/done` 并将 Trace 收敛为成功。
- **直接回答**：如果每个子问题都恰好只有一个 SYSTEM 意图，Pipeline 不检索知识库。它取第一个非空意图自定义 Prompt；没有就解析 `SYSTEM_CHAT` 槽位，然后组装 `system -> history -> user(改写问题)`，以 `temperature=0.7`、`thinking=false` 调用流式模型。
  - 这仍是一次生成模型调用，只是没有 KB/MCP 证据和 sources/grounding；返回取消句柄后也会绑定任务。
- **资料为空**：不属于纯 SYSTEM 时调用 E。若 E 返回的 MCP 文本和 KB 文本都为空，Pipeline 直接输出固定句“未检索到与问题相关的文档内容。”并正常完成。
  - 这里“无知识证据不调用知识回答模型”只描述最后生成阶段；前面通常已经调用过改写模型和意图分类模型，歧义边界还可能调用判断模型，检索内部也可能有 Embedding、Rerank 或 MCP 参数提取调用。
- **正常知识回答或混合回答**：E 至少返回 KB 或 MCP 上下文，Pipeline 才组装来源、grounding、引用编号和最终 Prompt，然后调用 G 的流式模型服务。后续见 2.7～2.9。

### 2.6 调用 E：子问题换回有限的证据上下文

- Pipeline 把 `subIntents` 交给 `RetrievalEngine.retrieve`。[检索与上下文流程 E](05-retrieval-and-context.md)对每个子问题识别 KB 与 MCP 意图，并行建立上下文：KB 路径进行当前启用通道的召回、融合/重排和请求级选择；MCP 路径提取参数、调用工具并格式化成功或失败文本。
- 返回的 `RetrievalContext` 对本篇最重要的是：
  - `kbChunks`：整次请求最终选中的、按 Prompt 选择顺序保存的知识块规范列表；不是全部候选池。
  - `kbContext`：由选中块格式化出的模型可读文本，同文档块会分组并在组内按 `chunkIndex` 恢复顺序。
  - `mcpContext`：工具结果文本；`eligibleIntentIds` 决定哪些意图可参与回答规则和模板选择。
  - `intentChunks` 保留归因关系，`retrievalDiagnostics` 只用于诊断，不直接放进最终 Prompt。
- 当前请求级 `contextTopK` 是整次请求额度；多子问题共享它。E 内部算法、向量/Rerank 映射和可选多通道融合由检索流程笔记展开，本篇只从最终 `kbChunks/kbContext/mcpContext` 接回。

### 2.7 用同一批选中块组装三种不同对象

- Pipeline 只用 `retrievalCtx.effectiveKbChunks()` 作为共同起点。新检索链优先返回 `kbChunks`；只有兼容旧调用方未填它时，才从 `intentChunks` 按 `RetrievedChunkKey` 去重恢复。未进入这份最终列表的候选，不能突然出现在 sources 或 grounding。
- 先组装文档级 `sources`，供完成事件、消息落库、来源面板和引用编号共同使用：
  - 丢弃缺少 `docId` 的块，以 `docId` 去重；同文档多个块只保留 score 更高的块作为代表。相同分数时保留先遇到的块。
  - 各文档按代表块 score 降序，分数相同时按 `docId` 排序，再从 1 连续赋 `index`。
  - 批量查询文档记录，补齐文档名、sourceType、fileType、外链和版本；代表块提供最多 100 字摘录、`chunkId/sheetName/cellRange`。
  - sources 没有独立的固定条数上限；它最多等于最终选中块涉及的不同文档数，间接受 E 的请求级上下文额度限制。它按文档去重，不是“一块一来源”。
  - 回调只暂存非空 sources，尚不发送给浏览器；正常/停止的完成载荷和 assistant 消息复用同一列表。
- 再给 `kbContext` 注入引用编号：
  - 上下文格式化时每个文档块先有内部 `data-ragent-doc-id`。`CitationContextEnricher` 根据 sources 的 `docId -> index` 把它替换为模型可见的 `ref="N"`；无匹配来源则只删除内部 docId。
  - 引用开关关闭时也会经过这里，但只删除内部 docId，不加 `ref`。当前主配置打开引用，因此回答 system Prompt 还会追加 `[N](#cite-N)` 规则。
  - 编号代表文档，而不是具体句子的自动证明。模型写出合法 `[1](#cite-1)` 只能说明编号存在；当前没有 claim 级蕴含校验确认该文档块真的支撑那一句。
- 最后组装块级 `grounding`，作为随回答保存的证据块快照：
  - 只保留同时有 `chunkId/docId/text` 的块，按 `chunkId` 去重；重复 ID 取 score 更高者，再按 score 降序取最多 8 条。
  - 每条保存 `chunkId/docId/docName/version/sheetName/cellRange` 和 trim 后的块全文。这里没有再做字符截断。
  - grounding 不是另一轮检索，也不等于 sources：前者按块、最多 8 条、为后续推荐追问或任务生成保存内容；后者按文档、带面板摘录和引用序号。grounding 对象本身不再放入本轮回答 Prompt，不过它与 Prompt 的 KB 资料来自同一批选中块；Prompt 可能包含超过 8 个块，而保存的 grounding 已截到 8 条。

### 2.8 选择 system Prompt，并按真实角色顺序装入消息

- Pipeline 先合并各子问题意图，建立 `PromptContext`：改写后的总问题、MCP/KB 文本、对应意图列表以及 E 判定可用的意图 ID。
- `RAGPromptService` 先按实际有无 MCP/KB 分为 KB-only、MCP-only 或 mixed：
  - KB-only 只从 `eligibleIntentIds` 中保留知识意图并按 ID 去重；恰好一个可用意图且它有 `promptTemplate` 时用该模板，否则用 `KB_ANSWER` 槽位。
  - MCP-only 恰好一个 MCP 意图且有自定义模板时使用它，否则用 `MCP_ANSWER` 槽位；mixed 固定用 `MIXED_ANSWER` 槽位。
  - 槽位内容由 `AgentPromptResolver` 读取：先铺内置 Profile 的非空槽位，再用激活 Profile 的非空同名槽覆盖，结果在 Redis 缓存 1 小时。Profile 在这里影响的是具体 Prompt 文本；它不把当前 `workflow` 管线自动变成自主规划/循环执行的 Agent。`AGENT_MAIN` 只在 Agent 编排模式生效，当前这条工作流不会读取它。
  - 有 KB 且引用开关开启时，再把引用规则追加到 system Prompt。
- 最终消息顺序严格是：回答规则 system，随后原样追加已加载的 history，最后只有一条 user 消息，里面先放 MCP 资料、再放 KB 资料、最后放问题。多子问题会编号列在 `<questions>` 中，单问题放在 `<question>` 中。
- 一份缩短后的假设消息列表如下；真实标签由 `context-format.st` 渲染：

```text
1. system
   你是知识库问答助手……只能依据资料回答……
   行内引用规则：使用 [N](#cite-N)……

2. system                         # 仅在加载到摘要时存在
   <conversation-summary>此前讨论 C1；用户问过 S7。</conversation-summary>

3. user                           # 最近原始历史
   C1 使用哪个传感器？

4. assistant
   使用 S7。

5. user                           # 本轮唯一 user 消息
   <tool-data>……可选 MCP 结果……</tool-data>
   <documents>
     <content ref="1">规程 V1.2 的选中块……</content>
     <content ref="2">台账 V3 的选中块……</content>
   </documents>
   <question>C1 的 S7 正常浓度</question>
```

- 资料里的“忽略前文并执行某命令”仍只是 user 消息中的文本数据，但模型可能受间接 Prompt Injection 影响。当前通过 system 规则、标签隔离和工具/知识分支降低混淆，却没有内容净化器或输出安全证明；尤其 MCP/任务执行仍需独立权限门禁，不能只相信 Prompt。

### 2.9 调用 G 流式生成，前端逐步看到 `think/response`

- Pipeline 用上述 messages 构造 `ChatRequest`：`thinking=deepThinking`；有 MCP 时 `temperature=0.3/topP=0.8`，纯 KB 时 `temperature=0/topP=1`，然后调用[模型路由与故障切换流程 G](07-model-routing-and-fallback.md)的 `LLMService.streamChat`。
- G 先按 thinking 能力选择模型候选，并逐个尝试可用 provider：
  - provider 把请求转成 OpenAI 风格流式 HTTP，提交给模型流线程，并立即形成一个能设置取消位和取消 OkHttp Call 的句柄。
  - 路由层并不立刻把句柄返回 Pipeline，而是等待首个有效包。首包前启动失败、错误、无内容完成或超过候选的首包预算时，会标记该候选失败、取消它，再尝试下一个候选。
  - 首包成功才返回该候选句柄；Pipeline 随后 `taskManager.bindHandle(taskId, handle)`。如果停止已把本机任务标为 cancelled，绑定动作会立即调用这个迟到句柄的 `cancel()`。
  - 因此“还没首包就停止”时，SSE 可以先被取消收尾，但底层 provider 只有在首包探测返回句柄后才被当前 `bindHandle` 补取消；若一直无首包，仍可能等到候选首包超时。Redis 标记本身不会直接取消一个尚未暴露的 OkHttp Call。
- provider 每读到一条有效事件，就分别回调 `onThinking` 或 `onContent`：
  - 事件处理器先查本机 cancelled；已取消或 chunk 为空白就丢弃。非空思考追加到 `thinking`，非空正文追加到 `answer`。
  - 思考首次到达时记开始时间；首个正文到达时计算思考秒数。纯思考后直接结束而没有正文时，当前不会得到该持续时长。
  - 每个 provider chunk 再按 Unicode code point 切成前端消息块；配置 `ai.model.stream.message-chunk-size` 当前是 1，Java 缺省是 5 且最小为 1。每块发送一个名为 `message` 的 SSE，data 为 `{type:"think"|"response", delta:"增量"}`。
  - 前端解析 SSE：`think` 追加到思考区，`response` 追加到正在显示的回答。服务端同时累计完整字符串，后续落库不依赖浏览器再上传。
- 首个 `onContent` 还让 Trace wrapper 写一个 `USER_TTFT` 节点，时长从 Pipeline run 开始算到第一个正文回调，包含改写、意图、检索和模型首包前置时间。思考先到但正文没到时，这个指标仍未结束。

### 2.10 正常完成：先保存消息和发协议终态，最后收敛 Trace

- provider 读到完成标记时回调 `onComplete`。事件处理器先检查本机任务是否已取消；若已经 cancelled，直接返回，由停止路径负责收尾。
- 未取消时，正常完成按以下真实顺序执行：
  - 用累计 `answer`、可选 `thinking` 和思考秒数构造 assistant 消息，附上暂存的 sources、最多 8 条 grounding、`replyToMessageId`，把 `messageStatus` 设为 `NORMAL`，再插入 `t_message`。
  - assistant append 保存消息后，把会话 ID、用户 ID 和本次消息交给摘要服务。当前 `summary-enabled=true`，且消息角色为 assistant，服务便向 `memorySummaryExecutor` 提交异步检查；关闭开关或非 assistant 直接返回。这里只提交任务，不等待摘要生成，记忆服务随后返回助手消息 ID，当前完成回调继续准备 finish；后台执行者另按下面过程更新以后聊天要用的摘要。
    - 后台先读取 `summaryStartTurns=9`、`historyKeepTurns=8`；任一非正数就结束。随后对 `ragent:memory:summary:lock:<userId>:<conversationId>` 执行 `tryLock()`，拿不到锁就跳过本次检查；拿到后统计该用户会话内未删除的 user 消息，少于 9 条也结束。这里按 user 消息计轮次，不按 token 数或所有消息条数触发。
    - 达到阈值后，读取最新摘要和按时间倒序的最近 8 条 user 消息；最近列表最后一条的 ID 是历史窗口起点 `historyStartId`。旧摘要的 `lastMessageId` 是已覆盖游标 `afterId`；首次无摘要则为 null，旧记录没有游标时才按摘要更新时间（没有则创建时间）回查当时及以前的最大消息 ID。若旧游标已经大于等于窗口起点，说明摘要覆盖仍与最近历史重叠，本次不用重做。
    - 需要刷新时，取倒序 user 列表下标 `(size-1)/2` 对应的 ID 作为 `summaryCutoffId`，再查同用户、同会话、未删除的 user/assistant 消息，要求 `id > afterId`（首次省略下界）、`id < summaryCutoffId`，按 ID 正序返回待压缩原文。最近列表、边界 ID 或待压缩列表缺失时都直接结束；正常时取待压缩列表最后一个消息 ID，作为本次准备保存的新覆盖游标。
    - 假设已有 9 轮，每轮各一条 user/assistant。最近 8 条 user 是 `U9…U2`，中点下标 3 对应 `U6`；第一次压缩就取 `U6` 之前的第 1～5 轮，而最近原文仍保留第 2～9 轮。摘要与原文重叠第 2～5 轮，让后续几轮不必每次都重新生成；等最近窗口起点越过旧覆盖游标，再从游标后补入下一段。这里用 user 列表确定压缩边界，不能把它与加载历史时的“最近 16 条消息”当作始终严格相同的八个完整问答对。
    - 待压缩消息先过滤空内容和非 user/assistant，assistant 正文剥离旧引用标记，再恢复对应角色。摘要服务从 `CONVERSATION_SUMMARY` 槽位渲染规则，填入 `summary_max_chars=400`；槽位同样按内置 Profile 加激活 Profile 覆盖解析，当前初始化的内置规则要求保留具体话题、处理状态和用户约束，不记录具体答案、数值或完整步骤，避免旧答案与以后新检索的资料冲突。实际消息顺序是 `system 摘要规则 -> 可选 assistant 旧摘要 -> 本批 user/assistant 原文 -> user 合并去重指令`；旧摘要前明确注明仅用于合并去重，若冲突以本批对话为准。最后一条 user 要求合并为不超过 400 字符的单行摘要。
    - 服务用 `temperature=0.3`、`topP=0.9`、`thinking=false` 同步调用 FAST 模型，返回的是摘要字符串；此处同步等待只占用后台摘要线程。程序没有强制截断、改成单行或核实设备名，400 字符与事实边界仍是 Prompt 约束。
    - 返回非空白文本后，将 `conversationId/userId/content/lastMessageId` 交给消息服务，插入 `t_conversation_summary`。后续聊天重新加载最新一条摘要并包装为 system 消息，当前已经加载的 history 不会因此被替换；原始消息也不会因生成摘要被删除。
    - 模型调用失败时返回旧摘要；过滤后没有有效原文也会直接返回旧摘要。若旧摘要非空，外层仍会按本批最后消息 ID 保存它，可能出现“文本没补进新事实，覆盖游标却前移”；首次失败且旧摘要为空则不写入。槽位渲染等更早异常或写库异常由后台外层捕获并记录，不补做新摘要；已取得的锁在 finally 中由持有线程释放，异步任务未处理的异常另记日志。摘要任务的成功或失败都不决定本次 finish 的消息状态。
  - 助手消息保存异常会被捕获，流程继续，但 `messageId=null`；所以正常事件序列不保证消息一定已落库成功。
  - 若处理器创建时判断会话不存在或标题空白，就在完成时回查会话标题；找到则发送实际标题，否则发“新对话”。老会话通常 `title=null`，表示前端不必更新。这里不是重新生成标题。
  - 发送 `finish`，载荷为 `{messageId?, title?, sources?, messageStatus:"NORMAL"}`。grounding 不通过 SSE 下发，只随消息保存在数据库。
  - 接着发送 `done` 的 `[DONE]`；前端用它清除流式状态。`finish` 负责把临时助手消息换成数据库消息 ID、挂上 sources/状态/标题，`done` 只表示该连接的事件序列结束。
  - `taskManager.unregister(taskId)` 先删除当前 JVM 任务项，再异步删除 Redis 取消键；随后 `sender.complete()` 关闭 emitter。
  - 最后才回到外层 `ForwardingStreamCallback` 的 `finally`，按任务此刻是否 cancelled 尝试把 Trace run 从 `RUNNING` 改为 `SUCCESS` 或 `CANCELLED`。因此数据库 Trace 收敛发生在 `finish/done/注销/关连接` 之后。
- Trace 的 run 终态更新都带 `trace_id = ? AND status = 'RUNNING'`。它是一种数据库 CAS：第一个成功的终态获胜，后到的 SUCCESS/ERROR/CANCELLED 更新 0 行，调用方不重试也不覆盖。`finishRun` 当前不返回影响行数；stop 的 `cancelRunByTaskId` 返回 boolean，但上层只把它当尽力上报，false 也继续停止并最终返回成功。
- “回答结束但客户端没收到 done”并不矛盾：assistant 消息和 finish 之前可能已经发送/落库，代理断线也可能吃掉最后事件。当前没有持久化 SSE event log 或 `Last-Event-ID` 续播；前端网络重试会重新发整个 GET，而不是从旧 task 的某个 token 恢复。用户可重新读取会话消息得到已落库完整答案，但不能据此恢复原流的精确事件序列。

## 3. 触发关系二：用户另发停止请求

用户看到一半点击停止，或点击时还没有首包，前端都走独立的 `POST /rag/v3/stop?taskId=...`。它与聊天 GET 并发执行；正常回答不会自动再调用 stop。

### 3.1 前端怎样取得 taskId 并提出停止

- 用户点击停止时，前端只把 `cancelRequested=true`：
  - 已收到 `meta`，就调用 stop 接口；它不立即 abort 当前 SSE，而是等服务端回 `cancel/done`。
  - 还没收到 `meta`，前端先记住停止意图；以后 `onMeta` 一到，立即用其中 taskId 补发 stop。这正是“还没首包就停止”的客户端握手。
  - 如果连接在 meta 前永久失败，就没有 taskId 可提交，当前前端只能走流错误收尾；没有通过问题或本地临时消息 ID 反查并停止服务端任务的接口。

### 3.2 stop 入口先留持久短标记，再广播在线实例

- Controller 收到 taskId 后调用 `StreamTaskManager.cancel`。当前只依赖全局登录拦截和非空请求参数，没有查询 Trace/消息确认 `taskId` 属于当前 `userId`。这是一处对象级 owner 检查缺口；难猜的雪花 ID 不能替代授权。
- `cancel` 按以下顺序执行：
  - `SET ragent:stream:cancel:<taskId> = true`，TTL 30 分钟。带 TTL 标记弥补“事件先到、任务后注册/Trace 后插入”和某实例短时没在线消费消息的问题；后到的登记可以主动读取它。TTL 到期后不会成为永久审计记录。
  - 直接按 taskId 查最近仍为 `RUNNING` 的 Trace run，并尝试 `RUNNING -> CANCELLED`，填 `endTime/durationMs`。找不到或并发更新失败返回 false；`reportTraceRunCancelled` 不把它上抛。
  - 向 Redis Pub/Sub 主题 `ragent:stream:cancel` 发布 taskId。Pub/Sub 弥补“原 HTTP stop 落到实例 B，而模型句柄在实例 A”的路由差异，让所有当前在线实例立刻尝试本地取消；它不持久，离线实例不会重放，所以仍要有前面的 TTL key。
- stop 方法不等待 provider 真正退出、部分消息保存或前端收到 cancel；上述调用未抛异常就同步返回 success。它是“取消信号已提交”的响应，不是完整取消证明。

### 3.3 持有任务的实例怎样取消句柄、保存部分回答并收尾 SSE

- 每个实例的订阅器收到 taskId 后查本机 Guava task cache。没有任务项就返回；有则对 `cancelled` 做 `false -> true` CAS，只有获胜线程执行一次本地取消。
- 获胜线程先再次尝试 Trace `RUNNING -> CANCELLED`。Trace 上报有单独 try/catch，失败只记日志，不能挡住实际取消。
- 如果模型句柄已经绑定，调用其幂等 `cancel()`：底层设置取消位、取消 OkHttp Call，并结束 provider stream span。句柄异常被捕获，后面仍继续；如果句柄尚未绑定，暂时跳过，后来的 `bindHandle` 看到 cancelled 后补调取消。
- 然后 `completeCancelledTask` 用另一个 `cancelCompletionStarted` CAS，防止“注册线程发现 Redis 标记”和“Pub/Sub 线程”重复发送取消终态：
  - 先调用处理器提供的 supplier 构造取消载荷。只有累计正文 `answer` 非空时，才保存 assistant 消息；消息带已累计 thinking/answer、sources、grounding、`replyToMessageId` 和 `messageStatus=INTERRUPTED`。只有思考没有正文时不保存消息，载荷 `messageId=null`。
  - 部分消息保存失败只记录错误，仍继续；标题按与正常完成相同的回查规则取得。
  - 发送 `cancel`，载荷为 `{messageId?, title?, sources?, messageStatus:"INTERRUPTED"}`；再发 `done`，最后在 `finally` 调 `sender.complete()`。构造载荷、发送事件、关闭连接分别隔离异常。
  - 前端收到 cancel 后保留已显示正文，追加“（已停止生成）”，把临时消息 ID 换成后端返回 ID，并把状态置为 cancelled/INTERRUPTED；done 再清理流状态。
- 当前取消路径**没有调用 `taskManager.unregister`**。本机 task cache 使用写入后 30 分钟过期且最大 10000 项，Redis 取消键也保留到 TTL；这是当前源码的注销边界，不能写成“取消后立即注销并删除标记”。正常完成/error 才显式 unregister。

### 3.4 Trace 条件终态、失败后的行为与崩溃边界

- `cancelRunByTaskId` 先查 `task_id = ? AND status = 'RUNNING'` 的最新一条，再按该 traceId 执行 `WHERE trace_id = ? AND status = 'RUNNING'` 更新。若正常完成或错误先写终态，更新 0 行并返回 false；stop 仍广播、本地尝试取消并向调用者返回 success。
- `finishRun` 同样只更新仍为 RUNNING 的 run。正常/错误回调发现更新 0 行时当前不会补偿或报给用户；CAS 只防终态互相覆盖，不保证外部模型、SSE 和消息状态与赢家完全一致。
- 进程崩溃后的能力要分层说：
  - Redis 取消键可让后来重新登记的同 task 看到取消，但普通进程重启不会自动重建旧 emitter、内存任务或 provider 句柄。
  - Pub/Sub 不补发离线期间的消息；provider 句柄只在所属进程内，别的实例无法直接调用它。
  - 当前没有扫描过期流式 Trace 并修复遗留 `RUNNING` 的 watchdog，也没有持久取消命令队列或断线续播。已有摄取任务扫描不能冒充聊天 Trace 修复器。

## 4. 触发关系三：连接和模型各自怎样回调

这条关系解释“不是用户点停止，但流结束了”的情况。连接回调主要关闭发送或撤销排队 ticket；模型回调决定正常保存还是错误结束。当前实现没有把它们统一转换成主动取消。

### 4.1 客户端断开或主动 abort

- `SseEmitterSender` 在 emitter 的 completion、timeout、error 回调里只把自己的 `closed` 原子位设为 true；以后 `sendEvent` 静默返回，避免继续写已关闭连接。
- `ChatQueueLimiter` 另给同一个 emitter 登记 completion/timeout/error 回调，用来调用 ticket.cancel：
  - ticket 仍在 `PENDING` 时，它 CAS 为 `CANCELLED`，从 Redis 队列移除并撤销轮询，不执行 Pipeline。
  - ticket 已是 `GRANTED` 时，CAS 失败；许可仍由获准 Runnable 的 finally 释放，避免在前置工作尚未结束时把同一 slot 让给别人。
- 一旦任务已获准，这些 emitter 回调没有调用 `StreamTaskManager.cancel(taskId)`，也没有直接拿模型句柄。因此客户端断开后模型可能继续生成，handler 只是因为 sender closed 不再发出事件；若模型最终正常回调，仍可能保存完整 assistant 消息并把 Trace 写 SUCCESS。
- 前端的 `streamAbort` 能关闭本地 fetch，但当前“停止生成”按钮并不调用它，而是发 stop。网络层真正 abort 与业务 stop 是两件事。

### 4.2 SSE 的 5 分钟超时

- emitter 到 300 秒触发 `onTimeout`，sender 标记 closed；排队 ticket 若尚为 PENDING 会被撤销。
- 已获准任务不会因此自动写 INTERRUPTED/CANCELLED，也不会自动取消 provider。模型后来完成仍可落库；只是浏览器已无法从原连接收到 finish/done。这里不能写成“超时等价于用户取消”。

### 4.3 模型报错或所有首包候选都失败

- provider 流在未被取消的前提下遇到 HTTP、读取或异常结束，会回调 `onError`。首包探测阶段的单个候选错误先由路由层取消该候选并尝试下一个；所有候选失败后才把最终错误交给用户 callback 并抛出。
- `StreamChatEventHandler.onError` 若任务未标取消，会先 `unregister(taskId)`，再用 `sender.fail(t)` 以 `completeWithError` 关闭 SSE；它不保存已累计的部分 answer，也不发送正常 finish/done 或 cancel/done。
- 外层 Trace callback 在 delegate error 处理的 finally 中尝试 `RUNNING -> ERROR` 并记录截断后的错误。若同步 Pipeline 阶段抛错，Trace runner 也会把它送入同一 trace-aware `onError`，其内部 CAS 保证 run 收尾钩子只执行一次；sender 的 closed CAS 防止重复关连接。
- 所以模型错误、连接超时、客户端断开和用户 stop 的落库结果不同：模型错误可能没有 assistant 消息且 Trace=ERROR；连接问题可能让模型继续并最终存 NORMAL；用户 stop 才走部分正文 `INTERRUPTED` 和 cancel/done。

## 5. 三个关键竞态：用两个执行者按时间推演

### 5.1 stop 早于任务/句柄注册

对应主流程 2.1、2.9 和停止流程 3.2～3.3。执行者 A 是聊天线程/模型路由，执行者 B 是 stop 请求或 Redis 订阅线程。

- 交错一：B 发生在本机任务登记之前。
  - A 已把 `meta(conv-7, task-9)` 写向网络，但还没执行 `register`。
  - 前端收到 meta，B 发 stop：先写 30 分钟 Redis key；此时 Trace 尚不存在，条件取消返回 false；Pub/Sub 到 A 实例时本机也还查不到任务。
  - A 随后 `register(task-9, sender, supplier)`，主动读到 Redis key，把本机 cancelled 设为 true，尝试 Trace 取消并发 `cancel/done` 关连接。
  - 若队列 Runnable 后来仍启动，A 插入 RUNNING run 后再次检查本机 cancelled，立刻写 CANCELLED 并跳过 Pipeline。TTL key 解决的是“广播错过”，run 插入后的检查解决的是“取消早于 run”。
- 交错二：任务已登记，但句柄尚未返回。
  - A 已在改写、检索或等待 G 的首包，taskInfo.handle 仍为 null。
  - B 的订阅线程把 cancelled CAS 为 true，先收敛 Trace、保存当前已有正文（首包前通常没有）并完成 SSE；此时没有句柄可调用。
  - G 首包成功后 A 才取得句柄并 `bindHandle`；绑定当场看到 cancelled，立即取消。若首包一直不来，当前只能等候选首包预算结束，停止不能越过路由层直接拿到内部 Call。

### 5.2 正常完成与停止竞争

对应主流程 2.10 与停止流程 3.3。执行者 A 是 provider 完成回调，执行者 B 是 stop/订阅线程；消息、Trace、SSE 不在一个事务里，因此 CAS 只能收敛各自对象，不能制造全局原子终态。

- 交错一：B 的 Trace 取消先赢，A 已通过最初的 cancelled 检查。
  - A 进入 `onComplete`，看到本机 cancelled=false，开始保存 NORMAL assistant。
  - B 的 stop 入口先把数据库 run 从 RUNNING 改为 CANCELLED，再发布消息。
  - A 仍可能发 finish/done、unregister 并关连接；外层随后尝试 SUCCESS，但数据库条件更新 0 行，所以 Trace 保持 CANCELLED。
  - 若 Pub/Sub 在 unregister 后才到，本机找不到任务，客户端甚至只看到正常 finish。当前可能留下“消息 NORMAL、Trace CANCELLED”的边界；run CAS 防覆盖，却不跨表协调消息和协议。
- 交错二：A 的完整正常收尾先赢。
  - A 保存 NORMAL、发 finish/done、unregister、关闭 SSE，外层把 RUNNING 改 SUCCESS。
  - B 稍后 stop：Trace 条件查询找不到 RUNNING，返回 false；广播后各实例也找不到已注销任务。
  - stop HTTP 仍返回 success，但不会把已完成回答改成 INTERRUPTED。这里 success 表示请求处理成功，不表示任务真的从 SUCCESS 逆转为取消。

### 5.3 一个清理动作抛错

对应停止流程 3.3。执行者 A 是 stop 入口，执行者 B 是持有任务的订阅线程。

- A 先写 Redis key、尽力更新 Trace、发布 taskId；即使第一次 Trace 写库抛错，`reportTraceRunCancelled` 捕获后仍会发布。
- B 赢得 cancelled CAS，再次尽力写 Trace。假设接着 `handle.cancel()` 抛错，`cancelHandleQuietly` 只记录日志；B 仍调用 supplier 保存部分正文，再尝试发送 cancel/done 和 complete。
- 若 supplier 自身抛错，B 使用当前便捷构造器得到的最小载荷 `{messageId:null,title:null,sources:null,messageStatus:NORMAL}` 发送 cancel/done；前端虽然把 UI `status` 置为 cancelled，但会沿用载荷中的 `messageStatus=NORMAL`，这是一个协议不一致边界。若底层发送失败，`SseEmitterSender` 自己先异常关闭并吞掉异常，后续 done 因 closed 而跳过，最外层 finally 仍尝试 complete。
- 这种隔离保证“一个清理失败不阻断后续动作”，但不能保证失败动作已经补做：provider cancel 失败可能继续耗资源，Trace 写库失败可能留下 RUNNING，部分消息保存失败会丢失已生成文本。当前也没有取消路径的立即 unregister。

## 6. 回答之后相邻的短流程

- **会话列表与历史读取**：`GET /conversations` 用当前 userId 查询未删除会话，按 `lastTime` 倒序返回 `conversationId/title/lastTime`；选择会话后，`GET /conversations/{id}/messages` 先校验会话属于当前用户，再按时间和 ID 正序返回消息、thinking、sources、推荐问题、messageStatus 和该用户反馈。接口不返回保存的 grounding 文本。
- **反馈**：用户对一个 assistant `messageId` 发点赞/点踩或取消，Controller 先把 `messageId/userId/vote/reason/comment/submitTime` 发到 MQ，消费者再校验消息属于该用户且角色为 assistant，并 upsert 反馈；较旧事件不能覆盖较新记录。它是用户信号，不直接等于经过复核、无偏且可用于评测的 gold。
- **推荐追问**：用户展开面板时另发 `POST /conversations/messages/{messageId}/recommended-questions`。服务校验 NORMAL assistant 归属，通过 `replyToMessageId` 读取问题，取保存的回答和 grounding，按问题 1000 字、回答 6000 字、块文本总计 6000 字预算调用快速档模型，解析至多 3 个问题并缓存到消息；INTERRUPTED/REJECTED 直接返回空，不占聊天流关键路径。
- **看板**：管理接口按时间窗统计会话、消息和 Trace。性能摘要把 SUCCESS、ERROR、CANCELLED 都放进总请求分母；无知识率则按内容恰好等于固定无资料句的 assistant 消息计算。它能观察状态和延迟，不证明回答事实正确。

## 7. 当前边界与可选强化放在对应步骤理解

- **Prompt Injection** 对应 2.8：当前有角色与标签隔离、引用规则和业务门禁，但检索资料仍是不可信输入。可增加摄取时扫描、工具最小权限和输出结构校验；这会加强防护，不应写成现已消除注入。
- **断线续播** 对应 2.10、4.1：当前任务依附原 emitter，没有持久事件序列。若以后把 `taskId + seq + event` 持久化，并让新连接携带 `afterSeq/Last-Event-ID`，才能重放后追尾；成本是事件存储、顺序、清理和隐私治理。
- **claim 级验证** 对应 2.7：当前 sources 与 `[N]` 只建立“模型收到过该文档”的关联。若以后把答案拆成可验证陈述，再用引用块做蕴含/冲突检查，可发现“编号存在却不支撑该句”；它会增加延迟且验证器也会错，当前没有接入。
- **历史摘要纠错** 对应 2.3～2.4：当前摘要是模型生成的有损记忆；保存覆盖消息范围能帮助审计，但问答时没有自动回查原消息纠正设备名。高风险数值/否定可未来改成结构化事实槽或历史检索，仍需冲突策略。
- **对象级停止授权与崩溃修复** 对应 3.2、3.4：应在发布取消信号前按 `(taskId,userId)` 校验活动 Trace/任务归属；需要进程崩溃后修复时，还要持久 cancel command 和过期 RUNNING 扫描。当前两项都未实现。

## 8. 影响理解的重要修正

- stop 是用户另发的独立请求；正常回答不会固定接着 stop。
- `meta` 在排队前发送，且只含 conversationId/taskId；用户与助手 messageId 都在后续落库时产生。
- 当前全局队列许可不覆盖完整 token 流，`@IdempotentSubmit` 更不覆盖 SSE 生命周期；不能把两者写成全程每用户互斥。
- 正常完成是保存 assistant 后发 finish/done、注销并关 SSE，随后外层才尝试把 Trace 写 SUCCESS；取消路径当前没有立即 unregister。
- 客户端断开和 SSE 超时在已获许可后不会自动取消 provider；模型 error 也不会像用户 stop 那样保存部分 INTERRUPTED 文本。
- grounding 是从本轮选中块做出的最多 8 条持久快照，不是新检索，也不证明回答逐句被证据支撑。

## 9. 源码反查入口

- 聊天入口与排队：[RAGChatController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java)、[RAGChatServiceImpl](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java)、[ChatQueueLimiter](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/ChatQueueLimiter.java)、[FairDistributedRateLimiter](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/FairDistributedRateLimiter.java)
- 主编排与事件：[StreamChatPipeline](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java)、[StreamCallbackFactory](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java)、[StreamChatEventHandler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java)
- Prompt、来源与快照：[RAGPromptService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java)、[SourcesAssembler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourcesAssembler.java)、[CitationContextEnricher](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationContextEnricher.java)、[GroundingChunksAssembler](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/GroundingChunksAssembler.java)
- 取消与 Trace：[StreamTaskManager](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java)、[StreamChatTraceRunner](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/trace/StreamChatTraceRunner.java)、[RagTraceRecordServiceImpl](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java)、[取消修复记录](../changes/2026-08-12-cancel-trace-run-hang.md)
- 会话与摘要：[DefaultConversationMemoryService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/DefaultConversationMemoryService.java)、[JdbcConversationMemoryStore](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java)、[JdbcConversationMemorySummaryService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemorySummaryService.java)
- 配置与前端：[application.yaml](../../../bootstrap/src/main/resources/application.yaml)、[IdempotentSubmitAspect](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/idempotent/IdempotentSubmitAspect.java)、[chatStore.ts](../../../frontend/src/stores/chatStore.ts)、[useStreamResponse.ts](../../../frontend/src/hooks/useStreamResponse.ts)
