# /rag/v3/chat 接口全链路流程梳理

> 本文档基于对源代码的阅读整理，描述一次 SSE 流式问答请求从 HTTP 入口到 LLM 输出、再到前端的完整调用链路。

---

## 一、整体架构概述

Ragent 是一个自研的 RAG（检索增强生成）对话系统，**没有直接使用 Spring AI 框架**，而是基于自定义的抽象层（`LLMService` / `ChatClient`）支持多模型厂商（百炼、SiliconFlow、Ollama）路由切换。

核心思路：
- 用户的问题经过**记忆加载 → 查询改写 → 意图识别 → 多路检索 → Prompt 组装**后送给 LLM；
- LLM 的输出通过 **SSE（Server-Sent Events）流式推送**给前端；
- 取消请求通过 **Redis Pub/Sub 跨节点广播**。

---

## 二、请求生命周期全链路

```
HTTP GET /api/ragent/rag/v3/chat?question=Q&conversationId=C&deepThinking=false
         │
         ▼
┌────────────────────────────────────┐
│  IdempotentSubmitAspect（AOP）      │  ← 幂等拦截：同一用户并发请求直接拒绝
│  key = UserContext.getUserId()     │    Redis 分布式锁，非阻塞，失败立即抛异常
└───────────┬────────────────────────┘
            │
            ▼
┌────────────────────────────────────┐
│  RAGChatController.chat()          │  ← HTTP 控制器
│  创建 SseEmitter(0L)               │    timeout=0 表示永不超时
│  → ragChatService.streamChat(...)  │    立即返回 SseEmitter，HTTP 连接保持
└───────────┬────────────────────────┘
            │
            ▼
┌────────────────────────────────────┐
│  ChatRateLimitAspect（AOP）        │  ← 限流排队
│  Redis 信号量 + 有序集合队列        │    max-concurrent=1，超时返回 REJECT 事件
└───────────┬────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  RAGChatServiceImpl.streamChat()                                    │
│                                                                     │
│  1. 生成/复用 conversationId（前端未传则雪花ID生成新会话）            │
│  2. 生成 taskId（复用 RagTraceContext 或新建雪花ID）                 │
│  3. callbackFactory.createChatEventHandler()                        │
│     └─ 创建 StreamChatEventHandler                                 │
│        └─ 发送 META 事件（conversationId + taskId）→ 前端            │
│        └─ 向 StreamTaskManager 注册任务（支持取消）                  │
│  4. memoryService.loadAndAppend()                                   │
│     └─ 并行加载摘要 + 最近消息 → 合并历史                           │
│     └─ 追加当前 USER 消息到数据库                                   │
│  5. queryRewriteService.rewriteWithSplit()                          │
│     └─ 解歧义（消解指代）+ 问题拆分为子问题                          │
│  6. intentResolver.resolve()                                        │
│     └─ 并行识别每个子问题的意图（SYSTEM / KB / MCP）                 │
│                                                                     │
│  ┌── 分支判断 ────────────────────────────────────────────────┐     │
│  │ A. 歧义引导：多系统分数相近且问题中无系统名称               │     │
│  │    → onContent(引导文案) + onComplete() [早期返回]          │     │
│  │ B. 纯系统意图（SYSTEM only）                                │     │
│  │    → streamSystemResponse() [早期返回，跳过检索]            │     │
│  │ C. 检索到空上下文                                           │     │
│  │    → onContent("未检索到相关文档") + onComplete() [早期返回]│     │
│  │ D. 正常 RAG 路径（见下方流程）                              │     │
│  └─────────────────────────────────────────────────────────┘     │
│                                                                     │
│  7. retrievalEngine.retrieve()                                      │
│     ├─ 知识库检索（KB）：向量相似度 + 重排序                        │
│     └─ MCP 工具调用：并行执行各意图对应的 MCP 工具                  │
│  8. intentResolver.mergeIntentGroup()                               │
│     └─ 合并各子问题的 KB / MCP 意图                                 │
│  9. promptBuilder.buildStructuredMessages()                         │
│     └─ 组装消息：系统提示词 → MCP证据 → KB证据 → 历史 → 当前问题    │
│  10. llmService.streamChat(chatRequest, callback)                   │
│      └─ 发起 LLM 流式请求，立即返回 StreamCancellationHandle        │
│  11. taskManager.bindHandle(taskId, handle)                         │
│      └─ 绑定底层取消句柄，支持随时中断                               │
└─────────────────────────────────────────────────────────────────────┘
            │
            │ LLM 异步生成 token
            ▼
┌────────────────────────────────────────────┐
│  StreamChatEventHandler（StreamCallback）  │
│                                            │
│  onThinking(chunk)                         │
│    → 发送 MESSAGE 事件（type=think）        │  ← 思考过程只展示，不落库
│                                            │
│  onContent(chunk)                          │
│    → answer.append(chunk)                  │  ← 累计完整回答
│    → 发送 MESSAGE 事件（type=response）     │  ← 按 messageChunkSize 分片（默认5字符）
│       使用 Unicode code point 安全分片      │  ← 防止 emoji 被拆坏
│                                            │
│  onComplete()                              │
│    → memoryService.append(ASSISTANT消息)   │  ← 完整回答写入数据库
│    → 查询会话标题（新会话时）               │
│    → 发送 FINISH 事件（messageId + title）  │
│    → 发送 DONE 事件（"[DONE]"）             │
│    → taskManager.unregister(taskId)        │
│    → SSE 连接关闭                          │
│                                            │
│  onError(throwable)                        │
│    → taskManager.unregister(taskId)        │
│    → SSE 报错关闭                          │
└────────────────────────────────────────────┘
```

