# 统一研究工作流的数据准备

这里实现 P2 的离线转换、校验与真实幂等摄取，以及 P3 的原生工具单研究运行联调。转换不调用模型；`import_corpus.py --execute` 复用项目已有分块、向量化和索引落点，再用知识搜索/原文读取服务回查。`smoke_research.py --execute` 调用真实研究模型，进度与失败记录见[执行记录](../../docs/iron-ore-rag/agentic-research-execution-log.md)。

## 来源与环境

- QASPER v0.3：使用已下载的 HF Parquet train/validation 分片；[数据卡](https://huggingface.co/datasets/allenai/qasper)与[官方加载脚本](https://huggingface.co/datasets/allenai/qasper/blob/main/qasper.py)。署名 Dasigi 等（2021），CC BY 4.0。
- MuSiQue v1.0：使用作者发布的 Ans 或 Full JSONL；[作者仓库及许可](https://github.com/StonyBrookNLP/musique)、[官方格式转换脚本](https://github.com/StonyBrookNLP/musique/blob/main/raw_data_to_official_format.py)。署名 Trivedi 等（2022），CC BY 4.0。每次只选择一种 variant；Full 已包含可回答与不可回答版本，不能再把 Ans 加进样本数。

支持 Python 3.8 及以上；Parquet 需要 [requirements.txt](requirements.txt) 中的 `pyarrow==17.0.0`，其余为标准库。复用已有 cs_evalkit 的分批 Parquet 读取和文件 SHA-256，不修改历史评测工具。源文件逐批/逐行读取，SQLite 在临时目录完成去重与排序，避免载入整份 MuSiQue。

默认数据根目录为仓库的 `local-data/agentic-research/`；可用 `AGENTIC_DATA_ROOT` 或 `--data-root` 覆盖。QASPER 文件位于 `raw/qasper/{train,validation,test}/*.parquet`，MuSiQue 文件位于 `raw/musique/data/musique_{ans,full}_v1.0_{train,dev,test}.jsonl`。原始大文件与生成产物继续留在 Git 忽略目录。

## 转换与校验

在仓库根目录运行，输出目录必须不存在；需要重跑时指定新目录，不覆盖已记录的快照。

```bash
python3 eval/agentic-research/prepare_dataset.py --dataset qasper --split train --output local-data/agentic-research/prepared/research-data-v1/qasper-train
python3 eval/agentic-research/prepare_dataset.py --dataset qasper --split validation --output local-data/agentic-research/prepared/research-data-v1/qasper-validation
python3 eval/agentic-research/prepare_dataset.py --dataset musique --split train --variant full --retrieval-mode distractor --output local-data/agentic-research/prepared/research-data-v1/musique-train
python3 eval/agentic-research/prepare_dataset.py --dataset musique --split dev --variant full --retrieval-mode distractor --output local-data/agentic-research/prepared/research-data-v1/musique-dev
python3 eval/agentic-research/verify_prepared.py --prepared local-data/agentic-research/prepared/research-data-v1/musique-dev --data-root local-data/agentic-research
python3 -m unittest discover -s eval/agentic-research/tests -v
```

校验需对每个输出目录分别执行。包含 `--data-root` 时同时验证原始文件的大小和 SHA-256；转换开始和结束也会检查源文件是否变化。转换使用临时目录，成功后才发布完整输出；失败清理自己创建的临时内容，保留原始文件和已有快照。校验错误以非零状态退出，不能把不完整转换当作通过。

默认种子为 `20260917`；smoke 为 20 题，regression 为 200 题，可通过 `--seed`、`--smoke`、`--regression` 调整。对全部问题按 SHA256(seed, question ID) 排序抽样，与源文件行序无关；smoke 是 regression 的前缀，不是独立数据集。小型夹具不足指定题数时取全部并记录实际数。QASPER test 和 MuSiQue test 需要显式 `--allow-test`，仅在最终配置确定后转换；MuSiQue 无答案字段的行保留 `gold=null`，不伪造不可回答标签。本批没有转换真实 test。

## P3 单研究运行联调

研究运行器接入 AgentScope Java 2.0.1，使用原生 `search_knowledge`、`read_source`、`ask_user` 和 `finish_research`。创建、查询、补充输入、取消接口位于 `/rag/research/runs`；事件接口目前返回 `after`/`limit` 分页 JSON，SSE 在 P6 接续。创建时必须提供当前用户的 `conversationId`、唯一 `clientRequestId`、目标、REPORT/PLAN 以及可用知识库范围。知识库仍采用项目现有全局共享规则，运行和会话按用户归属隔离。

本阶段返回 `state.researchResult` 中的发现、已读证据 ID、缺口和冲突；`artifact` 仍为空。COMPLETED 在 P3 只表示研究阶段形成经过引用身份检查的摘要，完整报告、计划 JSON 和卡片属于 P5/P6。资料没有提供的要求不能补造。等待输入通过 `state.question` 展示，回复携带当前 `revision` 与 `answer`；原研究范围保持不变，需要更换范围时使用新的请求。

运行器默认最多 16 次模型调用、24 次工具调用、300 秒累计活动时长，预留 2 次最终生成额度；最后两次研究调用限定为原生 finish_research。人工等待不计入活动时长，恢复保留已消耗额度。预算/超时退出时，有已读证据则保留为 PARTIAL，无证据则 FAILED。取消关闭本地 SDK/HTTP 订阅并阻止迟到写回，供应商是否停止远端计算保持 unknown。运行与模型并发分别限制为 2；本阶段采用单 JVM 执行，重启将失去执行者的 QUEUED/RUNNING 标为 INTERRUPTED，不恢复中间 token。

```bash
# 随机 PostgreSQL 隔离库与本地 HTTP 桩，无付费模型调用
bash scripts/validate-agentic-research-p3.sh

# 只生成 5 个请求与调用清单，不访问数据库或 API；run-dir 必须不存在
python3 eval/agentic-research/smoke_research.py --run-dir local-data/agentic-research/runs/<new-id>

# 真实供应商小规模联调，会消耗模型与 query embedding 额度
python3 eval/agentic-research/smoke_research.py --run-dir local-data/agentic-research/runs/<new-id> --execute
```

真实联调复用已导入的 `research_corpus_v1`，仅查询语料，将运行、证据和事件写入随机 `research_p3_*` 库并在结束时清理。参数可覆盖 prepared 路径、容器名和语料库名；使用 `--case waiting-and-resume` 等可只复测一条路径。凭证优先来自环境变量，也可复用既有 IDEA 配置，在子进程环境中传递，日志不输出其值。Java 命令复用现有向量检索；本阶段 smoke 不启用 rerank，不运行 A/B/C 或 EM/F1。

每批保留 `run.json`、`job.json`、`predictions.jsonl`、`traces.jsonl`、`usage.jsonl`、`embedding-usage.jsonl`、`java.log` 和 `summary.json`。请求只读取 gold-free `queries.jsonl`；不读取评分用 questions。源码/配置/模板 hash、实际 model ID、请求类型、工具参数/结果及未知 usage 分开记录。退出码 0 表示命令完成；每个样例是否形成闭环必须检查 predictions 的状态，不能把失败样例从报告中删除。

## 产物与标注隔离

| 文件 | 内容 | 后续消费者 |
| --- | --- | --- |
| corpus.jsonl | 每行一个真实来源段落，正文/hash、文档身份及来源 metadata | 摄取工具 |
| questions.jsonl | 问题、原始候选映射、答案/支持证据/分解等 gold | 离线评测器，整行不得传给模型 |
| queries.jsonl | 问题 ID、问题文字、明确的检索范围和模式，无 gold | 未来运行器的请求准备工具 |
| questions.smoke.jsonl / queries.smoke.jsonl | 同一固定 20 题的评测与请求投影 | smoke |
| questions.regression.jsonl / queries.regression.jsonl | 同一固定 200 题的评测与请求投影 | regression |
| manifest.json | 输入大小/hash、版本/来源/署名、转换源码 hash、精确条数、样本身份及全部产物 hash | 重放与审计 |

Python 校验器用明确字段集合检查 corpus 和 queries；即使更新了文件指纹，额外 `answer`、`is_supporting` 等字段也会被拒绝。测试还验证纯 gold 变化不改变语料、请求投影或抽样身份。这证明程序层面的字段隔离，不能代替后续提示词/运行器接线检查。

三个记录契约分别为 `research-corpus-v1`、`research-questions-v1` 和 `research-queries-v1`；manifest 为 `research-prepared-v1`。完整字段及校验见 [datasetkit.py](datasetkit.py) 和 [verify_prepared.py](verify_prepared.py)。离线 ID 使用完整 SHA-256，身份由来源内容与原始定位计算；它们是来源映射 ID，不能直接作为现有数据库的 20 字符业务主键或 ResearchBrief 的真实 docId。

QASPER corpus 保留原 paper ID、原段落下标、原 section 下标和名称（作为单元素 section_path），抽象字段保留 source_field=abstract。相同文本位于不同段落时不合并。空段落跳过但不重排原下标；下载文件只有图片/表格 caption 与路径，不把 caption 或图片路径编造为表格正文。每个问答保留所有标注者的不可回答、yes/no、抽取式及自由答案，不把分歧压成一个标签。Gold evidence 只按空白归一后的完整段落匹配，并保留所有匹配位置；无法匹配的文本标为 unresolved，不做模糊猜测。

MuSiQue corpus 按原始标题与精确正文去重，同标题不同正文仍为不同记录。每条资料均为 AVAILABLE_EXCERPT，单独保存为来源文档，不拼成完整文章。作者的 paragraph idx 是问题局部下标，保存在 questions 的 source_candidates 映射中；语料的 source_paragraph_id 是标题/内容计算的稳定身份。Full 的两种版本共享原问题 ID，转换 ID 加入各自候选上下文，保留成对行；它不依赖 answerable、is_supporting 或答案标签。

## MuSiQue 的检索范围

`--retrieval-mode pooled-context` 是命令默认值，queries 的 document_ids 为空，表示搜索该 split 的整个去重候选池；这必须标为 pooled-context，不能称为 fullwiki。Full 的不可回答标签针对原来每题提供的候选上下文，整个池可能补回缺失支持，因此不能在 pooled-context 上直接沿用这些标签报告不可回答准确率。

`--retrieval-mode distractor` 在相同来源池上生成每题原候选的 document_ids。后续导入完成后，把这些离线身份映射到真实数据库 docId，再作为服务端范围保存。它不利用 gold 支持标签选文档，保持原任务的资料边界；本批完整 train/dev 转换使用此模式。未来评分仍需明确 variant、范围、是否排除 unresolved gold 及成对问题口径，不能把同一个 source_question_id 的 Full 两行当作独立问题组。

固定 profile 按记录抽样，不保证两个版本都入选；Full 的 group sufficiency 等成对指标需要另行固定完整分组样本，不能直接在本批 200 行子集上冒用官方完整分组成绩。

## 真实摄取

[import_corpus.py](import_corpus.py) 校验转换产物，按 source_field / section_index / paragraph_index 聚合和排序 QASPER 论文；MuSiQue 每个段落独立成文档。仅 documents.jsonl 进入 Java 摄取命令，questions 的答案/支持/分解标注不进入正文、metadata 或模型请求。

先构建后端，再准备输入。省略 `--execute` 只生成输入，不创建数据库或调用供应商：

```bash
./mvnw -o -pl bootstrap -am -DskipTests package
python3 eval/agentic-research/import_corpus.py --prepared local-data/agentic-research/prepared/research-data-v1/qasper-validation --prepared local-data/agentic-research/prepared/research-data-v1/musique-dev --run-dir local-data/agentic-research/runs/p2-smoke --profile smoke
```

加 `--execute` 执行真实摄取；`--profile full` 导入所列 split 的全部语料。默认只创建/复用本机独立 `research_corpus_v1` 数据库，保留结果供 P3 使用；不加载业务种子、不启动 Web/MQ 服务、不运行答案生成或质量评分。`--prepared` 可重复指定四份训练/开发产物，真实 test 仍不使用。PostgreSQL 容器默认 `ragent-iron-ore-dev-postgres-1`。API key 只从环境/IDEA 的 RagentApplication 配置读取，沿用已有提取器，不输出或落盘凭证。

[ResearchCorpusImporter](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/research/service/ResearchCorpusImporter.java) 使用已有 ParagraphChunker、ChunkAssembler、ChunkEmbeddingService、RelationalChunkSink 和 PgVectorStoreService。每个来源段落独立分块，不经 Markdown 标题猜测或跨段打包；源 hash/标题、paper/paragraph/section 位置进入关系库和向量 metadata。长段落切成多块仍共享原始 source_paragraph_id。section_index 参与邻块边界，相同章节名不能跨节点展开。

[260917_04_research_corpus.sql](../../resources/database/upgrades/v1.1.0/260917_04_research_corpus.sql) 显式增量创建来源文档映射；新库使用 schema_pg.sql，不假定 Flyway 自动发现脚本。映射与实际 doc/chunk/vector 在短事务内提交；embedding 在事务外执行。已提交的同内容/配置重试复用原 docId/chunkId，不重新向量化；内容、metadata、预算或模型变化需新建语料库。单批失败回滚，命令按批重试；重启使用相同目录和命令，依据 DB 映射继续，进度文件仅供展示。

默认 QASPER 每批 8 篇，MuSiQue 每批 256 条；可用 `--batch-documents` 调整。每次供应商请求最多 32 条，最多四个 split 并行；单 split 最多 8 个独立批次及 16 个 embedding 请求在途。固定模型为 SiliconFlow Qwen/Qwen3-Embedding-8B，默认 1536 维，没有模型回退。已有数据库必须维度一致。

每个 split 保存 input/job、documents、mapping、progress/complete、traces、usage 和 source-probes。usage 逐请求保留实际 token、状态与耗时，未提供 usage 的失败标为 unknown；批次重试产生的额外请求也保留。mapping 记录离线来源文档/段落到实际 docId/chunkId/序号/hash；启动时重新导出已提交映射。导入结束用前三个 gold-free query 验证实际 scoped search 与原文/邻块读取，并检查越范围与不存在证据错误。这是链路联调，不是三题答案质量成绩。

2026-09-17 已完成 QASPER train/validation 与 MuSiQue Full train/dev 全量导入：共 122,620 文档、182,768 来源段落、182,896 实际块/向量。每个 split 的三条真实 scoped search/read 和拒绝检查通过，最终库存、正文 hash、来源映射、标注隔离及标准摄取配置审计通过。真实 test 未转换/入库，未运行答案生成或质量评分。

转换条数和指纹见[转换清单](manifests/prepared-development-2026-09-17.json)；实际主键、库存、源码/产物指纹及 usage 摘要见[导入清单](manifests/imported-development-2026-09-17.json)，失败和验证边界见[验证报告](../../docs/iron-ore-rag/agentic-research-validation-report.md)。大文件和原始日志留在忽略目录。
