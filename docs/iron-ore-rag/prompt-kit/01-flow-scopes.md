# 第一部分：流程划分与分会话提示词

建议拆成 **9 份流程笔记，对应 9 个独立会话**：7 份主流程，加上身份访问、模型调用两份支撑流程。拆分依据是一次业务动作和数据变化的边界，不按 Controller、Service、Mapper 分层拆。

原 09 的摄取链拆成“文档怎样进入处理、更新和收尾”与“文件内部怎样变成证据块和索引”；检索链拆成“问题怎样明确、决定走哪条分支”与“怎样找出并选中证据”。流式回答、模型故障处理也分别讲，避免一个会话承担整条在线链的全部细节。

本目录先提供任务提示词；下表中的 `flow-notes/` 文件是后续会话的输出目标，当前不表示它们已经生成。原 [08](../08-interview-playbook.md)、[09](../09-core-business-chain-review.md) 保留作参考，新的流程笔记按统一写法重新核对源码。

## 怎样使用

每次只复制下方一个会话的 `text` 代码块，再接上 [02-writing-style.md](02-writing-style.md) 全文；也可以在新会话直接发送：

```text
请读取 docs/iron-ore-rag/prompt-kit/01-flow-scopes.md 中“会话 C”的完整任务块，以及 docs/iron-ore-rag/prompt-kit/02-writing-style.md 全文，按要求直接编写本会话对应的流程笔记。
```

把 C 换成目标会话即可。每个会话只写自己的目标文件；可同时阅读公共源码，不共同修改目录索引或旧笔记。不需要等其他流程笔记写完才能开始，交接处直接核对当前源码中的入参和返回对象。

如果希望控制同时阅读的材料量，可以每批开 3 个会话，例如先 B / D / F，再 C / E / H，最后 A / G / I。这只是工作安排，不是运行时顺序；已经熟悉后端身份和模型适配时，复习可先从 7 份主流程开始。

## 九份笔记各负责什么

下面的输出文件名都相对 `docs/iron-ore-rag/flow-notes/`。

| 会话 | 流程与输出文件 | 从哪里开始，到哪里结束 | 交接给谁 |
| --- | --- | --- | --- |
| A，支撑 | 身份登录与访问边界：`01-login-and-access.md` | 账号登录 → 后续请求恢复身份 → 对象访问检查与上下文清理 | 各业务入口取得当前用户 |
| B，主线 | 文档接入、异步处理与更新：`02-document-lifecycle.md` | 建库准备、上传登记 → 单独启动摄取 → 消息消费与状态收尾；另讲刷新、修改、删除 | 调用 C 的摄取内核，接收其处理结果 |
| C，主线 | 解析、分块、向量化与索引：`03-document-to-evidence.md` | 原文件字节和摄取配置 → 结构块 → 检索块 → 向量与索引 | B 更新处理状态，E 检索已写入的块 |
| D，主线 | 问题改写、意图与工具分支：`04-query-understanding-and-routing.md` | 原问题与历史 → 改写/拆分 → 意图 → 知识作用域、澄清、直接回答或工具分支 | E 接收检索问题与作用域；F 使用回答分支及工具结果 |
| E，主线 | 候选召回、重排与上下文选择：`05-retrieval-and-context.md` | 子问题与意图 → 作用域内召回 → 排序与请求总预算 → 选中块和上下文 | F 构造回答 Prompt 与来源 |
| F，主线 | 会话、Prompt、流式回答与停止：`06-chat-stream-and-cancel.md` | 聊天请求 → 会话/排队 → 调用 D/E → 生成与持久化；另讲停止请求和断开回调 | H 使用已保存消息的来源与证据快照 |
| G，支撑 | 模型选择、调用与失败切换：`07-model-routing-and-fallback.md` | 业务层模型请求 → 候选/客户端 → 首包或同步结果 → 返回、降级或失败 | 为 C/D/E/F/H 提供不同类型的模型调用 |
| H，主线 | 证据任务、审批、模拟与版本比较：`08-evidence-task-and-version.md` | 用户选中回答来源 → 草案 → 另次批准/模拟；版本 diff 是独立请求 | 用户得到可审核对象或确定性差异 |
| I，主线 | 评测、问题定位与结果判断：`09-evaluation-and-diagnosis.md` | 一个待验证问题 → 固定数据与配置 → 采集/评分 → 判断改动是否值得保留 | 解释默认选择和历史结果的适用范围 |

这些文件之间的业务关系可以这样读：

