# 数据摄入流水线接口流程分析（/ingestion/pipelines）

> 文件路径：
> - Controller：`bootstrap/.../ingestion/controller/IngestionPipelineController.java`
> - Service：`bootstrap/.../ingestion/service/impl/IngestionPipelineServiceImpl.java`
> - 节点类型枚举：`bootstrap/.../ingestion/domain/enums/IngestionNodeType.java`

---

## 一、接口总览

| HTTP 方法 | 路径                         | 功能             |
|----------|------------------------------|----------------|
| POST     | /ingestion/pipelines         | 创建摄入流水线   |
| PUT      | /ingestion/pipelines/{id}    | 更新摄入流水线   |
| GET      | /ingestion/pipelines/{id}    | 查询流水线详情   |
| GET      | /ingestion/pipelines         | 分页查询流水线列表|
| DELETE   | /ingestion/pipelines/{id}    | 删除摄入流水线   |

---

## 二、核心概念：摄入流水线

摄入流水线（Ingestion Pipeline）定义了文档从原始文件到向量数据库的完整处理链路，由一组有序节点组成，节点间通过 `nextNodeId` 串联形成单向链表结构：

```
fetcher → parser → [enhancer] → chunker → [enricher] → indexer
  ↓          ↓          ↓           ↓          ↓           ↓
获取文档   解析文本   文档级AI增强  文档分块   块级AI增强   向量化入库
```

括号内节点为可选。流水线定义与具体文档上传任务解耦，一条流水线可被多个摄入任务复用。

---

## 三、节点类型（IngestionNodeType 枚举）

| 枚举值    | 存储值     | 说明                             |
|---------|-----------|----------------------------------|
| FETCHER  | fetcher   | 从数据源获取原始文档              |
| PARSER   | parser    | 将文档解析为纯文本                |
| ENHANCER | enhancer  | 对整篇文档进行 AI 增强（文档级）  |
| CHUNKER  | chunker   | 将文本切分为多个 Chunk            |
| ENRICHER | enricher  | 对每个 Chunk 进行 AI 增强（块级） |
| INDEXER  | indexer   | 将 Chunk 向量化并写入 Milvus      |

节点类型值在写入时经 `IngestionNodeType.fromValue()` 校验规范化，未知类型直接抛出 `ClientException`。

---

## 四、各接口详细流程

### 1. 创建流水线 `POST /ingestion/pipelines`

**请求参数（IngestionPipelineCreateRequest）**

| 字段        | 类型                          | 说明                    |
|-----------|-------------------------------|------------------------|
| name      | String                        | 流水线名称（全局唯一）   |
| description | String                      | 描述（可选）             |
| nodes     | List\<IngestionPipelineNodeRequest\> | 节点配置列表（可为空） |

**节点配置（IngestionPipelineNodeRequest）**

| 字段         | 类型     | 说明                                 |
|------------|---------|--------------------------------------|
| nodeId     | String  | 节点业务标识（流水线内唯一，如 "node-1"）|
| nodeType   | String  | 节点类型（枚举值，详见上方类型表）      |
| settings   | JsonNode | 节点配置参数（结构随 nodeType 不同）  |
| condition  | JsonNode | 执行条件（可为 null，表示无条件执行）  |
| nextNodeId | String  | 下一节点 nodeId（末尾节点为 null）    |

**处理流程**

```
客户端 POST /ingestion/pipelines
  │
  ├─ 1. 构建 IngestionPipelineDO，写入 t_ingestion_pipeline
  │     → DuplicateKeyException（名称重复）转为 ClientException
  │
  ├─ 2. upsertNodes()：逐条插入节点到 t_ingestion_pipeline_node
  │     - nodeType 经枚举校验规范化（未知类型抛 ClientException）
  │     - settings/condition（JsonNode）序列化为 JSON 字符串存储
  │
  ├─ 3. fetchNodes() 重新查库，保证返回数据与库中一致
  │
  └─ 4. 返回 IngestionPipelineVO（含节点列表）
```

**事务**：`@Transactional(rollbackFor = Exception.class)`，主记录与节点记录原子写入。

---

### 2. 更新流水线 `PUT /ingestion/pipelines/{id}`

**请求参数（IngestionPipelineUpdateRequest）**

| 字段        | 更新语义    | 说明                                    |
|-----------|-----------|------------------------------------------|
| name      | Patch     | 非空字符串才更新（空字符串不生效）         |
| description | Patch   | 非 null 才更新（传 "" 可清空描述）        |
| nodes     | 全量替换   | 非 null 时先删除旧节点再重新插入全部节点  |

**处理流程**

```
客户端 PUT /ingestion/pipelines/{id}
  │
  ├─ 1. selectById 校验流水线存在
  │     → 不存在抛 ClientException
  │
  ├─ 2. 更新基本信息（Patch 语义）
  │     name 非空才 set；description 非 null 才 set
  │     updateById 写库
  │
  ├─ 3. nodes 非 null 时调用 upsertNodes()
  │     先物理删除该流水线所有旧节点，再重新插入新节点列表
  │
  └─ 4. 返回更新后的 IngestionPipelineVO
```

**事务**：`@Transactional(rollbackFor = Exception.class)`。

---

### 3. 查询流水线详情 `GET /ingestion/pipelines/{id}`

**处理流程**

