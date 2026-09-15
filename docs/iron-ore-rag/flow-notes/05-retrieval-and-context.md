# 05｜从检索问题和范围开始，最后为什么留下这几块资料

这一段接在查询改写和意图识别之后：D 已经把本轮问题整理成一个或多个 `SubQuestionIntent`，每项包含可独立检索的子问题和它命中的意图分数。`RetrievalEngine` 接住这些结果，控制一次请求的总预算，按子问题和通道找候选，经过确定性的去重、融合与截断以及模型重排，再把最终块、归属和 `kbContext` 交给 F。D 怎样生成子问题和意图、F 怎样给来源编号并构造最终 Prompt，不在这里重复展开。

当前基础配置开启的是向量通道，关键词、图和 Web 通道都关闭；开启 `iron-ore-demo` profile 时还会把无有效意图的 fallback 从全库改成空范围，并关闭补充路。因此先顺着“向量通道 + 当前默认请求级选择”走完主线，再在实际接入位置说明其他分支。

## 一、一次请求怎样把问题变成有限候选

- `RetrievalEngine.retrieve(subIntents)` 收到 D 交来的子问题和意图后，先处理空输入，再一次性建立本请求的三层预算。
  - `subIntents` 为空时直接返回空的 `kbChunks` 和 `intentChunks`，不会启动检索任务；上层聊天链随后还要结合 MCP 上下文判断是不是整个检索为空。
  - 非空时从 `rag.search` 读取：`contextTopK = default-top-k`，`recallBudget = recall-budget`（非正时才回退到 `contextTopK`），`candidateLimit = fusion.rerank-candidate-limit`。当前基础配置分别是 10、20、40。
  - 三个数字限制的不是同一个列表：
    - `recallBudget` 限制一个子问题的一条检索通道准备召回多少候选；定向向量路还可能被意图节点自己的 `topK` 覆盖，图/Web 也有各自适配规则。
    - `candidateLimit` 限制通道结果去重、融合后，最多多少个候选继续回表并送入 rerank；它是每个子问题的候选池上限，不是最终 Prompt 条数。
    - `contextTopK` 限制整个用户请求最终可选的块额度。它是块数，不是字符数或 token 数；当前选择器没有额外的请求级 token gate。
  - 配置启动时只校验 `recallBudget >= contextTopK`、有效的 `candidateLimit >= contextTopK`，并不承诺一次实际执行恰好产生“20 个召回 → 40 个候选 → 10 个上下文块”。例如默认单向量通道全局最多先取 20 个，40 只是不会触发的上界；多通道各自召回后，去重候选才可能超过 20；过滤、重复、空库、超时和最终去重又都可能使结果不足。

- 引擎把请求级 `contextTopK` 近似均分给子问题，得到每题的最终额度；`recallBudget` 和 `candidateLimit` 不随题数相除。
  - 公式是 `base = contextTopK / questionCount`、`remainder = contextTopK % questionCount`。每题先得 `base`，原列表中前 `remainder` 题各多 1 条。例如总额度 10、三题时是 `[4, 3, 3]`；两题时是 `[5, 5]`。
  - 若子问题数超过 10，后面的题会得到 0 个 KB 额度并跳过 KB 检索，防止总块数随拆题数量膨胀。它的 MCP 意图仍会继续处理，所以“跳过 KB”不等于整个子问题不再执行。
  - 公平回填默认关闭时，每题带自己的小 `contextTopK` 进入后续 rerank，最终只看这个额度长度的题内前缀。公平回填打开时，每个非零额度题暂时携带请求级完整预算，即 rerank 可为每题保留更深的头部和尾部，初始 `[5,5]` 等配额只在请求级选择时执行。

- 引擎用 `ragContextExecutor` 为每个子问题建立一个 `CompletableFuture`，所以各子问题可并发检索；任务结果最后按原 `subIntents` 列表逐个 `join` 汇合，而不是按谁先完成谁先排。
  - 一个子问题任务先构造它的 KB 结果，然后才处理该题的 MCP 意图；这两段在同一个任务里不是并行关系。若有多个 MCP 工具，工具调用内部再用 `mcpBatchExecutor` 并发，并在格式化 MCP 上下文前全部汇合。
  - 子问题内部抛到最外层的异常会被捕获，该题降级为“空 KB + 空 MCP”的 `SubQuestionContext`，其他题继续。这里没有独立的请求级总超时；最终仍要等待每个子问题的 future 完成。