```text
文档管理员：B 上传/启动 → C 解析、分块、Embedding、写索引 → B 状态收尾
普通用户：  F 接收问题 → D 理解问题 → E 找证据 → F 生成、保存和返回
后续操作：  用户另外选中回答来源 → H 创建草案；批准、模拟、版本比较各有入口
支撑关系：  A 提供请求身份；G 在需要模型的步骤被调用；I 独立评测这些环节
```

这是业务交接图，不是每一层 Java 的精确调用栈。D/E 中部分方法位于同一个引擎内，写笔记时仍按真实调用位置解释，不能因为拆了文件就编造新的 HTTP 请求或阶段。文档不会在每次聊天前重新解析，评测也不是聊天的最后一步。

## 会话 A：身份登录、请求上下文与访问边界

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“谁在使用系统，以及请求进入业务后到底检查了哪些访问条件”。
只编写或调整：docs/iron-ore-rag/flow-notes/01-login-and-access.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 08 的 4.1、7.1，以及 09 的 6.1。先简要说明普通登录用户和管理员的实际区别，再分别走这些请求：
1. 用户提交账号密码，查用户、验证账号信息、创建登录态、返回 token；登出是另一请求。
2. 用户随后携带 token 访问接口，按实际拦截器顺序说明哪些地址放行、哪些要求登录，用户资料怎样恢复到 UserContext，业务读到什么，什么时候清理。
3. 选择“读取一条会话/消息”和“访问知识文档”比较对象检查：SQL 是否带当前用户条件，资源不存在和不属于自己如何返回。管理接口的角色检查在实际位置解释，不据角色字段推断所有接口都有校验。

在调用位置展开 token 的取得与校验、用户记录的查询条件、上下文中 userId/username/role 的来源。继续查异步执行时身份如何传入；不要假设 ThreadLocal 会自动跟着线程池或 MQ 消费者走。聊天和摄取消费各用一个交接例子即可，业务处理内部归 F/B。
密码如何比较以当前实现为准，不能补成已经采用加盐哈希。知识库和检索没有完整 ACL 时，说明实际查询范围；created_by、意图 scope 与资源授权分别代表什么。停止接口只核实是否验证 task owner，取消信号和终态机制归 F。

源码起点（相对 bootstrap/src/main/java/com/nageoffer/ai/ragent/）：
- user/controller/AuthController.java、user/service/impl/AuthServiceImpl.java。
- user/config/SaTokenConfig.java、UserContextInterceptor.java、SaTokenStpInterfaceImpl.java。
- rag/controller/ConversationController.java 及其服务、知识文档和任务服务的对象查询。
- framework 模块的 UserContext；按需查 schema_pg.sql 和异步上下文传递代码。

业务情形：登录过期；用户换一个 conversationId 访问别人的消息；线程复用；已登录但访问的是共享知识管理面。
09 的 ACL/RLS 扩展只在说明当前边界后作简短比较，不另写完整多租户设计。不要移植秒杀项目的消费者/商户两套 token、商户隔离或 Redis 登录 Lua。
```

## 会话 B：文档上传、异步摄取、远程刷新与生命周期

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“原文件怎样登记并进入处理，完成、失败和资料变化后怎样继续”。
只编写或调整：docs/iron-ore-rag/flow-notes/02-document-lifecycle.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 的 1.4、1.5、1.7～1.9，以及 08 的 7.4；09 的 6.2 仅作当前更新能力的比较材料。
请按不同触发者分别串起：
1. 建立知识库作为准备请求，说明 collectionName、Embedding 模型与维度等后续输入从哪里保存和取得；不重复普通 CRUD。
2. 上传本地文件或登记远程来源：校验参数、取得文件、写对象存储、校验解析能力、解析文档身份、写 PENDING 元数据并返回。上传成功这时实际具备什么。
3. 单独启动切分：读取 docId，构造事务消息与操作者信息，本地回调用什么条件把文档切为 RUNNING，如何更新调度信息，发送调用如何返回。Broker 回查作为另行触发的分支，在交接位置解释。
4. 消费者收到消息后恢复什么上下文，读取什么文档/知识库/摄取规格，写处理日志，调用固定摄取内核，成功或失败后怎样更新状态和日志并清理上下文。
5. 远程刷新是调度流程：到期选择、租约与心跳、ETag/Last-Modified/hash 判变、未变化跳过、变化时处理新文件，以及状态和文件清理。停留 RUNNING 的超时扫描与刷新租约分别解释。
6. 更新、重新切分、启停、人工改块和删除按独立操作说明：哪些影响当前检索，旧块/向量/原文件什么时候变化，哪些操作会被 RUNNING 状态拒绝。共用索引写入细节归 C，在这里说明操作语义与失败后果。

消息本地回调的条件更新、影响行数和回查判断必须就地展开。尤其核实异常有没有传播到 MQ：当前 runChunkTask 中有捕获后记录 FAILED 的路径，不能沿用“摄取报错就自动消息重试”的笼统说法；要读清 catch 的范围，区分内部被捕获的错误和仍可能抛到消费者的错误。
文件已写入而登记失败、重复消费、外部索引写失败、成功状态写失败，分别讲当前处理。没有通用补偿或防重约束时直接说明实际结果。事务消息不覆盖对象存储、模型调用和所有索引，也不等于只执行一次。

摄取内核内部由 C 负责：本篇在调用处说明传入原文件字节、DocumentRef、IngestionSpec、VectorTarget，内部经过解析/分块/Embedding/写索引，返回 IngestionOutcome 后本方法怎样使用块数与耗时。无需重复表格切分算法。
独立 /ingestion/tasks 引擎作为可选入口简要走通节点执行与结果流转；知识文档 processMode=pipeline 是否能调用它要看实际分支，不能因 runPipelineProcess 方法存在就认定可用。

源码起点：KnowledgeBaseController/KnowledgeBaseServiceImpl、KnowledgeDocumentController/KnowledgeDocumentServiceImpl；knowledge/mq/、ScheduleRefreshProcessor、KnowledgeDocumentScheduleJob、RemoteFileFetcher；DocumentIdentityResolver、IngestionSpecCodec、VectorTargetResolver；按需查 IngestionTaskController/IngestionEngine、KnowledgeChunkServiceImpl、相关 Mapper 和 schema_pg.sql。
业务情形：上传完立即提问；连续点击开始；解析报错后页面显示失败；远端内容没变；刷新中租约丢失；文档停用后是否仍能检索到旧块。
不要把 09 中版本化索引发布、Outbox、增量对账等扩展方案写成现有恢复机制。
```

