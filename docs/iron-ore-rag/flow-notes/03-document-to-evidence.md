# 03｜进入摄取内核后：文档怎样逐步变成可检索证据

本文承接 B 的异步摄取流程：B 已经取得文档身份、原文件字节、文档级摄取规格，以及知识库对应的向量落点，然后同步调用摄取内核。这里不再重复上传、对象存储、事务消息和任务状态流转，而是只追 `DefaultIngestionKernel` 内部固定的五步：**探测与解析 → 按 Block 切分 → 组织两份文本与来源 → 指定模型向量化 → 替换各索引中的旧块**。内核完成后把结果交回 B，由 B 更新文档状态和摄取日志。

为了避免几种“身份”混在一起，先记住内核入口收到的四份数据：

- 文档引用 `DocumentRef` 只有 `docId`、`kbId`、`filename`。`docId` 决定块属于哪篇文档，也用于图片资产目录；`kbId` 决定关系记录归属；`filename` 同时辅助 MIME 探测并写入来源信息。B 更早得到的 `document_key`、版本等文档表字段没有全部装进这个对象。
- `bytes` 是已经从对象存储读回的原文件字节，不是文件 URL，也不是解析后的文本。
- 文档级摄取规格 `IngestionSpec` 包含解析档 `parseProfile` 和分块预算 `maxChars`、`overlapChars`、`rowsPerChunk`、`toleranceFactor`。配置为空时内核采用统一默认值：FAST、`maxChars=1024`、`overlapChars=128`、`rowsPerChunk=50`、容忍倍数 3。
- 向量落点 `VectorTarget` 由 B 在进入内核前通过 `VectorTargetResolver` 生成：`partition` 取知识库的 `collection_name`，`embeddingModel` 取知识库指定的模型 ID，`dimension` 取部署级 `rag.default.dimension`。模型或维度缺失会在进入内核前失败，不会悄悄改用默认模型。当前主配置的维度是 1536，候选 `qwen-emb-8b` 对应 `Qwen/Qwen3-Embedding-8B`；某个知识库实际用哪个候选仍以它自己的 `embedding_model` 为准。

## 一、完整流程：从原文件字节到替换完成的证据块

### 1. 内核先探测真实 MIME，再用“解析档 × MIME”选择解析器

- 内核首先检查原文件字节。`bytes` 为 `null` 或长度为 0 时直接抛出“文件内容为空”，后面不会探测 MIME、调用解析服务或删除旧索引。
- 字节非空后，`MimeTypeDetector` 把**字节和文件名一起**交给 Apache Tika 探测 MIME。扩展名只是辅助信息，不是路由的唯一依据；探测不到非空 MIME 时抛出“无法识别文件类型”。
- 得到 MIME 后，`ParserRegistry.require(mime, requestedProfile)` 按以下顺序查注册表：
  - 先查请求档的精确 MIME，再查请求档的 MIME 大类通配；
  - 请求档不是 FAST 且没有专用解析器时，再查 FAST 的精确 MIME和通配；
  - 全部没有命中才抛出“不存在对应解析器”。这叫回落 FAST，不是把未知二进制文件塞给 Tika 硬读。
- 内核给选中的解析器传原字节、探测 MIME，以及 `sourceFile=filename`、`documentId=docId` 两个选项。当前内核**没有**从摄取规格传 `headerRows`，所以 POI Excel 路线实际使用解析器默认的一行表头；`ExcelTableNormalizer` 本身具备多行表头展平能力，相关测试也覆盖了它，但这不等于当前内核入口已开放多行表头配置。
- 解析器返回 `ParsedDocument`。内核只继续使用其中有序的 `blocks`；解析器返回 `null` 块列表时按空列表处理。这里不要求解析器先给出一段统一纯文本，因为标题、表格、列表、代码和图片要保留成不同 Block，下一步才能按结构选择切法。
- 解析器正常返回但 Block 为空时，内核仍会进入分块；分块返回空 chunk 后才抛出“分块结果为空”。因此“外部解析调用成功”不等于这篇文件已有可写入的证据。该异常发生在替换旧索引之前，旧块仍保留。

#### 各格式在当前注册表中的实际解析分支