---

## 三、各阶段详解

### 3.1 幂等校验（IdempotentSubmitAspect）

| 项目 | 说明 |
|---|---|
| 实现方式 | Redisson 分布式锁，`tryLock()` 非阻塞 |
| Lock Key | `idempotent-submit:key:{userId}`（聊天接口使用 SpEL 自定义 key） |
| 冲突处理 | 加锁失败立即抛 `ClientException`，返回 HTTP 400 |
| 锁释放时机 | 方法执行完毕（含异常）后在 `finally` 块释放 |

**作用**：同一用户在当前 SSE 流未结束前，再次发起聊天请求会被直接拒绝，防止重复消费。

### 3.2 会话记忆加载（ConversationMemoryService）

```
DefaultConversationMemoryService.load()
    ├─ CompletableFuture #1 → JdbcConversationMemorySummaryService.loadLatestSummary()
    │    └─ 从 conversation_summary 表取最新摘要（SYSTEM 角色）
    └─ CompletableFuture #2 → JdbcConversationMemoryStore.loadHistory()
         └─ 从 conversation_message 表取最近 history-keep-turns*2 条消息
              └─ 过滤：只保留 USER / ASSISTANT、内容非空的消息
              └─ normalizeHistory：去掉开头的连续 ASSISTANT 消息

合并结果：[摘要(可选)] + [近期消息]
```

**配置项**：
- `history-keep-turns: 4` → 最多保留 4 轮（8 条）最近消息
- `summary-start-turns: 5` → 超过 5 轮用户发言后开始异步摘要
- `summary-enabled: true`

### 3.3 历史摘要压缩（JdbcConversationMemorySummaryService）

每次 ASSISTANT 消息写入后，异步检查是否需要压缩：

```
compressIfNeeded(conversationId, userId, ASSISTANT消息)
    ↓ [异步，memorySummaryExecutor 线程池]
doCompressIfNeeded()
    1. 检查 summaryEnabled && 用户消息数 >= summaryStartTurns
    2. 获取 Redis 分布式锁（防止多节点同时压缩）
    3. 计算 cutoffId（近期保留区起点：最近 N 轮最早的用户消息ID）
    4. 计算 afterId（上次摘要终点：lastMessageId 或按时间推算）
    5. 查询 (afterId, cutoffId) 区间内的消息
    6. 调用 LLM 生成增量摘要（合并已有摘要内容）
    7. 将摘要写入 conversation_summary 表，记录 lastMessageId
```

