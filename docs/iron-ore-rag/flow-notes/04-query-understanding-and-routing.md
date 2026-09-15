# 04｜查询理解与路由：用户问题怎样变成检索和工具执行计划

本篇从回答流程 F 已经取出的“本次原问题 + 此前会话历史”开始，不重复聊天入口、用户消息落库和历史存储。它负责解释 `StreamChatPipeline` 怎样把自然语言问题变成一个或多个可检索子问题，怎样为每个子问题识别叶子意图，以及程序据此选择澄清、纯系统回答、知识库检索或可选 MCP 工具。普通知识召回的内部排序归流程 E，最终 Prompt、流式输出和回答落库归流程 F。

先记住三个边界：查询改写不是回答问题；意图分类不是向量召回；知识库作用域（scope）只决定这次去哪些集合查，不等于用户有权访问这些集合。

## 1. 在线请求的完整流程

### 1.1 Pipeline 接过原问题和旧历史，先做术语归一化

- `StreamChatPipeline` 此时已经从会话记忆取得旧 `history`，并已把本轮用户原句另行写入消息表；传给改写服务的是 `ctx.question` 和加载时得到的旧历史，因此历史中不含刚写入的本轮问题副本。
  - 本篇从这两个值开始。前一步如何加载、写消息和返回 `replyToMessageId` 归流程 F。
  - Pipeline 调用 `queryRewriteService.rewriteWithSplit(originalQuestion, history)`，得到 `RewriteResult(rewrittenQuestion, subQuestions)`。这个 record 不保留模型的 `should_split` 字段；该字段只在解析时决定 Java 最终采用哪组子问题。
- `MultiQuestionRewriteService` 无论是否开启 LLM 改写，都会先调用 `QueryTermMappingService.normalize`，用管理端维护的领域术语映射做确定性替换。
  - 映射优先从 Redis 键 `ragent:query-term:mappings` 读取；缓存读取异常按未命中处理。未命中时从 `t_query_term_mapping` 查询 `enabled=1` 的记录，按优先级降序、再按源词长度降序排序，并把有序列表缓存 7 天。管理端新增、更新或删除映射后会清缓存。
  - 执行时只接受 `enabled=1`、`match_type=1`、源词和目标词均非空的规则，按上述顺序逐条对字符串做字面子串替换。它不是分词、Embedding 或模糊匹配。
  - 如果命中位置本来已经以目标词开头，工具会整段跳过目标词，避免例如把目标词中包含的短源词再次扩写；其他命中直接替换，后续规则看到的是前一条已经改变后的文本。
  - 没有映射时原样返回。Redis 和数据库同时不可用、数据库查询抛错时，这一步没有降级保护，异常会继续向外传播；不能笼统写成“归一化总会回退原句”。

### 1.2 改写开启时，把系统规则、截取后的历史和当前问题交给快速档模型

- 主配置 `rag.query-rewrite.enabled=true`，因此通常进入 LLM 改写分支。服务先加载 `prompt/user-question-rewrite.st` 作为 system 消息，再装配历史和当前问题。
  - 第一条消息是 system：要求保留专有名词和限制，删除礼貌语、回答格式指令等检索噪声，结合历史还原“它/这个”等指代，只在多问号、显式列举、分号或换行等条件下拆分，并严格返回 `rewrite`、`should_split`、`sub_questions`。
  - 中间消息只允许旧历史中的 USER 和 ASSISTANT；SYSTEM 摘要被过滤，不直接发给改写模型。
  - **当前代码并不稳定等于“过滤后最近 4 条”。**它先过滤角色，却用过滤前的 `history.size()` 计算 `skip(max(0, history.size()-4))`。例如旧历史为 `SYSTEM + USER + ASSISTANT + USER + ASSISTANT` 共 5 条，过滤后有 4 条，但仍会跳过第 1 条，只留下 3 条，甚至可能从 ASSISTANT 开始。SYSTEM 条目越多，实际保留的 user/assistant 条数可能越少。
  - 最后一条是 user，内容只放归一化后的当前问题。原问题只用于日志，不会再作为另一条模型消息发送。
  - 请求使用 `temperature=0.1`、`topP=0.3`、`thinking=false`，以 `Tier.FAST` 同步调用模型。改写模型没有知识库原文，只能根据规则、截取后的对话和当前问题改写；历史里没留下实体时，它无法可靠还原指代。
- 模型应返回一个 JSON 对象，例如：

  ```json
  {
    "rewrite": "设备 C1 出现故障状态时应先检查什么？",
    "should_split": false,
    "sub_questions": ["设备 C1 的传感器是否正常？"]
  }
  ```

