# 模型路由与故障切换：业务请求最终交给谁，返回什么

本文从业务层已经构造好的模型请求开始，说明 `infra-ai` 怎样选候选、调用 provider、判断成功或失败，并把结果或取消句柄交还上层。Prompt 为什么这样写、检索证据怎样形成，由相应业务流程篇解释；Redis 跨实例取消、回答落库和最终业务状态由流程 F 继续负责。

当前主配置登记了聊天、Embedding、Rerank 和 VLM 四组模型。这里的“登记并启用”只说明候选能参加路由；环境变量、网络、账号额度和远端服务是否实际可用，仍要到调用时才能知道。本篇不会记录凭证，也不把静态配置当作真实模型调用证据。

## 1. 先分清五类调用：一次业务不等于依次调用全部模型

| 业务动作 | 交给模型的主要输入 | 路由层拿回什么 | 调用形态 |
| --- | --- | --- | --- |
| 问题改写、意图判断、任务草案、标题或摘要 | 业务层已装好的 `messages`，其中可能要求普通文本或约定 JSON | 一个完整字符串；JSON 仍由业务层清理、解析和校验 | 同步 Chat |
| 最终回答 | 规则、历史、检索上下文和本次问题组成的 `messages` | 多次内容/思考增量回调，外加完成或错误事件；启动方法返回取消句柄 | 流式 Chat |
| 文档或 query 向量化 | 单个字符串或字符串列表 | 每个输入对应的浮点向量 | 同步 Embedding |
| 候选重排 | query、有限候选的排序文本和 `topN` | 映射回原候选的列表，命中项可能带新相关分 | 同步 Rerank |
| 图片解析 | 文本指令、图片 MIME 和图片字节 | 一段图片描述/OCR 文本 | 同步 VLM，多模态输入 |

- 这些是按业务需要分别触发的调用，不是一条固定的“Chat → Embedding → Rerank → VLM”模型流水线。
  - 例如改写可能先同步调用 Chat，检索时另调 Embedding 和 Rerank，最终生成再流式调用 Chat；纯系统回答可以不检索，普通文本摄取也不会调用 VLM。
  - 同步 Chat 返回 JSON 字符串，不代表路由层理解了 JSON；它只确认 provider 返回了非空白 `choices[0].message.content`。字段是否合法、内容是否符合业务事实，仍由改写或任务流程判断。
- 所有 OpenAI 风格 Chat 适配器都会发送消息列表，并按非空情况发送 `temperature`、`top_p`、`top_k`、`max_tokens`；还会发送 `enable_thinking=true/false`。
  - `enableTools` 目前只是 `ChatRequest` 的预留字段，当前适配器没有把工具定义或该开关写入请求体，不能据此说这些调用已启用原生 Tool Calling。
  - 同步响应只抽取普通 `content`；流式解析在 `thinking=true` 时才读取 `reasoning_content` 并走思考回调。

## 2. Chat 候选怎样产生

业务层把 `ChatRequest` 交给 `RoutingLLMService`。路由先决定本次应尝试的模型 ID 顺序，再把每个 ID 解析成含候选配置、provider 配置和本次超时的 `ModelTarget`。

- 路由先确定本次聊天档位。
  - `thinking=true` 且配置了 `deep-thinking-tier` 时，优先使用深度思考档；这个判断发生在显式 `Tier` 覆盖之前。因此即使同步调用传了 `Tier.FAST`，思考请求仍走 `deep`。
  - 不要求思考时，调用点传了 `Tier` 就用该档位；未传则使用 `ai.chat.default-tier`。
  - 当前主配置的 `fast` 候选依次是 `qwen-flash → qwen-plus → qwen3-local`，每个候选的调用/首包预算为 5 秒；`standard` 是 `qwen3-max → qwen-plus → qwen3-local → gpt-5.4`，每个为 30 秒；`deep` 是 `qwen3-max → glm-4.7`，每个为 120 秒。这里是仓库主配置，不证明某次部署没有被 profile 或环境覆盖。