### 3.4 查询改写（QueryRewriteService）

两个职责：
1. **消解指代**：结合历史消息，把"它"、"这个产品"等指代词还原为具体实体
2. **问题拆分**：将"A 和 B 的区别，以及 C 如何使用"拆成多个独立子问题，每个子问题单独检索

同时，写入前会通过 `QueryTermMappingService` 做**同义词映射**：
- 规则从数据库加载，启动时缓存到内存
- 按优先级降序、源词长度降序排列（防止短词先替换打断长词）
- 精确子串匹配，已是目标词时跳过（防止重复替换）

### 3.5 意图识别（IntentResolver）

对每个子问题并行识别意图，可能的意图类型：

| 意图类型 | 含义 | 后续动作 |
|---|---|---|
| SYSTEM | 通用对话、闲聊、内置能力 | 直接调 LLM，不检索 |
| KB | 匹配到某个知识库节点 | 向量检索 + 重排序 |
| MCP | 匹配到某个 MCP 工具节点 | 调用 MCP 工具接口 |

意图按置信度打分，低于 `INTENT_MIN_SCORE` 的直接过滤。

### 3.6 歧义引导（IntentGuidanceService）

若多个知识库/MCP 节点置信度接近，且用户没有在问题中明确指定系统名，则：
- 返回引导文案（如"您的问题可能涉及以下系统，请明确选择：A / B / C"）
- 通过 `callback.onContent()` + `callback.onComplete()` 走正常 SSE 完成流程
- **提前返回**，不进入检索阶段

### 3.7 多路检索（RetrievalEngine）

按子问题并行执行两路检索：

**知识库检索（KB）**：
```
MultiChannelRetrievalEngine
    ├─ VectorGlobalSearchChannel：全局向量相似度检索
    └─ IntentDirectedSearchChannel：意图定向检索
    
PostProcessor 链（按 order 顺序执行）：
    → DeduplicationPostProcessor（去重）
    → RerankPostProcessor（使用 qwen3-rerank 重排序）
    → SearchResultPostProcessor（结果过滤）
```

**MCP 工具调用**：
- 从问题中提取工具参数
- 并行调用各 MCP 节点工具接口
- 格式化工具返回结果

### 3.8 Prompt 组装（RAGPromptService）

消息组装顺序（固定）：

```
1. system prompt（根据场景选择：KB_ONLY / MCP_ONLY / MIXED）
2. MCP 工具返回内容（如有）
3. KB 检索片段（如有）
4. 历史对话消息（来自记忆层）
5. 当前用户问题（多子问题时显式编号）
```

**温度参数策略**：
| 路径 | temperature | topP |
|---|---|---|
| 纯系统对话 | 0.7 | 默认 |
| 纯 KB 检索 | 0.0 | 1.0 |
| 含 MCP 检索 | 0.3 | 0.8 |

### 3.9 LLM 路由（RoutingLLMService）

自定义路由层，支持多模型 failover：
- 按优先级排列候选模型
- 流式调用时使用 `ProbeBufferingCallback` 等待首包确认
- 首包超时（60s）或出错时自动切换下一个候选模型
- `ModelHealthStore` 记录熔断状态，故障模型在 `openDurationMs`（默认 30s）内不再尝试

---

## 四、SSE 事件协议

前端接收到的事件类型及顺序：

| 事件类型 | 触发时机 | 数据格式 |
|---|---|---|
| `META` | 任务创建后立即发送（第一个事件） | `{conversationId, taskId}` |
| `MESSAGE` | 模型每生成 N 个字符 | `{type: "think"/"response", content: "..."}` |
| `FINISH` | 模型正常完成 | `{messageId, title}` |
| `DONE` | 流结束协议标记 | `"[DONE]"` |
| `CANCEL` | 用户主动停止 | `{messageId, title}` |
| `REJECT` | 限流超时队列满 | `{content: "等待超时..."}` |

---

## 五、取消流程

