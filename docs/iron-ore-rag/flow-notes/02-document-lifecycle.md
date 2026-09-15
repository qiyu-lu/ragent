# 02｜文档生命周期：从原文件登记到失败后继续

这篇只回答知识资料怎样进入系统、何时变成可检索块，以及上传、异步摄取、远程刷新和人工维护之间怎样交接。解析后的 `Block` 怎样切成 chunk、`embedding_text` 怎样生成、向量怎样和块 ID 对齐，由[流程 C：文档到证据](03-document-to-evidence.md)展开；这里在调用边界说明摄取内核收到什么、返回什么，以及外层怎样改变文档状态。

先记住四类不会自动绑成一笔事务的数据：对象存储里的原文件、PostgreSQL 的文档/块/向量/日志、可选的 ES 或 LightRAG 外部索引、RocketMQ 消息。某一步写了 `SUCCESS` 或用了事务消息，都不能据此推导另外三类副作用已经原子完成，更不能推导任务只执行一次。

## 1. 建立知识库：为后续文件、模型和向量落点准备身份

这是管理员先创建一个知识库的同步请求。它不处理普通文档，也不重复介绍知识库的查询、重命名和删除 CRUD；它的作用是把后续登记文件和生成向量所需的库级输入固定下来。

- 客户端向 `POST /knowledge-base` 提交 `name`、`embeddingModel` 和 `collectionName`，Controller 把请求交给知识库 Service，并最终返回新知识库 ID。
  - `name` 是界面名称；`collectionName` 是稳定的逻辑分区名，后面同时用作对象存储目录名和向量行的分区值；`embeddingModel` 是后续文档侧 Embedding 使用的模型候选 ID。
  - 当前请求类没有 Bean Validation 注解。Service 会先对 `name` 去掉空白后查未删除知识库的重名，再按原始 `collectionName` 查重；数据库还对 `collection_name` 有唯一约束。代码没有在这里显式检查三个字段是否为空，也没有验证 `embeddingModel` 一定能被模型路由找到，错误可能在空值操作、数据库约束或以后真正向量化时暴露。
- 校验通过后，Service 在数据库事务中插入 `t_knowledge_base`：保存知识库 ID、原始名称、`embedding_model`、`collection_name`、当前用户名和未删除标志。
  - 后续处理文档时先按文档的 `kb_id` 读回这行，再由 `VectorTargetResolver` 组合出 `VectorTarget(partition, embeddingModel, dimension)`：`partition` 取数据库的 `collection_name`，模型取数据库的 `embedding_model`，维度却不存于知识库，而取部署配置 `rag.default.dimension`。
  - 当前主配置把维度设为 `1536`，`schema_pg.sql` 的 `t_knowledge_vector.embedding` 也固定为 `vector(1536)`；这是部署级和表结构契约，不是创建请求可以逐库覆盖的字段。Embedding 候选自身也应输出相同维度，真正调用后还会逐条校验。
- 插入数据库行后，Service 用 `collectionName` 在全局私有原文件桶下建立一个零字节目录标记，再让 `VectorStoreAdmin` 确认向量空间存在。
  - 当前默认 PGVector 实现不是每库建一张表：所有库共享 `t_knowledge_vector` 和 HNSW 索引，查询、写入靠 `collection_name` 过滤；`ensureVectorSpace` 主要确认共享 HNSW 索引。换成 Milvus 时，同一抽象可以对应物理 collection。
  - 数据库事务不能回滚对象存储。比如知识库行已经插入、目录标记已经写入，随后向量空间初始化失败，数据库行会回滚，但目录标记可能留下；当前创建链没有通用补偿去删除它。
- 三步都正常返回后，Controller 得到知识库 ID。此时只有“后续资料放到哪里、用哪个模型和维度”已经登记，还没有原文件、chunk 或向量；用户不能因为知识库创建成功就从中检索到资料。

## 2. 上传本地文件或登记远程来源：只把原文件和待处理元数据准备好

这是一次独立的同步登记请求。它接收知识库 ID、来源和摄取配置，取得原文件并写对象存储，然后插入 `PENDING` 文档；它不会顺便开始分块。

- 客户端向 `POST /knowledge-base/{kb-id}/docs/upload` 提交表单。
  - 路径给出知识库 ID；`sourceType` 只接受本地文件别名或 `url`。本地分支还要有 multipart `file`；远程分支要有 `sourceLocation`。
  - 可选调度字段是 `scheduleEnabled` 和 `scheduleCron`；可选处理字段是 `processMode`，以及 direct/chunk 模式的 `ingestionSpec` 或 pipeline 模式的 `pipelineId`。`processMode` 当前不能为空，默认值只存在于数据库列，不能替代本次 Java 校验。
  - Service 先查知识库是否存在，再规范化来源类型。URL 来源缺地址会直接失败；只有 URL 且明确启用调度时才要求 cron，cron 必须可算出下次时间，间隔不能短于配置值，当前默认是 60 秒。