## 二、每个子问题怎样确定实际查询范围

- `MultiChannelRetrievalEngine` 为当前子问题建立一份 `SearchContext`：原问题和改写问题都设为该子问题，意图列表只放这一项，同时挂上预算和由 `RetrievalScopeResolver` 算出的 scope。后续各 KB 通道共读这同一份范围，不再各自判断意图。
  - scope 解析器先从 `t_knowledge_base` 查询 `deleted=0` 的知识库，取得去空、去重后的有效 `collection_name` 列表。这是“全库”的实际边界，不是向量表或 ES 中可能残留的任意 collection。
  - 它只取当前子问题中的 KB 意图，过滤低于 `min-intent-score`、没有集合绑定的节点；同一意图节点重复出现时保留最高分。然后用存活意图的最高分与 `confidence-threshold` 比较。
  - 没有有效 KB 意图或最高分不足时，走 fallback：基础配置 `global` 返回全部有效集合；`iron-ore-demo` profile 覆盖为 `empty`，返回空集合并停止该题的 KB 召回。这里的作用域是知识路由，不是用户权限模型。
  - 分数达到阈值时走定向范围：先把意图绑定集合与当前有效集合求交，已删除或失效的绑定不会进入查询；交集为空仍走 fallback。交集得到 `targetCollections`（主集合），其余有效库成为 `supplementCollections`（补充集合）。
  - 基础配置的 `supplement-ratio=0.25` 会把每条通道的一部分产出名额留给补充集合；`iron-ore-demo` 将它改为 0，所以实际只有命中库主路。假设通道额度 20、比例 0.25 且确有补充库，`ScopeQuota` 四舍五入得到补充 5、主路 15，并保证有条件补充时主路至少保留 1 条。这里保证的是通道出口名额，补充块仍可能在融合、rerank 或最终选择时被淘汰。

## 三、当前向量通道怎样从问题查到 PGVector 块

这一段会第一次同时遇到三份文本，先固定它们的身份，后面才能看懂为什么召回、重排和最终 Prompt 不是在处理同一字符串：

| 文本 | 怎样得到 | 当前用途 |
| --- | --- | --- |
| 展示正文 `content` / `RetrievedChunk.text` | 解析和分块后保留的原始块正文，同时写入关系表与向量表 | 最终 `kbContext`、前端预览和后续来源证据所见正文 |
| 向量文本 `embedding_text` | 入库装配时用缺失的章节路径、表格键值正文等组织出的检索文本 | 文档侧 Embedding；关系表保留它供重建向量和检索后富化 |
| 精排文本 `rankingText` | 检索后用“去扩展名的文档名 + `embedding_text`”组成；缺 `embedding_text` 时回落 `text` | 只交给 rerank；不会替换最终 Prompt 正文 |

- `MultiChannelRetrievalEngine` 先筛出真正注册且配置启用的通道，按通道枚举顺序稳定派发；然后用 `ragRetrievalExecutor` 并发执行所有通道。基础配置中只有 `VectorSearch` 会进入这个列表。
  - 每个通道返回统一的 `SearchChannelResult`：通道类型、名称、按通道内相关性排列的 `RetrievedChunk` 列表、耗时和可选 metadata。`RetrievedChunk` 至少携带块 ID、文本、分数和 collection；文档名、块序号、表格位置等要到后面的元数据增强才补上。
  - 各 future 设有基础配置 15 秒的 `orTimeout`。超时只让上层 future 以该通道的空结果完成，并不会调用 `cancel`，也不能证明底层 Embedding HTTP、SQL 或图/Web I/O 已停止；迟到工作仍可能占用线程、连接或外部费用。
  - 通道自己捕获异常时同样返回空结果，超时处理也返回同一种空结构。普通日志能看到异常路径，但返回对象没有 `status/error` 字段；`RetrievalCapture` 甚至把任何空列表统一标成 `empty-or-failed-channel`。所以当前结构不能可靠区分“真的没有命中”和“通道失败后转空”，评测的 `degraded` 也可能把真实零命中算成降级。
  - 引擎随后按稳定通道列表 `join`，要等所有启用通道都交卷后才进入后处理；它不是“第一路有结果就返回”。

