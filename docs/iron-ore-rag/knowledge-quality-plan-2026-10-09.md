# 知识入库质量与检索改进计划

> 状态：**2026-10-09 修订版，已确认开工**，取代同日草案。修订依据：同日审阅（全角数字计数错误、解析修法改为按实验结果定、不做图谱、合并为三个阶段）。**2026-10-10 三个阶段与收尾完成**，结果见状态文件、三份改动说明与 §10。
> **执行会话只读两份文件：本文件 + [`knowledge-quality-status.md`](knowledge-quality-status.md)。** 不读冲刺计划、瘦身计划和旧执行记录。本文件里的路径、行号、数字都来自 2026-10-09，动手前用 `grep` 复核。
> 目标：把入库质量、知识治理、召回排序各做成一项改进，每项都要有清楚的机制、前后对照和可复现的数字。面向九号知识工程岗（转岗，不求全覆盖 JD）。
> 与旧工作的关系：8 月的 B0/C-final/D0/D1/D2 数字照旧可以引用（改动说明在 Git 历史 `3317b82^:docs/iron-ore-rag/changes/`）。本计划用新评测集，**新旧数字不放在同一张表里比**。

## 0. 原则

1. **先评测、后改动**：拿到基线和基线波动之前，不改解析和检索代码。
2. **调参只用调参集**。测试集只在每项改动定稿后跑一轮（一轮 = 3 次重复），跑完不再回头改这一项的参数。
3. **门槛在阶段 1 基线之后、改动之前写进状态文件**，写定后不改。没过门槛的改动保持默认关闭，负结果照样写改动说明。
4. **锚点取自原文，不取自解析结果**。否则解析丢掉的内容在评测里看不出来。
5. 数字遵守"写机制，不写效果"：进简历的数字必须带实验臂、样本量和条件。
6. 不在本计划内的事项见 §8，不顺手扩展。
7. **一个阶段一个会话。** 会话结束时提交代码与文档、更新状态文件、给出用户要跑的命令。下一个会话先读上一阶段的结果、写它的改动说明，再开始本阶段。
8. **数字计数一律按 Unicode 数字类别**（含全角 ０-９），比较前先做 NFKC 归一化。草案的"三份标准没有数字"就是只数了 ASCII 造成的误判。

## 1. 调查结论（2026-10-09，含审阅更正）

### 1.1 PDF 入库丢数字

`ragent_eval_current` 中《铁矿石 硅含量的测定 重量法》（GB/T 6730.10—2014）的切片，与 `pdftotext -layout` 抽出的原文对照：

| 位置 | 原文 | 入库切片（MinerU，`ocr: false`，`enable-formula: true`） |
| --- | --- | --- |
| 7.4.1.1 碱融法 | 温度控制在 400 ℃±20 ℃，放置 1min | 温度控制在 ,放置 。 |
| 6.2.1 | 硅含量高于 10%（质量分数） | 硅含量高于 (质量分数) |
| 8.2.3 修约规则 | 最左一位数字小于 5 时 | 最左一位数字小于 时 |
| 附录 | 12 个国家的 28 个实验室对 5 个铁矿石 | 个国家的 个实验室 |
| 条款号 | 4.1 / 6.2.1 / 7.4.1.1 | 41 / 621 / 7411 |

六份标准 PDF 的文字层情况（`pdftotext` + `pdffonts`，数字按 Unicode 计数；"入库数字"是评测库中该文档全部切片的数字字符数，含公式与页码）：

| 文件 | 页数 | 文字层字符 | 文字层数字 | 入库数字 | 判断 |
| --- | ---: | ---: | ---: | ---: | --- |
| 铁矿石+硅含量的测定+重量法 | 16 | 11,689 | 1,252 半角 | 615 | 文字层可用，入库丢约一半 |
| 铁矿石 取样和制样方法 | 76 | 72,423 | 7,991 半角 | 未入库 | 文字层可用 |
| 铁矿石+全铁含量的测定+三氯化钛还原法 | 16 | 11,222 | 1,450 全角，另 513 个全角字母 | 未入库 | 文字层可用，需全角归一化 |
| 钛铁矿…第 2 部分 全铁量 | 6 | 3,089 | 505 全角 | 未入库 | 同上 |
| 钛铁矿…第 3 部分 氧化亚铁量 | 6 | 2,978 | 458 全角 | 426 半角 | 保留约 93%，MinerU 已转半角 |
| 硫铁矿和硫精矿中硅含量的测定 | 8 | 874 | 68 | 未入库 | 扫描件，8 页 19 张图 |