- 如果同步入口还传了 `preferredModelId`，选择器先尝试把它放到队首，再追加所选档位的有序候选并按 ID 去重。
  - preferred 是“先试它，失败后仍可回退档位候选”，不是“只准调用它”。它即使不属于该档位，也会使用本次所选档位的超时预算。
  - preferred 未登记时只记警告并忽略；思考请求中的 preferred 若没有声明 `supports-thinking=true`，也会被忽略。
- 选择器随后逐个过滤候选。
  - 候选没有显式写 `enabled` 时，配置类缺省为 `true`；只有明确为 `false` 才被过滤。
  - `thinking=true` 时，档位候选也必须声明支持思考。
  - provider 配置缺失时不能形成目标；`noop` 是唯一允许没有 provider 连接配置的特殊候选。
  - 健康存储判断候选仍在熔断冷却期，或正有另一个半开探针占用名额时，也会先把它排除。冷却刚结束的 OPEN 候选不会在这里被排除，后面的调用许可会把它转成 HALF_OPEN 探针。
- 启动阶段的 `ChatTierConfigValidator` 会检查默认档、思考档和 `Tier` 枚举引用是否存在，每档候选是否登记，以及每档 `timeout-ms` 是否为正数。
  - 深度档必须至少有一个已启用且支持思考的候选；单个深度档候选未声明思考能力会告警。结构性错误会阻止应用启动，而不是等用户请求时静默选错。

### 2.1 不同 Chat 入口实际支持的路由参数

| 入口 | thinking | 显式 Tier | preferred model |
| --- | --- | --- | --- |
| `chat(request)` | 支持 | 不支持，走默认/思考档 | 不支持 |
| `chat(request, tier)` | 支持，且思考档优先于 tier | 支持 | 不支持 |
| `chat(request, tier, preferredModelId)` | 支持，且思考档优先于 tier | 支持，作为 preferred 失败后的档位 | 支持 |
| `streamChat(request, callback)` | 支持 | **不支持** | **不支持** |

- 当前最终回答只能通过最后一个流式入口，所以它依据 `thinking` 选择默认 `standard` 或 `deep`，不能像标题、改写或入库增强那样传 `Tier.FAST`，也不能指定 preferred。
- `EnricherNode`、`EnhancerNode` 的同步调用会把配置模型作为 Chat preferred；这与后文 Embedding 的“显式指定单一模型”语义不同，不能只看到参数名都是 `modelId` 就认为路由相同。

## 3. 同步 Chat：逐候选得到完整文本

同步 Chat 用于改写、分类、任务草案等需要先拿完整文本再继续的步骤。路由收到已选好的目标列表后，在当前调用线程按顺序尝试；前一个明确失败才进入下一个。

- 对每个 `ModelTarget`，执行器先用候选中的 provider 字符串到 `clientsByProvider` 查 `ChatClient`。
  - 这些 client 是 Spring 当前注册的适配器，按其 `provider()` 组成映射。候选有 provider 配置但没有对应 client 时，只记“client missing”并跳过，不把该模型记为失败。
- 找到 client 后，执行器向 `ModelHealthStore.allowCall(modelId)` 申请本次许可。
  - CLOSED 模型直接取得普通许可；仍在 OPEN 冷却期或已有 HALF_OPEN 探针时返回 `null`，本次请求跳过它。
  - 许可只是进程内熔断并发门禁，不是供应商配额、业务限流或费用授权。
- 取得许可后，client 把同一个 `ChatRequest` 与当前目标转成 provider 请求。
  - URL 优先用候选自己的 `url`，否则拼 provider 基址与 chat endpoint；远端模型名取候选的 `model`，不是内部候选 ID。
  - 当前 OpenAI 风格同步 client 校验 provider、模型以及非 Ollama provider 的 API key，发送 HTTP 请求，要求成功状态和可解析 JSON，再抽取非空白的 `choices[0].message.content`。
  - 命中档位的 `timeout-ms` 会派生同步 HTTP client，覆盖本次候选的 read timeout 和 call timeout；连接与写入超时仍继承基础 client。因为 call timeout 也被设置，它约束的是这个候选从 HTTP 调用开始到完整响应的总时长。