- **XLS/XLSX，FAST：**走 `ExcelDocumentParser`，由 Apache POI 直接读取工作簿、Sheet、单元格、公式、超链接和合并区域，产出 `HeadingBlock + TableBlock`，这是本文后续主线。
- **XLS/XLSX，FIDELITY：**标准的 Excel MIME 会精确命中 `MinerUDocumentParser`。它适合愿意支付外部版面解析成本的复杂表格；不会再执行本文下一节的 POI `ExcelTableNormalizer`。若 MIME 只被探成 `application/x-tika-msoffice` 或 `application/x-tika-ooxml` 这类通用 Office 别名，FIDELITY 没有对应认领，会回落到 FAST 的 POI 解析器；所以最终路线仍以探测 MIME 与注册表命中结果为准。
- **PDF、Word、PPT：**FAST 本身就由 MinerU 认领；选择 FIDELITY 后也因没有另一套专用认领而回落到同一个 FAST MinerU 解析器，所以这几类文件当前两档命中的是同一条路线。MinerU 先获取 Redis 分布式许可，申请上传地址，把原字节 PUT 到 MinerU，轮询任务，下载结果 ZIP；ZIP 中的 Markdown 再解析为标题、段落、列表、代码、HTML 表格和图片 Block。ZIP 没有 Markdown、下载/解包失败或等待超时都会抛错。
- **Markdown 和纯文本：**Markdown 解析器用 CommonMark AST 识别标题、段落、列表、代码、GFM 表格、HTML 表格和图片；`text/plain` 也由它精确认领。**HTML、JSON、XML、RTF 以及未被专用解析器认领的 `text/*`**才走 Tika，清洗平文本后按连续空行拆成 `ParagraphBlock`。所以不能笼统写成“Markdown/TXT 都由 Tika 解析”。
- **PNG、JPEG、SVG：**走图片解析器。SVG 先栅格化成白底 PNG；随后视觉模型接收图片字节、MIME、中文描述/OCR 指令和最大输出 token 配置，返回描述文本。描述为空会使独立图片解析失败；成功后原图写资产存储，得到 `ImageBlock`，其中展示侧保留描述与图片 URL，检索侧优先只使用描述。
- MinerU 请求中的 `ocr` 是“是否强制 OCR”的开关，当前主配置为 `false`；表格与公式提取分别为 `true`。这不关闭 MinerU 对原生文字层的版面解析，也不等于视觉模型提示词中的“识别图片文字”被关闭。
- POI Excel 内嵌图片还要同时满足三项条件：`excel-embedded-enabled=true`、Sheet 名在白名单、Sheet 是 XSSF。默认第一项为 `false`；`iron-ore-demo` profile 才把它打开，且当前只允许“浓度检测（双场景）”。满足条件时每张图调用视觉模型并保存资产；单张图失败只跳过该图，表格文本继续。这个白名单只约束 POI Excel 分支，不应扩大成所有格式统一的 OCR/图片开关。

<a id="excel-normalization"></a>

### 2. FAST Excel 把工作簿逐 Sheet 变成有序的标题块和二维表格块

- `ExcelDocumentParser` 用 `WorkbookFactory` 打开字节流，为本次工作簿创建 `DataFormatter` 和公式求值器，然后按工作簿顺序遍历 Sheet。隐藏和 very hidden Sheet 明确跳过，不进入证据。
- 每个可见 Sheet 先交给 `ExcelTableNormalizer`。它不是“把整张表拼成字符串”，而是依次构造和清洗二维 `grid`：
  - 先扫描所有行的最大列数，再逐坐标读取 cell。普通数值、日期、布尔值按 Excel 显示格式变成去首尾空白的字符串；公式优先现场求值，失败后依次回落缓存值、公式字符串；超链接把可见文字和 URL 合成 `[文字](url)`；带删除线的非空 cell 包成 `~~值~~`，表示保留原值并显式标注软删除。
  - 在展开合并区域**之前**记录每一源行是否原本有值。后面只有这份布尔快照能区分“用户真的录入了两条相同记录”和“原行为空、仅因合并区域展开而出现的续行”。
  - 展开合并区域时，位于表头范围的合并值横向复制到区域内每列，便于下一层把父表头带给每个子列；位于数据范围时，只在合并区域第一列逐行携带值，不把同一长说明复制到多列。
  - 接着删除在表头和数据中始终全空的列，包括夹在中间的全空列；“表头空、数据有值”的列仍保留。全空数据行跳过。
  - 前 `headerRows` 行按列展平成 headers，同一列相邻重复的父/子标题只留一次，不同层用 `|` 相连。例如两行表头“检测条件 / 温度”会成为 `检测条件|温度`。但如上所述，当前内核主链的 `headerRows` 实际为 1。
  - 数据行按保留列投影为 `rows`。如果一行在原工作簿中整行为空，展开后又与上一规范化行完全相同，程序不新增业务行，而是把上一行的来源范围延伸到当前行；除此之外，即使两行内容完全相同也分别保留。