- `VectorSearchChannel` 先对当前子问题只生成一次 query 向量，然后让主路和补充路复用它。
  - `PgVectorRetrieverService.embedAndNormalize(question)` 把子问题原字符串交给全局 `EmbeddingService.embed`。模型请求是一个 OpenAI 风格的 `input: [question]` 数组并带配置维度，返回一个浮点数组；程序转为 `float[]` 后做 L2 归一化。Embedding 只给出检索用坐标，不生成答案。
  - 文档侧早在 C 的入库流程中，把每个块的 `embedding_text` 批量交给知识库指定的嵌入模型，并要求返回向量数量与块数量一一对应、每个维度符合部署配置；向量与块共享同一个 chunk ID 后写入 PGVector。
  - 当前查询侧与入库侧的模型选择并没有被代码强制统一：查询调用无 `modelId` 的全局路由，当前首选 `qwen-emb-8b`，失败时还可转向其他同维候选；入库则使用 `t_knowledge_base.embedding_model` 指定的单个模型且不允许回落。只有知识库配置恰为本次查询实际选中的同一模型、同一输入协议时，两个向量空间才一致。1536 维相同只能通过列宽校验，不能证明语义空间相同；跨多个使用不同模型的知识库做一次全局查询也没有被当前检索器隔离。

- 向量通道根据 scope 决定取数方式和深度。
  - 全局 scope 对全部 `targetCollections` 发一次查询，深度直接取该题预算中的 `recallBudget`，当前通常为 20；`candidateLimit` 要到融合后才生效。
  - 定向 scope 先检查命中意图节点的 `topK`：每个节点有正数就用它，否则用 `recallBudget`，多节点取最大值，再由 `candidateLimit` 钳住。因此定向向量深度可能小于或大于 20，但不会超过当前有效的候选池上限 40。之后再按补充比例拆成主/补额度。
  - 有补充额度时，补充集合查询用 `innerRetrievalExecutor` 异步发起，当前线程同步查主集合，最后 `join` 补充结果。补充路异常只丢补充结果，不带走已经成功的主路；两路合并后按同源相似度统一降序并截到各自额度的总和。

- PG 实现支持跨多个 collection 一次查询，因此主路或补充路各执行一条带集合过滤的 SQL。
  - 代码在查询前连续执行 `SET hnsw.ef_search=200` 与 `SET hnsw.iterative_scan=relaxed_order`。表上的 HNSW 索引使用 `vector_cosine_ops`：HNSW 是预先把近邻组织成多层图的近似索引，查询不用与全表每条向量精确比较；`ef_search` 增大搜索时探索的候选量，通常以更多计算换取更高的近邻召回机会。
  - SQL 核心等价于：

    ```sql
    SELECT id, content, collection_name,
           1 - (embedding <=> :query_vector) AS score
    FROM t_knowledge_vector
    WHERE collection_name IN (:allowed_collections)
    ORDER BY embedding <=> :query_vector
    LIMIT :depth;
    ```

  - `<=>` 是余弦距离，越小越近；程序用 `1 - distance` 恢复成越大越相关的相似度分数。集合条件在同一次近似查询中生效。普通近似索引可能先取近邻再被过滤，留下不足 `LIMIT` 的结果；iterative scan 会在过滤后不足时继续扫描。`relaxed_order` 用较宽松的返回顺序换召回，因此代码不把数据库返回顺序直接当最终名次。
  - 这两个 `SET` 是连接会话级参数，而当前方法没有显式事务把三次 `JdbcTemplate` 调用固定到同一物理连接；连接池是否恰好复用同一连接不能由这段源码保证。仓库也没有记录运行库的 pgvector 扩展版本。因此“代码发出了设置”是已核实事实，“本次 SQL 一定实际启用了 pgvector 0.8+ 的 iterative scan”仍需在运行环境用同连接查询参数、扩展版本与 `EXPLAIN` 核实。
  - JDBC 把每行映射成 `RetrievedChunk(id, content, collectionName, score)`。回到通道后，`ChunkRanking` 再按有限 score 降序排列，缺失、NaN 或无穷分沉底，然后才截取前 `depth`，从而恢复主/补混合后的严格通道名次。此时 `text` 是向量表的 `content`，还没有 `rankingText` 和文档元数据。

## 四、可选通道接入后增加什么候选