## 会话 C：原文件怎样变成证据块、向量与索引

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“进入摄取内核后，每一份数据怎样转换成下一份数据”。
只编写或调整：docs/iron-ore-rag/flow-notes/03-document-to-evidence.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 的 1.2、1.3、1.5、1.6、1.10，以及 changes/2026-08-13-xlsx-structure-aware-chunking.md。主线用 XLSX；其他文件在解析分支说明差异，不复制整条公共链。
从 B 已拿到的文档身份、原文件字节、摄取规格、目标集合和模型配置开始：
1. 探测 MIME，结合解析档选择解析器；不支持、空字节或解析空结果如何处理。
2. Excel 读取工作簿和 Sheet，得到单元格值与结构；合并表头/数据区域怎样处理，哪些列和行被保留，二维数据怎样变成 TableBlock，Sheet、范围和标题如何关联。
3. 根据 Block 类型选择切分逻辑。表格按完整行累计，同时计算 Markdown 与检索文本预算，单行过宽时按非空列切片；解释 rowsPerChunk、maxChars、piece 标记、ChunkPacker 与最终组装的先后。
4. 把 Chunk 的展示文本、embedding_text 和 metadata 分别组织好；批量把检索文本送给指定 Embedding 模型，再把返回向量按原顺序与 chunk 对齐，验证数量、空值和维度。
5. 写入器按当前 sink 顺序替换关系块/向量，已启用的外部索引如何接收同一文档和 chunk。讲清 ID、删除旧数据、插入新数据、事务提交与出错范围，最后返回块数、MIME 和阶段耗时给 B。

表格规范化、TableChunker 的分组判断、ChunkPacker 的合并条件和向量写入 SQL 都要在对应步骤展开，不能只说“结构化解析后入库”。原始就是两条相同记录，与原行为空、因合并区域展开后成为相同的续行，要分别推演；不要写成对全部相同业务行去重。
用一张小表贯穿演示：原始单元格 → headers/rows → 两份实际文本 → 来源 metadata → 一条带向量的记录。正常行、加下一行超预算、单个键值对本身过长，各给必要的中间变化。向量可以只用占位表示 1536 个数，不伪造实际 Embedding 输出。
maxChars 按当前实现的 Java 字符长度说明，不能换算成固定 token 数；超长原子字段的例外和后续组装是否再加前缀也要交代，不承诺任意输入最终都小于 1024。

PDF/Word/PPT 的 MinerU 路线、Markdown/Tika、图片模型各在解析分支按实际路由说明输入输出。OCR 开关、Excel 内嵌图片允许条件与 FAST/FIDELITY 区别按当前配置核实，不扩成所有格式的解析教材。
模型候选切换机制归 G，本篇讲知识库指定模型、维度、文本和返回数组的契约；提前说明 E 的 query 向量需要与入库向量兼容，同维度不保证同一语义空间。

