# RAG Trace 链路追踪接口流程分析（/rag/traces/runs）

> 文件路径：
> - Controller：`bootstrap/.../rag/controller/RagTraceController.java`
> - 查询 Service：`bootstrap/.../rag/service/impl/RagTraceQueryServiceImpl.java`
> - 写入 Service：`bootstrap/.../rag/service/impl/RagTraceRecordServiceImpl.java`
> - AOP 切面：`bootstrap/.../rag/aop/RagTraceAspect.java`
> - 链路上下文：`framework/.../trace/RagTraceContext.java`
> - 注解定义：`framework/.../trace/RagTraceRoot.java`、`RagTraceNode.java`
> - 配置：`bootstrap/.../rag/config/RagTraceProperties.java`

---

## 一、接口总览

| HTTP 方法 | 路径                              | 功能                         |
|----------|----------------------------------|------------------------------|
| GET      | /rag/traces/runs                  | 分页查询链路运行记录列表       |
| GET      | /rag/traces/runs/{traceId}        | 查询单条链路详情（含节点列表） |
| GET      | /rag/traces/runs/{traceId}/nodes  | 仅查询链路执行节点列表         |

> 本接口组为**只读**接口；链路数据由 AOP 切面在 RAG 请求执行过程中自动写入。

---

## 二、核心概念：注解式链路追踪

RAG Trace 通过两个注解 + 一个 AOP 切面实现对 RAG 请求执行过程的全自动追踪：

```
@RagTraceRoot（标记链路入口，如 RAGChatServiceImpl.chat()）
  │
  └─ RagTraceAspect.aroundRoot()
       ├─ 生成 traceId，写入 RagTraceContext（TransmittableThreadLocal）
       ├─ insert t_rag_trace_run（状态 RUNNING）
       ├─ 执行方法（过程中各阶段方法上有 @RagTraceNode）
       │    └─ RagTraceAspect.aroundNode()（可多层嵌套）
       │         ├─ 读取 RagTraceContext.currentNodeId() 作为 parentNodeId
       │         ├─ insert t_rag_trace_node（状态 RUNNING）
       │         ├─ pushNode → 执行方法 → popNode
       │         └─ update t_rag_trace_node（状态 SUCCESS/ERROR）
       └─ update t_rag_trace_run（状态 SUCCESS/ERROR，补充耗时）
```

### 关键设计点

| 特性              | 说明                                                                        |
|-----------------|---------------------------------------------------------------------------|
| TransmittableThreadLocal | 使用 TTL 而非普通 ThreadLocal，支持 traceId 在线程池异步场景中自动透传     |
| 节点栈（Deque）    | push/pop 维护节点调用层级，`currentNodeId()` 取栈顶为 parentNodeId，`depth()` = 栈大小 |
| 两阶段写入         | 方法开始时 insert（RUNNING），方法结束时 update（SUCCESS/ERROR）；即使中途宕机也有 RUNNING 记录 |
| 幂等保护           | aroundRoot 检查当前线程是否已有 traceId，已有则跳过，防止嵌套调用创建多条链路 |
| 全局开关           | `rag.trace.enabled=false` 时切面直接透传，零开销                            |
| 错误截断           | 异常信息截断至 `rag.trace.max-error-length`（默认 1000），防止长文本写库   |

---

## 三、各接口详细流程

### 1. 分页查询链路运行记录 `GET /rag/traces/runs`

**请求参数（RagTraceRunPageRequest extends Page）**

| 参数           | 说明                                  |
|--------------|--------------------------------------|
| current      | 当前页码（从 1 开始）                  |
| size         | 每页条数                              |
| traceId      | 链路 ID，精确匹配（可选）              |
| conversationId | 会话 ID，精确匹配（可选）            |
| taskId       | 任务 ID，精确匹配（可选）              |
| status       | 状态过滤：RUNNING / SUCCESS / ERROR（可选）|

**处理流程**