- Java 收到字符串后先去掉可选 Markdown 代码围栏，再按以下顺序决定真正使用的结果。
  - 根节点必须是 JSON 对象；`rewrite` 必须存在、能读成字符串且 `trim` 后非空，否则整份结果无效。
  - `sub_questions` 只有在字段存在且为数组时才读取；其中只保留字符串元素，逐项去首尾空白并丢弃空串。字段缺失、类型不对或元素不是字符串，不会单独报错，而是得到空列表。
  - `should_split` 存在且是 primitive 时按布尔值读取；字段缺失时，为兼容旧响应，用“清洗后的子问题是否多于 1 条”推断。值无法转成布尔、JSON 非法或其他读取异常会使整份解析失败。
  - `should_split=false` 时，即使模型同时给了多个子问题，Java 也忽略它们，强制使用唯一的 `rewrite`。这是“模型说不拆却给多个问题”时的确定性处理。
  - `should_split=true` 时，子问题按原顺序 `trim`、去空并用字符串完全相等去重；如果最后为空，则仍用唯一的 `rewrite`。去重不做语义合并，所以同义但文字不同的两问仍会并发检索。
  - 最终返回 `RewriteResult(rewrite, actualSubQuestions)`；后续只看 Java 已采用的列表，不再重新解释 `should_split`。
- 模型调用抛错，或上述解析返回无效结果时，服务降级成 `RewriteResult(normalizedQuestion, [normalizedQuestion])`，即保留术语归一化，但不做规则拆分。
  - 这里的 try/catch 从 `llmService.chat` 才开始；改写模板加载和请求构造发生在 try/catch 外。模板不存在或加载失败不会走这个 fallback，而会向外抛出。
  - “调用成功但把 C1 错改成 C2”属于质量错误：只要 JSON 合法，当前解析器就无法识别。排查时先对照原句、实际保留的历史、归一化问题、原始模型响应和最终 `RewriteResult`。

### 1.3 改写关闭时走规则分支，不调用模型也不使用历史

- 如果部署把 `rag.query-rewrite.enabled` 设为 `false`，服务仍先完成术语归一化，然后调用 `ruleBasedSplit`。
  - 它按中英文问号、句号、分号和换行 `[?？。；;\n]+` 切开，逐项去空；每个非空片段若没有问号，就补一个中文问号。
  - 最后仍按字符串完全相等去重。切不出任何片段时返回原归一化字符串作为唯一子问题。
  - 这条分支没有会话消歧：历史参数不会参与规则拆分。因此关闭改写后，“它异常时先查什么”里的“它”不会由这段代码还原。

### 1.4 为分类准备意图树：Redis/数据库恢复层级，再把叶子平铺给模型

- `IntentResolver` 接到 `RewriteResult` 后，以非空 `subQuestions` 为准；列表为空才回退到唯一的 `rewrittenQuestion`。它随后为每个子问题分别调用一次 `DefaultIntentClassifier.classifyTargets`。
- 每次分类先恢复当前可用的意图树，而不是在代码中逐层询问模型。
  - 分类器先读 Redis 键 `ragent:intent:tree`。命中时取得序列化的根节点列表；读取/反序列化异常按缓存未命中处理。
  - 缓存为空时，从 `t_intent_node` 查询 `deleted=0 AND enabled=1` 的扁平记录。`IntentNodeMapper` 只是 MyBatis-Plus `BaseMapper`，筛选条件由分类器明确加上。
  - 第一遍把每条记录转成内存节点：数据库 `intent_code` 成为分类 ID，`parent_code` 成为父 ID；多集合字段 `collection_names` 为空时才回退旧单集合字段 `collection_name`。
  - 第二遍按父 ID 找父节点并追加到 `children`；无父 ID 的记录成为根，引用了不存在父节点的记录也降级成根，避免节点直接丢失。
  - 最后从根向下生成 `fullPath`，例如“设备运维 > 浓度检测 > 异常排查”。构造好的根列表写回 Redis，TTL 为 7 天；之后在本次分类内展平整棵树，建立全部节点的 `id -> node` 映射，并只取没有 children 的叶子节点作为候选。
- 意图数据和启用状态由数据库记录控制，而不是普通聊天请求携带开关。
  - `IntentTreeController` 提供查询、创建、更新、删除以及批量启用/停用入口；服务修改 `enabled` 或逻辑删除记录后清除意图树缓存，下次分类再从数据库恢复。
  - `IntentTreeFactory` 是一份可显式调用的初始化种子；当前普通聊天链不会每次调用它重建树，当前 Controller 也没有暴露“每次请求从 Factory 初始化”的入口。
  - 如果直接改数据库却不清 Redis，分类可能继续读取旧树；管理接口会清缓存，历史评测脚本则在改评测节点后清对应 Redis 逻辑库。