- 规范化结果是三份严格对齐的数据：`headers`、二维 `rows`、每个规范化数据行对应的 `rowCellRanges`。范围使用原 Excel 坐标，从保留列中的第一列跨到最后一列；折叠合并续行时还跨越被折叠的源行。
- 如果规范化后表为空，这个 Sheet 不生成标题或表格 Block。否则解析器按顺序生成：
  - 一个一级 `HeadingBlock`，文字是 Sheet 名，来源带 `sourceFile + sheetName`；
  - 一个 `TableBlock`，携带同一来源、`headers`、`rows`、`rowCellRanges`。
  - 因此 Sheet 名不是临时日志字段：下一步处理标题时会成为顶级 `outlinePath`，同时作为不同 Sheet 之间不得混块的边界；数据范围则要等表格分组完成后才汇总到具体 chunk。

### 3. 分块服务按 Block 类型产草稿，表格逐行做双文本预算

- `ChunkingService` 先看预算是否为“整篇不分块”。如果是，它直接把全部 Block 渲染为一份文本，只取第一个 Block 的来源组装单个 chunk，不再执行表格专用规则。以下结构感知过程对应正常的非整篇模式，也是默认路径。
- 非整篇模式中，调度器保持一个章节路径：读到 `HeadingBlock` 时先按标题级别更新路径，再把这个标题也交给 `HeadingChunker`。Excel 的 Sheet 标题于是产生展示草稿 `# Sheet名`，检索正文是没有 `#` 的 `Sheet名`，并把 `[Sheet名]` 交给随后表格草稿。
- 其他 Block 只在各自分支产生草稿，不复制公共后半链：
  - 段落优先保持整段；超过 `toleranceChars` 才按 `maxChars`、句末/换行边界和 `overlapChars` 切；
  - 列表按完整列表项累计，不从单项中间截断；代码优先整块，过大时按完整行切并为每片补代码围栏；HTML 表格按 `<tr>` 切并重复表头；
  - 图片一图一草稿，`content` 是“描述 + Markdown 图片链接”，`embeddingBody` 有描述时只取描述；
  - 这些草稿最后都和表格草稿进入同一个 `ChunkPacker`，再统一装配 ID、检索前缀和 metadata。

#### `TableChunker` 怎样决定一行放进哪里

- 它从 `TableBlock` 取得 headers、rows 和逐行范围，并读取两个独立上限：`rowsPerChunk` 限制每块最多含多少**数据行**，`maxChars` 限制当前表格草稿的 Java `String.length()`。这里的“字符”是 Java UTF-16 code unit 长度，不是 token 数，也不能固定换算成“1024 字符等于多少 token”。
- 对每个数据行，程序先单独判断这行连同完整表头能否同时满足两份预算：
  - 展示候选实际渲染为 Markdown：表头行、`---` 分隔行、数据行、管道符、空格、转义符和换行都计入长度；cell 中的 `|` 转义为 `\|`，换行变成 `<br>`。
  - 检索候选把非空 cell 渲染为 `列名: 值`，同一行用 `; ` 连接，多行用换行连接，并把当前 `outlinePath` 按 ` / ` 连接后连同一个换行的成本一起计入。空 cell 不进入这份文本。
- 如果单行能放下完整宽表，程序再尝试把它加入当前 `group`：
  - 当前 group 已有 `rowsPerChunk` 行时，先把 group 落成草稿，再以当前行开新 group；
  - group 非空且“加上下一行”会使 Markdown 或带路径的检索文本任一超过 `maxChars` 时，也先落当前 group；
  - 当前 group 为空时不会先落一个空块，因此该行随后必定进入 group。正常行本身此前已通过单行预算判断。