- 关键词通道需要同时满足 `rag.keyword.type=es` 使实现注册、`rag.search.channels.keyword.enabled=true` 使通道启用；当前两项分别是 `none` 和 `false`，所以默认不运行。
  - 开启后它拿同一个子问题、同一 scope 的主/补集合，并把 `recallBudget` 按相同比例分配。主路与补充路可并发查询 ES，补充失败只丢补充结果。
  - ES 共享索引以 chunk ID 作 `_id`，保存 `content`、`collection_name`、`doc_id`、`chunk_index`；检索对 `content` 做 match，并用 `collection_name terms` 过滤，再取 TopK。ES 默认相关度是 BM25：词在当前块中出现越有辨识度、在全库越稀有，贡献通常越大，同时会校正文档长度和重复词的边际收益。因此型号、标准号等精确词可能比只看语义更稳，但中文效果还依赖实际 IK 分词器和词典。
  - 命中被映射成与向量相同的 `RetrievedChunk`，`score` 是 BM25 分，列表按 ES 相关性顺序返回。向量相似度和 BM25 分值量纲不同，不能拿 `0.86` 与 `15.4` 直接比较。

- 图通道只有 `rag.graph.type=lightrag` 注册后端且 `channels.graph.enabled=true` 才运行；当前均关闭。
  - 开启后它把子问题、LightRAG query mode、TopK 和主集合交给适配器。LightRAG 当前是单一全局图，没有真正的 per-request workspace；适配器先查全图，再按证据的 `file_path` 归属做结果侧范围过滤。定向查询会把远端 TopK 放大 3 倍以缓解“远端先截断、回来再过滤”造成的不足，随后仍按 `recallBudget` 的主/补配额收口。
  - 输出仍是 `RetrievedChunk`；图证据可能只有 `docId` 而没有关系块 ID，后续元数据增强只能按 `docId` 补标题。它不是当前产品主链的运行证据，也不能视为数据库级子图隔离。

- Web 通道只需自身开关与可用的 You.com API Key，当前关闭；它不读取 KB scope 的集合和配额。
  - 开启后输入是子问题字符串，输出把 web/news 的标题、描述、snippets 和 URL 拼成 `text`，以 URL 作可用时的 ID，并按通道内顺序给 `1/(rank+1)` 的中性分数。配置 `count` 控制 web/news 合并后的总上限，默认 5、最大 20，而不是复用 `recallBudget`。
  - 它是公开网络适配器，不是本地知识库证据；HTTP、鉴权、限流或解析异常都转为空结果。最终是否允许把它与受控工业资料一起交给回答模型，仍需产品侧信任与引用策略，当前配置没有开启这条边界。

### 小候选表：向量与 BM25 为什么比较名次而不是原分

下面是假设同一子问题开启两路后的三条候选，数字只用于演示：

| chunk | 向量相似度 / 名次 | BM25 分 / 名次 | `k=20`、两路权重 1 时的 RRF |
| --- | --- | --- | ---: |
| `X` | `0.88 / 1` | `8.7 / 2` | `1/21 + 1/22 = 0.0931` |
| `Y` | `0.86 / 2` | 未命中 | `1/22 = 0.0455` |
| `Z` | 未命中 | `15.4 / 1` | `1/21 = 0.0476` |

`15.4` 不能解释成比 `0.88` 更相关；RRF（倒数名次融合）丢掉原始量纲，只累计 `weight / (k + rank)`。两路都靠前的 `X` 因此领先，只有 BM25 第一名的 `Z` 略高于只有向量第二名的 `Y`。这解决的是异构分数可比性，不证明融合后的首位就是答案。

## 五、候选怎样按实际处理器顺序收敛

- 所有通道 future 汇合后，引擎先把各通道列表摊平，再按处理器 `order` 依次执行：`Deduplication(1) → Fusion(5) → CandidatePoolLimit(6) → MetadataEnrichment(8) → Rerank(10)`。
  - 每个处理器若抛异常，外层记录错误并继续使用该处理器执行前的 `chunks`；不会回滚前面已经做完的去重、融合或元数据修改。若没有任何通道注册/启用，直接返回空知识结果；若有通道但全为空，处理器链仍会走，只是多数步骤原样返回空列表。

- `Deduplication` 先统一候选身份，但保留各通道原列表供后面的名次归因。
  - 身份键优先用 chunk `id`；没有 ID 的 Web 等候选用完整 `text` 的 SHA-256。处理器按稳定通道列表和各通道内部顺序扫描，同一 key 只保留首次出现的对象，不比较不可比的跨通道原始分数。
  - 重复候选在哪些通道、各排第几并没有丢：`Fusion` 仍读取原始 `SearchChannelResult`，而不是从已去重列表倒推。这样一个块虽只保留一份实体，仍能累加向量第 1、BM25 第 2 等全部名次依据。