- 分类 Prompt 把所有叶子节点一次性拼进 `{intent_list}`。每个候选实际带：
  - `id`：`intent_code`；
  - `path`：由父子关系生成的 `fullPath`；
  - `description`：节点知识/工具范围说明；
  - `type`：`KB`、`MCP` 或 `SYSTEM`；
  - `toolId`：仅 MCP 节点且配置非空时出现；
  - `examples`：有示例时用 ` / ` 连接。
  - 知识库 `collection_names`、`top_k`、回答规则片段和完整 Prompt 模板不会直接放进分类候选文本；它们在模型选回 ID 后由 Java 节点对象继续参与路由或回答规划。

### 1.5 有叶子候选时，每个子问题做一次 LLM 分类，再由 Java 校验、过滤和限额

- 对一个子问题，分类器先检查刚恢复的叶子列表。没有可用叶子时，直接返回空意图 `[]`，不构造 Prompt，也不调用分类模型；这不是把空候选发送给模型后再等它返回空数组。
  - 空列表回到 `IntentResolver` 后仍保留这个子问题。若各题都没有意图，后面的澄清和纯 SYSTEM 条件均不成立，请求继续进入检索，由每题 scope 的 global/empty fallback 决定知识库范围。
- 有叶子候选时，分类器才构造两条消息：动态 system 消息是完整 `intent-classifier.st` 加全部叶子候选，user 消息只含当前这个子问题。
  - 分类模型**不直接收到聊天历史**，也不直接看到原始问题、知识库 chunk 或查询向量。历史若有作用，只能先影响改写结果，再间接影响分类。
  - Prompt 要求先判断实体/主题，避免跨系统误配，弱相关时返回 `[]`；默认返回一个核心意图，明确多问题可返回两个，同名主题歧义可返回最多三个。
  - 请求使用 `temperature=0.1`、`topP=0.3`、`thinking=false`，调用标准档 `llmService.chat(request)`。这是把所有叶子放入同一次请求整体选择，不是从根到叶逐层遍历、每层调用一次 LLM，也不是用 Embedding 相似度做当前分类。
- 模型预期返回 JSON 数组；每项形如 `{"id":"intent-c1-fault","score":0.82,"reason":"..."}`。程序也兼容外包一层 `{"results":[...]}`。
  - 先去 Markdown 围栏；根既不是数组也不是带 `results` 数组的对象时返回空。
  - 数组元素不是对象，或缺少 `id`/`score` 时跳过；ID 不在当前整棵树的映射中时记录告警并跳过。因此模型不能凭一个新 ID 临时创建路由。
  - 程序读取 ID 和 double 分数，`reason` 不进入 `NodeScore`，后续路由不使用它；有效项按分数降序。Prompt 虽要求分数在 0～1，解析器没有再钳制或拒绝越界 double，因此排查异常路由时也要检查原始分数。调用失败、非法 JSON、字段类型错误或整体解析异常都降级为空分类结果。
- 结果回到 `IntentResolver` 后，再做第一层程序门禁。
  - 每个子问题丢弃分数 `< INTENT_MIN_SCORE(0.35)` 的节点，然后最多保留 `MAX_INTENT_COUNT(3)` 个。这一步对 KB、MCP、SYSTEM 一视同仁。
  - 多个子问题通过 `intentClassifyExecutor` 各自异步分类；外层按原子问题列表顺序 `join`，所以结果顺序稳定，但完成顺序不保证。某个任务异常只把该子问题降级成空意图，不直接取消其他分类。
  - 全部汇合后才做请求级意图数收口：若总数不超过 3 原样返回；超限时先为每个有候选的子问题保留最高分，再把剩余名额按全局分数降序分配，最后重建各子问题结果。
  - **当前上限有一个实现边界：**聊天改写路径没有 Java 级子问题数量上限。如果“有候选的子问题数”本身超过 3，保底列表已经超过 `MAX_INTENT_COUNT`，当前算法不会再删掉这些保底项，因此总意图数仍可能大于 3。不能把常量描述成所有输入下绝对成立的硬上限。
- 空结果和低分不会在这里直接拒答。它们仍随子问题回到 Pipeline，后面由澄清、SYSTEM 判断和检索 scope 的 fallback 共同决定去向。

### 1.6 回到 Pipeline，先判断澄清，再判断纯 SYSTEM，最后才进检索引擎

