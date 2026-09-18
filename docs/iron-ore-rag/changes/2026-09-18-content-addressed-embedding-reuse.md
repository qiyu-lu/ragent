# 增量重建：内容寻址的 embedding 复用

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | `2026-09-18` |
| 所属阶段 | 秋招冲刺 W5：增量重建 |
| 状态 | 已实施；回归通过；X5 用模拟上游跑完，真实上游的那次待跑 |
| 分支 | `feat/llm-backend-hardening` |
| Git 提交 | 实现与迁移 `80125b0`；PostgreSQL 集成测试 `7d43998`；X5 命令与脚本 `2355966`；标签 `career-w5` |

## 改动目的与发现过程

文档改一行，重新入库时仍把整份文档的每个块重新送去向量化：更新慢，而且按 token 重复付费。只读核对（计划 E5）确认：

- 重新入库走 `ChunkIndexWriter.replaceDocument`，先删掉这份文档的全部块再插入新块；
- 块表已经保存了 `content_hash`，但没有任何地方用它来比对；
- 摄取侧只有一个向量化入口 `ChunkEmbeddingService`（普通入库、管道节点、单块重嵌入都经过它）。

## 方案取舍与选择理由

- **按内容寻址，不按文档寻址**：参考 LangChain 的 `CacheBackedEmbeddings` 与 Indexing API、LlamaIndex 的 IngestionPipeline 去重。键是（模型、维度、向量文本的 SHA-256），与块 ID、文档、知识库无关。所以同一份文档重新入库、上传新版本成为另一份文档、回退旧版本，都能命中。
- **键里的模型用解析后的“供应商:模型名”**，不用候选别名：别名 `qwen-emb-8b` 改指向另一个模型后，旧向量不能再用。和“embedding 不能跨模型降级”是同一条规则。维度也在键里，同一模型截成不同维度的向量互不复用。
- **哈希的对象是向量文本**（章节路径 + 正文），不是展示文本：真正送给模型的是向量文本。
- **缓存写入不和块写入放在同一个事务**（计划原写“同库同事务”，实施时改了）：缓存只省钱，不影响正确性。同一段文本在同一个模型下的向量不依赖它属于哪份文档。如果放进块写入的事务，块写入失败就会连已经付过费的向量一起回滚，重试时还要再付一次。
- **缓存故障时降级**：查表或写表出错只记 warn，这一批改走上游，入库不失败。
- **存 `REAL[]`，不用 pgvector 的 `vector`**：缓存不需要相似度索引；`REAL[]` 按 float4 二进制原样往返，维度也不固定，不同维度的模型可以共用一张表。建表时加了 `cardinality(embedding) = dimension` 约束。
- **容量**：写入后按 `last_used` 淘汰超出上限的最旧条目（默认 10 万条，1536 维一条约 6 KB）。命中也刷新 `last_used`。

## 实现与调用链

```text
DefaultIngestionKernel.run → ChunkEmbeddingService.embedWithStats(chunks, target)
  ① 每块 sha256(embeddingText)；解析 target.embeddingModel → "siliconflow:Qwen/Qwen3-Embedding-8B"
  ② EmbeddingCache.lookup(model, dimension, 哈希集合)：UPDATE … SET last_used … RETURNING，命中同时刷新
  ③ 未命中的按首次出现顺序去重（同一文档里重复的表头只送一次）→ embedBatch → 逐条校验维度
  ④ store：INSERT … ON CONFLICT DO UPDATE SET last_used（先写入的向量保留）→ 超出上限按 last_used 淘汰
  ⑤ 按原顺序组装 EmbeddedChunk；计数 (块数, 命中, 上送文本) 随 IngestionOutcome 返回
→ ChunkIndexWriter.replaceDocument：一个事务内对块表与 pgvector 先删后插
→ 分块日志 t_knowledge_document_chunk_log 新增 embed_cache_hits / embed_cache_misses
```

配置 `rag.ingestion.embedding-cache.enabled / max-entries`。迁移 `resources/database/upgrades/v1.1.0/260918_03_embedding_cache.sql`，同步修改 `schema_pg.sql`。离线语料导入（`ResearchCorpusImporter`）有自己的续跑机制，继续用不带缓存的构造函数。

**版本切换的可见性（计划第 3 项）**：核对结果是已经满足，不需要改。应用只有一个数据源；`RelationalChunkSink`（MyBatis，`SpringManagedTransactionFactory`）与 `PgVectorStoreService`（`JdbcTemplate`）都加入 `ChunkIndexWriter` 开启的事务。`ChunkReplaceAtomicityPostgresIT` 证明了这一点：让第三个落点在两张表都已经删旧插新、还没提交时停住，另一个连接读到的仍是旧版本的 3 块；放行提交后读到新版本的 2 块；如果第三个落点抛异常，两张表都回滚到旧版本。