```
客户端 GET /rag/traces/runs?current=1&size=10&status=ERROR
  │
  ├─ 1. 构建 LambdaQueryWrapper：
  │      ORDER BY startTime DESC
  │      非空时追加：traceId=? AND conversationId=? AND taskId=? AND status=?
  │
  ├─ 2. selectPage 执行分页查询（t_rag_trace_run，逻辑删除过滤 deleted=0）
  │
  ├─ 3. loadUsernameMap：批量查询本页所有 userId 对应的 username（IN 查询，避免 N+1）
  │      只 select id, username 两列，减少网络 IO
  │
  └─ 4. pageResult.convert(toRunVO)：DO → VO，username 从 usernameMap 中取
```

---

### 2. 查询链路详情 `GET /rag/traces/runs/{traceId}`

**处理流程**

```
客户端 GET /rag/traces/runs/{traceId}
  │
  ├─ 1. selectOne 按 traceId 精确查询 t_rag_trace_run（limit 1 防止脏数据）
  │      traceId 不存在时返回 null
  │
  ├─ 2. loadUsernameMap 加载该条记录的用户名
  │
  ├─ 3. toRunVO：DO → RagTraceRunVO
  │
  ├─ 4. listNodes(traceId)：查询所有 TraceNode（复用接口 3 的逻辑）
  │
  └─ 5. 组合为 RagTraceDetailVO { run, nodes } 返回
```

---

### 3. 查询链路节点列表 `GET /rag/traces/runs/{traceId}/nodes`

**处理流程**

```
客户端 GET /rag/traces/runs/{traceId}/nodes
  │
  ├─ selectList 按 traceId 过滤 t_rag_trace_node
  │   ORDER BY startTime ASC, id ASC
  │   （同毫秒内启动的节点用雪花 ID 兜底排序，保证顺序确定性）
  │
  └─ stream().map(toNodeVO)：DO → RagTraceNodeVO 列表返回
```

---

## 四、AOP 切面工作原理（RagTraceAspect）

### aroundRoot — 链路根节点

```
拦截 @RagTraceRoot 方法
  │
  ├─ 检查 traceProperties.isEnabled()？→ 否则直接透传
  │
  ├─ 检查 RagTraceContext.getTraceId()？→ 已有则透传（幂等保护）
  │
  ├─ 生成 traceId（雪花 ID）
  │
  ├─ resolveStringArg：从方法参数列表中按名称（conversationIdArg/taskIdArg）提取值
  │
  ├─ startRun：insert t_rag_trace_run（RUNNING）
  │
  ├─ RagTraceContext.setTraceId(traceId)
  │
  ├─ joinPoint.proceed()  →  方法执行（内部 @RagTraceNode 被同步拦截）
  │
  ├─ finishRun（SUCCESS 或 ERROR）
  │
  └─ finally: RagTraceContext.clear()  ← 防止线程池线程复用时数据泄露
```

### aroundNode — 链路子节点

```
拦截 @RagTraceNode 方法
  │
  ├─ 检查 enabled + traceId 是否存在（不在链路中则透传）
  │
  ├─ 从 RagTraceContext 取 parentNodeId（节点栈顶）和 depth（栈大小）
  │
  ├─ startNode：insert t_rag_trace_node（RUNNING）
  │
  ├─ RagTraceContext.pushNode(nodeId)  ← 当前节点成为后续嵌套节点的父节点
  │
  ├─ joinPoint.proceed()  →  方法执行
  │
  ├─ finishNode（SUCCESS 或 ERROR）
  │
  └─ finally: RagTraceContext.popNode()  ← 恢复父节点为当前节点
```

### 节点树形结构示意

```
RagTraceRun（traceId=xxx）
  └─ 节点 A（depth=0, parentNodeId=null）       ← 如：意图解析
       └─ 节点 B（depth=1, parentNodeId=A）     ← 如：查询改写
            └─ 节点 C（depth=2, parentNodeId=B）← 如：多问题扩展
  └─ 节点 D（depth=0, parentNodeId=null）       ← 如：向量检索
  └─ 节点 E（depth=0, parentNodeId=null）       ← 如：LLM 生成
```