- Pipeline 首先把“改写后的主问题 + 全部子问题意图”交给 `IntentGuidanceService.detectAmbiguity`。
  - `rag.guidance.enabled` 的 Java 默认值为 `true`。只处理恰好一个子问题、且至少有两个达到 0.35 的 KB 候选的情况；MCP 和 SYSTEM 不参与这个歧义组。
  - 服务先沿每个候选的父节点向上找，用领域节点（DOMAIN）下一层的品类节点（CATEGORY）ID 分组；如果途中已无父节点，就用走到的节点 ID。组内只留最高分候选，再按分数降序排列；不足两个组就不澄清。这里保留的是组内候选节点，后面展示其名称和完整路径。
  - 接着计算第二名与第一名分数比，默认 `< 0.65` 就不澄清。达到下限后，再沿候选父链找 DOMAIN 名称：问题和名称都去首尾空白、转小写并删除正则匹配的标点/空白，名称至少两个字符且被问题包含时，跳过澄清。因此“按 CATEGORY 分组”和“显式提到 DOMAIN 就免澄清”是两个层级；只写出某个 CATEGORY 名称不一定满足后一条件。
  - 没被上述规则跳过时，比值 `>= 0.8` 直接判歧义；落在 `[0.65, 0.8)` 时才调用 `AmbiguityLLMChecker` 做语义确认。
    - 确认器把改写主问题、各组保留候选的 ID、名称、路径和分数填进 `guidance-ambiguity-check.st`，要求判断能否确定用户所问品类。完整 Prompt 只作为一条 user 消息发送，不带聊天历史或 KB 原文；请求用 `temperature=0.1`、`topP=0.3`、`thinking=false`，同步调用 `Tier.FAST`。
    - 模板要求 JSON 对象，例如假设返回 `{"ambiguous":false,"category_ids":["c1-fault-sop"],"reason":"问题已有明确设备线索"}`。程序去 Markdown 围栏、解析对象，实际只用 `ambiguous` 决定是否澄清；`reason` 只写日志，`category_ids` 不被消费，也不会据此重新筛选原分类结果。
    - 模型调用失败、JSON 非法、根不是对象、缺少 `ambiguous` 或字段读取抛错时，按“存在歧义”处理。模板渲染和请求构造发生在这段 try/catch 外，若这里抛错则向 Pipeline 传播，不会自动得到澄清文本。
  - 确认需要澄清时，服务按 `maxOptions` 截取分组后的候选，用第一项名称作为主题、各项完整路径作为选项（路径为空才回落名称/ID），渲染选项提示。Pipeline 直接通过 callback 发送文本并 `onComplete()`，本轮到此返回，不做 KB/MCP 检索，也不调用最终知识回答模型。
  - 规则或模型判定无需澄清时，服务返回“不引导”。分组和确认只用于这次澄清判断，没有替换 Pipeline 持有的 `subIntents`；外层仍拿原来的全部分类候选继续下面的 SYSTEM 判断和检索。
- 未被澄清短路时，Pipeline 再判断是否为“纯 SYSTEM”。
  - 每个子问题必须都恰好保留一个节点，且该节点类型为 SYSTEM，`allSystemOnly` 才成立。某题空意图、KB/MCP 意图、或一题同时有 SYSTEM 与其他意图，都不会走这条分支。
  - 纯 SYSTEM 分支取所有命中节点中第一份非空 `promptTemplate`，没有则用 `SYSTEM_CHAT` 默认系统提示；消息顺序是 system、完整旧 history、user（改写后的主问题）。然后以 `temperature=0.7`、`thinking=false` 流式调用模型并绑定取消句柄。
  - 这不是固定字符串回答，也不是检索回答；历史在这个最终 SYSTEM 对话中会重新完整加入，和前面的分类模型“不直接收历史”是两件事。
- 其余情况都进入 `RetrievalEngine.retrieve(subIntents)`，包括普通 KB、MCP、KB+MCP 混合以及空意图。
  - 引擎先读取请求级检索预算，再为各子问题分配最终上下文额度；随后用 `ragContextExecutor` 并发构建每个 `SubQuestionContext`。这一层的 KB 检索和 MCP 分支见下面两节。
  - 某个子问题构建异常会被降级为空 KB/MCP 上下文，其他子问题继续。所有 future 按原顺序汇合，再由引擎做请求级 chunk 选择和上下文格式化。

### 1.7 KB scope 在检索引擎内部按子问题计算，随后交给流程 E 召回