```
前端调用 POST /rag/v3/stop?taskId=T
    ↓
RAGChatController.stop()
    ↓
RAGChatServiceImpl.stopTask(taskId)
    ↓
StreamTaskManager.cancel(taskId)
    1. Redis.set(cancelKey, true, TTL=30min)   ← 持久化取消标记
    2. Redis Topic.publish(CANCEL_TOPIC, taskId) ← 广播到所有节点

所有节点订阅回调（cancelLocal）：
    1. tasks.getIfPresent(taskId) → 只有持有该任务的节点有数据
    2. CAS：cancelled.compareAndSet(false, true)（保证幂等，最多执行一次）
    3. handle.cancel()  ← 中止底层 HTTP/模型请求
    4. onCancelSupplier.get()  ← 保存已生成的部分回答到数据库
    5. 发送 CANCEL 事件 + DONE 事件
    6. SSE 连接关闭
```

**竞态覆盖**：
- 取消早于注册：`register()` 时检查 Redis 标记，立即执行收尾
- 取消早于 handle 绑定：`bindHandle()` 后检查 `cancelled` 状态，立即调用 `handle.cancel()`

---

## 六、关键配置项速查

```yaml
rag:
  memory:
    history-keep-turns: 4      # 保留最近 N 轮原始对话（N*2 条消息）
    summary-start-turns: 5     # 超过 N 轮用户发言后触发摘要
    summary-enabled: true      # 是否开启历史压缩摘要
    summary-max-chars: 200     # 摘要最大字符数
    title-max-length: 30       # 会话标题最大字符数

  rate-limit:
    global:
      enabled: true
      max-concurrent: 1        # 全局最大并发流式请求数
      max-wait-seconds: 3      # 限流队列中最大等待时间
      lease-seconds: 30        # 信号量租约时间（防止任务卡死不释放）

  query-rewrite:
    enabled: true
    max-history-messages: 4    # 查询改写时参考的最多历史条数
    max-history-chars: 500     # 查询改写时参考的最多历史字符数

  search:
    channels:
      vector-global:
        confidence-threshold: 0.6   # 向量检索分数过滤阈值
      intent-directed:
        min-intent-score: 0.4       # 意图定向检索的最低分数
```

---

## 七、核心类索引

| 类名 | 路径（bootstrap 模块） | 职责 |
|---|---|---|
| `RAGChatController` | `rag/controller/` | HTTP 入口，创建 SSE，委托给 Service |
| `RAGChatServiceImpl` | `rag/service/impl/` | **核心编排**：串联所有下游组件 |
| `StreamChatEventHandler` | `rag/service/handler/` | 大模型回调 → SSE 事件转发 + 消息落库 |
| `StreamTaskManager` | `rag/service/handler/` | 任务注册/取消/跨节点广播 |
| `DefaultConversationMemoryService` | `rag/core/memory/` | 记忆编排（摘要 + 近期消息） |
| `JdbcConversationMemoryStore` | `rag/core/memory/` | 近期消息 JDBC 读写 |
| `JdbcConversationMemorySummaryService` | `rag/core/memory/` | 异步历史压缩摘要 |
| `QueryTermMappingService` | `rag/core/rewrite/` | 同义词映射（启动时缓存规则） |
| `ConversationServiceImpl` | `rag/service/impl/` | 会话元数据（创建/重命名/删除/标题） |
| `ConversationGroupServiceImpl` | `rag/service/impl/` | 聚合查询（摘要边界计算） |
| `ConversationMessageServiceImpl` | `rag/service/impl/` | 消息落库 + 反馈查询 |
| `AuthServiceImpl` | `user/service/impl/` | 登录/登出（Sa-Token） |

| 类名 | 路径（framework 模块） | 职责 |
|---|---|---|
| `UserContext` | `framework/context/` | TTL 线程上下文，存当前登录用户 |
| `ChatMessage` | `framework/convention/` | 大模型消息抽象（SYSTEM/USER/ASSISTANT） |
| `IdempotentSubmit` | `framework/idempotent/` | 幂等注解 |
| `IdempotentSubmitAspect` | `framework/idempotent/` | 幂等 AOP（Redis 分布式锁） |
| `RagTraceContext` | `framework/trace/` | 链路追踪上下文（TTL 异步传播） |