- client 正常返回字符串时，执行器立即 `markSuccess(modelId)`，结束候选循环，把字符串交回业务层。
  - “成功”在这里表示适配器得到了合格的非空文本，不表示 JSON 业务字段正确、改写合理或答案正确。后续解析失败是否回退原问题，由调用该同步 Chat 的业务流程决定，不会回到本层改试另一个模型。
- client 抛出异常时，执行器保存该异常、`markFailure(modelId)`，记录本次模型/provider 失败，然后尝试下一个已选目标。
  - 候选 HTTP 可能已被远端接收；本地超时或异常不能证明供应商没有开始推理、停止计费或没有留下请求记录。
- 如果候选列表一开始为空，直接抛 `RemoteException("No Chat model candidates available")`。如果列表非空但所有可尝试候选都失败或被跳过，抛带 `REMOTE_ERROR` 的 `RemoteException`，消息为 `All Chat model candidates failed` 并附最后一个异常消息；若没有实际异常则原因是 `unknown`。
  - 上层收到的是异常，不是空字符串。具体业务可能捕获后改用固定标题、原问题或空结果，那是上层降级，不是模型路由返回了这些值。

### 3.1 同步超时和候选总尝试不是同一个预算

- 档位 `timeout-ms` 是**每个候选**的完整同步调用上限，不是整个候选循环的共享 deadline。
- 当前没有额外的“本次最多尝试 N 个”或“所有候选合计 X 秒”配置；最大尝试数由筛选后候选列表长度决定。preferred 不在档位中时，还可能在档位列表前多出一次尝试。
- 因而按静态上界估算，`standard` 四个候选都超时可能接近 `4 × 30 秒`，外加循环和少量本地处理；这不是 30 秒内保证完成。OkHttp 的连接重试也发生在单候选 HTTP 调用内部，不等同于路由切换模型。

## 4. 流式 Chat：先隔离探测，再决定是否把这个流交给上层

最终回答调用 `streamChat(request, callback)`。这里不能等完整答案后再判断模型是否可用，所以路由用 `ProbeStreamBridge` 暂时隔开 provider 回调与业务回调，只在首个有效内容出现后提交这条流。

- 路由按第 2 节产生默认档或思考档候选；列表为空时直接抛“无可用大模型提供者”。这一条发生在候选循环前，本层没有先调用业务 `callback.onError`。
- 对每个候选，路由查 provider client 并申请健康许可，规则与同步调用相同。通过后为**这个候选**新建一个 bridge。
  - bridge 的上游是 provider client；下游是流程 F 已建立的业务 `StreamCallback`。探测期间到达的内容、思考、完成和错误动作先存入 bridge 的缓冲列表，不立刻发给用户。
- 路由调用 `client.streamChat(request, bridge, target)` 启动 provider 流，并取得 `StreamCancellationHandle`。
  - 当前 OpenAI 风格 client 先构造 `stream=true` 的请求，再把实际 OkHttp 读取任务提交到模型流线程池，因此“启动方法返回句柄”不等于 HTTP 已连接，更不等于已经收到模型内容。
  - 线程池拒绝时，异步提交器会取消尚未使用的 HTTP call，通过 bridge 报错，并返回 noop 句柄；首包探测随即看到 ERROR，仍会按失败候选处理。
  - 启动方法同步抛错或返回 `null` 句柄时，本候选记失败并尝试下一个。`null` 句柄分支无法取消一个可定位的旧调用；当前内置 client 正常会返回非空句柄。