源码起点（Java 前缀 bootstrap/src/main/java/com/nageoffer/ai/ragent/）：
- core/ingest/DefaultIngestionKernel.java、core/parser/registry/ParserRegistry.java 及实际解析器。
- core/parser/excel/ExcelTableNormalizer.java、core/chunk/blockaware/TableChunker.java、ChunkPacker.java，继续追 ChunkingService 和 ChunkAssembler。
- core/ingest/embed/ChunkEmbeddingService.java、knowledge/support/VectorTargetResolver.java。
- core/ingest/sink/ChunkIndexWriter.java、knowledge/sink/RelationalChunkSink.java、rag/core/vector/sink/VectorChunkSink.java 及当前适配器/Mapper。
按需读 TableChunker、ExcelTableNormalizer、provenance 测试及 resources/database/schema_pg.sql。
历史块数、重复率和锚点只说明对应文件的结构结果；完整评测归 I，不把结构变好写成回答准确率提高。
```

## 会话 D：问题改写、意图识别、作用域与工具分支

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“用户的问题怎样成为可检索的问题，以及程序接下来决定做什么”。
只编写或调整：docs/iron-ore-rag/flow-notes/04-query-understanding-and-routing.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 的 2.2～2.4、2.7、2.10，以及 08 的 7.3。从 F 已取出的原问题和会话历史开始，不重写聊天入口和历史存储。
1. 查询词归一化如何替换领域术语；改写开启时，怎样装配 system/history/user 消息，向模型要求 rewrite、should_split、sub_questions，解析后如何处理不拆分、重复子问题、字段缺失和调用失败；关闭时的规则拆分另作分支。
2. 对子问题做意图识别。先从数据库记录/Redis 缓存恢复树，按 parent_code 连接父子、生成 fullPath、取叶子作为模型候选；讲清 Prompt 里每个候选带哪些信息。
3. 每个子问题的一次模型分类返回什么，如何解析节点 ID 和分数，未知 ID、空结果、低分怎样处理；子问题并发及总意图数量限制在哪里发生。不能描述成逐层遍历树、每层调一次 LLM，也不能写成向量分类。
4. 回到 Pipeline，指导/歧义澄清、纯 SYSTEM、普通检索分别怎样判断和返回。接着在实际引擎调用位置说明 KB scope：有效集合、绑定去重、最低分和最高置信阈值、主集合/补充集合、global/empty 回退各如何形成。
5. 对存在 MCP 意图的可选分支，顺着工具 ID 找注册项，读取工具 schema，抽取参数，处理缺失必填项/抽取失败，再调用 executor 并格式化结果交回上下文。工具发现和配置准备是独立准备过程，不是每个问题都重建一次服务。

用“它异常时先查什么”配一条明确前文，展示原句 → 归一化 → 一份假设模型 JSON → Java 实际采用的子问题 → 意图分数 → 集合。分别解释改写如何使用历史、分类模型是否直接收到历史。当前过滤 system 后 skip 的计算也要核实，不能只引用“最近 4 条”的注释。
假设多条意图分数时，展示两道阈值各在哪一步起作用；补充集合不代表无条件都会查。scope 的计算实际发生在检索引擎内，文档归本会话只是阅读分工，不改变调用顺序；E 在该位置说明输入/结果后继续召回。
核对意图数据和启用状态从哪里控制；历史评测 intent=off 的实现方式、运行请求参数与普通聊天链分开，不凭评测结论给产品编造全局开关。

源码起点：StreamChatPipeline；MultiQuestionRewriteService、QueryTermMappingService、RewriteResult；DefaultIntentClassifier、IntentResolver、IntentTreeFactory、IntentTreeCacheManager；RetrievalScopeResolver；RetrievalEngine 中 MCP 执行方法、LLMMcpParameterExtractor、McpToolRegistry/McpClientToolExecutor。
同时读 prompt/user-question-rewrite.st、intent-classifier.st、mcp-parameter-extract*.st、IntentTreeController/Mapper、schema_pg.sql 的 t_intent_node、主配置和 iron-ore-demo profile。MCP 服务端只选一个实际工具追到返回，不遍历全部工具。
业务情形：指代没还原；模型说不拆却给了多个问题；低置信或绑定库已失效；工具缺参数；必须先查出设备编号才能发起第二跳。
KB scope 不等于权限。一次拆分并发查证据不等于迭代多跳；MCP、工具调用和 Agent 循环在实际分支后简要比较，HyDE/GraphRAG/自主 Agent 不写成当前步骤。普通召回内部归 E，最终 Prompt/流式收尾归 F。
```

