# 统一研究工作流的数据、评测与复测

这里实现离线转换、真实幂等摄取、原生研究与统一产物，以及 P7 固定 A/B/C 对照和应用原文核对。转换不调用模型；`import_corpus.py --execute` 复用项目分块、向量化和索引落点。真实评测的配置、逐题输出、trace、usage 和失败保存在独立批次，见执行记录与交接说明。

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

## 原生研究运行联调（P3/P4）

当前在线研究通过 `research.main-model-id`、`worker-model-id`、`finalization-model-id` 选择已注册模型，默认主/最终为 `research-max`（`qwen3.7-max-2026-05-20`），worker 为 `research-flash`（`qwen3.7-flash-2026-07-15`）。可通过 `RESEARCH_MAIN_MODEL_ID`、`RESEARCH_WORKER_MODEL_ID`、`RESEARCH_FINALIZATION_MODEL_ID` 覆盖注册别名；角色配置为空时沿用 `research.model-id`。三个角色共享原有限额和取消机制，错误注册项不会静默换模型。

固定评测使用配置中的 `models_by_role`，填写实际供应商模型 ID，运行时严格绑定注册项并保存到 `runtime.json/modelsByRole`。没有此字段的 p7/r5 配置会把所有角色明确固定为旧 `model_id`，不继承在线默认值。新增 [career.json](configs/career.json) 保持旧预算、thinking=false、rerank=false，使用 Max/Flash/Max；A/B/C 的最终生成统一为 Max，C 的 worker 为 Flash，混合模型下的 B/C 差异同时包含模型分配差异，不能作为纯架构因果结论。`summary.json/resources/model_usage_by_role` 按实际角色、模型汇总请求、已知 token、失败和 unknown；混合模型不套用旧单模型价格表，默认金额估算关闭。

```bash
# 首批固定 32 题/96 ABC 项；集中验收使用同一角色配置，禁止混入旧 R5 v2
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/career.json --case-ids eval/agentic-research/manifests/research-career-case-ids-2026-09-18.json --run-dir local-data/agentic-research/runs/<new-career-regression-id> --execute
```

研究运行器接入 AgentScope Java 2.0.1，主 Agent 使用原生 `search_knowledge`、`read_source`、`conduct_research`、`ask_user` 和 `finish_research`，worker 只注册检索、阅读、结束三个工具。创建、查询、补充输入、取消接口位于 `/rag/research/runs`。P3/P4 当时提供 `after`/`limit` 分页 JSON 和必填 conversationId；当前 P5/P6 已增加 SSE、可选会话与完整产物，契约见下节。知识库仍采用项目现有全局共享规则，运行和会话按用户归属隔离。

`--phase p3/p4` 联调返回 `state.researchResult` 中的发现、已读证据 ID、缺口和冲突，保留 artifact 为空的历史阶段边界。COMPLETED 在这些联调中只表示研究摘要形成。资料没有提供的要求不能补造。等待输入通过 `state.question` 展示，回复携带当前 `revision` 与 `answer`；原研究范围保持不变，需要更换范围时使用新的请求。

运行器默认最多 16 次模型调用、24 次工具调用、300 秒累计活动时长，预留 2 次最终生成额度；最后两次研究调用限定为原生 finish_research。人工等待不计入活动时长，恢复保留已消耗额度。预算/超时退出时，有已读证据则保留为 PARTIAL，无证据则 FAILED。失败 worker 的运行原因写入 executionIssues，原文缺口写入 gaps，状态为 PARTIAL；主 Agent 预算/超时退出仍保留成功 worker 的 findings。取消关闭父子 SDK/HTTP 订阅并阻止迟到写回，供应商是否停止远端计算保持 unknown。运行与模型并发分别限制为 2；本阶段采用单 JVM 执行，重启将失去执行者的 QUEUED/RUNNING 标为 INTERRUPTED，不恢复中间 token。

