# Ragent 整体架构分析（后端 + AI）

## 1. 技术栈清单

| 类别 | 技术 |
| --- | --- |
| 后端框架 | Java 17、Spring Boot 3.5.7、Spring Web、MyBatis-Plus 3.5.14 |
| AI 能力层 | 自研 `infra-ai` 路由层（`RoutingLLMService` / `RoutingEmbeddingService` / `RoutingRerankService`） |
| 模型供应商/模型 | BaiLian（qwen-plus/qwen3-max/qwen3-rerank）、SiliconFlow（GLM-4.7、Qwen3-Embedding-8B）、Ollama（qwen3 本地模型） |
| RAG 核心 | Query Rewrite + 多问拆分、Intent Resolver、多通道检索、Rerank、Prompt 组装 |
| 向量/检索存储 | PostgreSQL + pgvector（可切换 Milvus 2.6.6） |
| 关系数据库 | PostgreSQL |
| 缓存 | Redis + Redisson 4.0.0 |
| 消息队列 | RocketMQ 2.3.5 |
| 对象存储 | AWS S3 SDK 2.40.2（配置使用 RustFS 兼容 S3） |
| 鉴权 | Sa-Token 1.43.0 |
| 前端 | React 18 + TypeScript + Vite 5 + Zustand + Axios |

## 2. 项目结构图（目录层级 + 核心职责）

```text
ragent/
├─ bootstrap/                         # 主业务后端（API入口 + RAG业务编排）
│  ├─ src/main/java/.../ragent/
│  │  ├─ rag/                         # 对话、检索、意图、重写、记忆、trace
│  │  │  ├─ controller/               # /rag/v3/chat 等接口
│  │  │  ├─ service/impl/             # RAGChatServiceImpl 主链路编排
│  │  │  └─ core/                     # rewrite/intent/retrieve/mcp/memory/prompt
│  │  ├─ knowledge/                   # 知识库与文档管理
│  │  ├─ ingestion/                   # 文档入库流水线
│  │  ├─ user/                        # 登录与用户管理
│  │  └─ admin/                       # 管理端接口
│  └─ src/main/resources/application.yaml
├─ framework/                         # 通用基础设施层（context、trace、异常、幂等等）
├─ infra-ai/                          # AI接入层（chat/embedding/rerank 路由与容错）
├─ mcp-server/                        # 独立 MCP 工具服务（JSON-RPC）
├─ frontend/                          # React 管理台 + 问答页面
└─ resources/database/                # schema/init/upgrade SQL
```

## 3. 架构模式

1. **分层单体（Modular Monolith）**：`bootstrap`（业务）+ `framework`（基础设施）+ `infra-ai`（AI适配）。
2. **事件/异步增强**：SSE 流式返回、并行检索、并行 MCP 调用、异步记忆摘要。
3. **外部工具协作（MCP）**：主服务通过 MCP 协议调用独立 `mcp-server`。
4. **策略 + 责任链**：多检索通道策略 + 去重/Rerank 后处理链。

## 4. AI 能力矩阵

| 功能模块 | 使用模型/算法 | 输入输出 | 调用方式 |
| --- | --- | --- | --- |
| 查询改写与拆分 | LLM + JSON 解析 | 输入：用户问题/历史；输出：`rewrite + sub_questions` | 同步 `llmService.chat()` |
| 意图识别 | IntentClassifier + 评分阈值 | 输入：子问题；输出：意图节点与分数 | 并行线程池 |
| 歧义引导 | 规则判定 + Prompt 模板 | 输入：意图候选；输出：引导文案/无引导 | 同步判定 |
| 多通道检索 | IntentDirected + VectorGlobal | 输入：子问题意图、topK；输出：Chunk 列表 | 并行检索 |
| 检索后处理 | Dedup + Rerank 模型 | 输入：候选 Chunk；输出：TopK 重排结果 | 责任链 |
| MCP 参数提取 | LLM 参数抽取 | 输入：用户问题 + 工具定义；输出：参数 Map | 同步 `llmService.chat()` |
| MCP 工具调用 | JSON-RPC `tools/call` | 输入：工具名+参数；输出：工具文本结果 | HTTP POST `/mcp` |
| 会话记忆 | 滑窗 + 摘要压缩 | 输入：会话消息；输出：摘要化上下文 | 异步加载/压缩 |
| 模型路由与容错 | 优先级 + 熔断 + Fallback | 输入：chat/embedding/rerank 请求；输出：可用模型结果 | 路由执行器 |

## 5. 数据流图（请求入口 -> AI 推理 -> 响应返回）

```text
Client
  -> GET /api/ragent/rag/v3/chat?question=...
  -> RAGChatController (SSE)
  -> RAGChatServiceImpl
      1) ConversationMemoryService.loadAndAppend
      2) QueryRewriteService.rewriteWithSplit
      3) IntentResolver.resolve
      4) IntentGuidanceService.detectAmbiguity
         └─ 若命中歧义：直接返回引导内容并结束
      5) RetrievalEngine.retrieve
         ├─ MultiChannelRetrievalEngine (并行检索 + 后处理)
         └─ MCP 分支：
             LLMMCPParameterExtractor -> MCPToolExecutor
             -> HTTP JSON-RPC /mcp (mcp-server)
      6) RAGPromptService.buildStructuredMessages
      7) RoutingLLMService.streamChat
         ├─ ModelSelector 选择候选
         ├─ ModelHealthStore 熔断判断
         └─ 多模型失败自动 fallback
  -> StreamCallback -> SseEmitter 持续推送
  -> Client 实时接收回答
```

## 6. 关键依赖（requirements.txt / package.json）

### Python
- 仓库中未发现 `requirements.txt`（核心后端为 Java Maven 工程）。

### Frontend（`frontend/package.json`）
- `react` `^18.3.1`
- `react-router-dom` `^6.26.2`
- `axios` `^1.7.5`
- `zustand` `^4.5.5`
- `typescript` `^5.5.4`
- `vite` `^5.4.3`
- `tailwindcss` `^3.4.10`
- `zod` `^4.3.6`

### Backend（Maven）
- `spring-boot` `3.5.7`
- `mybatis-plus-spring-boot3-starter` `3.5.14`
- `milvus-sdk-java` `2.6.6`
- `tika` `3.2.3`
- `sa-token` `1.43.0`
- `redisson-spring-boot-starter` `4.0.0`
- `rocketmq-spring-boot-starter` `2.3.5`
- `okhttp` `4.12.0`
- `s3` `2.40.2`
- `pgvector` `0.1.6`