另外：切片中混有水印行（"中国标准出版社授权北京万方数据股份有限公司……推广使用""东北大学"）；`pdftotext` 默认模式会把 E-BZ 字体的数字拆成一行一个字符，`-layout` 正常，**文字层基准统一用 `pdftotext -layout`**；Java 侧若用 PDFBox 做基准，先核对它在硅含量上也能抽到约 1,250 个数字。

丢失环节的假设，阶段 1 用实验判定：

- **H1 公式识别吃掉数字（主要假设）**。硅含量 16 块中 8 块含 `$`，同一文档里有的数值以 LaTeX 出现（`$\rho = 1.19 g/mL$`），有的整段消失；文字层本身数字完整。当前 `enable-formula: true`（`application.yaml:308`）。
- **H2 撤回**。草案认为 MinerU 丢弃没有 Unicode 映射的字形，依据是三份 PDF 文字层数字为 0，实为全角。全铁那份 `pdffonts` 显示 `uni=yes`，PUA 字符只有 17 个。
- **H3 自己的解包或分块丢内容**。`MinerUResultUnpacker.java:158` 只取 zip 里第一个 `.md`，解析和分块代码没有处理 `$` 的逻辑。可能性低，阶段 1 对比 MinerU 原始 `full.md` 与入库切片的数字数即可排除。

### 1.2 评测资产现状

- 8 月的 24 题与锚点随 `local-data/eval/` 在 2026-09-19 删除。
- 仍保留：PostgreSQL 中的 `ragent_eval_baseline`、`ragent_eval_current`（后者 101 块：调研表 79、硅含量 16、氧化亚铁 6）；`local-data/backups/` 下的两个 dump；原始资料在 `local-data/source/`（6 份标准 PDF 加调研表 V1.2；V1.3-demo 和课题 PPT 不进评测）。
- 只在 Git 历史里：评测接口在 `e2e7e5c` 删除（`rag/eval/EvalController.java` 等）；脚本在 `0a874a5` 删除（`eval/iron-ore/evalkit.py`、`verify_dataset.py`、`run_retrieval.py`、`audit_chunks.py`、`prepare_kb.py`、`compare_retrieval_repeats.py`、`restore_eval_database.py` 等）。
  - 旧 `EvalController` 依赖 `IntentResolver`、`SubQuestionIntent`，这个包已不存在；现在 `RetrievalEngine.retrieve(List<String> subQuestions)` 很简单。**重写一个百行控制器，不恢复旧的。**
- `ragent.eval.enabled: true`（`application.yaml:49`）同时被 `IdempotentSubmitAspect.java:67` 读取，为 true 时跳过问答的防重锁。本计划不改它。
- 离线入库入口 `IngestionReuseCommand` 硬编码只接受 `ragent_x5_*` 库名（`:108`）且要求向量缓存为空。开发用 RocketMQ nameserver 当前 unhealthy。
- 工具：Tika 3.2.3 自带 PDFBox 在 classpath；Python 侧 PyMuPDF 1.24.11（模块名 `pymupdf`，不是 `fitz`）；`pdftotext`、`pdffonts`、`pdftoppm` 可用；`~/.m2` 没有中文分词库。

### 1.3 检索链路现状