## 验证与效果

- 回归 `bash scripts/validate-agentic-research-p7.sh`：Python 41/41，Java 13 + 260（W4 后为 13 + 247）。结构校验 `validate-agentic-research-p2-database.sh`：新建库与升级库（迁移连跑两次）的表结构一致，覆盖新表和分块日志的新列。
- 单元测试 `ChunkEmbeddingServiceCacheTest`（7 项）：重复入库时上游一次都不调；只重嵌入变化的块，并保持块的原有顺序；同一批次里的重复文本只送一次；不同模型或不同维度不命中，别名指向同一模型、或来自另一个知识库时命中；缓存故障时退回上游；上游返回的维度不对时报错，且不写入缓存；离线构造函数不碰缓存。
- 集成测试 `EmbeddingCachePostgresIT`（4 项，真实 PostgreSQL）：极端浮点值按位往返；键的隔离；先写者保留；超出上限时淘汰最久未用的条目；接真实表后第二次入库上游 0 次，改一段只上送那一段。
- **X5**（`scripts/career-x5.sh`，离线命令 `IngestionReuseCommand` 走真实的解析→分块→向量化→事务写入链路，新建临时库、缓存从空表开始；运行目录 `local-data/agentic-research/runs/career_X5_stub_v1/`，汇总 `eval/agentic-research/manifests/career-x5-2026-09-18.json`）。**以下数字基于模拟上游**：块数和命中数只由文本决定，换成真实上游也一样；token 是模拟值，耗时不代表真实供应商。

| 步骤 | 块数 | 命中 | 上送文本 | 上游调用 | 模拟 token |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1.3 关闭缓存（对照） | 79 | 0 | 79 | 3 | 14,379 |
| V1.2 首次入库 | 79 | 0 | 79 | 3 | 14,352 |
| V1.2 原样重新入库 | 79 | 79 | 0 | **0** | 0 |
| 同一文档升级到 V1.3 | 79 | 77 | **2** | 1 | 428 |
| 回退到 V1.2 | 79 | 79 | 0 | **0** | 0 |

V1.2→V1.3 需要重嵌入的块占 2/79 = 2.5%，模拟 token 是全量重嵌入的 3.0%。每一步之后，块表和向量表里这份文档的行数都等于块数。模拟上游一共收到 7 次 embedding 请求，与各步“上游调用”之和一致。真实上游的调用数、token 和耗时要等 `X5_UPSTREAM=real` 跑完后补上。

## 限制与停止状态

- **复用率取决于块边界是否稳定**：表格分块按行顺序累加预算、分组。在一张表中间插入一行，这张表后面各块的边界都会移动，这些块全部失效。可以改用内容定义的分块边界来修复，但没有做。X5 只有一对真实版本，没有覆盖“插入行”的情况。
- 块表和向量表仍然是整份删除再插入，块 ID 每次重新生成。这次省下的只是向量化的调用，数据库写入量没有减少。块表是逻辑删除，每次重新入库都会留下一批 `deleted = 1` 的旧行，这是既有行为，未处理。
- 事务只覆盖 PostgreSQL。启用 ES 关键词、LightRAG 图谱或 Milvus 时，这些后端的先删后插不在事务里，失败时可能半新半旧，这是既有行为。
- Excel 内嵌图片的描述来自视觉模型，每次生成的文字可能不同，这些块基本不会命中缓存。X5 关闭了图片解析。
- 淘汰只在写入后做一次整表计数。条目远超 10 万时，应该换成定时任务或按时间分区。
- 缓存不带知识库信息（W4 已经清点过）。能拿到命中向量的调用方，手里本来就有这段原文，所以命中与否不泄露任何库里的内容。

## 数据兼容与回滚

迁移只做加法，可以重复执行。2026-09-18 已在开发库 `ragent` 上执行；其他已有的库要先执行一次 `260918_03`，再运行新代码（离线语料库不经过缓存，可以不执行）：少了分块日志的新列，写日志会报错；少了缓存表，查表会报错，但只会降级为全部走上游。只想关闭缓存可以设 `rag.ingestion.embedding-cache.enabled=false`，代码不用回滚。

```bash
git revert 2355966 7d43998 80125b0
```

执行前先检查当前分支、HEAD 和工作区。回滚后缓存表和两列可以保留，旧代码不会读它们。
