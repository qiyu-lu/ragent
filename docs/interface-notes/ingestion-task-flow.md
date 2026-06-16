# 数据摄入任务接口流程分析（/ingestion/tasks）

> 文件路径：
> - Controller：`bootstrap/.../ingestion/controller/IngestionTaskController.java`
> - Service：`bootstrap/.../ingestion/service/impl/IngestionTaskServiceImpl.java`
> - 执行引擎：`bootstrap/.../ingestion/engine/IngestionEngine.java`

---

## 一、接口总览

| HTTP 方法 | 路径                            | 功能                           |
|----------|---------------------------------|------------------------------|
| POST     | /ingestion/tasks                | 创建并执行采集任务（JSON 来源）  |
| POST     | /ingestion/tasks/upload         | 上传文件并触发采集任务           |
| GET      | /ingestion/tasks/{id}           | 查询任务详情                   |
| GET      | /ingestion/tasks/{id}/nodes     | 查询任务各节点运行记录           |
| GET      | /ingestion/tasks               | 分页查询任务列表                |

---

## 二、核心架构：同步流水线执行

任务执行为**同步阻塞**模式，接口调用会等待整条流水线全部跑完后才返回：

```
HTTP 请求
  │
  ├─ 创建任务记录（status=RUNNING）
  │
  ├─ IngestionEngine.execute()  ← 同步阻塞
  │     │
  │     ├─ 验证流水线（无环、节点引用合法）
  │     ├─ 找起始节点（未被 nextNodeId 引用的节点）
  │     └─ 链式执行：node1 → node2 → ... → nodeN
  │           每个节点：条件检查 → 执行 → 写 NodeLog
  │
  ├─ saveNodeLogs()：各节点日志写入 t_ingestion_task_node
  ├─ updateTaskFromContext()：最终结果写回 t_ingestion_task
  │
  └─ 返回 IngestionResult（taskId、status、chunkCount、message）
```

---

## 三、各接口详细流程

### 1. 创建并执行采集任务 `POST /ingestion/tasks`

**请求参数（IngestionTaskCreateRequest）**

| 字段          | 说明                                              |
|-------------|--------------------------------------------------|
| pipelineId  | 流水线 ID（必填）                                 |
| source      | 文档来源（type + location + fileName + credentials）|
| vectorSpaceId | 目标向量空间（可选，为 null 时由 indexer 节点决定） |

**source.type 支持值（SourceType 枚举）**

| 值      | 说明                         |
|--------|------------------------------|
| file   | 本地文件（需配合 location 路径）|
| url    | 网络 URL                     |
| feishu | 飞书文档                     |
| s3     | S3 兼容对象存储（如 RustFS）   |

**处理流程**

```
客户端 POST /ingestion/tasks
  │
  ├─ 1. 将 DocumentSourceRequest → DocumentSource（校验 type 非空）
  │
  ├─ 2. executeInternal()（rawBytes=null，mimeType=null）
  │     rawBytes 为 null → fetcher 节点负责从 source.location 拉取内容
  │
  └─ 3. 返回 IngestionResult
```

---

### 2. 上传文件并触发采集任务 `POST /ingestion/tasks/upload`

**请求格式**：`multipart/form-data`

| 参数/Part   | 类型          | 说明                  |
|-----------|--------------|----------------------|
| pipelineId | Query Param  | 流水线 ID（必填）      |
| file       | Part（必填）  | 上传的文件，Part 名固定为 "file" |

**处理流程**

```
客户端 POST /ingestion/tasks/upload?pipelineId=xxx  (multipart)
  │
  ├─ 1. 一次性读取文件字节（file.getBytes()），不落磁盘
  │
  ├─ 2. 获取原始文件名，无文件名时降级为 "upload.bin"
  │
  ├─ 3. MimeTypeDetector.detect(bytes, fileName)
  │     通过魔数（magic bytes）检测 MIME 类型，优先于 Content-Type 头
  │
  ├─ 4. 构建 source（type=FILE，location/fileName=原始文件名）
  │
  ├─ 5. executeInternal()（rawBytes=文件字节，mimeType=检测结果）
  │     rawBytes 非 null → fetcher 节点直接使用内存字节，无需再拉取
  │
  └─ 6. 返回 IngestionResult
```

---

### 3. executeInternal() —— 核心执行方法

