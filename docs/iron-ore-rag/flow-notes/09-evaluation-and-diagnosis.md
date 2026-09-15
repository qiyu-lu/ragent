# 09｜评测与诊断：判断修改改善了哪一层，以及为什么不默认启用

这条流程位于“代码或配置已经形成候选方案”和“决定是否改变产品默认行为”之间。它接收固定文档、问题、答案要点、证据锚点、索引与配置，依次检查原证据是否可检索、候选是否召回、最终上下文是否保留、回答是否正确、引用是否支持；最后把结果交给预先写好的继续/停止门槛，而不是看到某个数字上升就宣布整体问答变好。

本文复述的是仓库截至当前 checkout 已保存的执行过程和历史证据。本轮只静态核对源码、脚本、配置和变更记录，没有读取 Git 忽略目录里的业务原文，没有启动服务或模型，也没有重跑 B0～Hybrid。下文每个历史数字都绑定当时的数据、配置、日期或提交及指标定义，不代表生产收益。

## 1. 先从一个产品决策开始：多子问题去重后不足 TopK，要不要回填

- 我这里先把问题写成可否决的决策：一个问题被改写成多个子问题后，各子问题按配额取块，再按 chunk 身份去重，最终可能不足请求级 `TopK=10`。是否应继续从各子问题候选尾部轮流回填，直到补满 10 块？
  - 要比较的是同一批题、同一索引、同一组子问题和相同模型配置下，公平回填 `off` 与 `on` 的差异。只改变 `rag.search.request-level-refill-enabled`，不能同时增大 TopK、重新摄取或重新调用改写模型。
  - 固定项包括 24 题数据集及语料 SHA、D1 setup manifest、D1 已保存的逐题 `subIntents`、同一 D1 数据库、`intent=off`、`ocr=off`、`TopK=10`、`concurrency=1`。固定子问题隔离了改写漂移，但 query embedding 与 rerank 仍是真实在线调用，所以 off/on 各顺序跑三次并比较中位数。
  - 先检查工程门槛：最终唯一块不能超过 10；`on` 在候选足够时应把空位补满；返回的块数、诊断计数和开关标签必须互相一致。工程门槛只回答“回填是否按设计执行”。
  - 再检查质量门槛：目标题必须在同一次配对中由 off 未命中变为 on 命中，至少 `2/3` 次且该题 Anchor Recall 不下降；总体 Anchor Recall 中位数至少 `+0.01`；文档召回和 Hit@5 不下降；Context Precision 与路由纯度的中位数降幅分别不能超过 `0.02`。P95 只记录，不参与当时放行。
  - 继续条件是工程门槛和所有质量门槛同时通过；任何关键护栏失败就停止本轮调参、保持开关关闭。这样“填满了”不会被偷换成“更相关”或“答案更好”。
- 2026-08-14 的 D2 固定回放说明了这个决策怎样落地。
  - 代码实现提交为 `dcd9222`；六份服务端报告来自 `fa1bc3f001d0926e0b1a9ba4dea954fc8ecf9c70`；后续 `d2f0b1f` 收紧了配对恢复判定，`37fc9d0` 只处理公开输出脱敏，二者不改变已运行的检索行为。
  - `off` 三次都是 167 个最终唯一块、73 个空位；`on` 三次都是 240 个最终唯一块、0 个空位，19/24 道原本留空的问题都补满。工程目标成立。
  - 21 道有检索 gold 的题上，中位数 Anchor Recall `0.841 → 0.865`，但 Hit@5 `0.905 → 0.762`、Context Precision `0.254 → 0.200`、路由纯度 `0.915 → 0.811`；目标题的 off 三次本来都已命中，配对恢复是 `0/3`。最终 gate 为 fail，默认保持 `false`。
  - 这就是本文要保留的核心判断：空位补满是容量层成功，质量门槛未过是产品决策层停止；两个结论可以同时成立。

## 2. 准备一次工业评测：把原文、问题、答案和证据接成一条可核验链

- 先冻结评测所回答的问题，而不是先启动服务。
  - 历史工业集在 2026-08-13 建立，评测工具提交为 `318e1f3`。可核验协议是 3 份文档、24 道题、15 个解析锚点：`6` 道 XLSX、`6` 道原生文本 PDF、`6` 道扫描/异常文本 PDF、`3` 道跨领域近似问题、`3` 道不可回答问题。
  - 其中前 18 道是单文档正向题，另外 3 道跨领域题也有参考证据，因此检索质量汇总使用 21 道可回答/近领域题；只有前 18 道设置 `intent_scored` 和 `routing_scored`。3 道不可回答题没有 `reference_docs` 或 `reference_anchors`，是否正确拒答必须到回答层人工判断。
  - 旧 `eval/iron-ore/README.md` 的“每份文档 6 道可回答、3 道近领域、3 道不可回答”会算成 36 道，与校验器不闭合。这里以 `verify_dataset.py` 锁死的 family 分布和 2026-08-13 历史记录为准；本轮没有打开忽略目录中的原始题集补造分类。
- 每一题用一条 JSONL 记录把“问什么、正确答案需要什么、证据在哪里、哪些说法禁止出现”接起来。
  - `id` 是稳定题号；`tier` 区分 direct、hard、scope trap、unanswerable；`family` 表示 XLSX、两类 PDF、跨领域或负例。
  - `question` 是实际送入检索/回答入口的文本；`answerable` 决定后续使用正向四字段还是拒答两字段判分。
  - `reference_docs` 保存去扩展名后的业务文档标识，和 `/rag/eval` 返回的 `retrievedDocIds` 对齐；它不是数据库雪花主键。
  - `reference_anchors` 是应该在检索块中出现的稳定短语，用于自动定位证据；`expected_facts` 是人工答案必须覆盖的事实要点；`forbidden_claims` 是出现即不能严格通过的越界结论。锚点命中和答案正确因此不会混为一个字段。
  - `expected_kbs`、`expected_intent_ids` 只给需要评分的路由题使用。`expected_intent_ids` 填接口返回的意图 code，而不是意图表主键。