```
客户端 GET /ingestion/pipelines/{id}
  │
  ├─ 1. selectById 校验流水线存在
  │
  ├─ 2. fetchNodes() 查询该流水线下所有未删除节点
  │
  ├─ 3. toVO() 转换：
  │     - IngestionPipelineDO → IngestionPipelineVO（BeanUtil）
  │     - 每个节点的 settingsJson/conditionJson 反序列化为 JsonNode
  │     - nodeType 经枚举规范化后输出
  │
  └─ 4. 返回 IngestionPipelineVO（含节点列表）
```

---

### 4. 分页查询流水线列表 `GET /ingestion/pipelines`

**请求参数**

| 参数      | 默认值 | 说明                   |
|---------|-------|----------------------|
| pageNo  | 1     | 当前页码               |
| pageSize | 10   | 每页条数               |
| keyword | -     | 名称关键字（可选，模糊） |

**处理流程**

```
客户端 GET /ingestion/pipelines?pageNo=1&pageSize=10&keyword=xxx
  │
  ├─ 1. 构建查询条件：deleted=0，keyword 非空时 LIKE '%keyword%'
  │     ORDER BY update_time DESC
  │
  ├─ 2. pipelineMapper.selectPage() 执行分页查询
  │
  ├─ 3. 对当前页每条流水线，分别调用 fetchNodes() 查询其节点列表
  │     （注意：每条记录触发一次节点查询，存在 N+1 风险）
  │
  └─ 4. 组装 Page<IngestionPipelineVO> 返回
```

---

### 5. 删除流水线 `DELETE /ingestion/pipelines/{id}`

**处理流程**

```
客户端 DELETE /ingestion/pipelines/{id}
  │
  ├─ 1. selectById 校验流水线存在
  │
  ├─ 2. 流水线主记录逻辑删除（deleted=1，updateById）
  │
  ├─ 3. 节点记录物理删除
  │     DELETE FROM t_ingestion_pipeline_node WHERE pipeline_id = ?
  │     （节点无独立生命周期，随流水线清除）
  │
  └─ 4. 返回空成功响应
```

**事务**：`@Transactional(rollbackFor = Exception.class)`。  
**注意**：删除前不检查是否有摄入任务正在引用该流水线，调用方需自行保证。

---

## 五、核心私有方法说明

### upsertNodes()

节点列表的全量替换逻辑，被创建和更新接口共享：

```
upsertNodes(pipelineId, nodes)
  │
  ├─ nodes 为 null → 直接返回（不修改节点）
  │
  ├─ 物理删除 pipeline_id=pipelineId 的全部节点
  │
  └─ 逐条插入新节点：
       nodeType → IngestionNodeType.fromValue() 校验规范化
       settings/condition（JsonNode）→ .toString() 序列化为字符串存库
```

### toVO() / toNodeVO()

将数据库实体转换为前端 VO：
- `settingsJson` / `conditionJson`（String）→ `objectMapper.readTree()` → `JsonNode`
- `nodeType` → `normalizeNodeTypeForOutput()`（解析失败时原样返回，不抛异常）

---

## 六、数据库结构

### t_ingestion_pipeline（流水线主表）

| 字段        | 类型     | 说明                                   |
|-----------|---------|----------------------------------------|
| id        | bigint  | 雪花 ID（ASSIGN_ID）                   |
| name      | varchar | 流水线名称（唯一索引，重复时抛 DuplicateKeyException）|
| description | text  | 描述                                   |
| created_by | varchar | 创建人                                 |
| updated_by | varchar | 修改人                                 |
| create_time | datetime | 创建时间（自动填充）                  |
| update_time | datetime | 更新时间（自动填充）                  |
| deleted   | tinyint | 逻辑删除：0=正常，1=已删除             |

### t_ingestion_pipeline_node（节点配置表）

| 字段          | 类型     | 说明                                   |
|-------------|---------|----------------------------------------|
| id          | bigint  | 雪花 ID                                |
| pipeline_id | bigint  | 所属流水线 ID                          |
| node_id     | varchar | 节点业务标识（流水线内自定义）          |
| node_type   | varchar | 节点类型（规范化后的枚举值）            |
| next_node_id | varchar | 下一节点 node_id，定义执行顺序         |
| settings_json | text  | 节点配置参数（JSON 字符串，JsonbTypeHandler）|
| condition_json | text | 执行条件（JSON 字符串，JsonbTypeHandler）|
| created_by  | varchar | 创建人                                 |
| updated_by  | varchar | 修改人                                 |
| create_time | datetime | 创建时间                              |
| update_time | datetime | 更新时间                              |
| deleted     | tinyint | 逻辑删除（物理删除时不使用此字段）      |

---

## 七、异常处理规则

| 异常类型          | 触发条件                                      |
|----------------|---------------------------------------------|
| ClientException | 请求为空；流水线不存在；流水线名称重复；未知 nodeType |
| DuplicateKeyException | 数据库唯一索引冲突（已在 create 内部转为 ClientException）|

---

## 八、设计要点

| 特性            | 说明                                                      |
|---------------|----------------------------------------------------------|
| 名称唯一        | 依赖数据库唯一索引保证，不做应用层预查（避免并发时 TOCTOU）  |
| 节点全量替换    | 更新时先删后插，简化节点顺序调整和类型变更的处理逻辑         |
| JSON 透传      | settings/condition 由前端定义结构，后端以 JsonNode 透明存取 |
| 删除策略差异    | 流水线主记录逻辑删除；节点记录物理删除（无独立业务价值）      |