- 通道只剩 `VECTOR`（`SearchChannelType.java`），`WEB_SEARCH` 关闭。`FusionPostProcessor` 是 RRF：`score = Σ weight / (k + rank)`，`rrf-k: 20`，单通道时跳过融合。
- 处理器顺序：Deduplication(1) → Fusion(5) → CandidatePoolLimit(6) → MetadataEnrichment(8) → Rerank(10) → `RequestLevelChunkSelector`。预算：`recall-budget: 20`、`rerank-candidate-limit: 40`、`default-top-k: 10`。
- **没有任何分数阈值**。重排模型 `qwen3-rerank`（Bailian text-rerank，分数 0 到 1），另有 `rerank-noop` 候选。**重排分是否写回 `RetrievedChunk.score` 需在阶段 1 确认**，目前只看到 `FusionPostProcessor:117` 写分。
- `RetrievalScope`（`channel/RetrievalScope.java:29`）只有 `targetCollections`；`VectorSearchChannel.retrieveOver` 已接受 `documentIds` 参数，只是没人传。
- `t_knowledge_vector` 列为 `id, collection_name, content, metadata, embedding`。**`embedding_text`（章节路径 + 正文）在 `t_knowledge_chunk.embedding_text` 列**，`t_knowledge_document` 有 `document_version` 和 `ingestion_spec` 列。
- 应用代码没有 NFKC 或全角半角归一化，目前靠 MinerU 代劳。
- PostgreSQL 镜像只有 `pg_trgm` 和 `vector` 扩展，没有 zhparser、pg_jieba。**`ts_rank` / `ts_rank_cd` 不含 IDF。**
- MinerU v4 API 的 `file-urls/batch` 接受顶层 `model_version`（`pipeline` 默认、`vlm` 官方推荐、`MinerU-HTML`）和每文件 `is_ocr`；`MinerUClient.java:83-92` 发了 `enable_formula`、`enable_table`、`language`、`is_ocr`，**没发 `model_version`**。

### 1.4 RAGFlow 借鉴（本地 `~/open-source-game/ragflow`，`5cc35c229`，Go 实现）

| 机制 | 出处 | 本计划 |
| --- | --- | --- |
| 文字层逐框质检：框内乱码（PUA、不可映射）≥ 50% 或字体编码乱码时，只对该框 OCR | `internal/deepdoc/parser/pdf/parser_ocr.go:624-636` | 阶段 2 闸门的扫描件与坏字体判定按文档做 |
| 混合打分 `sim = (1−w)·词项分 + w·向量分`，w 默认 0.3；最终分低于 0.2 丢弃 | `internal/service/nlp/retrieval.go:118`、`:454`、`:108` | 阶段 3 的阈值臂；blend 臂可选 |
| 有重排模型时重排分替换向量分，词项分保留 | `internal/service/nlp/reranker.go:196` | 阶段 3 blend 臂（可选） |
| 词项分字段加权：标题 ×2、人工关键词 ×5、生成问题 ×6 | `reranker.go:893` | 阶段 3 把文档名放进索引文本；生成问题不做 |
| 文档元数据过滤到 doc_id 集合；知识库 pagerank 加到分数 | `retrieval.go:1007 GetFilters`、`internal/entity/dataset.go:118` | 阶段 2 文档元数据 + 查询侧匹配加权 |
| 同义词词典、块级人工关键词 | `internal/service/nlp/synonym.go` | 阶段 2 术语表，同时作阶段 3 分词词典 |
| 标签集两阶段打标（词项覆盖 ≥ 55% 匹配样例，否则 LLM 从标签集选），标签分 ×10 进排序 | `internal/ingestion/component/extractor_tag.go`、`internal/service/tag.go` | 不做，文档级元数据已够区分混淆题（§8） |
| 图谱检索：问题抽实体、实体与关系按相似度 0.3 召回、并社区报告 | `internal/service/graph/`（构建侧未移植，只剩 cgo NER） | 不做（§8） |
| 按编号层级识别标题后切片 | `internal/ingestion/component/chunker/title.go`、`hierarchy.go` | 不做，已按条款切且章节路径进了 `embedding_text` |

## 2. Git 记录方式