- 文档清单把题目引用继续接回原文件。
  - `corpus-v1.json` 保存知识库定义、3 份文档的逻辑 ID、归属 KB、实际路径和 SHA-256。`verify_dataset.py` 先检查文件存在且哈希一致，再验证每个题目引用的文档和知识库都在清单内。
  - 如果允许读取原始源文件，校验器还会抽取 XLSX/PDF 文本，检查每个题目锚点确实存在于它声明的参考文档；`--skip-source-extraction` 只检查 schema 与哈希，不能冒充完成了原文定位。
  - 15 个解析锚点来自独立的 `parse-anchors-v1.jsonl`，每份文档恰好 5 个。它们回答“原文件中的关键短语是否被解析进任何块”，不与某一题的答案要点相混。
- 接着固定配置和索引状态。
  - `prepare_kb.py` 只复用名称、collection 完全匹配且文档数为 0 的知识库；已有非空评测库会被拒绝，不会为了方便覆盖。
  - 上传前再次校验 3 份源文件 SHA。实际摄取规格写入 setup manifest：`parseProfile=fast`、`maxChars=1024`、`overlapChars=128`、`rowsPerChunk=50`、`toleranceFactor=3`。这里的 1024 是字符预算，不是 CS-DEV 的本地 token 预算。
  - 脚本按文档上传、触发 `/chunk`、轮询到 `success`，再保存服务地址、语料 SHA、知识库 ID/collection、文档 ID、块数、摄取规格和完成状态。失败或超时会终止准备，不会把半成品索引当完整实验输入。
  - B0 与当前版使用不同数据库、Redis DB、MQ 后缀和对象桶；OCR 变化要从 seed 重新入库，意图开关只切专用评测意图。归档再记录 checkout commit/dirty 状态、配置与结果 SHA、数据库计数和可选 dump。
- 在跑检索前，必须单独确认“原证据是否已经变成可检索块”。
  - `audit_chunks.py` 从 setup manifest 取得本轮文档 ID，通过分页接口读取实际入库块和最近一次 chunk log；随后对每个解析锚点做 NFKC、横线/波浪号统一并移除空白和少量 Markdown 标记后的包含匹配；它不做大小写折叠。
  - 输出为每份文档的 `anchor_recovered/anchor_total`、每个锚点命中的块序号、块数、正文字符均值/P95/最大值、超目标/超容忍数量、规范化后的精确重复块数，以及 extract/chunk/embed/persist 等摄取耗时。
  - 一个原文事实若没有进入任何实际块，后面的“召回失败”没有意义；应先记作解析或映射不可用。反过来，锚点 5/5 只证明这些短语可检索，不证明题目能召回，更不证明模型会答对。

## 3. 从脚本到服务端采集：候选、最终证据、回答和错误怎样落盘

### 3.1 普通工业检索：运行到最终上下文就停止

- `run_retrieval.py` 读取冻结 JSONL、corpus manifest 和可选 setup manifest，根据 `--family`/`--id` 选择题目；报告已存在时直接拒绝覆盖。
  - 普通模式登录后逐题调用 `GET /rag/eval?question=...`；D2 固定回放则调用 `POST /rag/eval/replay`，请求体是原问题和已保存的 `subQuestions`。
  - 固定回放先校验数据集、语料、setup 三个 SHA，要求源报告无失败、题号与问题文本完全匹配、每题子问题非空、已 trim、无重复；还要求 `concurrency=1`、refill 模式和重复编号齐全。
- 服务端 `EvalController` 只在 `ragent.eval.enabled=true` 时注册。当前主 `application.yaml` 明确写的是 `true`，所以不能照类注释把当前配置说成默认关闭。
  - 全局拦截器要求登录，但普通 `/rag/eval` 本身没有 `admin` 角色、专用数据库名或唯一 collection 检查；它的隔离强度低于 pooled 入口。
  - 普通入口先用 `QueryRewriteService.rewriteWithSplit(question, [])` 做真实改写，再由 `IntentResolver` 产生子问题及意图；replay 入口完全绕过在线改写，只把校验后的原问题和子问题构成 `RewriteResult`。
  - 两条分支随后都调用产品 `RetrievalEngine`。它并行执行各子问题检索，汇合后由产品 `RequestLevelChunkSelector` 或默认 legacy 前缀逻辑形成 `RetrievalContext.effectiveKbChunks()`。
  - `EvalController` 从这份请求级最终块列表按 chunk ID 再去重，保留选择顺序；它返回一一对应的 `retrievedChunkIds`、`retrievedContexts`、逐块 collection/score/sheet/cell range，并通过 `chunkId → 内部 docId → doc_name 去扩展名` 得到逐块文档 ID 和首次出现去重后的文档列表。
  - 它还返回 `subIntents`、各子问题最高意图叶子、请求级选择诊断和服务端 `latencyMs`。这里没有生成回答；纯检索实验到 JSON 响应落盘并自动算分就结束。
- 客户端为每个成功题保存题目元数据、`wall_ms`、自动 `score` 和完整 `raw_response`；传输、HTTP、业务包装或回放一致性错误进入 `failures`。
  - 当前普通脚本的汇总只对成功的 `details` 计算，失败不会自动作为零分进入自动指标；但报告保留 `selection.n` 和 `failures`，进程以非零退出。引用此类历史结果时必须同时说明失败数。D2 比较器更严格：六份报告有任何失败就拒绝比较。

### 3.2 需要回答时：创建独立会话并完整收集 SSE

- 只有协议要求评价最终回答时才运行 `run_answers.py`。历史主实验只对 B0 和 C-final 各跑 24 题；D0、D1、D2、CS-POOL/LIVE 和 Hybrid 的主比较没有因此自动生成答案。
  - 脚本按题顺序调用 `/rag/v3/chat?question=...&deepThinking=false`，每题由聊天接口建立独立会话语境，避免上一题历史污染下一题。
  - SSE 解析器保存每一个 `{event, data}`。`message` 事件中 `type=response` 的 `delta` 按收到顺序拼成答案，`type=think` 单独拼成思考文本；`reject` 的 delta 也进入可见答案；`finish` 或 `cancel` 保存终态 payload。
  - `run_answers.py` 还要求拼接答案非空且事件名中出现 `done`，然后保存 `finish.sources`、`messageStatus`、墙钟耗时和完整 `raw_events`。SSE 中途断开时，异常会说明断开前已收到多少事件，失败题进入 `failures`，不会用半截答案参与盲评。
  - 回答报告保存数据集/setup SHA、服务端提交、intent/OCR/deep-thinking、三段检索预算和模型 ID。它没有直接保存 `/rag/eval` 的候选阶段列表；要做“候选到答案”的逐题诊断，应把同一配置的检索报告、回答报告和最终来源按题号对齐。

