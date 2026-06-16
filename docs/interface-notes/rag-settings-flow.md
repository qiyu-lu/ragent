# RAG 系统设置接口流程分析（/rag/settings）

> 文件路径：
> - Controller：`bootstrap/.../rag/controller/RAGSettingsController.java`
> - 响应 VO：`bootstrap/.../rag/controller/vo/SystemSettingsVO.java`
> - 配置属性：
>   - `bootstrap/.../rag/config/RAGDefaultProperties.java`
>   - `bootstrap/.../rag/config/RAGConfigProperties.java`
>   - `bootstrap/.../rag/config/RAGRateLimitProperties.java`
>   - `bootstrap/.../rag/config/MemoryProperties.java`
>   - `infra-ai/.../infra/config/AIModelProperties.java`

---

## 一、接口总览

| HTTP 方法 | 路径          | 功能                               |
|----------|-------------|----------------------------------|
| GET      | /rag/settings | 获取系统全量配置（RAG + AI + 上传限制）|

> 只读接口，无请求参数，无鉴权依赖，直接读取 Spring 配置属性并汇聚返回。

---

## 二、接口处理流程

```
客户端 GET /rag/settings
  │
  └─ RAGSettingsController.settings()
       │
       ├─ 1. 读取 maxFileSize / maxRequestSize（@Value 注入，DataSize → toBytes()）
       │
       ├─ 2. toDefaultSettings(ragDefaultProperties)
       │      ← rag.default.collection-name / dimension / metric-type
       │
       ├─ 3. QueryRewriteSettings 直接从 ragConfigProperties 取字段
       │      ← rag.query-rewrite.enabled / max-history-messages / max-history-chars
       │
       ├─ 4. GlobalRateLimit 直接从 ragRateLimitProperties 取字段
       │      ← rag.rate-limit.global.*
       │
       ├─ 5. toMemorySettings(memoryProperties)
       │      ← rag.memory.*
       │
       ├─ 6. toAISettings(aiModelProperties)
       │      ├─ 遍历 providers map → ProviderConfig（url / apiKey / endpoints）
       │      ├─ toModelGroup(chat) → ModelGroup（defaultModel + candidates 列表）
       │      ├─ toModelGroup(embedding)
       │      ├─ toModelGroup(rerank)
       │      ├─ Selection（failureThreshold / openDurationMs）
       │      └─ Stream（messageChunkSize）
       │
       └─ 7. 组合为 SystemSettingsVO { upload, rag, ai } 返回
```

---

## 三、响应结构（SystemSettingsVO）

```json
{
  "upload": {
    "maxFileSize": 52428800,        // 50MB（字节数）
    "maxRequestSize": 104857600     // 100MB
  },
  "rag": {
    "default": {
      "collectionName": "default_collection",
      "dimension": 1536,
      "metricType": "COSINE"        // COSINE / L2 / IP
    },
    "queryRewrite": {
      "enabled": true,
      "maxHistoryMessages": 4,
      "maxHistoryChars": 500
    },
    "rateLimit": {
      "global": {
        "enabled": true,
        "maxConcurrent": 50,
        "maxWaitSeconds": 20,
        "leaseSeconds": 600,
        "pollIntervalMs": 200
      }
    },
    "memory": {
      "historyKeepTurns": 8,
      "ttlMinutes": 60,
      "summaryEnabled": false,
      "summaryStartTurns": 9,
      "summaryMaxChars": 200,
      "titleMaxLength": 30
    }
  },
  "ai": {
    "providers": {
      "openai": {
        "url": "https://api.openai.com",
        "apiKey": "sk-xxx",
        "endpoints": { "chat": "/v1/chat/completions" }
      }
    },
    "chat": {
      "defaultModel": "gpt-4o",
      "deepThinkingModel": "o1",
      "candidates": [
        {
          "id": "gpt-4o",
          "provider": "openai",
          "model": "gpt-4o",
          "url": null,
          "dimension": null,
          "priority": 1,
          "enabled": true,
          "supportsThinking": false
        }
      ]
    },
    "embedding": { ... },
    "rerank": { ... },
    "selection": {
      "failureThreshold": 2,
      "openDurationMs": 30000
    },
    "stream": {
      "messageChunkSize": 5
    }
  }
}
```