- `Fusion` 只在 `strategy=rrf` 时启用；当前策略就是 RRF。
  - 多个 `SearchChannelResult` 存在时，按上面的公式给每个 key 累加分数，通道权重取配置值，再把融合分覆盖到去重对象并降序排列。基础配置向量/关键词权重为 1、图为 0.8、Web 为 0.5。
  - 只有一个通道结果时不计算 RRF、不改写 score，也不为“单路”虚构第二个信号，直接保留该通道已经恢复好的顺序。注意判断依据是结果对象数量；异常通道也会以空结果对象存在，所以“一个有结果 + 一个失败转空”仍属于多结果列表，RRF 会按有结果通道的名次重新赋分。
  - `Fusion` 末尾已经按 `candidateLimit` 截一次，只让融合前部进入下游。若关闭 RRF 或 `Fusion` 自身失败，order=6 的 `CandidatePoolLimit` 会对当前顺序再执行同一上限；正常 RRF 路径中它通常只是幂等成本守卫。候选在这里被截掉后，后面的 rerank 无法重新找回。

- `MetadataEnrichment` 在候选池已受控后批量回关系表，补出 rerank 和上下文组装需要的信息，但不改变候选顺序。
  - 它先按 chunk ID 从 `t_knowledge_chunk` 读取 `doc_id`、`chunk_index`、`embedding_text` 和 JSON metadata，再按文档 ID 从 `t_knowledge_document` 读取文档名与版本；表格的 sheet、cell range、block type 从 chunk metadata 解析。查不到关系块的候选保持原字段，图证据若已有 `docId` 还会再按文档 ID 尝试补标题。
  - 它按前面的三文本契约构造 `rankingText`：正文已含文档身份时不重复添加。例如原表格块 `content` 可以是便于阅读的 Markdown 行，`embedding_text` 是“设备型号: C1\n传感器型号: S7”这类键值表示，`rankingText` 再在前面加入文档身份“浓度检测台账”。模型重排看到后两类检索信号，最终回答模型仍看到 `content`，不会把合成字段冒充原文。

- `Rerank` 用 Cross-Encoder 式的 query-document 联合判断精细相关性。
  - 调用输入是当前子问题 `query`，以及候选池中每个块的 `textForRanking()`；后者优先 `rankingText`，没有时回退 `text`。请求中 `documents` 数组顺序就是候选数组顺序，`top_n` 取该题预算的 `contextTopK`。
  - 这与 Embedding 的差别是：Embedding 在入库时单独编码 document、查询时单独编码 query，能预计算文档向量并大范围近邻搜索；rerank 必须把当前 query 与每份候选文本成对交给模型，让两边 token 共同参与判断，计算更贵，所以只能放在受 `candidateLimit` 控制的小池上。它仍只返回相关性名次和分数，不生成答案；没进候选池的证据也无法被它补回。
  - 百炼客户端读取响应中每项的 `index`，用它找回原候选对象；合法的 `relevance_score` 只覆盖 score，其他 ID、正文、collection 和元数据通过 `toBuilder` 整体保留。越界 index、缺 index 的项被跳过；返回不足 `top_n` 时按 rerank 前候选顺序补齐未返回项。
  - 路由服务当前先尝试 `qwen3-rerank`，失败后可落到 `rerank-noop`。noop 只保留原顺序的前 `top_n`；如果所有候选都失败并抛出，处理器链外层跳过整个 Rerank，留下 CandidatePoolLimit 之后的融合/单路顺序。因此“rerank 失败”可能表现为模型路由成功降级到原顺序，也可能表现为处理器异常后沿用原列表，两者都不会自动说明用户答案失败。
  - `RerankPostProcessor` 不只返回模型头部：它先放 rerank 的前 `top_n`，再按 rerank 前融合顺序追加所有未入头部的候选，按统一 chunk key 去重。这样默认选择可以取头部，而公平回填打开时仍有题内尾部可用；尾部没有新的 rerank 分数，仍保留进入模型前的融合或通道分数。

## 六、多子问题怎样选出请求级结果并交给 F