### 3.3 一题记录里真正需要看的字段

下面是假设题 `demo-c1-01` 的教学记录，用来说明字段交接，不是历史业务数据或本轮运行结果：

```json
{
  "id": "demo-c1-01",
  "family": "xlsx",
  "answerable": true,
  "question": "设备 C1 的正常浓度范围是什么？",
  "reference_docs": ["台账V3"],
  "reference_anchors": ["18%～22%"],
  "wall_ms": 6430,
  "score": {
    "n_chunks": 3,
    "anchor_recall": 1.0,
    "anchor_hit@5_any": 1.0,
    "anchor_hit@5_all": 1.0,
    "context_precision": 0.333
  },
  "raw_response": {
    "subIntents": ["设备 C1 正常浓度范围"],
    "retrievedChunkIds": ["ck-a", "ck-b", "ck-c"],
    "retrievedContexts": ["……18%～22%……", "无关块", "无关块"],
    "retrievedContextDocIds": ["台账V3", "规程V1.2", "台账V3"],
    "retrievalDiagnostics": {"finalUniqueCount": 3, "unfilledSlots": 7},
    "latencyMs": 6200
  }
}
```

若继续跑答案，另一个 answers 报告会以同一个题号保存拼接后的 `answer`、`sources`、`message_status` 和 `raw_events`。候选记录回答“证据有没有到 Prompt 前”，答案记录回答“生成和引用最后发生了什么”；两者不能互相代替。

### 3.4 pooled 与 hybrid：为阶段诊断增加捕获，但不改变产品默认入口

- `PooledEvalController` 是公开语料诊断入口，不是普通聊天链。
  - 它同时要求 Spring `pooled-eval` profile、`ragent.eval.pooled.enabled=true`、已登录管理员、数据库名匹配 `ragent_eval_pool_[a-z0-9_]+`、恰好一个 `cs_pool_` 知识库，且向量表中没有其他 collection 的向量。
  - 请求问题必须为 1～2000 字符。默认 `rewrite=false` 时只检索原问题；打开 rewrite 后才调用真实改写，并拒绝空问题或超过 4 个子问题。这里意图固定关闭，不经过业务意图/MCP 路由。
  - CS-LIVE 配置绑定 `127.0.0.1:9093`、数据库 `ragent_eval_pool_v1`、Redis 13、独立桶，使用向量 Top40、候选池 40、请求级 Top10，公平回填关闭；这不是主配置的向量 Top20。
- `RetrievalCapture` 由 pooled 请求显式创建并传给 `RetrievalEngine`，不是 ThreadLocal，也不是普通回答接口自动携带的全局监听器。
  - 各检索线程记录 `channel-VectorSearch`/`channel-KeywordSearch` 等原始通道结果，后处理器记录 `post-Deduplication`、`post-Fusion`、`post-Rerank` 等阶段，请求汇合后记录 `request-final`。
  - 每次记录立即复制 chunk 的 ID、展示文本、分数、collection、doc ID/name 和 ranking text 等标量，后续 metadata 补全或 rerank 改写对象时不会反向污染前一阶段快照。
  - 通道或后处理异常写在该 stage 的 `failure`。引擎对通道超时/异常可能降级为空结果，对后处理异常可能保留处理前列表继续；因此 HTTP 成功不等于所有 stage 健康，pooled 响应用任一 stage failure 计算 `degraded`。
- pooled 的同步摄取入口只解决这次隔离评测的调度阻塞。
  - `live_pooled.py ingest` 仍先走上传 API；当文档是 pending/failed 时，`POST /rag/eval/pooled/ingest/{docId}` 用数据库条件更新抢占为 running，再直接调用消费者使用的 `KnowledgeDocumentService.executeChunk(docId)`。
  - 这复用了实际解析、切块、embedding、持久化链，只绕过本机磁盘保护下不可用的 RocketMQ 投递；它没有验证 MQ 调度、重试或异步吞吐。恢复时复用 pending/doc ID，不重新上传；一个导入进程失败会取消尚未开始的 future，但在途任务可能继续完成。
- `HybridEvalController` 只用于同请求六臂配对。
  - 它要求 pooled controller 已注册，并要求 `rag.keyword.type=es`；入口先委托 `pooled.evaluate`，所以沿用管理员与数据库/collection 隔离检查，并拒绝 rewrite。
  - 产品混合链先在同一请求内得到向量 Top40、BM25 Top40、RRF 融合池和 `request-final`。控制器再从已经捕获的原始向量/BM25 候选分别补 metadata，各额外调用一次 qwen3-rerank，得到向量+rerank 和 BM25+rerank 两臂。
  - 六臂是向量、BM25、RRF、向量+rerank、BM25+rerank、RRF+rerank。额外两次 rerank 只为消融；整个 HTTP 请求耗时不是任一单独部署臂的延迟。
  - Hotpot 配置在 9093 复用隔离数据库并开启独立 ES `cs_pool_hybrid_v1`；SciFact 配置改到 9094、独立数据库 `ragent_eval_pool_scifact_v1`、Redis 12、独立桶与 ES 索引。多文档 YAML 的后一个文档负责把 keyword 实际覆盖为 enabled；第一次因 import 优先级没有打开通道的单题记录被保留，不能混入正式臂。
- 产品和实验选择器必须分开记。
  - 产品 `RequestLevelChunkSelector` 只处理多个子问题各自已经排序的候选：先按初始配额轮询取全局唯一块，再轮询尾部回填；它不比较跨 query 的原始 rerank 分数。主配置的公平回填开关当前仍为 `false`，所以默认产品使用各子问题固定前缀后再去重的 legacy 分支。
  - 实验 `DeterministicContextSelector` 由 `ContextSelectionReplayCli` 读取 gold-free JSONL 快照，支持 Prefix、Rerank、MMR 和 Coverage。全仓库实际调用点只有该 CLI 和测试，没有接到 `RetrievalEngine` 或聊天入口；类注释提到 production wiring 不等于当前已接入。

## 4. 评分：锚点、候选、最终上下文和人工答案分别怎样判