- 拿到句柄后，`LlmFirstPacketProbe` 最多按当前目标的档位 `timeout-ms` 等待 bridge 的第一次探测结果。这个独立 bean 让 Trace 能记录 TTFT 子节点。
  - 当前 OpenAI SSE 解析器跳过空行和没有 choices 的事件；普通 `content` 必须非空白才调用 `onContent`，思考 `reasoning_content` 必须非空字符串才调用 `onThinking`。
  - bridge 收到 `onContent` 或 `onThinking` 就把探测结果设为 SUCCESS。也就是说，深度模型先返回思考增量也算首包成功，并不要求先有最终答案文字。
  - 在任何内容前收到 `onError` 是 ERROR；先收到 `onComplete` 是 NO_CONTENT；预算内什么回调都没到是 TIMEOUT。HTTP 建连、200 状态、SSE 注释/空事件本身都不算探测成功。
- 如果结果是 SUCCESS，bridge 在锁内把此前缓冲的第一个及并发到达的事件按保存顺序转发给业务 callback，并切换为 committed；之后的新回调直接透传。
  - 路由把模型记为成功并返回该 provider 的取消句柄。`StreamChatPipeline` 随后把句柄交给流程 F 的 `StreamTaskManager.bindHandle(taskId, handle)`；如果取消信号已经先到，F 会在绑定时立即调用句柄。
  - provider 的异步读取仍继续使用 bridge，因此后续内容、思考、完成或错误会到同一个业务 callback。路由返回句柄不代表流已经结束。
- 如果结果是 ERROR、TIMEOUT 或 NO_CONTENT，路由不提交该 bridge 的缓冲，先把模型记为失败，再调用旧句柄的 `cancel()`，然后尝试下一个候选。
  - 取消句柄会设置本地 cancelled 标志并调用 OkHttp `Call.cancel()`，目标是停止本地读取和连接；它不能保证供应商已经停止推理或不再计费。
  - 等待线程被中断是另一条分支：路由恢复线程中断标记，先取消旧句柄，再按 token 释放自己持有的 HALF_OPEN 探测许可，通过业务 callback 报“流式请求被中断”，并立即抛异常，不再尝试下一个模型。普通 CLOSED 许可无需释放。
- 如果所有候选都失败，路由构造“`大模型调用失败，请稍后再试...`”的 `RemoteException`，先调用业务 `callback.onError`，再向调用者抛出同一个异常。
  - 流程 F 的当前事件处理器在 `onError` 中注销任务并以错误关闭 SSE；路由层不负责回答落库、Trace 最终状态或跨实例取消。

### 4.1 两条时间线：首包前可切，首包后不拼另一个模型

以下时间仅为帮助理解的假设，不是运行记录。假设本次是 `standard`，模型 A 的首包预算为 30 秒，模型 B 可用。

```text
模型 A 连接成功但不返回内容
t=0s    路由启动 A，取得取消句柄；bridge 尚未向用户转发任何东西
t=1s    HTTP/SSE 可能已连接，但没有 content/thinking 回调，因此仍不算首包成功
t=30s   首包探测 TIMEOUT；A 记失败，调用 A.cancel()，A 的 bridge 永不提交
t=30s+  路由启动 B，使用新的 bridge 和句柄
t=31s   B 返回第一个 content；bridge 提交并把该片段发给用户，B 记成功
t=31s+  B 的句柄返回给流程 F，后续 B 回调继续透传
```

- 用户看到的回答只从 B 开始，因为 A 在任何内容对外可见前就被隔离并取消。这解决的是首包可用性，不证明 A 的远端推理已经停止。

```text
模型 A 已经发出一段文字后报错
t=0s    启动 A并等待
t=2s    A 返回第一段 content；bridge 提交，这段文字已经发给用户，A 被记为成功
t=5s    A 的流读取报错；bridge 直接把 onError 转给流程 F
结果     当前 RoutingLLMService 不再启动 B，用户得到部分文字和流错误
```

