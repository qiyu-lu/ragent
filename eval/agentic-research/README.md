# 统一研究工作流的数据准备

这里实现 P2 的离线转换与校验。运行转换不创建知识库、不生成向量、不调用模型，也不产生问答成绩。真实摄取的幂等导入、usage 记录和研究运行器仍待接续，进度见[执行记录](../../docs/iron-ore-rag/agentic-research-execution-log.md)。

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

## 下一步摄取接线

QASPER 应按 document_id 聚合段落，按 source_field / section_index / paragraph_index 还原顺序；不要按 corpus.jsonl 的 hash 排序拼正文。重复章节名要保留 section_index，邻接检查不能只凭相同标题跨节点。MuSiQue 每个去重段落单独作为可用片段导入。

现有 [KnowledgeDocumentUploadRequest](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java) 没有任意来源 metadata 参数；直接上传 Markdown 不证明 paper/paragraph 身份已进入每个 chunk。后续需让受控摄取保留这些 metadata，沿真实分块、embedding、关系表及向量持久化链执行；保留离线 source/document ID → 实际 docId/chunkId 的映射与首批状态。正文 ID/hash 不是已生成向量的证明。

接续实现幂等批次、失败重试、进度与供应商 usage，先导入小样例，再做真实批量；检索/read_source 用实际入库来源复核。已有 Java/PG 合成测试保持独立，P3 的 SDK/运行器/取消机制不属于本转换工具。

本批实际转换条数和指纹见[紧凑清单](manifests/prepared-development-2026-09-17.json)，成功/失败日志与原始生成物路径见[验证报告](../../docs/iron-ore-rag/agentic-research-validation-report.md)。