### 4.1 工业锚点与文档指标

- `score_retrieval` 只对 `answerable=true` 的题计算文档和锚点指标。
  - 文档召回的分子是返回文档集合与参考文档集合的交集数，分母是该题全部参考文档数；文档精度的分母是该题返回的不同文档数。
  - 每个 reference anchor 在按顺序返回的 `retrievedContexts` 中找第一次包含位置。Anchor Recall 的分子是找到的锚点数，分母是该题全部锚点数；Anchor MRR 是第一个命中锚点所在块名次的倒数，而不是所有锚点倒数名次的平均。
  - `anchor_hit@5_any` 的评价单位是一道题：前 5 块至少命中一个锚点记 1，否则 0；`anchor_hit@5_all` 要求该题全部锚点都在前 5 块。汇总再对题取算术平均。
  - Context Precision 先在一道题内数“包含任一参考锚点的返回块”，除以该题全部返回块；汇总是逐题精度的平均，不是把全数据集相关块和总块一次相除。一个块含两个锚点仍只算一个有用块，两个重复块都含同一锚点则都会被计为有用，因此它不是语义去重后的 claim precision。
  - `routing_purity` 的分子是逐块文档映射到 expected KB 的数量，分母是能映射到已知 KB 的逐块文档数量；只有 `routing_scored=true` 才计算。意图开启时，`intent_top1_correct` 要求返回的最高叶子列表非空且全部属于 expected intent 集合。
- 不可回答题在纯检索阶段只记录返回了多少不同文档。检索为空不能直接判为正确拒答，检索有块也不能直接判为错误作答；最终要看模型输出。

### 4.2 固定候选选择与公开多跳证据

- CS-DEV 的 gold 用 `evidence_requirements` 表示：一题有多个必须满足的 requirement；每个 requirement 可以有多个 alternative；某个 alternative 的 `all_of` 候选 ID 必须全部被选中，才满足该 requirement。
  - 完整证据覆盖的评价单位是一道题：所有 requirement 都满足才记 1。Evidence Recall 的分子是已满足 requirement 数，分母是该题 requirement 总数，然后再对题平均。
  - `candidate_complete` 检查完整 gold 是否在冻结候选快照里；`gold_budget_feasible` 穷举/裁剪可行组合，检查在该题 token budget 和 max chunks 下是否存在容纳全部 gold 的组合；只有候选完整且预算可行但最终未完整的题，才是目标“选择损失”。
  - 选择器运行时只看到原问题、预测要点、候选正文、稳定 ID、离线分数/向量和本地 token 估算；gold 在选择结束后由 Python 单独加载。重复候选 ID、选择了候选外 ID、题目/快照缺失都会成为报告错误，而不是被当作普通 0 分。
- CS-LIVE 与 Hotpot Hybrid 都先把公开集原支持句映射到实际生产分块，但后面的失败计分和归因不是同一套规则。
  - 映射要求支持句经过 NFC 与去空白后，完整包含在它原始来源文档的某个实际块里；其他来源中恰好相同的句子不能代替。每个支持句得到一组对应 chunk ID；跨块断裂或解析变形可能使这组 ID 为空。
  - 接着用所选块检查 `full_support`：每个 requirement 至少要有一个 alternative，其所需原句都能在所选块中找到，整题才记 1。某个备选句没有映射，不一定让整题失败；只有无法组成满足全部 requirement 的完整支持组合时才记 0。
- CS-LIVE 的 `score_live_pooled.py` 先收集本题各阶段的 chunk ID，再给出阶段覆盖和一个互斥失败原因。
  - 同名 stage 的多个子问题结果先合并为集合。例如所有 `channel-VectorSearch` 合并后判断向量通道是否完整；Top10 则先分别取各子问题的前 10 块，再合并。向量与 BM25 这类不同通道不会在这一步合成一个集合。
  - 分类按顺序判断：请求有 error 或响应 degraded，记 `service_failure`；否则用全部已摄取块仍凑不齐完整支持组合，记 `ingestion_or_mapping_unavailable`；否则没有任何一个原始通道具有完整证据，记 `recall_missing`；否则原通道已有完整证据而 `request-final` 不完整，记 `selection_missing`；其余才是 `complete`。
  - “没有任何通道完整”不是“只要一条通道不完整”。假设向量完整、BM25 不完整，不能因此记召回缺失。反过来，若两路各拿到一半、联合才完整，这份脚本仍可能归为 `recall_missing`，因为它不检查跨通道联合覆盖；这是归因规则的边界，当前 CS-LIVE 主实验本身只启用向量通道。
  - 完整 200 首次尝试把 timeout/degraded 留在 captures 中；服务失败时，该题所有阶段的覆盖计数都不增加，并以 `service_failure` 留在全题分母中。healthy-only 只用于同题 rerank 前后配对说明，不能替代主分母。
- Hotpot Hybrid 的 `hybrid_eval.py` 改为按六个实验臂分别评分，不生成上面的互斥失败分类。
  - 每个臂找到对应 stage，缺失就视为失败；该 stage 有 failure 时本臂记 0，否则用其前 K 块计算完整支持句覆盖。一臂失败不会自动把其他正常臂也记 0，各臂主分母仍是全部 200 题。
  - 若原支持句无法映射，且没有其他可满足条件的支持组合，各臂覆盖都无法成立，但该脚本不会另标 `ingestion_or_mapping_unavailable`。因此应先检查来源到分块的映射，不能把覆盖为 0 一律解释成召回失败。
  - 最后才筛选请求无 error、hybrid 未 degraded、附加基线均无 failure 的 `healthy_all_arms` 题，做相对 vector+rerank 的逐题 gain/loss 配对；这个健康条件不用于抹掉其他题中正常臂的主评分。
- SciFact 的相关项是 qrels 中与 query 相关的源论文，不是 Hotpot supporting sentence。
  - 每一臂先取前 K 个块，再映射并按首次出现去重为源论文。Recall@K 的分子是命中的相关论文数，分母是该 query 的全部相关论文数；nDCG@K 用 qrels relevance 和名次折损，再除以理想排序 DCG。
  - 汇总对 300 个 query 平均；失败臂返回空排名，因此主分母仍是 300。只有 `healthy_all_arms` 的题进入相对 vector+rerank 的逐题 gain/loss 配对统计。