---

## 四、各配置块详解

### 4.1 upload — 文件上传限制

| 字段             | 来源配置项                                    | 默认值  | 单位 |
|----------------|---------------------------------------------|--------|------|
| maxFileSize    | spring.servlet.multipart.max-file-size       | 50MB   | 字节 |
| maxRequestSize | spring.servlet.multipart.max-request-size    | 100MB  | 字节 |

> DataSize 类型在 Controller 中通过 `toBytes()` 转为 Long 返回，前端可直接做文件大小校验。

---

### 4.2 rag.default — 向量数据库默认参数

| 字段            | 配置项                         | 说明                                    |
|---------------|------------------------------|----------------------------------------|
| collectionName | rag.default.collection-name  | 未指定知识库时使用的默认向量集合名称       |
| dimension      | rag.default.dimension        | 向量维度，须与 embedding 模型输出维度一致  |
| metricType     | rag.default.metric-type      | 相似度度量：COSINE / L2 / IP             |

---

### 4.3 rag.queryRewrite — 查询改写

| 字段                 | 配置项                                    | 默认值 | 说明                                      |
|--------------------|------------------------------------------|-------|------------------------------------------|
| enabled            | rag.query-rewrite.enabled                | true  | 是否在检索前通过 LLM 改写用户问题            |
| maxHistoryMessages | rag.query-rewrite.max-history-messages   | 4     | 改写时纳入的最大历史消息条数（user+assistant 各 1 条）|
| maxHistoryChars    | rag.query-rewrite.max-history-chars      | 500   | 改写时纳入的历史消息最大字符数               |

---

### 4.4 rag.rateLimit.global — 全局并发限流

基于 Redis 分布式信号量实现，超过最大并发数的请求进入轮询等待：

| 字段            | 配置项                                  | 默认值 | 说明                                         |
|---------------|----------------------------------------|-------|---------------------------------------------|
| enabled       | rag.rate-limit.global.enabled          | true  | 是否启用全局限流                               |
| maxConcurrent | rag.rate-limit.global.max-concurrent   | 50    | 最大并发 RAG 请求数                            |
| maxWaitSeconds | rag.rate-limit.global.max-wait-seconds | 20    | 等待超时秒数，超时返回限流错误                  |
| leaseSeconds  | rag.rate-limit.global.lease-seconds    | 600   | 许可自动释放时间（兜底，防止异常退出永久占用信号量）|
| pollIntervalMs | rag.rate-limit.global.poll-interval-ms | 200   | 等待队列轮询间隔（毫秒）                       |

---

### 4.5 rag.memory — 对话记忆

| 字段              | 配置项                            | 默认值 | 校验范围    | 说明                                       |
|-----------------|----------------------------------|-------|-----------|-------------------------------------------|
| historyKeepTurns | rag.memory.history-keep-turns    | 8     | 1~100     | 保留原文的最近 N 轮对话（user+assistant 为一轮）|
| ttlMinutes      | rag.memory.ttl-minutes           | 60    | -         | 对话历史缓存过期时间（分钟）                  |
| summaryEnabled  | rag.memory.summary-enabled       | false | -         | 是否启用对话摘要压缩                         |
| summaryStartTurns | rag.memory.summary-start-turns  | 9     | -         | 超过此轮数后开始生成早期对话摘要（须 > historyKeepTurns）|
| summaryMaxChars | rag.memory.summary-max-chars     | 200   | 200~1000  | 摘要最大字符数                              |
| titleMaxLength  | rag.memory.title-max-length      | 30    | 10~100    | 会话标题最大长度（LLM 生成标题时的提示词约束）  |