- 所有子问题任务汇合后，`RetrievalEngine` 从每题的 `KnowledgeRetrievalResult.chunks` 取得题内有序候选，先计算一份公平选择诊断，再由开关决定产品实际采用哪份结果。
  - 当前 `request-level-refill-enabled=false`，实际走 `selectLegacyPrefixes`：每题只取自己的初始配额前缀，不在题内跳过跨题重复，也不继续扫描尾部。随后按子问题顺序拼接，并用统一 key 做请求级首次出现去重，得到 canonical `kbChunks`。
  - 因为重复在“每题截前缀”之后才消除，空位不会归还给任何题；某题实际候选少于配额也不会由其他题补。所以最终唯一块数可以小于 10。保持不足有时能避免塞入低相关噪声，但也可能出现“候选池里有必要证据，最终没有选中”的 selection loss。
  - 代码无论开关为何都会调用 `RequestLevelChunkSelector.select` 计算候选数、旧前缀唯一数等诊断；关闭时真正的 `selectedByQuestion` 和 `orderedUniqueChunks` 随后被旧前缀结果覆盖。不能因为公平算法被计算过，就说它决定了当前产品上下文。

- 若显式打开公平回填，实际选择才改为 `RequestLevelChunkSelector` 的两段轮询。
  - 第一段按子问题轮询，每轮每题最多取一个；某候选与其他题已选块重复时，游标继续向后找，直到该题拿满初始配额、候选耗尽或请求总 TopK 已满。它保持每题内部顺序，不横向比较不同 query 的 rerank 分数。
  - 若有题候选不足而留下总额度，第二段继续按题轮询剩余唯一候选，直到填满请求 TopK 或所有候选耗尽。即使开关开启，候选本来就不足、跨题唯一块不足或部分题被跳过时，仍可能填不满。
  - 该机制只改变“已有候选怎样进入最终额度”，不提高召回深度本身，也不保证新增尾部块更相关。历史固定回放显示它能消除空位，但 Hit@5、上下文纯度和路由纯度未通过门槛，所以当前仍保持关闭；完整实验和成绩归 I，本篇只确认接入状态。

- 选中集合确定后，引擎同时构造“统一块列表”“每题展示上下文”和“意图归属”，三者用途不同。
  - `kbChunks` 是按实际选择顺序请求级去重后的 canonical 列表。后续 F 的来源面板、grounding 快照和评测读取它；本篇到此只交付块，不展开 F 怎样编号、注入引用规则或限制 grounding 数量。
  - `selectedByQuestion` 保留每题获得的块。引擎先计算该题哪些 KB 意图可以参与回答规则，再把资格集合和选中块交给 `DefaultContextFormatter`，不是由 formatter 重新判断 scope。
    - 因额度为 0 跳过 KB 的题，资格集合直接为空。其他题调用 `KnowledgeRetrievalResult.eligibleIntentIds`：属于本次 `directedIntentIds` 的意图，必须有最终存活块归属于它，才能保留；不属于该集合的候选意图仍可保留资格。因此这道证据门禁只约束本次定向意图，不能概括为“所有意图都必须有绑定库证据”。
    - 默认前缀路径用该题最终选中块判断存活归属。公平回填开启时，用该题候选中已被全请求选中的块判断，所以共享块即使被分配给另一题，也仍可支持本题意图资格；两条路径都不会让未被请求选中的尾部证据激活定向规则。
    - 例如假设本题未被前面的澄清分支短路，KB 意图 A/B 的分数是 0.55/0.50，均过上游 0.35，却未达到 scope 的 0.6 定向阈值。主配置走 global fallback，此时 `directedIntentIds` 为空；全局检索有选中块时，A/B 即使没有各自绑定库的存活证据，仍可同时贡献非空 `promptSnippet`。scope 阈值控制查询范围，不等于统一过滤回答规则资格。
  - formatter 只从引擎给定的资格集合中过滤该题 KB 意图：零个可用意图就只放正文，一个则加入其非空 `promptSnippet`，多个则将非空规则去空白、去重并编号。随后按 `docId` 分组正文：文档组之间保持该文档最佳块首次出现的顺序，组内按 `chunkIndex` 恢复原文顺序，实际拼入的是 `RetrievedChunk.text`，不是 `rankingText`。该题没有选中块时，引擎直接令其 KB 文本为空；多子问题时外层再为非空题包上 `<document><question>...` 段落，形成 `kbContext`。
  - 默认旧路径允许同一块同时留在两个题的前缀，所以它在分题 `kbContext` 中可能出现两次，但在 `kbChunks` 中只出现一次；公平路径用全局 `selectedKeys` 分配候选，同一块只归给第一次选中的题，因此两边都只出现一次。无论哪条路径，Prompt 所见的是选中块的 `content/text`。
  - 定向 scope 下，chunk 的 `collectionName` 属于哪个命中意图绑定库，就归属哪个意图；共享库可让同一块多归属。补充库块、全局 scope 块和 Web 块通常进入统一 `multi_channel` 分组。归属来自集合关系，不来自“是哪条通道召回了它”。
  - 返回的 `RetrievalContext` 包含 `kbContext`、`kbChunks`、按意图分组的 `intentChunks`、`eligibleIntentIds` 和选择诊断。若 KB 与 MCP 上下文都空，`StreamChatPipeline` 直接输出“未检索到与问题相关的文档内容。”；否则 F 接着用 `kbChunks` 组装来源和 grounding，用 `kbContext` 构造回答 Prompt。