| 步骤 | 约定 |
| --- | --- |
| 分支 | 当前 HEAD `a07e04d` 就是 `research/iron-ore-rag` 的 tip，直接 `git switch -c feat/knowledge-quality`。完成后 ff 合并回 `research/iron-ore-rag`；是否同步到 `feat/llm-backend-hardening` 由用户定 |
| 首个提交 | 本计划 + 状态文件，`git tag kq-v0-baseline` 打在这个提交上 |
| 提交粒度 | 每个会话至少一个提交，消息前缀 `kq(s1):`、`kq(s2):`、`kq(s3):`、`kq(close):`。只 `git add -- <明确路径>`。**`docs/iron-ore-rag/notes/面试问答.md` 有用户未提交的修改，任何提交都不要带上** |
| 结果回填 | 用户跑完后，下个会话把汇总数写进状态文件和改动说明，与该会话的代码一起提交 |
| 阶段标签 | `kq-s1`、`kq-s2`、`kq-s3`，打在"该阶段结果已回填"的提交上 |
| 数据 | 题集、原文锚点、数值事实清单、MinerU 原始输出、运行结果、库快照放在 Git 忽略的 `local-data/kq-eval/`。仓库只放 Schema、示例、脚本和 manifest（manifest 只记哈希与汇总） |
| 评测库 | `ragent_eval_kq_s1`（基线与阶段 3）、`ragent_eval_kq_s2`（闸门后重新入库）。不覆盖 `ragent_eval_baseline`、`ragent_eval_current` |
| 回退 | 单项 `git revert`。代码回退不会恢复索引，需从对应快照恢复评测库 |

## 3. 每个提交的回归

```
./mvnw -o -pl bootstrap -am -DskipTests clean package   # 阶段 3 加分词依赖后，第一次去掉 -o 联网拉取
bash scripts/validate-agentic-research-p7.sh             # 基线：Python 53/53、Java 13 + 211（2026-09-20 记录，开工时复跑）
bash scripts/validate-agentic-research-p2-database.sh
python3 -m unittest discover -s eval/kq/tests            # 阶段 1 起
```

## 4. 阶段 1：评测通道、题集、基线与解析定位（1 个会话 + 用户运行）

### 4.1 会话工作

1. **评测接口**：新写 `rag/eval/EvalController`、`EvalReplayRequest`、`EvalResponse`、`EvalProperties` 及测试。`POST /rag/eval/replay` 接受原问题（首次：改写并把子问题落盘）或子问题列表（回放），返回每个子问题的候选块：id、doc_id、文档名、通道分、重排分、是否最终入选。用固定评测用户登录以满足 W4 的知识库权限。不恢复 `PooledEvalController` 和 context selection。顺手确认重排分写回了 `RetrievedChunk.score`，没有就补上。
2. **脚本 `eval/kq/`**：从 `0a874a5^:eval/iron-ore/` 取 `evalkit.py`、`verify_dataset.py`、`run_retrieval.py`、`audit_chunks.py`、`compare_retrieval_repeats.py` 的可用部分，去掉意图切换。新写：
   - `pdf_textlayer.py`：`pdftotext -layout` 抽文字层，Unicode 数字计数，NFKC。
   - `mineru_probe.py`：直接调 MinerU v4 API（`file-urls/batch` → PUT 上传 → 轮询 `extract-results/batch/{batch_id}`），参数 `is_ocr`、`enable_formula`、`model_version`，保存 zip 和 `full.md` 到 `local-data/kq-eval/mineru-raw/<文件>/<参数>/`，输出数字计数、空槽数、耗时、zip 哈希。
   - `parse_metrics.py`：解析层四指标（§4.4），输入可以是 `full.md` 或评测库切片。
   - `tests/`：归一化、锚点校验、指标计算的单元测试；归一化测试用例放 `eval/kq/normalization_cases.json`，阶段 2 的 Java 归一化函数读同一份用例。