- scope 不是 Pipeline 先算出的全请求标签。`RetrievalEngine` 为某个子问题调用 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels` 后，后者在构造该子问题的 `SearchContext` 时才调用一次 `RetrievalScopeResolver.resolve([thisSubIntent])`；同一子问题的向量、关键词和图通道只读这份结果。
- scope 解析先从 `t_knowledge_base` 取得所有 `deleted=0` 的非空 `collection_name` 并去重，形成“有效集合”。这里的“有效”只是知识库记录未删除，不包含当前用户/租户权限判断。
- 接着从该子问题的意图中形成可用于 scope 的 KB 意图。
  - 只保留类型为 KB、分数 `>= rag.search.scope.min-intent-score`（主配置为 `0.4`）、且节点确实绑定了集合的意图。
  - 同一意图节点若重复出现，按节点 ID 去重并保留最高分；集合名从 `collection_names` 去空去重，只有新字段为空时才用旧 `collection_name`。
  - 这道 `0.4` 是 scope 自己的最低分门槛；此前 `IntentResolver` 的 `0.35` 是通用意图门槛。启动校验禁止把 scope 最低分配置到 0.35 以下，因为那样已经被上游删掉的节点不可能复活。
- 没有合格 KB 意图时走 fallback；有意图则取其中最高分，与 `confidence-threshold`（主配置 `0.6`）比较。
  - 最高分 `< 0.6`：不依据这些低置信绑定收窄，走 fallback。
  - 最高分 `>= 0.6`：把**所有通过 0.4 的 KB 意图绑定集合**合并去重，再与有效集合求交。最高分只决定是否允许定向，并不意味着只使用最高分节点的集合。
  - 求交后为空，说明绑定库已删除、改名或本来无效，也走 fallback；部分失效时丢掉失效集合并告警，保留仍有效的集合。
- fallback 由 profile 决定，不能只写一种默认行为。
  - 主配置 `fallback-mode=global`：`targetCollections=全部有效集合`、`directed=false`、补充集合为空。低置信、空意图或绑定失效会扩大到全库检索，但这个“全库”仍然没有用户 ACL 过滤，不能当权限控制。
  - `iron-ore-demo` profile 覆盖为 `fallback-mode=empty`：目标集合为空，知识通道查不到 KB 内容。它适合领域 Demo 防污染，但 MCP 若成功仍可形成非空上下文。
- 高置信定向时，主集合是“有效集合 ∩ 合格意图绑定集合”，补充集合是“全部有效集合 - 主集合”。补充集合只是一个范围，还不等于一定发起补充查询。
  - `ScopeQuota.split` 仅在定向、确有未命中库、`supplement-ratio>0` 且本通道预算为正时划名额；预算只剩 1 时也不会补，以保证主路至少 1 条。
  - 主配置比例为 `0.25`。例如假设本通道额度为 20，会给补充路 5、主路 15；两路结果各自按名额截断后再按分数合并，补充块仍可能在后续融合、候选池截断、Rerank 或请求级 TopK 中被淘汰。
  - `iron-ore-demo` 把比例覆盖为 `0`，所以即使 `supplementCollections` 非空也不查询补充路。主集合已覆盖全部有效库、全局/空 fallback、通道预算非正时同样不补。
- scope 放入 `SearchContext` 后，流程 E 接手普通召回：以该子问题为 query，在 scope 限定集合内执行启用通道、后处理和重排，返回有序 chunk 及意图归因。
  - 本篇只保留交接契约：输入是“子问题 + 该题意图 + 预算 + scope”，输出是该题的有序知识块；Embedding、PGVector、通道并发、融合与 Rerank 的内部算法归流程 E。
  - 多个子问题的一次并发查证据是预先拆好的并列检索，不会拿第一题证据自动生成第二跳 query。若必须先从台账查出设备编号，再把编号传给下一次检索或工具，当前固定 workflow 没有这个循环。

### 1.8 可选 MCP 分支：按 tool ID 找启动期注册项，再提参、校验、执行和格式化

- 某个子问题若保留了 MCP 意图，`RetrievalEngine.buildSubQuestionContext` 会先完成/跳过该题 KB 检索，再调用 `executeMcpAndMerge(question, mcpIntents)`。跨子问题仍由外层并发；同一子问题内 MCP 提参只收到该子问题字符串，不收到刚检索到的 KB 内容。
- 工具发现和注册是独立的启动准备，不是每道问题临时重建 MCP 服务。
  - `McpClientAutoConfiguration` 在启动时读取 `rag.mcp.servers`；主配置指向 `http://localhost:9099`。它为每个 server 建同步 client，初始化连接，调用一次 `tools/list`，把每个返回的 `Tool` 定义封装成 `McpClientToolExecutor` 并按工具名注册进内存 `McpToolRegistry`。
  - 本轮请求只用意图节点的 `mcpToolId` 查注册表。查不到执行器时记录告警并跳过该工具，不会在请求内重新发现服务；启动连接失败也只是该 server 的工具未注册。