### 4.3 人工回答与引用

- `prepare_review.py` 要求恰好两个 answer 报告，且每个报告题号集合与冻结数据集完全一致；缺题、额外题或回答采集失败都会阻止生成评审表。
  - 它按固定随机种子打乱每题的 B0/C-final 标签，评审表只展示问题、可回答性、expected facts、forbidden claims、reference docs、来源说明、答案和 sources；实验臂映射另存隐藏 key。
  - 可回答题逐项填写 `fact_correct`、`evidence_supported`、`source_correct`、`no_forbidden_claim`。四项全 true 才严格通过；这使“答案文字对但引用不支持”明确记为失败。这些是人工核验后的标签，评分脚本只读取布尔值，不会自动判断某句答案是否被所引文档支持。
  - 不可回答题只要求 `refusal_correct` 与 `no_forbidden_claim` 全 true。它不会因为没有 reference anchor 就自动通过。
- `score_review.py` 的分母是每个 arm 实际进入 `scored` 的评审行数，再分别按 overall、answerable、unanswerable 和 family 汇总。
  - review ID 重复或与隐藏 key 集合不同会直接终止；默认有必填布尔字段缺失时不写任何分数。显式 `--allow-incomplete` 时，缺字段的题 `strict_pass=0` 并留在分母，同时报告 incomplete；这不是把缺失人工标签猜成事实错误，而是保守的严格通过口径。
  - 严格通过率的分子是所有必需字段均 true 的题数，分母是该分组题数。各子字段也分别按有布尔标签的行求均值。

### 4.4 超时、缺失、无法映射与重复到底怎样计

| 情况 | 工业普通脚本 | CS-LIVE / Hybrid（分别说明） | 应怎样解释 |
| --- | --- | --- | --- |
| HTTP/模型超时 | `failures` 单列，普通 retrieval/answer 汇总只含成功 details，进程非零；D2 拒绝含失败报告 | CS-LIVE：失败题各阶段计 0，归 `service_failure`；Hybrid：缺失或失败臂计 0，其他臂各自评分，两者均保留全题主分母 | 必须区分整题失败与单臂失败，不能只报健康配对 |
| SSE 缺 `done`、答案为空 | answer 采集失败，不能进入盲评 | 主检索实验不生成 SSE | 是回答/传输失败，不是召回 0 |
| gold 支持句无法映射到生产块 | 工业先由 parse audit 单独报告 | CS-LIVE：整题支持组合不可用且无服务失败时归 `ingestion_or_mapping_unavailable`；Hotpot Hybrid：覆盖无法成立则计 0，不另输出该分类 | 先排除解析/映射问题，不能仅由覆盖 0 归因于召回器 |
| 题目、快照或清单缺失 | 校验/评分终止，不能组成正式报告 | manifest 数量、ID、SHA 不符即拒绝评分 | 不是可忽略样本 |
| 重复题 ID | 数据校验或合并终止 | 快照/运行/合并终止 | 不通过去重缩小分母 |
| 重复 chunk | EvalController 最终按 chunk ID 去重；Context Precision 仍按返回块计 | selector 要求候选 ID 唯一；原文重复另由来源映射约束 | 身份重复与内容相似不是同一概念 |
| 人工字段缺失 | 默认不出分；allow-incomplete 时 strict=0 | 不适用 | 必须注明采用了哪种模式 |

## 5. 用同一题逐层定位：不要在错误的层改参数

假设问题仍是“设备 C1 的正常浓度范围是什么？”，gold 文档是台账 V3，gold 锚点有 `A=设备 C1`、`B=18%～22%`。下面的 ID、排名和内容都是教学假设。

- 第一层先问原文是否被解析成块。
  - 原始文档含 A、B，但 `audit_chunks.py` 只在实际块中找到 A，B 因表格解析、OCR、跨块断裂或新版本源文件变化而不存在。
  - 结论是 ingestion/mapping 失败。此时增大 recallBudget、改 rerank 或选择器都找不回 B；应核对源文件 SHA、parser/profile、块清单与 provenance。
- 第二层问证据是否已进索引却没召回。
  - 块 `ck-b` 确实含 B，也有对应向量/ES 记录，但 `channel-VectorSearch`、`channel-KeywordSearch` 的候选 Top40 都没有它。
  - 结论是候选召回失败。检查 query 改写、Embedding/BM25 表示、scope/collection 过滤、索引一致性和 recall budget；rerank 看不到候选外证据，无法补救。
- 第三层问候选有证据，最终选择是否丢掉。
  - 向量 Top40 已含 `ck-a` 和 `ck-b`，但 rerank/请求级配额/去重后 `request-final` 只有 `ck-a`。
  - 结论是排序或选择损失。此时才比较候选排名、rerank 头部、各子问题配额、重复占位、token/块预算和最终列表。
- 第四层问证据进入 Prompt 后为什么仍答错。
  - `request-final` 与渲染上下文都含 A、B，回答却写成 `16%～20%`。
  - 这是生成/Prompt 层质量失败：检查最终消息里实际插入的上下文、指令冲突、上下文位置、模型版本和流式拼接；不能继续把它记作 Anchor Recall 问题。