## 会话 E：向量召回、可选混合检索、重排与上下文选择

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“从检索问题和范围开始，最后为什么留下这几块资料”。
只编写或调整：docs/iron-ore-rag/flow-notes/05-retrieval-and-context.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 第 2 节的检索、排序、预算与异常部分；changes 中请求级检索、回填记录按需反查。主线先走当前开启的向量通道，可选通道在相关位置说明接入后的不同。
1. RetrievalEngine 收到子问题和意图，怎样算一次请求的总 TopK、每子问题额度、每通道召回深度与候选池上限，哪些任务并发、在哪里汇合。
2. 调用 D 负责的 scope 解析后，通道实际拿到哪些主/补充集合和配额。VectorSearchChannel 怎样生成 query 向量，进入 PGVector SQL 做集合/相似度过滤、距离排序和限制条数；后端返回后怎样恢复分数与顺序。
3. 若开启 BM25/图/Web，各通道输入什么、返回什么统一结构；向量和 BM25 用同一个小候选表比较名次与分数。图/Web 仅说明适配器分支与当前关闭边界。
4. 按实际处理器顺序展开去重、融合、候选池截断、元数据增强和 rerank，保留重复候选的通道名次依据，说明单路融合如何处理。
5. Rerank 把什么 query 和候选文本交给模型，如何按返回 index/score 找回原 chunk，截出哪些头部、为何还保留未重排尾部；异常时外层留下什么排序。
6. 多子问题结果回到请求级选择，默认前缀/去重路径如何保证请求总预算，为什么可能不足 TopK；公平回填打开后的算法在同一位置另讲。随后构造 kbContext、选中块和归属信息，交给 F。

把 recallBudget、candidateLimit、contextTopK 分别说成它限制哪一层的数量，不把 20/40/10 写成一条必定实际有 20→40→10 个块的漏斗。用两个子问题共享同一块的例子，实际算一遍分配、重复消除和最终条数。
向量不是答案；用 query/document 分别编码与 query-text 联合重排的输入差别解释 Embedding 和 Cross-Encoder。rankingText、embedding_text、content 在第一次使用时展示差别，讲清最终 Prompt 使用哪份文本。HNSW、余弦距离、RRF 所需原理在对应计算处讲，不集中堆在末尾。
需要核实：向量 query 的模型选择与 C 的入库模型是否一致；近似查询的过滤和 relaxed_order；通道 future 超时是否取消底层 I/O；异常转空和真的没命中能否区分；默认回填关闭时实际采用哪个结果。
DeterministicContextSelector 和 RetrievalCapture 按调用方确认产品/评测归属；不要因计算过一个诊断结果就说它决定最终上下文。实验选择器原理和完整成绩归 I，这里只讲接入状态与产品交接。