- 在第一次文件副作用之前，Service 先校验处理模式。
  - `chunk` 模式把 `ingestionSpec` 交给 `IngestionSpecCodec.normalize`。空 JSON 表示以后读取系统默认；非空 JSON 被解析为 `parseProfile` 与字符/表格预算，非法 JSON、未知解析档位或越界预算在这里失败，合法值被重写为规整 JSON 保存。
  - `pipeline` 模式要求 `pipelineId` 并查询该 Pipeline 存在，然后保存 Pipeline ID、清空 `ingestionSpec`。这只说明配置可登记，不说明知识文档主链能运行 Pipeline；实际启动时会明确失败，见第 4 节。
- 参数通过后，Service 取得并保存原文件。
  - 本地上传由文件服务读取文件名、大小和文件流，用 Tika 探测 MIME，再写进私有原文件桶的 `{collectionName}/{随机 UUID}.{原后缀}`。数据库稍后保存的是对象 key，不是上传请求中的临时文件。
  - URL 登记先尽力发 HEAD 获取大小、文件名和 Content-Type，HEAD 失败则继续直接 GET；下载时同时按 multipart 最大文件大小限制流量，当前主配置是 50 MB。远程内容先写临时文件，以实际读取字节数上传对象存储，空内容失败，临时文件最后删除。
  - 当前 HTTP helper 直接把地址交给 OkHttp，源码里没有额外的协议/主机白名单或内网地址拦截；不能把“URL 非空且能下载”说成已经完成 SSRF 安全校验。
- 对象写成功后，Service 用 `StoredFileDTO.mimeType` 查询 `ParserRegistry.canParse`，确认至少一个解析档位能处理该 MIME。
  - 这一步只是能力预检，还没有真正解析。对远程文件，此 MIME 主要来自响应头，缺失时才可能按文件名推断；真正摄取时内核会重新根据文件字节和文件名探测 MIME，因此登记预检成功不保证实际解析一定成功。
  - 如果没有解析器，Service 删除刚写入的对象并抛错。这里的删除不是 quiet 模式；删除自身失败也可能覆盖原来的“不支持类型”结果。
- 解析能力通过后，`DocumentIdentityResolver` 从原始文件名解析文档身份。
  - 先去掉扩展名，再取文件名中最后一个形如 `V1.2` 或 `V1.3-demo` 的版本串；版本串之前去掉尾部空格、点、下划线或连字符后成为稳定 `document_key`，版本标准化为大写 `V` 开头，含 `-demo` 时设置演示资料标志。
  - 例如假设上传 `设备C1规程-V1.2.pdf`，会登记 `document_key=设备C1规程`、`document_version=V1.2`。这是文件名规则，不是读取正文后由模型判断的业务版本。
- Service 构造并插入 `t_knowledge_document`。
  - 它保存 `kb_id`、文档名和身份、对象 key、展示类型、预检 MIME、字节数、来源地址、调度开关与 cron、处理模式与摄取规格；同时写 `enabled=1`、`chunk_count=0`、`status=pending` 和当前操作者。
  - URL 初次登记不会保存本次响应的 ETag、Last-Modified 或内容哈希，也不会立刻建立调度表行。调度行是在后续“开始切分”的本地事务里 `upsert`；所以只登记一个计划刷新但从未开始切分的 URL 文档，不会被扫描器选中。
- 插入成功后，Service 返回完整 `KnowledgeDocumentVO`，Controller 包装为成功响应。到这里实际具备的是：一个可再次打开的原文件对象、一条启用且待处理的文档元数据、以后解析所需的规格；尚无关系 chunk 和向量。
  - 因此“上传完立即提问”时，这份新资料不会因为 `PENDING` 行自动进入检索。默认向量检索只查 `t_knowledge_vector`，并不会读取原文件或 `PENDING` 文档现场切分。
  - 对象写入和数据库插入不在同一种事务里。当前只对“不支持解析器”分支显式删对象；如果对象已经写入，而身份解析或文档插入失败，源码没有通用补偿或孤儿对象对账，文件可能留在存储中却没有文档行指向它。反过来，若文档插入已完成、随后响应或审计外围失败，也不能仅凭客户端收到失败就断言文档行不存在。

## 3. 单独点击“开始切分”：事务消息只交接执行权

这是登记完成后的另一次请求。它不在 HTTP 线程里解析文件，而是用 RocketMQ 事务消息把“数据库已允许执行”和“消费者以后能看到任务”连接起来。

- 客户端向 `POST /knowledge-base/docs/{doc-id}/chunk` 只提交文档 ID。Service 先查文档，缺失就抛“文档不存在”，同时为审计保存开始前快照和文档名。
- Service 构造 `KnowledgeDocumentChunkEvent`：先放 `docId` 和当前 `UserContext.username`，`kbId` 暂时为空；消息 key 也是 `docId`。然后同步调用 `sendInTransaction`。
  - 适配器为本次调用生成事务 ID，把本地回调暂存在当前进程内，向 Broker 发送 half message。half message 此时对普通消费者不可见。
  - Broker 接着回调 `DelegatingTransactionListener.executeLocalTransaction`，它用 Spring `TransactionTemplate` 执行下面的状态更新与调度更新；成功返回 COMMIT，任一异常被监听器捕获并返回 ROLLBACK。