- 第二种情况不能在同一 SSE 回答尾部无感拼接 B：B 不知道 A 实际生成到哪里，不同模型也可能采用不同论证、格式和安全策略。若产品以后选择重启，只能明确丢弃/标记旧片段并创建新的回答语义，不能承诺仍是一段连贯的同一答案。
- 需要始终分开三层结论：
  - **传输成功**：连接或 HTTP 状态可用，只说明请求/响应通道建立到某一步。
  - **首包成功**：预算内出现 content 或 thinking 回调，说明这条流开始产生可转发增量。
  - **答案正确**：内容被证据支持并满足业务约束，需要检索与答案评测；路由和首包探测都不判断这一点。

### 4.2 流式的三层时间边界

- **本候选首包预算**：档位 `timeout-ms` 对流式路径只约束等待首个 content/thinking，不约束首包后的完整生成时长。
- **候选总尝试**：当前没有共享总 deadline；候选按顺序各拿一份完整首包预算。因此四个 `standard` 候选都无首包时，路由层理论等待可接近 `4 × 30 秒`。一旦某个候选首包成功，循环结束。
- **外层聊天/SSE 超时**：Controller 创建 `SseEmitter` 时使用 `rag.default.sse-timeout-ms`，当前主配置为 300000 ms。它从外层连接生命周期限制 SSE，范围还可能包含排队与前置业务，不等同于某个 provider 的 TTFT。
  - 当前 `SseEmitterSender` 的 timeout 回调只把发送器标为 closed；从所读代码看，这条回调本身没有调用模型句柄或 `StreamTaskManager.cancel`。所以外层连接超时也不能表述成供应商一定停止生成或计费。
  - 流式 HTTP client 自身的 read timeout 和 call timeout 为无限，首包后的停止主要依赖正常完成、错误或显式句柄取消。

## 5. 健康状态怎样跨请求累计

`ModelHealthStore` 是 Spring 进程内单例，用 `ConcurrentHashMap<modelId, ModelHealth>` 跨本 JVM 的请求保存连续失败、状态、冷却截止时间和半开探针占用情况。它不是 Redis 状态：应用重启会清空，多实例之间也不共享；键只有模型 ID，若不同能力错误复用同一 ID，也会共享健康记录。

- 初始是 CLOSED，连续失败数为 0。
  - `allowCall` 给普通请求返回 `halfOpenToken=0` 的许可。一次成功就清零连续失败并保持/恢复 CLOSED。
  - 一次失败把连续失败数加一；达到 `ai.selection.failure-threshold` 后转为 OPEN，设置 `openUntil=当前时间+open-duration-ms`，并把连续失败数清零。当前配置阈值为 2、冷却 30 秒。
- OPEN 且仍在冷却期时，选择器的 `isUnavailable` 会过滤它；即使并发时走到 `allowCall`，许可也会被拒绝。请求直接考虑后面的候选，不向这个 provider 发调用。
- 冷却结束后，选择器允许它重新进入候选；第一个到达 `allowCall` 的请求原子地把状态改为 HALF_OPEN，设置 `halfOpenInFlight=true`，取得带唯一 token 的探测许可。
  - 其他并发请求看到 HALF_OPEN 探针正在执行，会跳过该模型，避免恢复时一拥而上。
- 半开探针被路由判定成功时，`markSuccess` 转回 CLOSED、清零计数并释放占用；探针失败时，`markFailure` 重新 OPEN 并开始新一轮冷却。
- 流式首包等待被线程中断时，代码既不能把它算作模型失败，也不能把半开名额永久占住，因此用原许可 token 调 `releaseHalfOpenPermit`。
  - 只有 token 与当前 HALF_OPEN 持有者完全一致才释放；过期请求不能误释放另一个新探针。取消旧句柄发生在释放许可之前。
- 同步通用执行器在正常返回/异常时分别调用 `markSuccess`/`markFailure`；流式路由只把“首包成功”记成功，把首包前错误、超时、无内容完成和启动失败记失败。
  - 首包成功后发生的流错误不会再更新本次模型健康状态，因为后续回调只是 bridge 透传。这是当前健康口径，不应写成“完整流成功才恢复健康”。
  - Embedding client 返回了错误维度但未在 client 内抛错时，路由会先记成功，之后摄取层的维度检查才可能报错；同理，语义质量差但协议合法不会触发熔断。熔断主要记调用/协议可用性，不是质量评分器。
  - VLM 当前没有通过 `allowCall`、`markSuccess` 或 `markFailure` 执行，因此自己的调用结果不会累计到这套健康状态，详见第 8 节。