源码起点：RetrievalEngine、MultiChannelRetrievalEngine、RequestLevelChunkSelector；retrieval/channel/ 和 postprocessor/ 中实际实现；PgVectorRetrieverService、PgVectorStoreService 及对应 Mapper；EsKeywordIndexService、ContextFormatter；SearchChannelProperties、application.yaml、schema_pg.sql。
业务情形：两个子问题各自相关但挤掉必要证据；相同块重复占位；型号词语义近却不是同一设备；向量超时；rerank 失败；候选里有证据但最终没选中。
不要把“有结果”“填满 TopK”“分数提高”直接写成答案更准确。sources/grounding 编号与 Prompt、引用归 F；D 的改写和意图内部不重复展开。
```

## 会话 F：聊天入口、会话、Prompt、SSE 与停止

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“用户发出问题后，怎样逐步看到回答，以及回答结束或停止后系统留下什么”。
只编写或调整：docs/iron-ore-rag/flow-notes/06-chat-stream-and-cancel.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 第 3 节、08 的 7.5，并按需查取消修复记录。用聊天主请求、独立停止请求、连接回调三条触发关系组织，不把 stop 写成每次回答的固定下一步。
1. 当前用户带问题和可选会话 ID 请求聊天，怎样取得/建立会话、生成 task/message 标识、创建 SSE、发送 meta、登记回调并进入全局队列，排队通过或拒绝分别怎样收尾。
2. 加载历史和摘要、保存当前用户消息。调用 D 做改写/意图判断、调用 E 检索；这里各用必要步骤说明数据含义及分支输出，不重复其内部算法。
3. 澄清、直接回答、资料为空和正常知识回答分别怎么结束或继续；明确“无知识证据不调用知识回答模型”仍不代表前面没调用改写模型。
4. 用选中块组装文档级 sources、块级 grounding、上下文引用编号，再把 system/history/user 按真实顺序装入 Prompt。展示一份小型消息列表，让人看清规则、历史、资料与问题分别在哪里。
5. 调用 G 的流式模型服务，获得取消句柄；think/response 等增量如何发送、如何累计；正常完成时标题、回答、来源、证据、消息状态与 Trace 怎样写入，finish/done 与清理按真实顺序说明。
6. 用户另发停止请求：Redis 标记和广播、本机/其他实例任务表、句柄取消、部分文本保存、Trace 条件终态、SSE 收尾和注销逐步展开。客户端断开、超时、模型报错各从对应回调接入，不简单合称“异常都取消”。

SSE、回调、取消句柄和 CAS 在发生位置用中文解释。重点展开 meta/finish 的字段如何关联对象、sources 与 grounding 如何分别去重及限制数量；grounding 是保存的块快照，不是另一轮检索，也不等于逐句证明。
停止早于任务/句柄注册、正常完成与停止竞争、一个清理动作抛错，各用两个执行者的交错顺序解释。给出数据库条件更新的状态条件及失败后调用方行为；Redis Pub/Sub 与带 TTL 标记分别弥补什么。
核实 IdempotentSubmitAspect 受什么配置控制、Controller 返回后锁的实际生命周期、全局队列限制谁；不能把它们写成全程每用户互斥。当前 stop 的 owner 检查、底层取消与进程崩溃后修复能力按源码说明。
会话列表/历史读取、反馈、推荐追问和看板只在相邻位置用短流程交代各自输入输出，避免单独发展成大章；反馈不直接等于 gold。Agent Profile/Prompt slot 怎样影响当前模板也在 Prompt 选择处说明，不把配置 Profile 等同自主循环。

源码起点：RAGChatController、RAGChatServiceImpl、StreamChatPipeline、StreamCallbackFactory、StreamChatEventHandler；RAGPromptService、SourcesAssembler、CitationContextEnricher、GroundingChunksAssembler；StreamTaskManager、RagTraceRecordServiceImpl；ChatQueueLimiter、FairDistributedRateLimiter、IdempotentSubmitAspect；DefaultConversationMemoryService、JdbcConversationMemorySummaryService 及相关模板、配置、前端事件处理。
业务情形：用户看到一半点停止；还没首包就停止；回答结束但客户端没收到 done；引用编号存在却没支撑该句；历史摘要弄错设备名；资料包含诱导模型执行的文字。
Prompt Injection、断线续播、claim 级验证只结合当前步骤讲边界和短比较，不把扩展方案写成已实现，也不自动改代码。
```

## 会话 G：模型路由、首包探测、熔断与返回契约

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“业务调用模型服务后，到底选了谁、拿到什么、失败时如何切换”。
只编写或调整：docs/iron-ore-rag/flow-notes/07-model-routing-and-fallback.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 08 的 7.2，09 中 Embedding 空间契约、3.8 与 6.6 的有关部分。从业务层已构造好的请求开始，Prompt 内容由相应业务篇解释。
1. 先用很短的调用对照说明：改写/任务草案需要同步文本或 JSON，最终回答需要流式增量，Embedding 返回向量，rerank 返回候选分数，图片解析需要视觉输入。这些是不同调用，不是一次请求依次调用所有模型。
2. 选择聊天模型：档位、thinking、preferred model、候选配置、enabled 和健康状态怎样参与筛选排序；核实不同重载和流式入口支持哪些参数，不能假设每条路径都相同。
3. 同步调用如何找到 provider client，取得调用许可、尝试候选、记录成功或失败，全部失败时上层收到什么。
4. 流式调用怎样建立 bridge、暂存/转发回调、等待首包，哪些事件算探测成功，失败/超时如何取消旧句柄并尝试下一个；成功后如何把句柄与后续回调交给 F。
5. 健康状态怎样跨请求累计：达到阈值后的拒绝、冷却、半开试探、试探成功/失败以及并发许可释放。具体状态与判断在 ModelHealthStore 的调用位置展开。
6. Embedding、rerank、VLM 各沿实际路由说明返回和降级差异；显式指定模型与默认候选是否使用同一逻辑，noop rerank 返回的到底是什么。

用“模型 A 连接成功但迟迟不返回内容，模型 B 可用”的时间线解释首包探测，再与“已经给用户发了一段文字后报错”比较。不能承诺中途切换仍是同一段连贯回答；传输成功、首包成功、答案正确分开说。
Embedding 要说明为何同为 1536 维也可能不兼容，绑定 C 的知识库模型和 E 的 query 路由解释当前限制；不把能切候选当成索引可以随意换模型。
说明本次请求的超时、候选总尝试与外层聊天超时分别约束哪里；不要把 future 超时或客户端 cancel 写成供应商一定停止推理和计费。