3. **评测库与语料**：建 `ragent_eval_kq_s1`（`schema_pg.sql` + `init_data_pg.sql`）和对应的 RustFS 桶；只导入 7 份资料；图片解析关闭；分块参数 `maxChars=1024`、`overlapChars=128`、`rowsPerChunk=50`、`toleranceFactor=3`（走 `ChunkerSettings`）。入库前先 `docker restart` RocketMQ nameserver 并确认消费者正常；仍不行则用 `IngestionReuseCommand`，把库名正则放宽到 `ragent_(x5|eval)_`，并先核对它经过 MinerU 解析。
4. **题集与数值事实清单**（§4.3）：Claude 从原文起草，用户逐条核对。
5. **冒烟**：1 题走通回放，确认候选块里有重排分。
6. 会话结束：提交；状态文件写明用户要跑的命令。

### 4.2 用户要跑的

- 核对题集和清单（约 2 小时），之后冻结、哈希写进 manifest。
- 入库 7 份；基线 `S1-base` 在调参集、测试集各跑 3 次（回放）。
- **解析定位实验，分步做，每步看完再决定下一步**（命令由会话写在状态文件里，每次记录参数、时间、zip 哈希）：
  - a. 硅含量，`pipeline`，`is_ocr=false`，`enable_formula=false`。数字若回到约 1,250，H1 成立。
  - b. 硫铁矿 `is_ocr=true`；全铁三氯化钛默认参数（看全角是否转半角、数字保留多少）。
  - c. 硅含量和硫铁矿各一次 `model_version=vlm`。
  - d. 只有 a 到 c 说不清时，才跑 6 份 × {is_ocr} × {enable_formula} 共 24 次（约 512 页）。
- 每步同时做 H3 排除：`full.md` 的数字数与入库切片的数字数应接近。

### 4.3 题集

规模约 120 题，按"文档 × 题型"分层后对半分成**调参集**和**测试集**。

| 题型 | 约数 | 说明 |
| --- | ---: | --- |
| 数值参数：温度、时间、浓度、质量、粒度、限值、精密度 | 45 | 直接检验阶段 2；每份标准都要覆盖 |
| 步骤与条件：试剂、仪器、操作顺序、适用范围 | 25 | |
| 调研表：工序、设备、场景 | 20 | |
| 近领域混淆 | 15 | 全铁（钛铁矿重铬酸钾法 vs 铁矿石三氯化钛法）、硅（铁矿石 vs 硫铁矿）、氧化亚铁 vs 全铁、取样 vs 制样。阶段 2 元数据加权的主要检验题 |
| 不可回答：领域内问题，资料里没有 | 15 | 如铁矿石磷含量的测定 |

- **措辞**：至少一半题目用口语或换词的说法，避免与原文字面重合。
- **数值事实清单**：每份标准约 20 条（如"7.4.1.1 碱融法：400 ℃±20 ℃，1 min"），记录文档、页码、条款和原文写法。文字层可用的取自 `pdftotext -layout`；硫铁矿渲染页面图片（`pdftoppm`）后人工读取。这份清单既是覆盖率的分母，也是数值题锚点的来源。
- **锚点**：每题 1～3 个，取自原文或清单。`verify_dataset.py` 校验每个锚点都能在参考原文中定位。
- 不做入库时生成问题（§8），所以不存在泄题。

### 4.4 指标

**解析层**（确定性，不调在线模型）：

- **数值事实覆盖率** = 清单中能在入库切片里找到的事实数 / 清单总数。匹配前两侧归一化：NFKC、去空白、`℃`/`°C` 等单位统一；再做子串匹配。**分两列报告**：按切片原文匹配（"丢失"），和把 LaTeX 去壳后匹配（"变形"）。按文档和总体报告。
- **数字保留率** = 切片 Unicode 数字字符数 / 文字层 Unicode 数字字符数。只看量级。
- **空槽数**：正则匹配"在 ,""(见 )""式()""小于 时"一类模式的次数，按每千字计。
- **噪声行数**：水印、页眉页脚模式的出现次数。