---

### 4.6 ai — AI 模型配置

#### 提供商（providers）

```yaml
ai:
  providers:
    openai:
      url: https://api.openai.com
      api-key: sk-xxx
      endpoints:
        chat: /v1/chat/completions
```

每个提供商包含基础 URL、API 密钥和端点映射。

#### 模型组（chat / embedding / rerank）

每个模型组结构相同：

| 字段              | 说明                                           |
|-----------------|----------------------------------------------|
| defaultModel    | 默认路由的模型标识 ID                           |
| deepThinkingModel | 深度思考模式使用的模型标识 ID（supportsThinking=true）|
| candidates      | 候选模型列表，priority 越小越优先               |

候选模型（ModelCandidate）字段：

| 字段              | 说明                                           |
|-----------------|----------------------------------------------|
| id              | 模型唯一标识（路由键）                           |
| provider        | 所属提供商（对应 providers 中的 key）            |
| model           | 传给 API 的 model 参数值                        |
| url             | 模型专用 URL，覆盖提供商基础 URL                 |
| dimension       | 向量维度（仅 embedding 模型有效）                |
| priority        | 路由优先级（数值越小越优先，默认 100）            |
| enabled         | 是否启用（false 时不参与路由）                   |
| supportsThinking | 是否支持思考链功能                              |

#### 熔断策略（selection）

| 字段              | 默认值  | 说明                                  |
|-----------------|-------|--------------------------------------|
| failureThreshold | 2     | 连续失败 N 次后触发熔断                 |
| openDurationMs  | 30000 | 熔断器打开持续时间（毫秒），期间跳过该模型 |

#### 流式响应（stream）

| 字段              | 默认值 | 说明                             |
|-----------------|-------|----------------------------------|
| messageChunkSize | 5     | 每次向客户端推送的字符块大小       |

---

## 五、配置属性对照表（YAML 完整示例）

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 100MB

rag:
  default:
    collection-name: default_collection
    dimension: 1536
    metric-type: COSINE
  query-rewrite:
    enabled: true
    max-history-messages: 4
    max-history-chars: 500
  rate-limit:
    global:
      enabled: true
      max-concurrent: 50
      max-wait-seconds: 20
      lease-seconds: 600
      poll-interval-ms: 200
  memory:
    history-keep-turns: 8
    ttl-minutes: 60
    summary-enabled: false
    summary-start-turns: 9
    summary-max-chars: 200
    title-max-length: 30

ai:
  providers:
    openai:
      url: https://api.openai.com
      api-key: sk-xxx
  chat:
    default-model: gpt-4o
    candidates:
      - id: gpt-4o
        provider: openai
        model: gpt-4o
        priority: 1
        enabled: true
  embedding:
    default-model: text-embedding-ada-002
    candidates:
      - id: text-embedding-ada-002
        provider: openai
        model: text-embedding-ada-002
        dimension: 1536
        priority: 1
  selection:
    failure-threshold: 2
    open-duration-ms: 30000
  stream:
    message-chunk-size: 5
```

---

## 六、设计要点

| 问题                          | 设计方案                                                          |
|-----------------------------|------------------------------------------------------------------|
| 多个配置类一次汇聚              | Controller 注入 5 个 Properties Bean，组合为单一 VO 返回            |
| DataSize 类型转字节            | `DataSize.toBytes()` 转 Long，前端直接用于文件大小校验             |
| `default` 是 Java 关键字       | RagSettings 字段名用 `defaultConfig`，Jackson 用 `@JsonProperty("default")` 序列化为 "default" |
| providers 为 null 时的防护     | `toAISettings` 先判断 null 再 forEach，避免 NPE                   |
| candidates 为 null 时不转空列表 | `toModelGroup` 保留 null，前端区分"未配置"和"空列表"              |
| 配置热更新                     | 当前为启动时注入，修改 YAML 需重启；如需动态生效可引入 @RefreshScope |