```bash
# 随机 PostgreSQL 隔离库与本地 HTTP 桩，无付费模型调用
bash scripts/validate-agentic-research-p3.sh
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

## P7 固定 A/B/C 质量对照

`evaluate_research.py` 使用同一份 gold-free query、允许范围、模型和检索配置，默认执行三个模式。A 对原问题做一次 scoped search、固定 top 10 后阅读，再使用共享生成器；这是复用项目组件的控制变量基线，不代表普通聊天的改写、意图、历史、MCP 和候选回退全链路。B 不注册委派工具，C 使用在线主 Agent 的按需委派逻辑；B/C 共用相同的全局研究额度，实际 worker 数单独报告。A 的 toolCalls 统计固定检索阶段，命中块读取是该阶段的正文装配；B/C 的 toolCalls 是原生工具调用数，两者不能直接当作相同粒度的数据库 I/O 次数。

[固定配置](configs/p7.json)保存 `qwen3.7-flash-2026-07-15`、并发 2、PGVector、rerank 关闭、召回 20 / 候选 40，以及技术调用上限。Java 命令校验实际应用模型和技术上限是否匹配，不能静默换模型。短答案格式只附加到最终生成提示，不混入研究 brief；问题与用户约束在 A/B/C 中相同。QASPER 使用 paper scope；MuSiQue Full dev 使用原 distractor scope。标签只在模型执行结束后的离线评分中解析，执行前仅保存文件指纹。

```bash
# 默认仅冻结请求、数据/源码指纹与配置，不调用 API
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<new-smoke-id> --profile smoke
# 20 + 20 个固定问题 × A/B/C = 120 个任务
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<new-smoke-id> --profile smoke --execute
# 200 + 200 个固定问题 × A/B/C = 1200 个任务
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<new-regression-id> --profile regression --execute
# full 默认只准备请求；追加 --execute 才消费额度
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<new-full-id> --profile full
# 原批次被中断：仅补未记录的任务，已记录的 FAILED/PARTIAL 同样保留
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<existing-id> --profile regression --resume --execute
# 只重新计算该批次已有输出的离线分数
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<existing-id> --profile regression --resume --score-only
# 原失败题复测：case-ids.json 是预先固定的唯一题目 ID 数组，另开新批次保留失败
python3 eval/agentic-research/evaluate_research.py --run-dir local-data/agentic-research/runs/<new-replay-id> --profile regression --case-ids <case-ids.json> --execute
```

恢复和离线重算必须使用相同数据、源码和配置；代码变化时创建新批次，或使用原批次 `source-snapshot` 的隔离检出。每批包含 `run.json`、`requests.json`、源码快照、逐模式 attempts、原始预测/trace/usage、规范预测、逐题分数、`summary.json` 和 `report.md`。失败与超时进入分母和耗时统计，未知 usage 保持 unknown，未执行数量单列。

按用户 2026-09-18 的调用约定，默认直接执行已授权实验，省略价格查询、金额估算和费用确认。`estimate_generation_cost=false` 时不计算金额，Java 的 `maxCostCny=null` 不触发金额门槛；实际 token、请求、耗时、失败和 unknown usage 仍留档。仅显式启用金额估算并设置正数上限时才使用可选费用拦截。历史批次的配置与报告保留原样。full 的任务规模与实际执行数量继续分开记录，提供命令不代表已经运行全量。

QASPER 答案按作者归一化 token F1、多标注取最大值；证据使用准备语料中的段落文本身份，单独多标注取最大值，未映射的标注仍留在分母，图表 FLOAT 标注忽略。块与邻块经 source_paragraph_id 映射为段落选择，选中一个块不代表模型看到整个段落；实际交付正文、extent 与截断字段保留。报告另给 EM 和逐题可回答性诊断，它们不是作者论文的新增官方指标。MuSiQue 的答案 EM/F1 与支持证据 F1 只对 answerable 记录计算，可回答性按全部记录计算。固定抽样不是完整成对记录，不报告作者 paired sufficiency。引用/已读段落覆盖都不能证明语义支持。

## 回归

```bash
# 程序行为与既有链回归：随机隔离数据库、本地 HTTP 桩，不调用付费模型
bash scripts/validate-agentic-research-p7.sh
bash scripts/validate-agentic-research-p2-database.sh
```

## 提示缓存与调用延迟报告（秋招 W1）

`cache_report.py` 只读已冻结批次的 `attempts/*/usage.jsonl`、`traces.jsonl` 与 `predictions.jsonl`，不调用 API。按批次标签、模式、角色输出调用数、输入、命中、命中率及单次调用命中率分布、显式缓存写入量，以及按供应商折扣折算的计费输入（输入 token 当量，不是金额）；延迟给出按 Agent 内调用序号分组的 P50/P95。调用耗时以 `MODEL_STARTED` 到 `MODEL_ENDED` 的事件时间为准，新旧批次口径一致；首 token 时延只有 W1 之后的台账有（`firstTokenMs`）。折扣默认取[百炼上下文缓存文档](https://help.aliyun.com/zh/model-studio/context-cache)（隐式命中 20%、显式命中 10%、显式写入 125%），可用参数覆盖。

```bash
python3 eval/agentic-research/cache_report.py --run before=local-data/agentic-research/runs/<before> --run after=local-data/agentic-research/runs/<after> --out <summary.json>
```