**检索层**（阶段 1 基线、阶段 2、阶段 3 都用）：Hit@5、锚点召回、MRR、上下文精度（含任一锚点的块数 / 返回块数，按题平均，沿用 8 月口径）、文档召回、平均返回块数。不可回答题只报平均返回块数和最高重排分，作为阶段 3 阈值的依据。**分题型报告，但门槛只按总体和数值题判定**：混淆题和不可回答题对半后每集只有 7～8 题，撑不起门槛。

### 4.5 基线与门槛

- `S1-base`：当前代码和当前解析配置，在调参集、测试集上各跑 3 次。报告每个指标的均值与极差，测试集结果封存。
- **调参集上的基线极差就是门槛下限**：一项改动的提升小于它，记为"未证实"。
- 阶段 2 会话开始时，把阶段 2、3 的门槛写进状态文件。

## 5. 阶段 2：解析质量闸门与知识治理（1 个会话 + 用户运行）

会话开始：读基线与解析实验结果，写门槛，按下表定闸门设计，再动代码。

| 实验结果 | 闸门设计 |
| --- | --- |
| a 成立（关公式即恢复） | 文字层可用的文档关公式；闸门只做审计与告警，不自动回退 |
| a 不成立但 c 成立 | 文字层可用的文档走 `vlm` |
| 硫铁矿 OCR 或 vlm 可用 | 扫描件判定后走该组合 |
| 都不理想 | 只做审计，把不合格文档标记"需人工复核"，负结果写改动说明 |

### 5.1 闸门 `ParseQualityAuditor`（PDF 解析之后、分块之前）

1. **预判**：PDFBox 抽文字层。每页字符数低于阈值判扫描件；乱码比例（PUA 加不可映射字符）高于阈值判坏字体；否则文字层可用。按判定选解析参数（公式开关、`is_ocr`、`model_version`），`MinerUClient` 补发 `model_version`。
2. **审计**：解析结果与文字层两侧 NFKC 后，比较数字保留率和空槽数。
3. **回退**：不合格就用备选组合重解析一次，保留得分高的结果。审计结果（保留率、空槽、所用参数）写入文档元数据，来源面板可见。
4. 阈值在调参集涉及的文档上标定，写进配置，默认值写进改动说明。

### 5.2 归一化与清洗

- 入库统一 NFKC（全角转半角）、单位（`℃`/`°C`）、空白，放在解析之后分块之前的一个函数里，用 `eval/kq/normalization_cases.json` 做测试。
- 水印、页眉页脚：只去掉在多数页面重复出现的整行（频次规则）。
- 条款号丢点（"7411"）随解析参数一起观察，**不写猜测性的修复规则**。

### 5.3 文档元数据与术语表（知识治理）

- **文档元数据**：标准号、发布年、代替的旧标准号、检测对象（铁矿石 / 钛铁矿精矿 / 硫铁矿）、被测组分（硅 / 全铁 / 氧化亚铁 / 取样制样）、方法类型（重量法 / 滴定法 / 还原法）。正则从文字层抽取，7 份由用户确认。落 `t_knowledge_document` 新 JSONB 列 `doc_metadata`，升级脚本放 `resources/database/upgrades/v1.1.0/`，同步改 `schema_pg.sql`。调研表：检测对象 = 铁矿石，组分 = 工序与设备。
- **时效性规则**：同一标准号出现多个版本时新版本优先，旧版本在来源面板标"已被代替"。本语料没有旧版本，只实现规则与展示，不评测。
- **术语表 `resources/kq/terms.csv`**：术语、同义写法、类别（检测对象 / 组分 / 方法 / 试剂 / 仪器 / 标准号）。Claude 从 7 份资料起草，用户核对。三处用它：查询侧元数据匹配、阶段 3 分词词典、来源面板展示。
- **检索侧 `MetadataBoostPostProcessor`**（order 11，Rerank 之后）：查询命中术语表里的检测对象或组分时，对应文档的块 `final = rerank + β·match`，β 在调参集上取 {0.1, 0.2, 0.3}。默认关闭，配置开关。

### 5.4 验证与产出