- 如果单行连完整表头都超预算，程序执行宽行切片：跳过空列，按原列顺序逐个加入非空的“列名 + 值”，每次用较窄的 headers 和这一行的对应 values 重新渲染两份候选；再加入一个键值对将超预算时，先落已有列片，再从当前键值对开始下一片。每片仍是一张合法的一行小表，并共享原始整行的 cell range。
  - 程序不会从一个键值对内部截断。若**第一个或某一个单独的键值对本身**已经超过 `maxChars`，因为当前片还为空，判断不会先落块，它会把该原子字段完整放入一个超限片段。这是刻意保留事实完整性的例外。
  - 所以 `maxChars=1024` 是当前表格分组的目标预算，不是对任意输入的最终硬保证。更不能承诺任意输入最终都小于 1024。
- 一个分组落草稿时，两份正文与来源同时确定：
  - `content` 是带完整表头的 Markdown 表格；
  - `embeddingBody` 是键值行，不重复整张 headers，也暂不重复章节路径；
  - metadata 写入 `outlinePath`、`block_type=table`、`source_file`、`sheet_name`，并用分组第一行和最后一行的范围合成 `cell_range`。宽行的多个列片当前都沿用同一个原始整行范围，而不是各自缩到列片范围。
- 如果一个 `TableBlock` 最终产生不止一个草稿，`ChunkDraft.pieces` 才把每个草稿标记为 `piece=true`；只有一份时不标。`piece` 不改变文本，它是给后续打包器的边界信号：这个 Block 已由专用 chunker 拆过，不许通用合并又撤销切分。

#### `ChunkPacker` 先合并草稿，`ChunkAssembler` 最后才生成成品块

- 调度器在所有 Block 都产生有序草稿后，先调用 `ChunkPacker`，不能先生成 chunk ID 或拼章节前缀。否则多个草稿合并时会留下多个 ID，并把同一前缀重复拼进检索文本。
- `ChunkPacker` 先按标题把草稿分成 section，同时把 `maxChars / 4` 当作避免极小尾块的最小体量参考。它按以下条件处理：
  - 顶级 `outlinePath` 不同先落当前缓冲，Excel 中就是绝不跨 Sheet 合并；
  - section 总长超过容忍上限，或其中任一草稿带 `piece`，进入逐草稿打包；`piece` 自身原样落块，只允许在“前导语 + piece”合并后仍不超过 `maxChars` 时把紧邻前导语带进去；
  - 没有 piece 的普通原子 section 可以在缓冲不足最小体量时使用 `toleranceChars` 决定是否继续并入，原子 section 自身也可能超过 `maxChars`；缓冲已够最小体量后，加入下一 section 超过 `maxChars` 就先落块；
  - 最后不足最小体量的小尾巴，只在与上一块同顶级路径且合并后不超过 `maxChars` 时才并回。合并时 `content` 与有效检索正文分别用空行连接，来源优先取带 `cell_range` 的更具体项，路径取各项公共前缀。
- 打包完成后，`ChunkAssembler.assembleAll` 才按最终列表顺序从 0 分配 `chunk_index`，并为每块生成全局雪花 `chunkId`。重新摄取会重新生成 ID，不沿用旧 chunk ID。
- 装配器保持展示正文不变，然后生成最终 `embedding_text`：先检查 `content` 已经包含了章节路径中的哪一级标题，只补“正文尚未覆盖”的前缀，再换行追加草稿的 `embeddingBody`；没有显式检索正文的普通块则回落 `content`。
  - Excel 第一片常与 `# Sheet名` 标题草稿一起打包，所以展示正文含标题，检索正文也已有标题词面，装配器不会再机械加一遍；后续表格片没有标题正文，装配器会补 `Sheet名\n`，再接键值文本。
  - `TableChunker` 的预算已预估完整 `outlinePath + 换行 + embeddingBody`，但 `ChunkPacker` 还能为 piece 加满足 `maxChars` 的前导语。原子超限字段、非 piece 原子 section 以及整篇模式仍说明：最终链没有“任何输入必定小于 1024”的承诺。
- 到这里得到的 `Chunk` 仍没有向量，但已经完整拥有 `chunkId`、顺序号、展示用 `content`、模型检索用 `embedding_text` 和结构化 `metadata`。构造器会拒绝空 ID、负序号、`null content` 或空白 `embedding_text`。

### 4. 指定知识库模型批量编码，并按位置把向量装回原 chunk