- 本地事务先做一次带条件的文档更新，条件和结果必须一起看。
  - Wrapper 显式条件是 `id = docId AND status <> 'running'`，更新值是 `running`、操作者和当前 `update_time`；实体上的 MyBatis-Plus 逻辑删除还会排除已删除行。这里没有 `enabled=1` 或更窄的 `status in (pending, failed, success)` 业务条件。
  - Service 读取受影响行数 `updated`。`updated > 0` 才取得运行权；`updated == 0` 时再按 ID 查文档：若查不到报“文档不存在”，否则统一报“文档分块操作正在进行中”。该异常使这条 half message 的本地事务回滚，不能发布给消费者。
  - 这就是连续点击的并发保护：第一个回调通常把状态改成 `RUNNING`；第二个回调影响 0 行并回滚自己的消息。它不是全生命周期防重，因为消费者端没有按消息 ID 或执行版本做幂等登记。
- 条件更新成功后，本地事务重新读取文档，把真实 `kbId` 回填到内存中的事件对象，再调用 `scheduleService.upsertSchedule(document)`。
  - half message 在本地回调之前已经发出，不能依赖这次事后 mutation 一定改变 Broker 中已经序列化的消息体；消费者本来也不使用事件 `kbId`，而是按 `docId` 重查文档和知识库。
  - 只有 URL 文档会处理调度。Service 根据文档是否启用、是否打开调度、cron 是否存在来计算 `enabled` 和 `next_run_time`；没有调度行就插入，有就更新 cron、启用标志和下次时间。文件来源直接跳过。
  - 状态更新和调度表写入处于同一个数据库事务；调度校验或写入抛错时，`RUNNING` 更新一起回滚，事务消息也回滚。
- `sendInTransaction` 等 RocketMQ 同步发送调用返回后只记录 `sendStatus`、本地事务状态和 `msgId`，接口自身返回 `void`；文档 Service 与 Controller 不把这些字段返回给前端。
  - 网络发送抛出的 `Throwable` 会继续抛到 HTTP 请求。可是本地回调的业务异常已被事务监听器转换成 `ROLLBACK` 状态，适配器没有再按 `localTransactionState` 抛错。因此第二次点击即使自己的消息被回滚，外层 `startChunk` 仍可能继续写审计快照并返回普通成功响应；不能仅凭 HTTP 200 认定新任务已提交。
  - 同理，适配器对非 `SEND_OK` 只注销本地回调，没有显式抛错。当前调用边界给前端的是“发送 API 已返回”，不是可查询的任务句柄或一次执行保证。

### Broker 状态不确定时的另行回查

正常本地回调已经明确给出 COMMIT/ROLLBACK 时，不需要把回查硬塞进每次点击流程。只有 Broker 未确认二阶段结果时，它才另行触发按 topic 注册的事务检查器。

- 检查器从消息体读 `docId`，查询当前文档；只有“文档仍存在且当前状态严格等于 `RUNNING`”才返回 true，通用事务监听器据此向 Broker 返回 COMMIT，否则返回 ROLLBACK。
- 查询本身抛异常时返回 `UNKNOWN`，留给 Broker 以后再次判断。
- 这个判断只看回查时刻的一个状态，没有执行版本、消息 ID 或 fencing token。`FAILED`/`SUCCESS` 都会判回滚，即使某次本地事务曾经成功；反过来，只要另一条流程把同一文档置为 `RUNNING`，检查器也无法证明就是这条消息造成的。因此它是当前实现的交接判据，不是严格的一次性证明。

## 4. 消费者执行：恢复操作者，调用固定摄取内核，再记录终态

Broker 提交消息后，`KnowledgeDocumentChunkConsumer` 在另一个线程或实例接手。这里要分清“消费者外层抛异常”和“摄取任务内部记录失败后正常返回”，两者对 MQ 的结果不同。

- 消费者从 `MessageWrapper.body` 取得事件，日志记录其中的 docId 与消息 key。实际执行只使用 `docId` 和 `operator`；事件中的 `kbId` 即使有值也不参与后续查询。
- 消费者用事件里的用户名构造只有 `username` 的 `LoginUser`，写进线程级 `UserContext`，然后调用 `documentService.executeChunk(docId)`；无论结果如何，`finally` 都清空上下文，避免线程复用时串用户。
  - `executeChunk` 先按 docId 查询文档。若文档已不存在，它只告警并正常返回，消息会被视为消费完成。
  - 它没有检查文档当前是否仍为 `RUNNING`，也没有保存或检查消息唯一键。重复投递会再次进入完整摄取：重新读文件、重新解析、再次调用 Embedding，并按文档整体 replace；所以事务消息不等于只执行一次，`docId` 消息 key 也不是防重约束。
- `runChunkTask` 在进入主体 try/catch **之前**恢复本次执行所需配置。
  - 从文档行规范化 `processMode`；按 `kb_id` 查知识库；`VectorTargetResolver` 从知识库的 `collection_name`、`embedding_model` 与部署级维度得到向量落点；`IngestionSpecCodec.read` 从文档 `ingestion_spec` 读解析档位和预算，空值或损坏 JSON会记录告警并回落默认；再构造 `DocumentRef(docId, kbId, docName)`。
  - 随后插入一条 `t_knowledge_document_chunk_log`，初始为 `RUNNING`，保存处理模式、实际规格中的解析档位、Pipeline ID 和开始时间。
  - 上述任一步抛错——例如知识库缺失、模型或维度配置缺失、处理模式非法、日志插入失败——都还没有进入 catch，会穿过 `executeChunk` 和消费者。消费者没有自己的 catch，只在 finally 清上下文，所以这些异常仍可能让 RocketMQ 按消费失败策略重投。