由 `execute()` 和 `upload()` 共同调用，两者区别仅在 rawBytes/mimeType 是否为 null。

```
executeInternal(pipelineId, source, rawBytes, mimeType, vectorSpaceId)
  │
  ├─ 1. resolvePipelineId()：校验 pipelineId 非空
  │
  ├─ 2. pipelineService.getDefinition()：加载流水线节点定义
  │
  ├─ 3. 创建 IngestionTaskDO（status=RUNNING，startedAt=now），insert
  │
  ├─ 4. 构建 IngestionContext（贯穿所有节点的共享上下文）
  │     - taskId / pipelineId / source / rawBytes / mimeType / vectorSpaceId
  │     - logs: 空列表，各节点执行后追加
  │
  ├─ 5. IngestionEngine.execute(pipeline, context)  ← 同步执行整条链
  │     返回执行完成的 context（含 status、chunks、logs、error）
  │
  ├─ 6. saveNodeLogs()：将 context.logs 逐条写入 t_ingestion_task_node
  │
  ├─ 7. updateTaskFromContext()：将最终状态写回 t_ingestion_task
  │     - status（RUNNING→COMPLETED 或 FAILED）
  │     - chunkCount
  │     - errorMessage
  │     - logsJson（去除 output 的日志摘要）
  │     - metadataJson（context.metadata + keywords + questions）
  │     - completedAt=now
  │
  └─ 8. 构建并返回 IngestionResult
```

---

### 4. 流水线引擎执行逻辑（IngestionEngine）

```
engine.execute(pipeline, context)
  │
  ├─ 1. 构建 nodeId→NodeConfig 映射
  │
  ├─ 2. validatePipeline()：检测有向图是否有环、nextNodeId 引用是否合法
  │
  ├─ 3. findStartNode()：
  │     收集所有 nextNodeId 引用集合，不在其中的节点即为起点
  │
  └─ 4. executeChain()：从起点开始链式执行
        对每个节点：
        ├─ condition 非 null → 评估条件，不满足则 skip（不终止链路）
        ├─ node.execute(context, nodeConfig) → NodeResult
        ├─ 失败（success=false）→ context.status=FAILED，终止链路
        ├─ shouldContinue=false → 主动终止链路
        └─ 成功 → currentNodeId = config.getNextNodeId()
```

**节点状态判断（resolveNodeStatus）**

| 条件                              | 节点状态   |
|----------------------------------|----------|
| log.success=false                | failed   |
| log.message 以 "Skipped:" 开头   | skipped  |
| 其他                             | success  |

---

### 5. 查询任务详情 `GET /ingestion/tasks/{id}`

```
客户端 GET /ingestion/tasks/{id}
  │
  ├─ selectById 查 t_ingestion_task，不存在则抛 ClientException
  │
  └─ toVO()：
       - logsJson（String）→ List<NodeLog>（反序列化，不含 output）
       - metadataJson（String）→ Map<String, Object>
       - sourceType/status 经枚举规范化后输出
```

**响应字段（IngestionTaskVO）**

| 字段             | 说明                               |
|---------------|-----------------------------------|
| id            | 任务 ID                            |
| pipelineId    | 所用流水线 ID                      |
| sourceType    | 来源类型（file/url/feishu/s3）     |
| sourceLocation | 来源位置（文件路径或 URL）          |
| sourceFileName | 原始文件名                        |
| status        | 任务状态（pending/running/completed/failed）|
| chunkCount    | 成功生成的 Chunk 数量              |
| errorMessage  | 错误信息（成功时为 null）           |
| logs          | 节点运行日志摘要列表（不含 output） |
| metadata      | 元数据（keywords、questions 等）   |
| startedAt     | 流水线开始执行时间                 |
| completedAt   | 流水线执行完成时间                 |

---

### 6. 查询任务节点运行记录 `GET /ingestion/tasks/{id}/nodes`

```
客户端 GET /ingestion/tasks/{id}/nodes
  │
  └─ 查 t_ingestion_task_node（deleted=0, taskId=id）
     ORDER BY node_order ASC, id ASC
     → 每条记录转为 IngestionTaskNodeVO 返回
```

**响应字段（IngestionTaskNodeVO）**