- 解析层四指标前后对比。MinerU 结果会漂移（8 月观察到 14 → 16 块），同一组合解析两次，记录时间和哈希。
- 用户重新入库到 `ragent_eval_kq_s2`，跑两臂：`S2-gate`（闸门 + 归一化 + 清洗，boost 关）、`S2-gate+boost(β)`，调参集各 3 次；过门槛的配置在测试集跑一轮。
- 改动说明两份（由阶段 3 会话写）：`changes/2026-10-xx-parse-quality-gate.md`、`changes/2026-10-xx-document-metadata-governance.md`。沿用 8 月模板：目的、发现、根因、方案取舍、实施、验证、效果、限制、回滚。

## 6. 阶段 3：中文全文通道与融合（1 个会话 + 用户运行）

会话开始：读阶段 2 结果，写两份改动说明，回填状态文件，打 `kq-s2`。

- **通道**：新增 `SearchChannelType.FULL_TEXT` 和 `PgFullTextSearchChannel`，与向量通道共用 `RetrievalScope`。`KnowledgeAccessMatrixPostgresIT` 补一个全文通道用例。
- **分词在应用侧**：`jieba-analysis`，用户词典 = 阶段 2 的术语表。标准号、化学式、带单位的数值先用正则整体切出再分词。索引和查询两侧用同一个归一化函数。依赖要联网拉一次（用户）。
- **存储**：`t_knowledge_chunk` 加 `content_tsv tsvector` 和 GIN 索引，索引文本 = 文档名 + `embedding_text`，写入用 `to_tsvector('simple', <分词后空格连接>)`。升级脚本 + `schema_pg.sql`。存量回填用一次性命令。`RetrievalScope` 的 collection 经 `t_knowledge_base.collection_name` 映射到 `kb_id`。
- **打分**：用 `@@` 取候选（上限 `recall-budget` 的 3 倍），应用侧算 BM25，文档频率和平均长度按 `kb_id` 存在 `t_kq_term_stats`，入库时更新。时间不够则退回 `ts_rank_cd`，改动说明里写明它没有 IDF。
- **臂**（调参集上比较，只选一种进测试集）：

| 臂 | 做法 | 要回答的问题 |
| --- | --- | --- |
| `S3-rrf` | 现有 RRF，全文通道权重取 {0.5, 1.0} | 词项信号只影响谁进候选池，够不够 |
| `S3-thr` | 在 `S3-rrf` 上加阈值：重排分绝对值 {0.1, 0.2, 0.3}，或低于首名 x 倍丢弃 | 能否提高上下文精度、减少不可回答题的返回块 |
| `S3-blend`（可选） | RAGFlow 式：最终分 = α·归一化 BM25 + (1−α)·重排分，α 取 {0.3, 0.5, 0.7} | 词项信号参与最终排序是否更好 |

- **验证**：分题型报告，重点看数值题和混淆题的 Hit@5 与 MRR；阈值臂看不可回答题的返回块数。调参集各 3 次，测试集一轮。
- **收尾会话**（短）：读结果，写 `changes/2026-10-xx-hybrid-full-text-retrieval.md`，回填状态文件，打 `kq-s3`，写 §10 简历映射，ff 合并。

## 7. 节奏与分工

| 阶段 | 会话做 | 用户做 | 估时 |
| --- | --- | --- | --- |
| 1 | 评测接口与脚本；建库入库；起草题集与清单；冒烟 | 核对题集和清单（约 2 小时）；入库；基线 6 次；解析实验 a→c（d 视情况） | 1 天 |
| 2 | 写门槛与改法；闸门、归一化、清洗；元数据、术语表、boost | 核对元数据与术语表（约 0.5 小时）；重新入库；两臂评测 | 1 天 |
| 3 | 阶段 2 改动说明；全文通道、分词、迁移、BM25、三臂 | 联网拉依赖；回填；评测 | 1 天 |
| 收尾 | 阶段 3 改动说明；状态；简历映射；合并 | 无 | 0.5 天 |

- **用户需准备**：`MINERU_API_KEY` 和额度（OCR 与 vlm 更慢）；Embedding 与重排模型的 key。
- **长时间运行**：超过几分钟的解析、入库、评测，由用户在终端启动。会话把命令写进状态文件，下个会话读结果。