---

## 五、数据库表

### t_rag_trace_run（链路运行记录）

| 字段           | 类型     | 说明                                         |
|-------------|---------|---------------------------------------------|
| id          | bigint  | 自增主键                                     |
| trace_id    | varchar | 链路唯一 ID（雪花 ID），用于关联 TraceNode     |
| trace_name  | varchar | 链路名称，来自 @RagTraceRoot(name)            |
| entry_method | varchar | 触发入口方法（类全限定名#方法名）              |
| conversation_id | varchar | 会话 ID                                  |
| task_id     | varchar | 任务 ID                                      |
| user_id     | varchar | 触发用户 ID                                  |
| status      | varchar | RUNNING / SUCCESS / ERROR                    |
| error_message | varchar | 截断后的错误信息（仅 ERROR 时有值）          |
| start_time  | datetime | 链路开始时间                                |
| end_time    | datetime | 链路结束时间（RUNNING 时为 null）            |
| duration_ms | bigint  | 总耗时（毫秒）                               |
| extra_data  | text    | 预留扩展字段（JSON）                         |
| deleted     | int     | 逻辑删除标记（@TableLogic）                  |
| create_time | datetime | 创建时间                                    |
| update_time | datetime | 更新时间                                    |

### t_rag_trace_node（链路节点记录）

| 字段           | 类型     | 说明                                               |
|-------------|---------|--------------------------------------------------|
| id          | bigint  | 自增主键                                           |
| trace_id    | varchar | 所属链路 ID                                        |
| node_id     | varchar | 节点唯一 ID（雪花 ID），在同一链路内唯一             |
| parent_node_id | varchar | 父节点 ID（null 表示根节点的直接子节点）          |
| depth       | int     | 调用深度（0 最外层）                               |
| node_type   | varchar | 节点类型，如 INTENT / REWRITE / RETRIEVE / LLM 等 |
| node_name   | varchar | 节点展示名称                                       |
| class_name  | varchar | 被拦截方法所在类全限定名                            |
| method_name | varchar | 被拦截方法名                                       |
| status      | varchar | RUNNING / SUCCESS / ERROR                          |
| error_message | varchar | 截断后的错误信息                                 |
| start_time  | datetime | 节点开始时间                                      |
| end_time    | datetime | 节点结束时间                                      |
| duration_ms | bigint  | 节点耗时（毫秒）                                  |
| extra_data  | text    | 预留扩展字段（JSON）                              |
| deleted     | int     | 逻辑删除标记                                      |
| create_time | datetime | 创建时间                                         |
| update_time | datetime | 更新时间                                         |

---

## 六、配置项（application.yml）

```yaml
rag:
  trace:
    enabled: true          # 是否启用链路追踪（false 时切面完全透传）
    max-error-length: 1000 # 错误信息落库最大长度
```

---

## 七、设计要点总结

| 问题                     | 设计方案                                                           |
|------------------------|--------------------------------------------------------------------|
| 跨异步线程透传 traceId    | 使用 TransmittableThreadLocal（TTL），线程池提交时自动复制上下文   |
| 节点父子关系的维护        | 节点栈（Deque）push/pop，aroundNode 进入时 push，finally 时 pop   |
| 中途宕机时链路状态可查     | 两阶段写入：开始 insert RUNNING，结束 update 实际状态              |
| 嵌套 @RagTraceRoot 防止重复 | aroundRoot 检查 traceId 是否已存在，已存在则透传                  |
| 分页查询用户名 N+1 问题   | loadUsernameMap 批量 IN 查询，一次 SQL 获取本页所有用户名          |
| 错误信息过长写库问题       | truncateError 截断至 maxErrorLength，格式：ClassName: message     |
| 线程复用上下文污染         | aroundRoot finally 中调用 RagTraceContext.clear() 清理所有 TTL     |