- 找到执行器后，程序从其中读取已发现的 `Tool` 定义，包括工具名、描述和 `inputSchema`，再调用 `LLMMcpParameterExtractor`。
  - 无 schema 或 properties 为空的无参工具直接以空参数成功，不调用提参模型。
  - 有参数时，system 消息优先使用意图节点的 `paramPromptTemplate`，否则使用 `mcp-parameter-extract.st`；user 消息由 `mcp-parameter-extract-user.st` 渲染，内含工具 ID、描述、每个属性的类型/必填性/说明/default/enum，以及当前子问题。这里也不带聊天历史和 KB 检索结果。
  - 提参模型以 `temperature=0.1`、`topP=0.3`、`thinking=false` 返回 JSON 对象。程序只遍历 schema 声明的属性，因此模型多给的未知字段不会进入参数 Map；合法值会按 string/integer/number/boolean/array/object 做保守转换，并校验 enum。
  - 必填且无默认值的参数缺失或为 `null`，判为 `NEED_CLARIFICATION`；可选参数或带默认值的参数缺失先忽略，只有整体成功后才由 Java 填默认值。
  - 空响应、非 JSON 对象、模型调用失败，以及任一已提供值无法转换或不在 enum 中，都判为 `FAILED`。非法的可选字段也不会静默丢弃后扩大查询范围。
- `RetrievalEngine` 只在 `SUCCESS` 时调用执行器。
  - `NEED_CLARIFICATION`：不调用远端工具，构造 `isError=false` 的文本结果，明确列出缺少的必填参数并要求最终模型向用户询问，不能编造。
  - `FAILED`：不调用远端工具，构造 `isError=true` 的“参数提取失败、已跳过”结果。
  - `SUCCESS`：`McpClientToolExecutor` 用已注册工具名和参数构造 `CallToolRequest`，通过同步 client 调远端 MCP Server；远端异常被标准化成 `isError=true` 的文本结果。
- 以仓库里一个实际工具 `weather_query` 为例，只追到它的返回边界：
  - 服务端 schema 要求必填 `city`，另有默认 `queryType=current`、`days=3`。问题“北京未来三天天气”可提取为 `{"city":"北京","queryType":"forecast","days":3}`；缺城市则在客户端提参校验处先形成澄清结果，正常不会调用服务端。
  - 成功调用时，服务端读取参数，检查城市是否在支持列表、把天数上限钳到 7，生成当前天气或预报文本，包装为 `CallToolResult` 的 `TextContent`；不支持城市或执行异常返回 `isError=true` 文本。这里的数据是该示例 MCP Server 生成的演示数据，不应写成真实生产天气源。
- 同一子问题的多个 MCP 意图会在 `mcpBatchExecutor` 中并发，按原列表 `join` 后以 tool ID 分组。`ContextFormatter` 把成功文本放正文，把错误文本放“工具调用失败”段，并结合意图的 `promptSnippet` 格式化成 `mcpContext`。
  - 缺参结果刻意标为非错误，所以会作为正文提示最终模型追问；参数抽取失败和远端失败进入错误段。工具返回上下文以后，程序不会自动再次调用工具。
  - 因此“必须先查出设备编号才能发起第二跳”在当前链路无法自动完成：即使同一题先做了 KB 检索，提参器仍只看原子问题。可由用户补充编号后发起下一轮，或未来引入有最大步数、权限、费用和停止条件的 Agent 循环；不能把当前并发拆问描述成自主多跳。

### 1.9 RetrievalContext 回到 Pipeline，空则直接结束，非空交还 F 生成回答

- `RetrievalEngine` 汇合各子问题的 KB 结果和 MCP 结果，按请求总 TopK 选择知识块，分别格式化 `kbContext`、`mcpContext`，返回 `RetrievalContext`。
  - 普通召回的内部选择仍归流程 E；本篇需要记住的是：最终上下文可能只有 KB、只有 MCP，或两者都有。
  - `RetrievalContext.isEmpty()` 的判断是 `kbContext` 和 `mcpContext` 都为空，不是“没有 KB chunk 就一定为空”。例如 MCP 缺参形成的澄清文本会让 MCP 上下文非空，后面仍会调用最终模型组织追问。
- 两种结果在 Pipeline 分流。
  - 两类上下文都空：直接发送“未检索到与问题相关的文档内容。”并完成回调，不调用最终回答模型。`iron-ore-demo` 的 empty scope、无 MCP 结果时容易进入这条路。
  - 至少一类非空：Pipeline 合并 KB/MCP 意图，组装来源和 grounding，再把改写主问题、子问题、旧历史、KB/MCP 上下文交给流程 F 构建最终 Prompt 和流式回答。本篇到此完成交接。

## 2. 贯穿示例：从“它异常时先查什么”推到集合

下面的映射、节点、分数和集合名均为教学假设，不是本次运行证据。这个例子对应 1.1～1.7，重点展示历史截取、模型协议后处理以及两道 scope 阈值。

- **原句和旧历史**：用户本轮问“它异常时先查什么？”。旧历史假设共 5 条：一条 SYSTEM 摘要，随后两轮 USER/ASSISTANT；最近的 assistant 文本明确提到“设备 C1”。
  - 当前代码过滤 SYSTEM 后得到 4 条 user/assistant，却按原始长度 5 计算 `skip=1`，所以实际只送最后 3 条历史，再追加本轮问题。