## 示例：两个子问题共享一块时，为什么默认只留下 9 块

这个假设例子对应上面的“额度分配 → 题内排序 → 请求级选择 → 上下文构造”。设请求总 `contextTopK=10`，D 给出两个子问题，因此配额是 `[5,5]`。两题经过各自检索和 rerank 后的候选为：

```text
问题 A： [X, A, B, C, D, E]
问题 B： [X, F, G, H, I, J]
```

其中 `X` 是同一个 chunk ID，例如两问都需要的“设备 C1 使用传感器 S7”台账块。

- 默认关闭回填时：
  - A 只取前 5 个：`[X,A,B,C,D]`；B 只取前 5 个：`[X,F,G,H,I]`，一共先消费 10 个题内位置。
  - 请求级按首次出现去重，canonical `kbChunks` 变成 `[X,A,B,C,D,F,G,H,I]`，实际只有 9 个唯一块。
  - B 的 `J` 即使是必要证据，也已经在前缀截断时失去机会；程序不会用它补重复 `X` 留下的空位。这就是“候选里有证据但最终没选中”，不是向量库没召回。
  - 分题 `kbContext` 中，`X` 仍会在 A、B 两个 `<document>` 段各出现一次；F 使用的 canonical `kbChunks` 只保留一份 `X`。

- 打开公平回填时：
  - 第一轮 A 取 `X`；轮到 B 时发现 `X` 已选，游标继续取 `F`。后续轮询依次让 A/B 取得 `A/G`、`B/H`、`C/I`、`D/J`。
  - 两题各拿 5 个唯一块，最终是 `[X,A,B,C,D,F,G,H,I,J]` 共 10 个；`X` 只归给 A，B 用 `J` 补足自己的初始配额。
  - 这只证明额度被利用，并不证明 `J` 比保持空位更能提高答案质量。若 B 没有 `J`，第二阶段可以尝试用 A 的 `E` 回填；若所有剩余项也重复或候选耗尽，结果仍少于 10。

## 七、诊断和实验代码处在哪条边界上

- `RetrievalCapture` 是显式随请求传入的线程安全快照容器。它在通道出口、每个后处理器之后和请求最终结果处复制 ID、正文、分数、collection、文档信息与排序文本，避免后续原地改分或富化改写早期快照。
  - 当前普通 `StreamChatPipeline` 调用不传 capture；`PooledEvalController` 和 `HybridEvalController` 等隔离评测入口才创建它。因此它记录发生了什么，不参与排序、选择或产品 Prompt。
  - Hybrid 评测还会从捕获的单通道候选另做向量/BM25 rerank 对照；这些诊断结果是评测响应，不会回写本次产品 `RetrievalContext`。空结果与失败目前在 capture 中不能彻底区分，这是读评测结果时必须保留的限制。

- `DeterministicContextSelector` 当前由本地 `ContextSelectionReplayCli` 和单元测试使用，没有被 `RetrievalEngine`、`StreamChatPipeline` 或产品 Controller 装配。
  - 它可以离线比较 Prefix、按 rerank、MMR 和 coverage 等固定候选选择策略，并同时约束 token 与最大块数；但“production wiring”式注释不是当前调用证据。
  - 产品当前实际选择器只有 `selectLegacyPrefixes` 与开关保护的 `RequestLevelChunkSelector`。实验选择器的算法细节、数据协议和完整成绩归 I；本篇只说明它尚未接管产品，不能把离线诊断选择结果写成最终上下文。