- 第五层把“答案对”和“引用支持”再拆开。
  - 假设本轮同时检索到台账 V3 和一份维护规程，sources 分别给它们编号 1、2。台账支持 `18%～22%`，维护规程只讲保养、不含这个范围；模型却把正确数值标成 `[2](#cite-2)`。
  - 人工核对后可以填 `fact_correct=true`、`source_correct=false`，严格回答仍为 0。这里可能是模型读到了台账的证据，却选错引用编号，不能直接断言它靠参数记忆猜答案，也不能直接认定来源装配错误。
  - 再看一个不能据此判错的情况：`ck-a`、`ck-b` 都来自台账 V3，前者含设备名、后者含范围，且两块都进入本轮 Prompt。sources 按文档只保留最高分代表块，因此它可以只展示 `ck-a` 的摘录和位置，而不再单列 `ck-b`。这只说明代表定位未展示全部证据；应继续核对实际上下文和被引文档，不能仅凭代表块没有数值就填 `evidence_supported=false` 或判严格失败。来源装配的完整流程见[聊天流程 2.7](06-chat-stream-and-cancel.md#27-用同一批选中块组装三种不同对象)。

一个小排名例子可以把相关指标算清：候选前 5 块依次是 `[ck-a(A), noise-1, ck-a2(A 的重复内容), ck-b(B), noise-2]`。

- 相关锚点共 2 个，A 和 B 都在前 5，因此 Anchor Recall=`2/2=1.0`，Hit@5(any)=`1`，Hit@5(all)=`1`。
- 5 块中有 3 块包含任一锚点，因此这道题的 Context Precision=`3/5=0.6`。`ck-a2` 内容重复但仍按返回块计一个有用块，这正是该字符串指标的限制。
- 如果最终选择变成 `[ck-a(A), noise-1, noise-2]`，Anchor Recall=`1/2=0.5`，Hit@5(any)=`1`，Hit@5(all)=`0`，Context Precision=`1/3≈0.333`。只看“至少命中一个”的 Hit 会漏掉 B 已被选丢，所以多锚点题还要看 Recall/all-hit。
- 若这里讨论普通 Precision@5，则分子是前 5 个相关项数、分母固定为 5；项目历史 Context Precision 使用“含任一字符串锚点的块/实际返回块”，二者名称相近但定义不能混用。

## 6. 历史实验怎样一步步推进，又在哪里停下

### 6.1 先问端到端回答是否优于冻结基线：B0 与 C-final

- 2026-08-13 的工业小型系统评测在 3 类中文业务文档、24 道冻结问题上比较冻结基线 B0 与当前配置 C-final。
  - B0 服务端提交为 `bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6`；C-final 为 `b375a7e5493a09d35af498b6c241536440ecdd1c`。模型固定 `qwen3-max`、`qwen-emb-8b`、`qwen3-rerank`，每类只留一个候选，失败不静默换模型。
  - C-final 由 C0/C1/C2 的预设门槛选择，最终 `intent=off`、`ocr=off`；分块为 `1024/128/50/3`，最终 TopK 10、召回 20、rerank 池 40。
  - C-final 的 21 道检索题 Anchor Hit@5(any) `95.2%`、Anchor Recall `86.5%`，检索 P95 `8.5 s`；24 个回答均完成，回答 P95 `22.6 s`。同配置较早一次 C0 的 Hit@5 是 `85.7%`，所以必须保留在线波动限制。
  - B0/C-final 的 48 条回答盲评后，两组严格通过率都为 `19/24=79.2%`；21 道可回答/近领域题都是 `16/21=76.2%`，3 道不可回答题拒答都是 `3/3=100%`，24 道配对标签无差异。
- 决策是：当前版维持了这套小样本上的回答质量，没有证明回答提升。解析、检索或工程能力的后续改进需要独立指标支持，不能把 B0/C-final 平局改写成端到端提升。

### 6.2 再问请求级预算能否减少噪声：D0

- 2026-08-13 的 D0 复用 C-final 已冻结的 `123+14+4=141` 个块和向量，只把检索代码换为 `fd538a8`；仍是 24 题、21 道检索 gold、`intent=off`、`ocr=off`、请求 TopK 10、召回 20、候选池 40。
  - 平均最终唯一块 `13.05 → 6.52`，超过 TopK 的题 `17/21 → 0/21`，Context Precision `17.1% → 29.2%`，路由纯度 `73.6% → 84.8%`，文档召回保持 `97.6%`。
  - 同时 Anchor Recall `86.5% → 84.1%`，Hit@5 `95.2% → 90.5%`；检索 P95 `8.5 s → 16.8 s`，但运行时不同且含在线模型调用，不能归因给本地代码。
- 这次“块更少/纯度更高，但召回下降”不是自相矛盾。
  - 收紧的是多个子问题汇合后的最终上下文：删除了大量噪声，也可能删掉少量低位 gold，所以精度和召回方向可以相反。
  - 文档召回不变只表示正确文档仍出现，不表示文档里的每个锚点都保留。Hit@5 和 Anchor Recall 的下降必须作为负指标一起保留。
  - D0 没有重跑答案，因此只能说请求级预算收口和块级纯度改善，不能说回答准确率提高。

### 6.3 再问新分块与新检索能否一起工作：D1

- D1 同时应用 XLSX 分块提交 `ed2590e` 和检索提交 `fd538a8`，从 seed 干净重建 3 份文档；历史证据归档检查点为 `03a89db`。
  - XLSX 从 123 块变为 79 块，正文最大字符 `12,489 → 1,019`，超过 1,024 的块 `70 → 0`，多余精确重复块 `17 → 0`，5 个解析锚点保持 5/5；离线与在线重建块形态一致。这些是可归因于结构处理的结果。
  - 两份 PDF 却从冻结索引的 14/4 块变为 16/6 块，说明重新调用 MinerU 时解析结果发生漂移。D1 总体检索变化同时包含分块、检索代码和远端解析变化，不能作为任何单项机制的因果结果。
  - XLSX 六题中 Hit@5(any) 保持 100%，Context Precision 相对 C-final `27.9% → 39.3%`，Anchor Recall `100% → 94.4%`。丢失锚点对应块仍在索引和候选中，但三个子问题先截断再去重后没有进入最终额度，于是形成“公平回填是否值得做”的假设。
- D1 的作用是组合兼容与漂移诊断，不是越过指标门槛后的新默认。后来的 D2 才在固定 D1 数据库上隔离回填开关。

### 6.4 单独验证“空位补满是否值得”：D2

- D2 的实验问题、固定项和结果已在第 1 节展开。这里记住它与 D1 的关系即可：D1 的一次选择缺口提出假设，D2 用六轮固定子问题回放检验；D2 的 off 三次都已命中目标题，所以没有稳定配对恢复。
- 最终保留请求级上限、去重、结构化 ranking text 和 `should_split` 契约修复；公平回填开关保持 `false`。不能因为回填代码和测试存在，就说产品默认会补满 TopK。

### 6.5 候选已经固定时，复杂覆盖策略是否胜过简单方法：CS-DEV

- 2026-09-05 的 CS-DEV 是新的公开集开发实验，不是 D2 的下一轮放行。
  - 数据是 HotpotQA distractor 镜像 revision `1908d6a`，seed `20260905`；从 train 取 200 个 `public_dev`，从官方 dev 取 400 个本地冻结 `public_test`，两组支持文档无重叠。它是给定候选的选择诊断，不是 fullwiki 检索或官方成绩。
  - 运行时 Java 只读 gold-free 候选快照；特征是离线 BM25、256 维 signed hashing 与本地 token 估算，不能称为真实 qwen embedding/rerank 或在线延迟。
  - 1024 本地 token、最多 10 块时，开发集 200 题的 gold 在候选中且有可容纳组合。Prefix `179/200`，原问题 Rerank `184/200`，最强简单 CS-M(`mu=0`) `198/200`，最佳 Coverage `198/200`；最佳 Coverage 相对简单基线改善 0、退化 0。
  - 预先要求 Coverage 至少比最强简单基线高 `+5` 个百分点才进入冻结测试和在线/回答阶段。实际增益 0；512 预算只 `+0.5` 个百分点，2,048 两者均 `200/200`，所以 gate fail。
- 该批代码与记录是 2026-09-05 当前 WIP，未提交；状态文档记录的起始 HEAD 为 `05b9137d841e139cd0457af95369a14d16736404`。冻结 400 题没有运行逐题策略结果，在线模型和回答调用为 0，产品 legacy 保持默认。

### 6.6 候选不再由题目附送时，损失发生在哪里：CS-POOL 与 CS-LIVE

- CS-POOL 先问：把 200 道 public_dev 的候选正文合成一个公共池后，选择器之前是否已经丢证据？
  - 2026-09-05 将 1,992 次段落出现按标题与句子序列合成 1,988 个唯一段落，只放开发题正文，不放冻结 400；问题文件和 gold 文件分离。
  - 本地 BM25 全池 Top40 完整证据 `173/200`；在同一代理快照上，CS-R 最终 `141/200`、相关性/token 贪心 `132/200`。这说明候选来源本身已造成 27 题缺失，选择又继续丢失；本地分数和耗时只是代理。
  - 这轮当时因真实服务和费用边界尚未完成而停止。后来的 CS-LIVE 是独立的候选来源诊断，不是 CS-DEV gate fail 后偷偷继续 Coverage 的冻结测试，也不能用后来的在线结果改写 CS-POOL 当时的“未在线”状态。
- CS-LIVE 再问：相同公开开发池经过真实解析、切块、Qwen3 Embedding、PGVector 和 qwen3-rerank 后，候选/选择损失怎样变化？
  - 日期仍为 2026-09-05，WIP 未提交、起始 HEAD `05b9137`。1,988 份公开段落经真实摄取内核变成 1,991 块及同 ID 向量；481 个支持句都能映射到其原来源的实际块。
  - 配置是 intent/MCP/keyword/graph/web 关闭，SiliconFlow Qwen3-Embedding-8B 1536 维、PGVector Top40、候选池 40、百炼 qwen3-rerank、请求 Top10、公平回填关闭；默认不改写。5 题改写仅是冒烟，不混入 200 题主结果。
  - 200 个首次尝试保留 5 次服务超时；全题分母下，向量 Top40 完整证据 `192/200`，最终 Top10 `182/200`。互斥归因为服务失败 5、候选召回损失 3、选择损失 10、完整 182；10 个选择损失全是 bridge 题。
  - 在 195 个健康题上，rerank Top10 相对 vector Top10 改善 8、退化 5、其余 182，净 +3/200。它描述的是既有 rerank 的阶段行为，不是新 Coverage 算法收益，也没有生成答案。

### 6.7 同一次真实请求里，混合检索是否值得成为默认：Hybrid

- 2026-09-05 的 Hybrid 固定同一题的向量/BM25 原始候选，用现有等权 RRF(`k=20`)和 qwen3-rerank 形成六臂；当前 WIP 未提交，默认聊天配置没有改变。
  - Hotpot 使用同一 200 题公开开发池：Top10 完整支持句覆盖为 vector `184`、BM25 `151`、RRF `181`、vector+rerank `187`、BM25+rerank `181`、RRF+rerank `185`。同请求健康配对中，混合重排相对向量重排改善 0、退化 2。
  - SciFact 使用完整 5,183 篇公开英文论文与 official test 300 queries；按前 10 个块映射/去重到源论文。vector+rerank Recall@10/nDCG@10 为 `90.42%/0.7786`，RRF+rerank 为 `91.66%/0.7856`；相对向量重排逐题 nDCG 改善 11、退化 18、271 不变，未做显著性检验。
  - Hotpot 是支持句完整覆盖，SciFact 是文档相关性 Recall/nDCG；两个数据集的百分比不能相加，也不能称为中文工业答案准确率。六臂按相同块数 TopK，不是严格相同 token 成本；混合还多一路召回。
- 决策是保留“向量 + qwen3-rerank”为默认，混合检索只保留隔离评测中的可切换配置。
  - Hotpot 上默认臂更好；SciFact 的混合收益很小且逐题退化数更多，跨数据集方向不一致；默认开启还会增加 ES 通道、索引同步、召回和运维成本。
  - 这不是说 BM25/RRF 没有价值，而是现有证据不足以承担默认复杂度。以后若在新的中文业务开发集和冻结测试集上获得稳定非退化结果，应新建协议再决策，不能回看本轮 frozen/公开结果继续调权重。

## 7. 怎样把一次修改准确归到某一层

- 如果只改善解析锚点恢复、超长块或重复块，结论写“输入表示/摄取层改善”。要重新入库才能生效；不能直接推导检索或回答提高。
- 如果候选 Recall/完整支持证据提高，但最终上下文不变或下降，结论写“候选召回层改善，选择层仍是瓶颈”。同时报告候选预算、索引和 scope。
- 如果候选冻结且最终 evidence coverage/Context Precision 提高，结论写“排序/选择层改善”。离线代理实验还要明确没有真实模型、没有在线延迟、没有回答质量。
- 如果相同最终 Prompt 下答案事实或拒答改善，才归到生成/回答层；若 Prompt、模型或会话历史也变了，就不能单因果归因。
- 如果答案事实正确但引用错误，先对照模型写出的编号，再检查编号到文档/位置的映射和本轮实际上下文：映射正确、模型选错文档属于生成/引用选择问题；编号、文档或位置装配不符才归到来源装配层。grounding 是块快照，不是已经执行过的逐句事实校验；答案准确率不能掩盖 citation failure，也不能只凭引用错误就断定根因。
- 如果质量相同但 P95、错误率、费用或吞吐改善，归到系统效率/可靠性层；单次墙钟或一次六臂总耗时不足以支持生产 SLO。
- 如果自动和人工指标通过，仍不能直接称业务收益。还需要真实用户分布、权限、安全、shadow/canary 和业务结果；本仓库历史实验没有完成这一层。

## 8. 为什么有实现最终没有默认启用

- `RequestLevelChunkSelector` 的公平回填实现正确、回归可测，但 D2 证明“更多块”会带来更多低位噪声并让 Hit@5、纯度、路由纯度越过护栏，所以开关保持 `false`。
- `DeterministicContextSelector` 的 Coverage/MMR/消融实现形成了可复现研究工具，但 CS-DEV 的复杂 Coverage 没有超过简单基线的 +5 个百分点 gate；冻结测试、在线和回答阶段被跳过，因此它只接 CLI，没有接产品检索引擎。
- BM25/RRF 已经在隔离 profile 中真实跑通，但英文公开集结果不一致，Hotpot 还对默认向量重排更有利；增加 ES 索引及同步成本没有得到中文工业证据补偿，所以保持可选而非默认。
- OCR/意图也遵循同一原则：代码能力存在不等于默认开启。C-final 的固定选型器要求意图 Top-1、各正向文档族 Hit@5、路由纯度共同通过，OCR 要求扫描件解析锚点增加且原生 PDF 不退化；历史门槛最终选择两者都关闭。
- 关闭不是“实现失败后什么都没留下”。保留下来的测试、快照协议、阶段捕获、失败分桶和负结果，能阻止以后重复走同一条无收益路线，也能在数据分布改变时用新标签重新提出假设。

## 9. 复述时必须守住的边界

- 候选冻结不等于真实模型调用：CS-DEV 是 gold-free 固定候选和离线代理；D2 固定的是 subIntents，但 embedding/rerank 仍在线；CS-LIVE/Hybrid 才捕获真实模型检索阶段。
- 开发集不等于冻结测试集：CS-DEV 的 200 public_dev 可用于选型，400 public_test 因 gate fail 没有执行；不能把 200 题结果叫测试集泛化。
- 离线代理不等于在线分数：BM25、signed hashing、本地 token/耗时只能筛机制；不能冒充 Qwen 向量、qwen rerank、服务 token 或提供商账单。
- 块级不等于文档级：一个正确文档被召回不代表全部锚点进入上下文；SciFact 先按块 TopK 再映射论文，Hotpot 则要求原来源支持句完整覆盖。
- 公开英文集不等于中文业务数据：Hotpot 诊断多跳选择，SciFact 诊断科学论断到论文的检索；历史工业 24 题才是中文业务小样本，但规模很小。
- 后来的独立诊断不能倒写成前一轮“过门槛后继续”：CS-DEV gate fail 后，冻结 400/在线回答确实停止；CS-POOL/LIVE 和 Hybrid 回答的是候选来源、真实阶段与成熟混合检索的另一个问题。
- 当前源码、主配置、测试和历史运行是四类证据：方法存在不代表入口调用，主配置启用不代表生产部署，测试通过不代表历史模型结果重跑，本轮静态核对更不代表任何旧数字已重新验证。

## 10. 关键源码与资料反查

- [工业评测说明](../../../eval/iron-ore/README.md)与 [RUNBOOK](../../../eval/iron-ore/RUNBOOK.md)：24 题主实验、B0/C-final、D2 固定回放和恢复边界；其中 README 的题目分类短句应以校验脚本为准。
- [数据校验](../../../eval/iron-ore/verify_dataset.py)、[知识库准备](../../../eval/iron-ore/prepare_kb.py)、[块审计](../../../eval/iron-ore/audit_chunks.py)：分别锁定题量/文档/锚点、摄取配置与 setup manifest、原证据是否进入实际块。
- [检索采集](../../../eval/iron-ore/run_retrieval.py)、[回答采集](../../../eval/iron-ore/run_answers.py)、[人工评分](../../../eval/iron-ore/score_review.py)：分别在最终检索上下文、完整 SSE、盲评严格通过处收束。
- [CS-LIVE 评分](../../../eval/context-selection/score_live_pooled.py)、[Hotpot Hybrid 评分](../../../eval/context-selection/hybrid_eval.py)、[SciFact Hybrid 评分](../../../eval/context-selection/beir_hybrid.py)：分别核对互斥失败归因、逐臂支持句覆盖、逐臂文档相关性与健康配对口径。
- [普通评测入口](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/EvalController.java)、[pooled 入口](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/PooledEvalController.java)、[hybrid 入口](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/HybridEvalController.java)、[阶段捕获](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalCapture.java)：对应三种采集入口及隔离边界。
- [产品请求级选择器](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RequestLevelChunkSelector.java)与[实验确定性选择器](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/selection/DeterministicContextSelector.java)：前者受产品开关控制，后者当前只由 JSONL replay CLI 和测试调用。
- [上下文选择状态](../context-selection-status.md)、[CS-DEV 记录](../changes/2026-09-05-context-selection.md)、[CS-LIVE 记录](../changes/2026-09-05-live-pooled-retrieval.md)、[Hybrid 记录](../changes/2026-09-05-hybrid-retrieval-comparison.md)：保存 2026-09-05 WIP 的阶段、结果和停止边界。
- [请求级检索纯度](../changes/2026-08-13-request-level-retrieval-purity.md)、[XLSX 结构分块](../changes/2026-08-13-xlsx-structure-aware-chunking.md)、[D2 公平回填](../changes/2026-08-14-request-level-fair-refill.md)：保存 D0/D1/D2 的提交、配置、正负指标和回滚条件。

本文按校验脚本把工业题目分布纠正为 `6+6+6+3+3=24`，并以主 YAML 的显式配置说明普通 eval 已启用；`EvalProperties` 的字段缺省值不能覆盖它。评分部分按各脚本区分 CS-LIVE 与 Hybrid，引用部分区分文档代表块、实际入模证据和模型选择的编号；这些是流程与口径修正，不是历史实验重跑。无法从公开仓库重新核对的逐题原文、原始报告与模型账单，继续按历史报告口径标注，没有在本轮补造。