| 字段          | 说明                                      |
|-------------|------------------------------------------|
| nodeId      | 节点业务标识                              |
| nodeType    | 节点类型（fetcher/parser/chunker 等）     |
| nodeOrder   | 执行顺序编号（从 1 开始）                 |
| status      | success / failed / skipped              |
| durationMs  | 节点执行耗时（毫秒）                      |
| message     | 节点执行结果消息                          |
| errorMessage | 错误信息（失败时）                       |
| output      | 节点输出摘要（超 1MB 时已截断）           |

---

### 7. 分页查询任务列表 `GET /ingestion/tasks`

```
客户端 GET /ingestion/tasks?pageNo=1&pageSize=10&status=completed
  │
  ├─ 构建条件：deleted=0；status 非空时等值过滤；ORDER BY create_time DESC
  │
  ├─ taskMapper.selectPage() 执行分页查询
  │
  └─ 每条 DO 转 VO（logsJson/metadataJson 反序列化）
```

---

## 四、数据库结构

### t_ingestion_task（任务主表）

| 字段              | 类型     | 说明                                          |
|----------------|---------|---------------------------------------------|
| id             | bigint  | 雪花 ID                                      |
| pipeline_id    | bigint  | 所用流水线 ID                                 |
| source_type    | varchar | 来源类型（file/url/feishu/s3）               |
| source_location | varchar | 来源位置（文件路径或 URL）                    |
| source_file_name | varchar | 原始文件名                                  |
| status         | varchar | 任务状态（pending/running/completed/failed） |
| chunk_count    | int     | 生成的 Chunk 数量                            |
| error_message  | text    | 错误信息                                     |
| logs_json      | text    | 节点日志摘要 JSON（不含 output，JsonbTypeHandler）|
| metadata_json  | text    | 元数据 JSON（keywords/questions 等）          |
| started_at     | datetime | 流水线开始时间                               |
| completed_at   | datetime | 流水线完成时间                               |
| created_by     | varchar | 创建人                                       |
| create_time    | datetime | 记录创建时间（自动填充）                     |
| deleted        | tinyint | 逻辑删除：0=正常，1=已删除                   |

### t_ingestion_task_node（节点运行记录表）

| 字段           | 类型     | 说明                                      |
|-------------|---------|------------------------------------------|
| id          | bigint  | 雪花 ID                                   |
| task_id     | bigint  | 所属任务 ID                               |
| pipeline_id | bigint  | 所属流水线 ID                             |
| node_id     | varchar | 节点业务标识                              |
| node_type   | varchar | 节点类型                                  |
| node_order  | int     | 执行顺序编号（由 buildNodeOrderMap 推算）  |
| status      | varchar | success / failed / skipped              |
| duration_ms | bigint  | 耗时（毫秒）                              |
| message     | varchar | 执行结果消息                              |
| error_message | varchar | 错误信息                                |
| output_json | text    | 节点输出（超 1MB 截断）                   |
| create_time | datetime | 记录创建时间                             |
| deleted     | tinyint | 逻辑删除                                 |

---

## 五、关键设计说明

| 特性               | 说明                                                                     |
|------------------|------------------------------------------------------------------------|
| 同步执行           | 整条流水线在 HTTP 请求线程内同步跑完，不适合超大文件；超时需在网关层配置          |
| 内存处理（upload） | 上传文件字节保存在 JVM 堆内存，不落磁盘；大文件场景应改为流式处理或先存 S3        |
| MIME 自动检测      | 通过魔数（文件头字节）检测，比 Content-Type 更可靠，避免客户端伪造类型            |
| 节点日志分级存储    | 主表 logs_json 存摘要（无 output），节点表 output_json 存完整输出，查询分离      |
| output 截断保护    | output_json 超过 1MB 时截断并附说明，防止超出 MySQL max_allowed_packet 限制   |
| 节点顺序推算       | 根据 nextNodeId 链从流水线定义推算 nodeOrder，不依赖节点列表的插入顺序           |
| 状态流转           | 任务创建时写 RUNNING；引擎全部节点成功后转 COMPLETED；任意节点失败则转 FAILED    |

---

## 六、异常处理规则

| 异常类型          | 触发条件                                              |
|----------------|-----------------------------------------------------|
| ClientException | pipelineId 为空；文件为空；文档来源类型为空；流水线不存在；流水线存在环或无效 nextNodeId 引用；执行节点超过上限 |