- **术语归一化**：假设启用映射 `异常 -> 故障状态`，字面替换后得到“它故障状态时先查什么？”。归一化本身没有消解“它”。
- **改写模型输入和假设输出**：system 要求结合保留下来的历史还原指代；模型据最近 assistant 中的 C1 返回：

  ```json
  {
    "rewrite": "设备 C1 出现故障状态时应先检查什么？",
    "should_split": false,
    "sub_questions": [
      "设备 C1 的传感器是否正常？",
      "设备 C1 的供电是否正常？"
    ]
  }
  ```

  - 虽然数组有两项，Java 以 `should_split=false` 为准，实际采用的 `RewriteResult` 是：主问题“设备 C1 出现故障状态时应先检查什么？”，子问题仅 `["设备 C1 出现故障状态时应先检查什么？"]`。
- **一次意图分类的假设结果**：分类模型只收到上述唯一子问题和全部叶子候选，不直接收到那 5 条历史。假设它返回三个当前树中存在的 KB 节点：

  ```json
  [
    {"id":"c1-fault-sop","score":0.82,"reason":"命中 C1 故障排查"},
    {"id":"sensor-check","score":0.58,"reason":"涉及传感器检查"},
    {"id":"power-general","score":0.38,"reason":"可能涉及供电"}
  ]
  ```

  - 分类器校验 ID 后降序返回。`IntentResolver` 的第一道通用门槛是 0.35，所以三项暂时都保留，且数量刚好不超过每题/请求常量 3。
- **进入检索引擎后计算 scope**：假设 `c1-fault-sop` 绑定 `kb-c1-sop`，`sensor-check` 绑定 `kb-sensor-manual`，`power-general` 绑定 `kb-power`，三个库在知识库表中都未删除。
  - scope 的最低分门槛 0.4 先删掉 0.38 的 `power-general`；这是第一道 scope 阈值，它决定哪些意图有资格贡献绑定集合。
  - 剩余最高分 0.82 再与置信阈值 0.6 比较；0.82 达标，允许定向。这是第二道 scope 阈值，它决定使用绑定集合还是 fallback。
  - 主集合因此是 `{kb-c1-sop, kb-sensor-manual}` 与有效集合求交后的结果，不包含被 0.4 删除的 `kb-power`。
  - 补充集合是其他有效库。若运行主配置且该通道预算假设为 20、没有节点级 topK 覆盖，则比例 0.25 可划成主路 15、补充路 5；若运行 `iron-ore-demo`，比例为 0，补充集合虽算出却不会发起补充查询。
- **结果交接**：上述子问题、主/补充范围和预算交给流程 E。E 返回实际召回并重排后的知识块，本篇再把它们连同可选 MCP 文本汇成 `RetrievalContext`，随后交回流程 F。若正确步骤必须先从第一批证据发现“C1 的传感器编号是 S7”，再用 S7 构造第二条 query，当前这次并发检索不会自动完成第二跳。

## 3. 常见异常时先查什么

- **“它”没有被还原**：先看进入改写服务的旧 history，再按“过滤 USER/ASSISTANT，但用过滤前长度算 skip”复算实际消息；然后看归一化问题、改写模型原始 JSON 和最终 `RewriteResult`。不要先查分类器，因为分类器根本不直接收历史。
- **模型说不拆，实际却查了多题**：先确认运行代码是否已经消费 `should_split`，并查看最终 `RewriteResult.subQuestions`，不要只看模型原始数组。当前实现中 `false` 必须收成唯一 rewrite；字段缺失才按数组条数推断。
- **同一问题命中了错误意图**：先核对 Redis 中是否是旧树，再看数据库节点的 `enabled/deleted`、`parent_code`、生成的 `fullPath`、description/examples/type/toolId，最后看分类原始 ID/score 和 0.35 过滤结果。不要查向量模型，当前分类不是向量分类。
- **低置信问题查了全库或完全没查**：先区分三道值：上游 0.35、scope 最低分 0.4、最高置信阈值 0.6；再看 active collections、绑定求交结果和当前 profile 的 fallback。主配置低置信走 global，`iron-ore-demo` 走 empty。
- **高置信仍没查到绑定库**：核对意图绑定的是实际 collection 名而非知识库展示名，并确认 `t_knowledge_base.deleted=0` 且 collection 非空。已失效绑定会被求交删除；补充集合也可能因 profile 比例为 0、预算为 1、已覆盖全库或后续排序淘汰而没有最终证据。
- **工具没有调用**：按顺序查 MCP 意图是否过 0.35、节点 `mcpToolId` 是否非空、启动期 registry 是否有同名执行器、工具 schema 是否有缺失必填项、提参 JSON 是否类型/enum 合法。缺必填是正常澄清，提参失败是错误上下文，未知 tool ID 则直接跳过。
- **需要设备编号的工具只返回追问**：确认编号是否原本就在子问题中。当前提参模型不读取 KB 结果，也没有观察结果后再次规划的 Agent 循环；需要用户下一轮补充，或另行设计受控多步执行。