源码起点（相对 infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/）：model/ModelSelector.java、ModelRoutingExecutor.java、ModelHealthStore.java；chat/RoutingLLMService.java、LlmFirstPacketProbe.java、ProbeStreamBridge.java；embedding/RoutingEmbeddingService.java、rerank/RoutingRerankService.java、vlm/RoutingVlmService.java。
继续读当前启用 provider 的 client、模型配置类和 application.yaml；测试按需验证边界理解，不运行真实模型。
不要记录 API key 或真实凭证，不遍历无关供应商，不展开为模型训练/选型调研。F 负责 Redis 跨实例取消和最终业务状态，本篇负责 provider 连接与路由返回。
```

## 会话 H：证据任务、批准、模拟与版本差异

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“用户得到回答后，怎样把证据用于可审核的任务，以及怎样比较资料版本”。
只编写或调整：docs/iron-ore-rag/flow-notes/08-evidence-task-and-version.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 第 4 节及工业知识 demo 改动记录。输入是 F 已保存的 assistant 消息及 sources/grounding，不重做检索和聊天。
1. 用户带消息 ID 和一个来源文档 ID 创建任务；按当前用户取消息，检查角色、来源归属，筛出所选文档的 grounding，得到允许引用的 evidence ID 白名单。
2. 用问题、文档、版本、证据和 schema 构造模型请求；说明每类信息从哪里来，输出怎样清理和解析，何时修复一次、修复仍失败如何返回。
3. Java 校验器按实际顺序检查字段、步骤和 evidenceIds，版本由谁确定，随后怎样保存 DRAFT、payload 和证据快照。重复查询与唯一冲突分别在哪里处理。
4. 用户另外请求批准，按 owner 和当前状态判断、条件更新；再次请求模拟，execution 的唯一约束、事件生成、模板状态变化和重复执行返回分别在实际位置展开。
5. 版本比较从另一请求重新开始：取得同一稳定文档键下的旧/新版本与文件字节，按可见 Sheet/单元格形成键值，比较公式和显示值，稳定输出新增、删除和修改。
6. ROS mission 只作可选独立分支，说明输入状态、编译白名单、计划 hash、dry-run 开关、网关返回与记录。普通模拟和 ROS dry-run 不混用，更不接成默认真实控制。

至少演示一组“允许 c1/c2，模型引用 c9”的拒绝，以及“ID 合法但动作没有被原文支持”的能力边界。任务当前选单文档时，跨文档 grounding 不能未经校验混入。sources 有某文档但 grounding 截断后没有它时，实际如何处理。
讲清 schema 合法、引用 ID 合法、动作语义正确的差别；owner 自审是否允许，已有状态之外不能添加拒绝、撤销等环节。低 temperature 不等于模型完全确定。
版本 diff 用两三格前后值解释，行列插入导致地址变化、隐藏 Sheet/样式等比较范围按源码确认，不声称理解所有 Excel 语义。

源码起点：IronOreTaskController、IronOreTaskTemplateService、TaskTemplatePayload、TaskTemplateValidator；IronOreVersionController、WorkbookDiffService；IronOreTaskTemplateDO、IronOreTaskExecutionDO、相关 Mapper 和 schema_pg.sql；prompt/iron-ore-task-template.st。
可选分支查 IronOreRobotMissionController、RobotMissionCompiler、RobotMissionService、RobotGatewayClient；必要时读对应定向测试和历史记录。
业务情形：拿别人的消息创建草案；来源有文档但缺证据快照；模型伪造证据；重复批准/模拟；同版本名称或单元格地址发生变化。
09 的生产审批/设备控制扩展只解释与当前门禁的差距，不生成改造方案；历史 dry-run 不证明真实设备效果。
```

## 会话 I：评测怎样采集、定位和决定是否保留改动