## 6. Embedding：默认候选可切，知识库显式模型只试一个

Embedding 接收文本而不是 Chat messages，返回浮点数组而不是答案。当前主配置的默认顺序是 `qwen-emb-8b → qwen-emb-local → text-embedding-3-large`，三者都请求部署配置的 1536 维；顺序由 `default-model` 置顶，其余按 `priority`、ID 排序。

- 不带模型 ID 的 `embed(text)` / `embedBatch(texts)` 使用完整候选列表和通用同步 fallback。
  - 对每个候选查 `EmbeddingClient`、申请健康许可，再发送 `model`、输入字符串数组、`dimensions`；远端适配器通常还发送 `encoding_format=float`，Ollama 不发送这个字段。
  - SiliconFlow 和 AIHubMix client 将超过 32 条的批次按输入顺序切成多个 HTTP 子批次，再把各段结果写回对应区间。若后一个子批次失败，通用执行器会把整个调用视为该候选失败，并可能用下一候选重做整批；此前子批次可能已经在远端执行。
  - 适配器按响应 `data` 数组出现顺序收集向量，没有读取响应对象可能携带的 `index` 重新排序。因此“返回列表与输入一一按序对应”目前依赖 provider 保持 data 顺序；摄取层只能按列表下标把向量配回 chunk。
- 带模型 ID 的 `embed(text, modelId)` / `embedBatch(texts, modelId)` 语义不同。
  - 先从**当前可选择的 Embedding 候选**中精确匹配 ID，所以该模型必须已登记、enabled、provider 配置存在且未因健康状态不可用；空 ID 或找不到都会直接抛 `RemoteException`。
  - 找到后把单元素目标列表交给通用执行器。调用失败只会得到“All Embedding model candidates failed”，不会回退默认模型或其他候选。
- client 成功时返回 `List<Float>` 或 `List<List<Float>>`；它检查响应存在非空 `data` 和每项非空 `embedding`，但不在这里校验总条数和维度。
  - 摄取侧 `ChunkEmbeddingService` 随后校验向量数等于 chunk 数、每项非空且维度等于 `VectorTarget.dimension`，再按下标生成 `EmbeddedChunk`。
  - query 侧 `PgVectorRetrieverService` 把单向量转成 `float[]` 并做 L2 归一化，然后拿它与指定 collection 范围内的已存向量计算距离。

### 6.1 为什么同为 1536 维仍不能随意 fallback

- 维度只说明数组长度相同，不说明每一维表达同一语义坐标。不同模型、权重版本、query/document instruction、tokenization 或归一化方式，都可能产生彼此不可比较的 1536 维空间。
- 流程 C 的文档摄取先由 `VectorTargetResolver` 读取知识库记录的 `embeddingModel`，缺失就失败，不回落全局默认；`ChunkEmbeddingService` 再调用显式模型重载，所以文档侧只试该知识库绑定模型。
- 流程 E 的当前 PGVector query 路由却调用不带模型 ID 的 `embeddingService.embed(query)`：它从全局默认候选开始并允许切到后续候选；同一条 query 向量随后可能同时搜索 scope 内多个 collection。
- 因此当前限制是：代码能生成一个长度正确的 query 向量，不等于它与这些知识库的存量向量处于同一空间。若知识库由 `qwen-emb-8b` 建索引，而 query 因故障切到 `text-embedding-3-large`，数据库可能不会报 1536 维错误，却会计算没有业务意义的距离；一个 scope 混入不同模型建立的 collection 也有同类风险。
- 正确的契约应把 query 绑定到目标索引的模型版本、instruction、归一化和维度；不同空间分别召回后才能做名次级融合。当前源码尚未实现这样的 `vectorSpaceId` 路由，所以不能把“有 Embedding 候选 fallback”写成“索引可任意换模型”。更换模型需要重嵌文档并切换相匹配的查询路径。