- `ChunkEmbeddingService` 按当前 chunk 列表顺序提取每一项的 `embedding_text`，形成 `texts=[text0,text1,...]`。发送给模型的不是 Markdown `content`、原文件字节或 metadata，也不会让模型生成答案。
- 它调用 `EmbeddingService.embedBatch(texts, target.embeddingModel)`。这里显式传的是知识库指定模型 ID；路由器只解析这一个候选，不在失败时换用其他 embedding 候选。底层 OpenAI 风格请求包含模型名、按原顺序排列的 `input` 字符串数组、`dimensions=target` 对应候选的配置维度和 `encoding_format=float`。
- 供应商返回 `data[].embedding` 浮点数组。当前客户端按返回数组的出现顺序收集结果，没有另按响应中的 index 重排；因此“返回顺序与输入一致”是模型适配器必须满足的契约。若供应商有批量上限，适配器可按连续切片请求，并把各片结果写回原偏移，仍保持总体顺序。
- 返回后服务执行三层校验，再按相同下标组合：
  - 整个向量列表不能为 `null`，条数必须等于 chunk 数；
  - 第 `i` 个向量不能为 `null` 或空；
  - 第 `i` 个向量长度必须等于 `target.dimension`，当前部署是 1536。通过后才转为 `float[]`，生成 `EmbeddedChunk(chunks[i], vector[i])`。
- 任一检查失败都抛异常，索引替换尚未开始，所以数据库中的旧块和旧向量不受这次失败影响。这里只校验形状和位置契约，不判断向量语义质量。
- E 在查询侧生成的 query 向量必须与这些文档向量兼容：模型、权重/版本、任务指令、归一化方式和维度都应属于同一语义空间。**同为 1536 维只说明数组能做运算，不保证距离有语义可比性。**当前查询侧不带知识库模型 ID 的全局路由风险由 E/G 继续说明；本篇只固定入库侧契约，模型候选切换策略归 G。

### 5. 写入器依次替换关系块和向量；提交后才把结果交回 B

- 内核把 `VectorTarget`、`DocumentRef` 和全部 `EmbeddedChunk` 交给 `ChunkIndexWriter.replaceDocument`。写入器用 Spring `TransactionOperations` 包住 `List<ChunkSink>` 的顺序调用；当前两个一等 sink 的顺序由 `@Order` 固定：关系库 sink 在前，向量 sink 在后。
- `RelationalChunkSink` 先按 `docId` 删除 `t_knowledge_chunk` 中旧块。由于实体的 `deleted` 字段带 MyBatis-Plus `@TableLogic`，这条 Mapper 删除按当前 ORM 配置表现为逻辑删除，不应写成物理清表。接着为每个新块构造关系记录：
  - 主键直接使用装配阶段生成的 `chunkId`，同时写 `kb_id`、`doc_id`、从 0 开始的 `chunk_index`；
  - `content` 原样保存，另计算 SHA-256、Java 字符数和 tokenizer 统计值；`embedding_text` 单独落列，便于以后不重做解析就重新向量化；
  - metadata 通过唯一序列化入口写成 JSONB，包含可用的 `source_file`、`sheet_name`、`cell_range`、`block_type`、assets 和扩展字段；关系 metadata 本身不重复写 `doc_id/chunk_index`，这两个已有独立列；
  - 最后批量插入新关系行。新摄取使用新 ID，因此不会与本次逻辑删除的旧主键复用。
- 随后的 `VectorChunkSink` 在同一事务回调中调用当前 `VectorStoreService`：
  - 先按 `partition + metadata.doc_id` 删除该文档旧向量；PGVector 实现执行 `DELETE FROM t_knowledge_vector WHERE collection_name=? AND metadata->>'doc_id'=?`，这是物理删除；
  - 再批量执行 `INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding)`。`id` 与关系行是同一个 `chunkId`，`content` 仍是展示文本；向量 metadata 在块 metadata 基础上补 `doc_id` 和 `chunk_index`；`float[1536]` 被序列化为 `[v0,v1,...]` 字面量并交给 `?::vector`。该整文替换 SQL 使用普通 INSERT，不是单块编辑路径的 `ON CONFLICT` upsert，所以必须先删旧向量。