## 八、怎样定位几类典型业务问题

- 两个子问题各自相关却挤掉必要证据：先看每题通道结果和 post-rerank 候选中证据是否存在。候选有、默认前缀后无，是请求级选择损失；候选池截断前就无，才继续查 scope、召回深度与 query 表达。
- 相同块重复占位：核对统一 key 是否相同。相同 ID 会在通道内后处理中去重；跨子问题默认路径则要到各题前缀选完后才去重，因此会留下请求级空位。
- 型号词语义近但不是同一设备：向量分高只说明向量空间接近，不能证明型号相同。可选 BM25 有助于精确型号词召回，rerank 可联合比较 query 与结构化文本，但最终仍需正确版本、设备身份和范围证据进入候选。
- 向量超时：上层在 15 秒后把该通道转空并继续其他通道；默认只有向量通道时通常就得到空 KB。底层 I/O 未被取消，且返回结构与真实零命中相同，须结合异常日志、模型路由和数据库耗时判断。
- rerank 失败：先看是否由模型路由落到 noop；若整个处理器抛错，则候选保留融合/单路顺序。响应可能继续生成，但排序质量已降级，不能把“最终有块”当成 rerank 成功。
- 候选里有证据但最终没选中：依次看 RRF/candidate limit 是否提前截掉、rerank 是否把它放到尾部、默认子问题前缀是否截掉、跨题去重是否留下空位。增加 `recallBudget` 只对第一类召回不足可能有用，不会自动修复后面的选择规则。

## 关键源码反查

- [`RetrievalEngine`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalEngine.java)：请求预算分配、子问题并发、默认/公平选择、归属合并和 `kbContext` 构造。
- [`MultiChannelRetrievalEngine`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java)：scope 建立、通道并发与超时、处理器排序和异常跳过。
- [`RetrievalScopeResolver`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java)、[`VectorSearchChannel`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/VectorSearchChannel.java)：主/补集合、配额和向量通道出口顺序。
- [`PgVectorRetrieverService`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java)、[`schema_pg.sql`](../../../resources/database/schema_pg.sql)：query Embedding、PGVector SQL、余弦 HNSW 索引和固定 1536 维列。
- [`DeduplicationPostProcessor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/DeduplicationPostProcessor.java)、[`FusionPostProcessor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/FusionPostProcessor.java)、[`CandidatePoolLimitPostProcessor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/CandidatePoolLimitPostProcessor.java)：去重、RRF 和候选池成本守卫。
- [`MetadataEnrichmentPostProcessor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/MetadataEnrichmentPostProcessor.java)、[`RerankPostProcessor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/RerankPostProcessor.java)、[`BaiLianRerankClient`](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java)：三份文本、模型请求、index/score 回映射和尾部保留。
- [`RequestLevelChunkSelector`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RequestLevelChunkSelector.java)、[`DefaultContextFormatter`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/DefaultContextFormatter.java)：公平回填算法与最终正文分组渲染。
- [`application.yaml`](../../../bootstrap/src/main/resources/application.yaml)、[`application-iron-ore-demo.yaml`](../../../bootstrap/src/main/resources/application-iron-ore-demo.yaml)：基础默认值与铁矿 profile 的 scope 覆盖；有效运行值还取决于启动 profile 和外部配置。

## 相对旧笔记的重要修正

- 当前处理器链在 Fusion 之后还有独立的 `CandidatePoolLimit(order=6)`，它为关闭/失败的融合分支兜底；不能只写成 Fusion 自己截池，也不能把 20/40/10 当作每次实际数量。
- query Embedding 与文档入库 Embedding 目前只在配置恰好一致时共享语义空间，代码没有按 scope 中每个知识库的模型生成 query 向量；同维度不等于同模型。
- 默认关闭公平回填时，产品实际使用的是每题固定前缀后再请求级去重；公平选择结果虽被计算用于诊断，但不会决定最终块。`DeterministicContextSelector` 也只接在离线回放，不属于产品聊天链。
- 通道超时不取消底层 I/O，且空结果结构不能区分真没命中与异常降级；“有结果”“填满 TopK”或 rerank 分数变化都不能直接推出回答更准确。