## 7. Rerank：模型打分失败时可以落到 noop，但上层还有一层保序降级

当前默认候选是百炼 `qwen3-rerank`，后面是 `rerank-noop`。它们同样按 default、priority、enabled、健康状态选出，再走通用同步执行器；Rerank 接口没有显式指定模型的重载。

- 百炼 client 先处理输入候选。
  - 候选为空返回空列表；先按 `RetrievedChunkKey` 去重；`topN<=0` 返回空；去重后只有一条时直接返回这一条，不发远端请求。
  - 其余情况把 query 和每个候选的 `textForRanking()` 组成 documents 数组，发送 `top_n=min(topN, 去重候选数)`，要求返回 documents。
- provider 返回 `results` 后，client 逐项读取原候选 `index`，越界或缺 index 的项跳过；合法项映射回原 `RetrievedChunk`，若有 `relevance_score` 就只覆盖 score，其他字段整体保留。
  - 收集到 `topN` 即停止；如果合法结果不足，再按模型调用前的候选顺序补齐尚未加入的块。因此返回通常是至多 `topN` 个原候选，前部带模型顺序/分数，补位项可能保留旧分数。
- qwen3-rerank 抛错后，通用执行器将它记失败并尝试 `rerank-noop`。
  - noop **不生成分数、不去重、不改变相对顺序**。候选为空返回空；`topN<=0` 或候选数不超过 `topN` 时原样返回整表；候选更多时只返回原列表前 `topN`。
  - 在当前 `RerankPostProcessor` 中，这个头部后面还会按融合顺序追加未进入头部的候选，所以 noop 的最终效果是保留原融合候选池和原顺序，而不是返回“每项分数为 0”的伪重排结果。
- 如果连 noop 都不可用或失败，路由抛 `RemoteException`。外层后置处理器链逐处理器捕获异常，记录失败后保留进入 Rerank 前的 `chunks`，继续后续处理器；所以检索请求仍可能成功，但排序已降级为融合顺序。
  - 这层 catch 才是“全部 rerank 候选失败后的上层降级”；不能说通用路由自己返回了原列表。

## 8. VLM：名字中有 Routing，但当前只取首候选且不故障切换

VLM 用在知识入库期的图片转文本，不在最终聊天热路径。当前主配置只有 `qwen-vl-max`，provider 是百炼。

- `RoutingVlmService` 调 `selectVlmCandidates()`，候选仍按 `default-model → priority → id` 排序，并过滤 disabled、缺 provider 配置和健康存储认为不可用的目标；随后只取列表第一个。
  - 它没有调用 `ModelRoutingExecutor`，也没有遍历剩余目标；首候选失败直接抛 `ModelClientException`。
  - 它也没有申请健康许可或按结果 `markSuccess/markFailure`。因此不能把 Chat/Embedding/Rerank 的熔断与半开恢复原样套到 VLM。
- 服务把 prompt 放在 user message 的 text part，把图片字节编码为 `data:<mime>;base64,...` 放在 image_url part；两部分组成同一条多模态 user message。正数 `maxOutputTokens` 才写为 `max_tokens`。
- VLM 复用 provider 的 chat endpoint，同步发送后抽取 `choices[0].message.content`。
  - VLM 服务会拒绝缺少 choices/message/content 的响应，但没有在本层拒绝空白字符串；`ImageAssetProcessor` strip 后发现空白才抛业务异常。
- 上层图片类型决定最终降级差异。
  - 独立图片文档直接经过 `ImageAssetProcessor`，VLM 失败或空描述会使这次图片解析失败。
  - Excel 内嵌图片逐张调用时会捕获异常，跳过该图片而保留表格文本；MinerU 内嵌图描述失败也只记警告，该图的向量文本可能只剩已有图片链接。这里是解析层的降级，不是 VLM 换了候选。