- 进入 try 后先判断处理模式。
  - 当前 `pipeline` 分支直接抛“管道模式重构中，暂不可用，请改用直接分块”。类里虽然保留了 `runPipelineProcess` 方法，但调用代码被注释，不能因为方法存在就说知识文档 `processMode=pipeline` 可用。
  - 该异常落在本方法 catch 内，正常情况下会把文档与日志记为 `FAILED` 后返回消费者，不会自动形成 MQ 重试。
- direct/chunk 模式读取 `document.file_url` 指向的原文件全部字节，然后调用固定 `IngestionKernel.run(doc, bytes, spec, target)`。
  - 四个入参分别是：文档身份 `DocumentRef`、对象存储读出的真实字节、文档级解析/分块规格 `IngestionSpec`、包含逻辑分区/Embedding 模型/维度的 `VectorTarget`。
  - 内核内部按固定顺序重新探测 MIME，用 MIME 与解析档位选解析器，解析成结构块，按预算生成 chunk；再把每个 chunk 的检索文本交给指定 Embedding 模型，校验返回数组条数、空值和每条维度；最后按文档整体替换关系块和向量索引。表格切分等内部算法由流程 C 说明。
  - 内核返回 `IngestionOutcome`，里面有最终 MIME、解析器类型、Block 数、最终 chunks，以及解析、切分、Embedding、索引四段耗时。它返回时索引写入已经执行，不是只返回一份待保存列表。
- 内核正常返回后，外层取 `chunkCount` 和四段耗时，先把字节探测得到的 MIME 回填文档，再把文档更新为 `SUCCESS`、写 `chunk_count` 和当前操作者，最后把 chunk 日志更新为 `SUCCESS`、块数、各阶段耗时、总耗时与结束时间。
  - `refreshMimeType` 和 `markChunkSucceeded` 都没有检查 `updateById` 的影响行数；若更新返回 0 而没有抛异常，流程仍会继续写成功日志。当前没有“成功状态必须影响 1 行”的约束。
- try 内任何异常都会进入 catch：记录错误日志，调用单独的数据库事务把文档改为 `FAILED`，再把本次 chunk 日志写成 `FAILED`、块数 0、已取得的阶段耗时、总耗时、错误消息和结束时间。
  - 只要 `markChunkFailed` 与失败日志更新都成功，catch 不重新抛原异常，消费者会正常返回；解析报错后页面可从文档状态和 chunk 日志看到失败，但 RocketMQ 不会因为这次原始摄取错误自动重投。继续处理要由用户再次点“开始切分”，或由适用的远程调度以后再次触发。
  - 如果 catch 内的失败状态事务或失败日志更新再次抛错，该新异常会传播到消费者，才可能触发 MQ 重投；此时原始错误可能被二次写入错误覆盖。
  - 如果索引已经成功，而 `markChunkSucceeded` 或成功日志写入抛错，catch 会尝试把文档改成 `FAILED`，但不会删除刚写的新块/向量。常见结果是“索引已经可读，页面状态却失败”；若状态更新只返回 0 不抛错，还可能保留旧状态却写成功日志。当前没有通用终态对账。

### 索引替换失败时旧数据怎样留下

- `ChunkIndexWriter.replaceDocument` 用 Spring 数据库事务依次调用所有 `ChunkSink`。关系块落点优先执行“删该 doc 的旧块，再插新块”，向量落点再执行“删旧向量，再插新向量”。当前默认 PGVector 与关系块共用 PostgreSQL，因此抛错能让这两类数据库改动一起回滚，通常继续保留事务前的旧块与旧向量。
- Spring 事务不覆盖对象存储、模型调用、Milvus 或其他外部服务；Embedding 费用不会回滚。若把主向量后端换成外部服务，它在失败前做出的部分删除/写入也不受本地事务保证。
- 可选 ES 关键词索引和 LightRAG 图索引是向量服务外层的 best-effort 装饰器：它们失败时只记告警并吞掉异常，主 PG/向量写入仍可成功，文档仍可被标为 `SUCCESS`，外部索引则可能继续是旧数据或缺数据。当前没有 Outbox、重放队列或增量对账把它们自动补齐；这些只能作为 09 第 6.2 节的扩展比较，不能写成现有恢复机制。

## 5. 远程刷新：调度器判断变化后用新文件重跑同一摄取业务

远程刷新不是上传请求或在线问答的一部分。它先依靠调度表和租约选出到期任务，再判断远端是否变化；只有变化才占用文档并处理新文件。