## 8. 不做及理由

- **知识图谱**：7 份资料，真正需要多跳的问题不到 10 道；全文通道加文档元数据已能回答"哪些标准用到重铬酸钾"一类问题；LLM 抽取两三百个实体需用户逐条核对约 2 小时，预计检索指标上测不出差异。术语表和元数据是实体层的雏形，面试讲 schema 设计够用。Go 版 RAGFlow 也没移植构建侧。
- **标签集自动打标**：文档级元数据已覆盖混淆题的区分，块级标签收益小。
- **入库时生成关键词或问题**：题目也由 LLM 起草，有泄题风险。
- **父子分段**：会改变上下文精度的口径。
- **DeepDoc、知识编译器、RAPTOR**：工作量与收益不匹配。
- **回答层盲评**：8 月做过 48 条，不重复。

## 9. 已定决定（2026-10-09）

1. 开工，三个阶段加收尾，一会话一阶段。
2. 分支 `feat/knowledge-quality`，基线标签 `kq-v0-baseline`。
3. 分词 `jieba-analysis` + 术语表词典，不用 HanLP。
4. 不做图谱，不做盲评。
5. 阶段 3 的 blend 臂可选，时间不够就不跑。

## 10. 简历映射（2026-10-10 收尾时按结果写定）

原则照旧：数字必须带实验臂、样本量和条件；没过门槛的只写机制。原来的写法模板保留在 Git 历史（`kq-s3^`）。

- **阶段 2 闸门（过门槛，测试集复现，已默认开启）**：解析质量闸门。可写："发现标准 PDF 入库时 MinerU 丢掉'数字 + 单位'（切片成了'温度控制在 ,放置 。'）；入库时用 PDFBox 文字层审计严格空槽与数字保留率，不合格的文档把 OCR 开关取反重解析一次。7 份资料 141 条数值事实的覆盖率 69.5% → 92.9%；数值题 MRR 调参集 0.470 → 0.674（22 题，3 次回放），测试集 0.400 → 0.698（23 题）。"面试时补充：闸门、归一化、去页面家具在同一臂里一起评测，检索层的提升分不到各项头上；阈值是在这 6 份 PDF 上标定的。
- **阶段 2 元数据治理（加权未过门槛）**：只写机制："文档元数据（标准号、代替、检测对象、组分、方法）入库抽取加人工确认，读时计算时效性，来源面板展示；60 条领域术语表在元数据抽取、查询识别、分词词典三处复用。"不写数字。负结果作面试素材：查询侧加权救不回不在候选池里的块，还会把问调研表、却点名"全铁"的题压到标准后面。
- **阶段 3 中文全文 + 向量混合检索（数值题 MRR 恰好达到门槛，测试集复现）**：可写："PostgreSQL 上加中文全文通道：jieba 加术语表在应用侧分词，标准号、化学式、带单位的数值整体切出，tsvector 存带位置的词项，应用侧按库统计算 BM25；RRF、重排分阈值、分数融合三种用法按预先写定的规则在调参集选型，测试集一次验证。选中的配置（全文 + 阈值 0.3）在测试集 52 道可答题（3 次回放）上总体 MRR 0.518 → 0.581、Hit@5 0.622 → 0.712，7 道不可答题平均返回块数 9.9 → 5.6。"不写"数值题 MRR 显著提升"：调参集只提升 1/22，正好卡在门槛上，其中一部分来自基线的向量超时。面试时补充：全文通道补上了向量召回漏掉的答案块（调参集 2 道、测试集 7 道）；测试集的提升里也有基线向量超时的成分。
- **负结果（只作面试素材）**：分数融合三个 α 都没过：qwen3-rerank 给头部几块的分挤在 0.95～0.98，按单题最大值归一化的 BM25 实际上决定了头部顺序。重排分的绝对值分不开可答与不可答（不可答题最高也到 0.96），所以阈值只能少返回一些块，不能当拒答信号。