```text
项目路径：/home/sd101t/IdeaProjects/ragent-iron-ore-rag。
本会话负责“怎样判断某次修改改善了哪一层，为什么有些实现最终没有默认启用”。
只编写或调整：docs/iron-ore-rag/flow-notes/09-evaluation-and-diagnosis.md。
应用后附的通用写作要求，不修改其他会话文件。

旧笔记主要看 09 第 5 节、context-selection-status.md、changes 中对应实验记录；以实际数据协议、脚本、配置和已保存结论交叉核实。本文讲执行过程和历史证据，不启动新实验。
1. 先用一个决策开始，例如“多子问题去重后不足 TopK，要不要继续回填”，说明比较什么、固定什么、哪些质量/延迟门槛决定继续或停止。
2. 走一次工业评测的数据准备：文档/问题/答案要点/解析锚点如何对应，配置和索引怎样固定，为什么要单独检查原证据是否已变成可检索块。
3. 沿脚本到服务端采集候选、最终证据、回答与错误，展示一题记录关键字段；需要回答的实验怎样收集 SSE，纯检索实验在哪里结束。
4. 沿评分脚本说明锚点、候选、最终上下文和人工答案分别如何判分，分子、分母与评价单位是什么；超时、缺失、无法映射和重复如何计入。
5. 用同一题逐层定位：原文未解析出来；进了索引但没召回；候选有但最终被选丢；证据进了 Prompt 但答错；答案对但引用不支持。用一个小排名例子算相关的 Recall/Hit/Precision，其他公式按确有需要补充。
6. 分开讲几次历史实验各怎样推进和停下：B0/C-final 的工业回答；D0 的检索预算/纯度；D1 的组合重建与解析漂移；D2 的固定回填对比；CS-DEV 的固定候选选择；CS-POOL/LIVE 的候选来源诊断；Hybrid 的同请求六臂。

每组先说人能理解的实验问题，再引入代号，不堆成绩表代替流程。保留足以支持结论的正负指标，解释一次“块更少/纯度更高，但召回下降”和一次“空位补满但质量门槛没过”。
区分候选冻结与真实模型调用、开发集与冻结测试集、离线代理与在线分数、块级指标与文档级指标、公开英文集与中文业务数据。不能把后来的独立诊断写成前一轮失败后继续越过门槛。
核实总题数与分类数量是否一致，旧文档的数字或分组不闭合时以可核验记录纠正；原始资料不可用就标明历史报告口径。引用任何结果同时说明数据、配置、日期/提交和指标定义，不把它们说成本轮重跑结果或生产收益。
产品 RequestLevelChunkSelector 与实验 DeterministicContextSelector、RetrievalCapture 的调用入口要分开。普通 eval、pooled、hybrid 的 profile/开关/用户/数据库约束和同步摄取绕过的链路也在采集入口说明。

源码与资料起点：eval/iron-ore/README.md、RUNBOOK.md、run_retrieval.py、run_answers.py、score_review.py；eval/context-selection/README.md、RUNBOOK.md、LIVE_RUNBOOK.md、HYBRID_RUNBOOK.md 及对应采集/评分/比较脚本；EvalController、PooledEvalController、HybridEvalController、RetrievalCapture、DeterministicContextSelector 和相关测试。
不读写忽略目录中的业务原文来补造证据，不重跑模型，不修改 gate、样本或默认参数。必要的回归、成本和人工判分原理放在对应步骤，不展开为完整 benchmark 教程。
```

## 公共内容怎样归属，避免重复与漏写

每份笔记要讲完整自己的范围；同一个跨篇步骤在调用处交代输入、简要处理、输出和本篇后续动作，详细内部过程由上表的负责会话展开。相邻笔记未生成时照样核实源码，可以先写文件名作后续导航，不依赖它才能继续写作。

- 知识库配置和文档生命周期归 B；解析、结构预算、向量与索引写入归 C。B 讲“这次操作怎样改变文档和检索状态”，C 讲“写入器怎样实际替换这些记录”。
- 改写、意图树、scope 计算和 MCP 分支归 D；召回、排序、最终选择与知识上下文格式归 E；调用位置仍以源码为准。
- 历史加载/保存、最终 Prompt、sources、grounding、SSE、Trace 和停止归 F。模型输入的业务含义各篇就地解释，通用候选路由/首包/熔断归 G。
- 登录和通用对象授权归 A；任务特有的 owner、证据白名单、审批状态归 H。其他篇说明当前用户如何参与自己的条件判断即可。
- 实验流程、成绩解释与停止依据集中在 I；B/C/E 只引用与本步骤设计直接有关的结论，不复制全部历史表。

旧 09 第 6 节不是第十条默认业务流程：权限隔离接 A，增量更新接 B，查询与多跳接 D，图检索比较接 E，注入/引用/长上下文接 F，调用故障接 G，执行权限接 H，质量/费用评估接 I。先讲当前实现；对尚未接入的内容，只保留能解释本步骤取舍的必要比较，详细扩展仍可回查旧文档。理解主流程所需的技术原理必须在新笔记中讲清，不能只留下链接。

全部完成后，按“B→C→B”和“F→D→E→F→H”各读一遍，A/G 在对应调用处补读，I 用来理解效果证据。重点检查同一个字段是否前篇产出、后篇接到，默认开关与独立请求的说法是否一致；有冲突就在负责该主题的笔记中核实和修正，不再复制一份总长文。