- 后台扫描器按固定延迟运行，当前配置默认每 10 秒扫描一次。它从调度表选择 `enabled=1`、`next_run_time` 为空或已到期、`lock_until` 为空或已过期的行，按下次时间排序并限制批量数，当前默认最多 20 条。
- 对每条候选，`ScheduleLockManager.tryAcquire` 做条件更新：只有锁仍为空或过期，才写唯一 `lock_owner` token 和新的 `lock_until`；影响行数大于 0 才得到 `ScheduleLockLease`。取得租约后把任务交给专用执行器，线程池拒绝时立即按 token 条件释放租约。
- 处理器启动时先主动续约确认所有权，再启动心跳。
  - 锁 TTL 至少 60 秒，当前配置 900 秒；心跳间隔取 TTL 的约三分之一并限制在 5～60 秒，所以当前通常每 60 秒续约。
  - 每次续约和最终释放都要求调度 ID 与 `lock_owner=本 token`。网络异常在安全窗口内只告警并继续重试；自上次确认已超过 TTL，或条件更新影响 0 行，心跳才标记租约丢失。
- 处理器重新读取调度行和文档，校验文档存在、未删除且启用，并确认仍是 URL 来源、调度开关打开且 cron 可计算。
  - 文档不存在/删除、文档停用、调度关闭、cron 非法或算不出下次时间时，只在仍持有租约的条件下禁用调度、清空下次时间并记录失败原因，然后结束。
  - 正常时先插入一条调度执行记录，状态 `RUNNING`，保存 schedule/doc/kb、开始时间；同时计算并暂存本次 `nextRunTime`，结束时才条件写回调度主表。
- `RemoteFileFetcher.fetchIfChanged` 读取调度表上次保存的指纹，并按下面顺序判断。
  - 先尽力 HEAD。若当前和上次 ETag 都非空，则 ETag 相等直接跳过；否则只要当前 Last-Modified 非空且等于上次值，也直接跳过。这两条命中时不会 GET 正文。
  - 无法用头信息确认未变化时才 GET 到临时文件，同时限制大小并计算 SHA-256；内容为空失败，hash 等于上次 `last_content_hash` 时删除临时文件并跳过。
  - 初次 URL 上传没有把 ETag/hash 写入调度表，所以第一次到期刷新通常不能凭登记时指纹跳过，可能会下载并当作变化处理；从一次调度成功或未变化跳过开始，调度表才保存可供下次比较的指纹。
- 判断未变化时，处理器不碰文档状态、原文件、chunk 或向量；它在仍持有租约的条件下把调度主表写成 `SKIPPED`，更新本次/下次时间与新指纹，并把执行记录写成 `SKIPPED` 和结束时间，然后释放租约。
- 判断有变化时，处理器先检查文档快照是否已经 `RUNNING`，再用 `DocumentStatusHelper.tryMarkRunning` 争抢文档运行权。
  - 条件是 `id` 匹配、未删除、已启用且状态不是 `RUNNING`；成功时写 `RUNNING`、`system` 和当前 `update_time`。失败就把本次调度记为跳过，不覆盖正在执行的人工切分。
  - 取得运行权后查知识库，把下载临时文件上传到知识库的 `collectionName` 目录，得到新对象。数据库此时仍指向旧 `file_url`；处理器只在内存中的 `runtimeDoc` 换上新文件名、URL、展示类型、大小和 `system` 操作者。
- 在真正切分前再次确认租约，随后设置系统用户上下文，调用 `documentService.chunkDocument(runtimeDoc)`，最后清理上下文。
  - 该方法走第 4 节相同的 `runChunkTask`，所以读的是内存 `runtimeDoc` 的新对象字节，使用数据库中同一 docId、同一知识库和摄取规格，按文档 replace 旧块/向量；内部错误通常被记录为文档 `FAILED` 后正常返回。
  - 调度器因此必须再查文档状态，只有最新状态严格为 `SUCCESS` 才认为摄取成功；否则将调度主表与执行记录记为 `FAILED`，不切换数据库文件元数据，并在 finally 删除刚上传的新对象。旧对象和通常仍可保留的旧索引继续存在，但文档页面状态是失败。
- 摄取成功后，处理器才把数据库文档的 `doc_name/file_url/file_type/file_size` 切到新对象，再条件写调度成功状态、成功时间、下次时间和新 ETag/Last-Modified/hash，执行记录也写成功及新文件信息。
  - 文件切换不重新计算 `document_key/document_version/demo_data`，也没有在 `applyRefreshedFileMetadata` 中写 MIME；MIME 已由 `runChunkTask` 的字节探测另行回填。因此远端响应文件名改变时，展示名会变，最初按文件名得到的文档身份字段却仍保持原值。
  - 完成文件切换后 finally 删除旧对象；删除失败只告警，旧对象可能成为孤儿。若分块已经完成但文件元数据切换失败，代码保留新对象供诊断，不删除它，旧元数据仍可能指向旧对象，而新索引已经写入。

### 刷新租约丢失与 RUNNING 超时不是同一种恢复