## 4. 当前实现、评测开关和扩展概念的边界

- 查询改写由 `rag.query-rewrite.enabled` 明确控制；意图分类没有一个对应的普通聊天全局 `intent.enabled` 开关。普通 `StreamChatPipeline` 总会调用 `IntentResolver`，能分类到什么由当前启用且未删除的数据库节点和 Redis 缓存决定。
- 历史铁矿评测中的 `intent=off` 是评测编排，不是聊天请求参数切换产品链路。
  - `set_intent_mode.py off` 实际删除两条评测专用 `t_intent_node` 记录并清对应 Redis 逻辑库；`on` 则插入/更新这两条记录。
  - `run_retrieval.py --intent-mode off|on` 把模式写进报告并决定是否计算意图/路由指标，但向后端 `/rag/eval` 发的仍只有 `question`；固定改写时 `/rag/eval/replay` 发 `question + subQuestions`。它没有向后端传一个“关闭意图分类”的布尔值。
  - `run_answers.py` 的同名参数也只写入评测报告；实际完整回答请求发往普通 `/rag/v3/chat`，query 只有 `question` 和 `deepThinking`。因此 on/off 的实际后端状态仍来自评测前对意图表和 Redis 的准备，不是普通聊天链临时切换。
  - 评测入口直接走改写（或 replay）→意图→`RetrievalEngine` 并返回检索诊断，不经过正常 SSE 聊天的历史、澄清、SYSTEM 和最终回答链。不能用历史 `intent=off` 结论编造产品级总开关。
- 当前查询改写属于“术语归一化 + 带历史的 LLM 改写 + 条件式并列拆问”，不是 HyDE，也不是为同一语义生成多条同义 query 的典型 Multi-Query。
- MCP 是发现和调用工具的协议；本项目在固定 workflow 的一个已知分支里调用它。单次工具调用不等于 Agent，多个子问题并发也不等于 Agent 循环。
- 自主 Agent 需要把“计划 → 工具/检索 → 观察结果 → 更新计划”放入有步数、时间、费用、权限和停止条件的循环。HyDE、GraphRAG 和这种 Agent 循环都不能写成当前在线请求已执行的步骤。

## 5. 源码反查入口

- [`StreamChatPipeline`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java)：改写、意图、澄清、SYSTEM、检索和返回 F 的真实调用顺序。
- [`MultiQuestionRewriteService`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/MultiQuestionRewriteService.java)、[`QueryTermMappingService`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java) 与 [`user-question-rewrite.st`](../../../bootstrap/src/main/resources/prompt/user-question-rewrite.st)：术语替换、消息装配、历史 skip、模型 JSON 解析和关闭分支。
- [`DefaultIntentClassifier`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/DefaultIntentClassifier.java)、[`IntentResolver`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentResolver.java) 与 [`intent-classifier.st`](../../../bootstrap/src/main/resources/prompt/intent-classifier.st)：树恢复、叶子候选、一次 LLM 分类、0.35 过滤和请求级意图收口。
- [`IntentTreeController`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/IntentTreeController.java)、[`IntentTreeCacheManager`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeCacheManager.java) 与 [`schema_pg.sql`](../../../resources/database/schema_pg.sql)：意图数据、启停、缓存失效和 `t_intent_node` 邻接表字段。
- [`RetrievalEngine`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalEngine.java)、[`MultiChannelRetrievalEngine`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java) 与 [`RetrievalScopeResolver`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java)：scope 的实际调用位置、子问题并发、KB/MCP 汇合与空结果。
- [`LLMMcpParameterExtractor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/LLMMcpParameterExtractor.java)、[`McpClientAutoConfiguration`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientAutoConfiguration.java)、[`McpClientToolExecutor`](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientToolExecutor.java) 与 [`WeatherMcpExecutor`](../../../mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/WeatherMcpExecutor.java)：启动期发现、schema 提参、三种提参结局、远端调用和一个实际工具返回。
- [`application.yaml`](../../../bootstrap/src/main/resources/application.yaml) 与 [`application-iron-ore-demo.yaml`](../../../bootstrap/src/main/resources/application-iron-ore-demo.yaml)：改写开关、scope 两道阈值、global/empty fallback 和补充比例的主配置/profile 差异。

本篇依据当前工作树做静态源码核对，没有启动 Redis、PostgreSQL、MCP Server 或模型服务，也没有运行真实聊天和评测。因此文中的默认值与分支是代码/配置事实，贯穿示例的模型输出、分数和集合是教学假设，不代表一次实测结果。