- 当前主配置 `rag.vector.type=pg`、`rag.keyword.type=none`、`rag.graph.type=none`，因此默认实际落点是关系块表和 PGVector 表。条件启用外部索引后，同一批文档/chunk 通过 VectorStore 装饰器继续接收：
  - ES 关键词索引启用时，在真实向量写成功后，用同一 `chunkId` 作为 ES `_id`，写 `collection_name`、`doc_id`、`chunk_index` 和经过长度保护的展示文本；当前 ES 文档不会复制整份块 metadata。删除同样按 collection 与 doc 进行。
  - LightRAG 启用时，在真实向量写成功后，把全部 chunk 的非空 `content` 用空行拼成一篇文档，并携带编码后的 `file_source(collection,docId)` 写图；替换仍依赖“先按 doc 删除、再整文插入”。它接收的是展示正文拼接，不是本项目生成的 1536 维数组。
  - 这两个外部同步都是 best-effort：异常被装饰器捕获并只记告警，不会使本地关系/向量事务失败。反过来，外部调用不是 PostgreSQL 事务资源；即使某次外部调用成功，数据库最终提交失败也不能由本地事务自动撤销外部结果。
- 若关系写、PGVector 删除/插入或事务提交抛异常，Spring 本地事务回滚这次关系表和 PGVector 表修改；旧关系块/旧向量应恢复到事务开始前的数据库状态。解析、视觉模型、Embedding、MinerU 上传和已发生的外部索引调用都不在这个数据库事务中，不能跟着回滚。
- 全部 sink 调用并成功提交后，内核返回 `IngestionOutcome`：探测 MIME、实际解析器类型、解析 Block 数、最终 chunk 列表，以及 parse/chunk/embed/index 四段毫秒耗时。`parseMillis` 从选解析器前开始计时但不包含更早的 MIME 探测；注释把它概括为解析阶段，阅读指标时以实际计时代码为准。
- 回到 B 后，当前外层实际取四段耗时和 `chunkCount`，用 MIME 回填文档表，再把文档标为 SUCCESS 并更新摄取日志；它没有把向量从内核结果再返回一遍。如果内核任一步抛错，B 的 `catch` 会尝试把文档标为 FAILED、日志块数写 0 并记录错误。只有写入失败状态和失败日志的调用都正常返回时，原摄取异常才被吞掉，消费者正常返回；catch 内二次写入若再抛异常，该新异常仍会传给 MQ 消费者，可能进入重投路径，具体接续见[流程 B 的消费者终态处理](02-document-lifecycle.md#4-消费者执行恢复操作者调用固定摄取内核再记录终态)。B 的状态更新不与内核的索引替换共用同一个显式事务：索引已经提交后，若后续 SUCCESS/日志更新失败，不能假定索引也回滚。

## 二、贯穿示例：一张小表怎样走到一条带向量记录

下面是教学数据，不是生产运行输出。它对应上面第 2～5 步，重点展示原始相同记录与合并续行的差别、两份文本和来源怎样保持同一身份。[读完可回到 Excel 规范化步骤](#excel-normalization)。

假设文件 `设备台账-V3.xlsx` 的可见 Sheet 为“浓度检测”，当前主链按一行表头读取：

| 原始行 | A：设备 | B：传感器 | C：指标 | D：正常范围 | 原始含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | 设备 | 传感器 | 指标 | 正常范围 | 表头 |
| 2 | C1 | S7 | 尾矿浓度 | 18%～22% | 用户录入记录 1 |
| 3 | C1 | S7 | 尾矿浓度 | 18%～22% | 用户又录入了一条相同记录 2 |
| 4～5 | C2（`A4:D5` 整区合并） | 空 | 空 | 空 | 第 5 行源坐标原本全空，只是合并区域续行 |

- POI 初读时，第 2、3 行都有源值，`rowsWithSourceValues` 都是 true，所以即使规范化后完全相同也保留为两行。程序没有“所有业务行正文相同就去重”的规则。
- `A4:D5` 展开到数据区时只在第一列逐行携带 `C2`：第 4、5 行规范化后都是 `[C2,"","",""]`。第 5 行的源行快照是空，且展开后等于上一规范化行，所以它不再生成第二条 row，只把上一条来源从 `A4:D4` 扩成 `A4:D5`。
- 最终 `TableBlock` 的关键数据是：

```text
headers = [设备, 传感器, 指标, 正常范围]
rows = [
  [C1, S7, 尾矿浓度, 18%～22%],
  [C1, S7, 尾矿浓度, 18%～22%],
  [C2, "", "", ""]
]
rowCellRanges = [A2:D2, A3:D3, A4:D5]
provenance = {sourceFile: 设备台账-V3.xlsx, sheetName: 浓度检测}
```

继续使用默认 `maxChars=1024`、`rowsPerChunk=50`。这三条规范化行连同表头的两份候选文本都没有超过预算，也没有达到行数上限，所以 C2 会接在两条 C1 后面，整个 TableBlock 只生成一个表格草稿，`piece=false`。草稿的两种文本是：

```markdown
| 设备 | 传感器 | 指标 | 正常范围 |
|---|---|---|---|
| C1 | S7 | 尾矿浓度 | 18%～22% |
| C1 | S7 | 尾矿浓度 | 18%～22% |
| C2 |  |  |  |
```

```text
设备: C1; 传感器: S7; 指标: 尾矿浓度; 正常范围: 18%～22%
设备: C1; 传感器: S7; 指标: 尾矿浓度; 正常范围: 18%～22%
设备: C2
```

前者是表格草稿的 `content`，相同业务行真实出现两次；后者先作为 `embeddingBody`，C2 的空单元格不写成空键值。表格来源由第一条 `A2:D2` 与最后一条 `A4:D5` 合成 `A2:D5`，涵盖被折叠的合并续行。

- 接着进入打包器。前面还有一个 Sheet 标题草稿：展示文本是 `# 浓度检测`，检索正文是 `浓度检测`，两份草稿的 `outlinePath` 都是 `[浓度检测]`。
  - 标题和这张短表属于同一 section，没有 piece，整体也未超过预算，打包器将它们合为一个草稿。展示文本与检索正文分别用一个空行连接，来源优先保留表格上带 `cell_range` 的信息。
  - 当前标题草稿没有设置 `block_type`，合并时空类型会被忽略，剩下的有效类型只有 `table`，所以成品的 `block_type` 仍为 `table`；不能仅因正文同时包含标题和表格就推导成 `mixed`。
- 装配器为合并后的唯一草稿分配 `index=0` 和新 chunk ID。展示正文已经含有“浓度检测”，所以不会再追加章节前缀；检索正文中来自标题草稿的“浓度检测”只出现一次。最终 `content` 是：

```markdown
# 浓度检测

| 设备 | 传感器 | 指标 | 正常范围 |
|---|---|---|---|
| C1 | S7 | 尾矿浓度 | 18%～22% |
| C1 | S7 | 尾矿浓度 | 18%～22% |
| C2 |  |  |  |
```

最终 `embedding_text` 是：

```text
浓度检测

设备: C1; 传感器: S7; 指标: 尾矿浓度; 正常范围: 18%～22%
设备: C1; 传感器: S7; 指标: 尾矿浓度; 正常范围: 18%～22%
设备: C2
```

这块最终落库的 metadata 同时涵盖两条原始重复记录与折叠后的 C2 来源：

```json
{
  "source_file": "设备台账-V3.xlsx",
  "sheet_name": "浓度检测",
  "cell_range": "A2:D5",
  "block_type": "table"
}
```

向量模型不会返回下面这个占位字符串；这里只表示契约。假设这块装配出的 ID 为 `chunk-假设-01`，模型返回与它位置相同的 1536 维浮点数组后，内存中的一项相当于：

```text
EmbeddedChunk(
  chunkId = "chunk-假设-01",
  index = 0,
  content = 上面的“Sheet 标题 + 三条数据行 Markdown 表格”,
  embeddingText = 上面的“浓度检测 + 空行 + 三条键值行”,
  metadata = 上面的来源 JSON,
  embedding = [v0, v1, ..., v1535]  // 占位，不伪造实际模型数值
)
```

关系表以同一 ID 保存 `doc_id + content + embedding_text + metadata`；向量表以同一 ID 保存 `collection_name + content + 补入doc_id/chunk_index的metadata + [v0...v1535]`。这样 E 从向量候选拿到 chunk ID 后，才能映射到同一份展示证据和来源。

### 三个预算分支怎样发生

- **正常行：**先渲染“完整 headers + 当前行”的 Markdown，再渲染“Sheet 路径 + 非空键值”。两份 Java 字符长度都不超过 `maxChars`，且当前 group 未达到 `rowsPerChunk`，该行进入当前 group。
- **加下一行才超预算：**假设当前 group 已有行 R1，两份候选长度分别为 `M(R1)`、`E(R1)`；把 R2 加入后得到 `M(R1+R2)`、`E(R1+R2)`。只要后两者任一超过 `maxChars`，程序先把 R1 落块，再以 R2 开新组。R1/R2 都不从行中间截断，每个新块重新带 headers。
- **单个键值对本身过长：**假设某行只有“处理要求: <一段超过 1024 Java 字符的完整条款>”非空。完整宽行超预算后进入按列切片，但第一个原子键值对加入空片时不会触发“先落已有片”；最终它作为一片完整保留，`content` 和/或 `embedding_text` 仍可能超过 1024。如果同一 TableBlock 最终只有这一份草稿，它不会被标成 piece，`ChunkPacker` 仍按不可再拆的原子 section 保留；若该表还产出其他草稿，所有草稿才会带 piece 边界。两条路径都不会截短这个字段。

## 三、失败边界与历史结构证据

- 在索引替换开始前失败——空字节、不支持 MIME、解析异常、空分块、Embedding 请求或形状校验失败——不会先删数据库旧块。
- 在本地索引事务中失败，关系块与 PGVector 修改由 PostgreSQL 事务回滚；模型费用、MinerU 任务、图片资产和外部 ES/LightRAG 不在该回滚范围。当前代码没有在本内核中为这些副作用建立补偿事务。
- ES/LightRAG 启用后的同步错误会被吞掉并记录告警，内核仍可能返回成功；所以文档 SUCCESS 不等于所有可选外部索引都同步成功。反之，索引事务已提交而 B 后续状态更新失败时，也可能出现“索引已新、文档状态失败或未完成”。
- 历史改动记录对固定工作簿报告过：XLSX 块数 `123→79`、展示正文超过 1024 的块 `70→0`、最大长度 `12489→1019`、处理链额外制造的精确重复 `17→0`，并保留 5/5 固定解析锚点。这些数字只描述该文件、对应代码与配置下的块结构；不能外推为任意 Excel 都不超限，也不能写成回答准确率提高。完整检索和回答评测归 I。

## 四、重要事实修正

- `maxChars` 是 Java 字符长度目标，不是固定 token 窗口；关系表里的 `token_count` 是落库时另行统计的字段，不参与 `TableChunker` 当前分组判断。
- 当前 POI 内核路径默认一行表头。规范化器支持多行表头展平，但内核没有传入 `headerRows` 摄取参数。
- 去掉的是“原行为空、由合并展开后成为相同续行”的处理链重复；用户原始录入的两条相同记录会保留。
- 表格先由 `TableChunker` 分组并标记 piece，再由 `ChunkPacker` 合并允许合并的草稿，最后 `ChunkAssembler` 分 ID、补检索前缀。顺序不能倒置。
- 关系块删除当前是逻辑删除，PGVector 文档向量删除是物理删除；两者共享新 chunk ID 和本地事务，但外部索引不是 PostgreSQL 原子参与者。

## 五、源码反查入口

- [DefaultIngestionKernel.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/DefaultIngestionKernel.java)：五步顺序、空输入/空分块处理、阶段计时与返回对象。
- [ParserRegistry.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/registry/ParserRegistry.java)：MIME 与解析档的精确/通配/FAST 回落规则。
- [ExcelDocumentParser.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/excel/ExcelDocumentParser.java) 与 [ExcelTableNormalizer.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/excel/ExcelTableNormalizer.java)：可见 Sheet、单元格读取、合并区域、表头/数据行和来源范围。
- [TableChunker.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/blockaware/TableChunker.java)、[ChunkPacker.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/blockaware/ChunkPacker.java) 与 [ChunkAssembler.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/model/ChunkAssembler.java)：双文本预算、宽行切片、piece 边界、草稿合并和最终检索文本。
- [ChunkEmbeddingService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/embed/ChunkEmbeddingService.java) 与 [VectorTargetResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/VectorTargetResolver.java)：知识库指定模型、位置对齐、条数/空值/维度校验。
- [RelationalChunkSink.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/sink/RelationalChunkSink.java)、[VectorChunkSink.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/sink/VectorChunkSink.java) 与 [PgVectorStoreService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)：sink 顺序、删除旧块、关系记录和 PGVector SQL。
- [schema_pg.sql](../../../resources/database/schema_pg.sql)：`t_knowledge_chunk.embedding_text`、metadata JSONB 和 `t_knowledge_vector.embedding vector(1536)` 的当前物理结构。