- 刷新租约只限制“哪个调度执行者可以继续推进并写调度主状态”。在领取文档运行权或开始切分前发现租约丢失时，处理器写一条租约丢失的执行记录，停止后续动作；如果已经把文档占为 `RUNNING` 但尚未启动切分，finally 用 `RUNNING` 条件把文档改为 `FAILED`，并删除本次新对象。
- 心跳不能中断正在同步执行的解析、Embedding 或索引。若租约在 `chunkDocument` 内部丢失，处理器没有取消句柄；方法返回后仍可能继续切换文件。之后写调度主表只检查 `lock_owner` token，不再检查 `lock_until`：若尚无人改写 owner，过期 token 仍可能写成功；若新持有者已经换掉 owner，更新影响 0 行，只给执行记录附上租约失效说明，不覆盖新持有者的调度状态。若文件已经切换而“写调度成功状态”本身抛错，catch 只把执行记录补成“刷新成功（调度状态写回失败）”，保留成功文档并删除旧文件。
- 另一条独立定时任务每 60 秒扫描文档表中 `enabled=1`、状态 `RUNNING` 且 `update_time` 早于阈值的行。阈值配置默认 30 分钟，并强制至少 10 分钟；它先记录候选 docId，再以“仍为 RUNNING”为条件批量改 `FAILED`，比较候选数和实际影响行数。
  - 这条扫描既覆盖人工 MQ 任务，也覆盖调度任务留下的文档状态；它不读取调度租约，也不取消仍在运行的线程、模型请求或索引写入。超时后旧执行者仍可能继续写 `SUCCESS`，所以它只是让页面和人工重试不永久卡住，不是 fencing 或副作用回滚。

## 6. 资料登记后的独立维护操作

这些操作各自有 Controller 请求和事务边界，不是摄取完成后的固定尾步骤。共用的解析、Embedding、关系块与向量写入细节仍归流程 C；这里关注操作语义、当前检索何时改变和失败后留下什么。

### 6.1 更新文档信息不会自动更新原文件或索引

- `PUT /knowledge-base/docs/{docId}` 先查文档；状态为 `RUNNING` 时直接拒绝。请求必须带非空 `docName`，所以它不是任意字段的 PATCH。
- Service 更新数据库中的显示名；若带 `processMode`，则重新校验并保存 direct 摄取规格或 Pipeline ID。URL 文档还可更新来源地址、调度开关和 cron，并在数据库更新后 `upsertSchedule`。
- 这个请求不上传新原文件、不重新解析、不重算文档身份、不改 `PENDING`，也不替换旧 chunk/向量。因此改显示名、分块预算或解析档位后，当前检索仍使用旧索引；要让新配置生效，还需另行点击“开始切分”。
- 改 URL 后，现有调度行的上次 ETag/Last-Modified/hash 没有清空；若新旧来源碰巧给出相同校验值，下一次刷新可能被判未变化。当前没有“来源地址变化即重置指纹”的处理。

### 6.2 重新切分会整体替换块，不替换原文件

- 对 `FAILED`、`SUCCESS` 或 `PENDING` 文档再次调用开始切分，会重新经过第 3 节的 `status <> RUNNING` 条件和同一 MQ 流程。原文件仍是文档当前 `file_url` 指向的对象；普通重新切分不会生成或删除原文件。
- 从状态变为 `RUNNING` 到内核索引阶段之前，旧块/向量不会先被删除，默认检索仍可能读到旧内容；真正 replace 时才删除该 doc 的旧块/向量并写新数据。
- 内核在索引前失败时旧索引通常保留，但文档被标 `FAILED`；默认向量 SQL并不按文档状态过滤，因此旧向量仍可能被问到。索引替换成功而终态写失败时则相反：新索引可能已经可检索，页面仍显示失败。没有版本化 staging/active 发布来保证“状态与可读版本同时切换”。

### 6.3 启用和停用直接改变默认向量检索可见性，但保留关系块与原文件

- `PATCH /knowledge-base/docs/{docId}/enable?value=false` 在文档为 `RUNNING` 时拒绝；状态已等于目标值时直接返回。
- 停用在一个 Spring 事务中把文档 `enabled=0`，同步已有调度为禁用，把该文档所有关系 chunk 设为禁用，并删除该 doc 的向量。原文件、文档行和关系 chunk 都保留，所以以后可以重新启用。
  - 当前默认 PG 向量查询只按 collection 过滤，不额外 join 文档状态；因此“停用后检索不到旧块”依赖向量删除成功，而不是查询时动态检查 `document.enabled`。默认 PG 删除成功后旧块不会从向量通道返回。
  - 可选 ES/LightRAG 删除失败会被 best-effort 装饰器吞掉，外部索引可能残留；当前这些检索通道默认关闭。若用外部主向量后端，Spring 数据库事务也不能撤销其部分副作用。
- 启用先在事务外读取数据库中保存的全部 chunk，用各自 `embedding_text` 和知识库模型重新计算向量；Embedding 失败时数据库启用状态不变。然后在事务中把文档及全部 chunk 设为启用、同步调度，并写回向量。
  - 这会把以前单独禁用的 chunk 一并启用，不保留逐块停用选择。若文档没有任何 chunk，只告警并更新启用状态，不会凭原文件自动切分。

### 6.4 人工新增、修改、启停或删除块会同步改当前索引