## 9. 复习时按这条边界判断“到底成功了什么”

- 路由选择成功：只是得到一个当前配置与健康门禁允许尝试的 `ModelTarget`。
- provider 调用成功：同步路径得到协议合格的结果；流式路径在首包预算内得到 content/thinking。它不等于业务 JSON 正确、向量空间兼容、重排更好或答案正确。
- fallback 成功：前一个候选失败后，后一个候选返回了本能力要求的结果。对 Embedding 还必须额外满足索引空间契约；对 noop rerank 则明确是保序降级。
- 用户最终看到什么：流式 callback、SSE、取消、落库和任务终态继续由流程 F 处理。尤其首包后报错不会由本路由拼接另一个模型，外层 SSE 超时也不会自动证明 provider 已停止。

## 10. 影响理解的重要旧说法修正

- “流式失败会自动换模型”只适用于**尚未提交首个 content/thinking**的探测阶段；首包后错误直接交给上层，不再 fallback。
- “所有模型能力共用统一路由熔断”不准确：Chat 同步、Embedding、Rerank 走通用执行器，Chat 流式自行实现探测与健康更新；VLM 当前只选首候选且不更新健康状态。
- “指定模型”也不是统一语义：Chat preferred 是队首偏好并保留档位回退；Embedding 显式 model ID 是单候选、失败即异常；流式 Chat 和 Rerank/VLM 没有对应显式重载。
- “rerank 失败返回原候选”要分两层说：noop 返回原序列表或其前缀，后置处理器再补回融合尾部；所有 rerank 候选都异常时，是外层处理器 catch 后保留原 chunks。
- “1536 维即可互换”错误。当前文档侧绑定知识库模型、query 侧走全局候选，正因为两边路由契约不同，自动切换反而可能产生不报维度错的语义空间错配。

## 11. 关键源码反查

- [ModelSelector.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelSelector.java)：解析 Chat 档位/preferred/thinking，排序其他能力候选，并做 enabled、provider、健康预过滤。
- [ModelRoutingExecutor.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelRoutingExecutor.java)：同步能力申请许可、逐候选调用、记录成功失败并在全部失败后抛异常。
- [ModelHealthStore.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelHealthStore.java)：CLOSED/OPEN/HALF_OPEN、冷却、单探针和 token 所有权释放。
- [RoutingLLMService.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java)、[ProbeStreamBridge.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ProbeStreamBridge.java)、[LlmFirstPacketProbe.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LlmFirstPacketProbe.java)：同步重载差异、流式首包探测、缓冲提交、取消和失败切候选。
- [AbstractOpenAIStyleChatClient.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/AbstractOpenAIStyleChatClient.java)：Chat 请求字段、同步超时派生、异步 SSE 读取及取消句柄。
- [RoutingEmbeddingService.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/RoutingEmbeddingService.java)、[AbstractOpenAIStyleEmbeddingClient.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClient.java)：默认 fallback、显式单模型和向量请求/响应。
- [VectorTargetResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/support/VectorTargetResolver.java)、[ChunkEmbeddingService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/embed/ChunkEmbeddingService.java)、[PgVectorRetrieverService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java)：流程 C 的知识库模型绑定、摄取校验，以及流程 E 当前未绑定模型的 query Embedding。
- [RoutingRerankService.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/RoutingRerankService.java)、[BaiLianRerankClient.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java)、[NoopRerankClient.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/NoopRerankClient.java)：重排输入、index/score 回映与 noop 的实际返回。
- [RoutingVlmService.java](../../../infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/vlm/RoutingVlmService.java)：首候选、多模态请求体与无 fallback 的当前实现。
- [application.yaml](../../../bootstrap/src/main/resources/application.yaml)、[HttpClientConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/HttpClientConfig.java)：当前仓库候选顺序、档位预算、熔断阈值、SSE 与基础 HTTP 超时；部署值仍可能被外部配置覆盖。