- 人工新增 chunk 要求文档不为 `RUNNING` 且已启用；正文非空。Service 确定序号，计算 hash、字符数和 token 数，把人工块的 `embedding_text` 直接设为正文，先插关系行并给文档 `chunk_count + 1`，再用知识库 `VectorTarget` 生成向量并写索引。
- 人工修改和删除都在文档 `RUNNING` 时拒绝，并校验 chunk 确实属于该 doc。
  - 修改正文后同步改 hash、字符/token 数与 `embedding_text`，再重算并 upsert 同 ID 向量；原文件不会反向修改，所以以后整体重新切分会按原文件覆盖这次人工内容。
  - 删除先删关系 chunk、把文档块数最低减到 0，再删除同 ID 向量；原文件同样不变，重新切分可能再次产生语义相同的块。
- 单块或批量启停也在文档 `RUNNING` 时拒绝。启用 chunk 还要求整份文档已启用；停用会改关系状态并删向量，启用会按库中 `embedding_text` 重新嵌入后写向量。批量请求必须明确给出最多 500 个都属于该文档的 ID。
- 这些方法把关系更新和当前默认 PG 向量写放在 Spring 事务范围内，但模型调用、对象存储与外部索引仍不受事务覆盖。尤其当前“修改 chunk”没有检查文档或 chunk 是否启用：对已停用文档/块做人工修改仍会调用向量 `updateChunk`，而默认检索不检查 enabled，可能重新插入一个可召回向量。这是当前行为，不能概括成“停用状态永久保证不可检索”。

### 6.5 删除文档会删登记、索引和原文件，但原文件清理是 best-effort

- `DELETE /knowledge-base/docs/{doc-id}` 先查文档，`RUNNING` 时拒绝。随后在数据库事务中删除该 doc 的调度执行记录和调度行、分块日志，逻辑删除文档，再让统一索引写入器删除关系 chunk 和向量。
- 索引删除成功后，Service 尝试删除文档当前 `file_url` 对象；该异常被捕获并只记告警。因此请求仍可提交数据库删除，原文件可能成为没有元数据指向的孤儿。解析过程中另行生成的图片资产是否全部清理由当前这条方法没有给出通用证明。
- 如果索引删除向外抛异常，数据库事务会回滚本地逻辑删除和本地块删除；但已经对外部向量服务做出的删除不能由 Spring 自动恢复。当前没有删除 tombstone、跨存储完成清单或后台通用补偿。

## 7. 独立 `/ingestion/tasks` 引擎：可选入口，不等于知识文档 Pipeline 已接通

仓库还有一套按节点定义执行的通用摄取任务。它有自己的任务表、节点日志和同步 HTTP 返回，不能和上面的“知识文档登记 + RocketMQ 消费”混成同一默认链。

- `POST /ingestion/tasks` 接收 `pipelineId`、`source(type/location/fileName/credentials)` 和可选 `vectorSpaceId`；`POST /ingestion/tasks/upload` 则接收 Pipeline ID 与文件，先把文件一次性读成 byte[]、探测 MIME。两者都在当前 HTTP 线程同步执行，并返回 `IngestionResult(taskId, pipelineId, status, chunkCount, message)`。
- Task Service 要求请求、来源类型和 Pipeline ID存在，读取 Pipeline 定义，然后先插一条 `RUNNING` 任务行，构造 `IngestionContext`。
  - 普通入口把 source 与 `vectorSpaceId` 放进上下文，上传入口还放原始字节与 MIME；请求里的 `metadata` 当前没有写入上下文，是一个未接通字段。
  - 引擎先把节点列表按 ID 建图，拒绝环、缺失的 next 节点、零个或多个起点；然后从唯一入口沿 `nextNodeId` 串行执行。节点条件不满足时记 skipped 结果并按 `NodeResult` 的继续标志流转；节点返回失败或抛异常时记录 NodeLog，把上下文置为 `FAILED` 并停止后续节点；走完仍为 RUNNING 才改 `COMPLETED`。
  - Fetcher 可从来源取得字节，Parser 生成结构文档，Chunker 生成 chunk 并做 Embedding，Indexer 附加允许的管线 metadata 并写向量；每个节点的输入输出都通过同一个 `IngestionContext` 传给下一节点。
- 引擎返回后，Service 把节点日志逐条写 `t_ingestion_task_node`，再把任务终态、块数、错误、概要日志和 metadata 写回任务表，最后把概要结果返回 Controller。节点内部失败通常成为一个 `status=FAILED` 的正常响应；图结构校验等发生在节点执行外的异常会继续抛出，并因外层数据库事务回滚任务行。
- 当前能力有两个必须就地说明的限制。
  - 知识文档 `runChunkTask` 在 `processMode=pipeline` 处直接失败，被注释掉的 `runPipelineProcess` 不会调用这套引擎。因此上传知识文档时选 pipeline，不能走到 `/ingestion/tasks` 的节点链。
  - 独立任务当前只把请求的 `vectorSpaceId` 放进上下文，没有构造 ChunkerNode 要求的 `VectorTarget(partition, embeddingModel, dimension)`；执行到 ChunkerNode 会因缺向量落点失败，除非某条特殊 Pipeline 不经过该节点或以后补齐契约。代码存在和节点可编排，不等于当前请求已具备知识库 direct 主链的可执行能力。

## 8. 用几个业务情形把状态推到底

| 情形 | 当前实际结果 | 怎样继续 |
| --- | --- | --- |
| 上传完立即提问 | 只有原文件与 `PENDING/chunk_count=0` 文档，默认向量表没有新资料 | 另行点开始切分，等待文档与日志进入 `SUCCESS`；上传响应本身不是可检索证明 |
| 连续点击开始 | 第一个本地回调通常取得 `RUNNING`；第二个条件更新影响 0 行并回滚自己的消息，但 HTTP 仍可能普通成功 | 看文档状态与 chunk 日志，不用第二次 HTTP 200 推断产生了两个有效任务 |
| 解析报错 | 主体 catch 写文档与日志 `FAILED` 后正常结束消费；旧索引若原来存在通常仍可能被检索 | 修正原文件/解析配置后人工再次启动；当前不是靠 MQ 自动重试原解析错误 |
| 远端内容没变 | ETag 优先，其次 Last-Modified，再到下载后的 hash；命中后只写调度 `SKIPPED` 与新指纹 | 保留当前文件和索引，等待下个 `next_run_time` |
| 刷新前租约丢失 | 停止推进；已占用的 `RUNNING` 文档条件改 `FAILED`，新对象按阶段删除 | 新租约持有者按调度状态继续；旧执行者不应覆盖主调度行 |
| 刷新在摄取中丢租约 | 同步摄取不会被取消，仍可能完成索引和文件切换；调度主表只按 owner token 写，owner 已换才会拒绝旧执行者 | 以执行记录、文档状态、文件 URL 与索引共同排查，不能只看调度 last_status |
| 外部索引写失败 | ES/LightRAG 当前只告警并吞错，文档仍可能 SUCCESS；外部主向量后端失败则主体记 FAILED，但外部部分副作用不保证回滚 | 当前没有 Outbox/通用对账自动修；需要人工重摄或按后端诊断 |
| 索引成功、成功状态写失败 | catch 可能把文档记 FAILED，新块/向量仍保留；catch 再失败则异常到 MQ，可能重复执行 | 对照 chunk 日志、块数和实际向量，不以单一状态判断 |
| 文档停用 | 成功删除默认 PG 向量后不再从默认向量通道召回；关系块和原文件保留 | 可重新启用并重嵌入；注意停用后人工改块当前可能重新 upsert 向量 |

## 9. 与扩展方案的边界

当前已有稳定文档键/版本、按文档整体 replace、远程 ETag/Last-Modified/hash 和调度租约，但没有[旧 09 第 6.2 节](../09-core-business-chain-review.md#62-生产知识生命周期增量更新与索引发布)设想的版本化 staging 索引、质量门禁、active manifest/alias 原子发布、Outbox 重放、tombstone、跨存储增量对账或通用 GC。现有恢复主要是条件状态更新、失败日志、人工重新切分、调度下次再跑和 RUNNING 超时置失败；不能用扩展方案替它解释当前失败结果。

## 10. 源码反查入口

- [KnowledgeBaseController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java) 与 [KnowledgeBaseServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java)：创建知识库、保存模型/分区并准备对象目录与向量空间。
- [KnowledgeDocumentController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java) 与 [KnowledgeDocumentServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)：上传登记、事务消息、本次摄取状态、更新/启停/删除。
- [KnowledgeDocumentChunkConsumer.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java)、[KnowledgeDocumentChunkTransactionChecker.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkTransactionChecker.java) 与 [DelegatingTransactionListener.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/DelegatingTransactionListener.java)：消费者上下文、本地事务和 Broker 回查边界。
- [DefaultIngestionKernel.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/DefaultIngestionKernel.java)、[IngestionSpecCodec.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/IngestionSpecCodec.java)、[VectorTargetResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/VectorTargetResolver.java) 与 [DocumentIdentityResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/DocumentIdentityResolver.java)：固定内核的输入输出、规格、落点和文档身份。
- [KnowledgeDocumentScheduleJob.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/KnowledgeDocumentScheduleJob.java)、[ScheduleRefreshProcessor.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java)、[ScheduleLockManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleLockManager.java)、[ScheduleStateManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleStateManager.java) 与 [RemoteFileFetcher.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/handler/RemoteFileFetcher.java)：到期选择、租约、判变、文件切换与清理。
- [KnowledgeChunkServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java)：人工块维护和文档启停时的重嵌入/索引变化。
- [IngestionTaskController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/controller/IngestionTaskController.java)、[IngestionTaskServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IngestionTaskServiceImpl.java) 与 [IngestionEngine.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java)：独立任务入口、节点执行和结果落库。
- [schema_pg.sql](../../../resources/database/schema_pg.sql)：知识库、文档、块、向量、摄取日志和调度表的当前 PostgreSQL 结构。

静态核对时需要修正旧 09 的一句概括：不能说“摄取任一步抛错后由消息重试决定下一步”。当前 `runChunkTask` 主体会捕获异常并落 `FAILED`，成功记录失败后异常不会到 MQ；只有 catch 外初始化错误或 catch 内二次写失败等仍向外抛出的异常，才可能进入消费者失败与重投路径。另一个修正是：知识文档 Pipeline 主链当前显式不可用，独立 `/ingestion/tasks` 引擎存在也不能替代这一事实。
