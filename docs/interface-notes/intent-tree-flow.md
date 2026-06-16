# 意图树管理接口流程分析（/intent-tree）

> 文件路径：
> - Controller：`bootstrap/.../rag/controller/IntentTreeController.java`
> - Service：`bootstrap/.../ingestion/service/impl/IntentTreeServiceImpl.java`
> - 缓存：`bootstrap/.../rag/core/intent/IntentTreeCacheManager.java`

---

## 一、接口总览

| HTTP 方法 | 路径                           | 功能           |
|----------|-------------------------------|--------------|
| GET      | /intent-tree/trees            | 查询完整意图树  |
| POST     | /intent-tree                  | 创建意图节点   |
| PUT      | /intent-tree/{id}             | 更新意图节点   |
| DELETE   | /intent-tree/{id}             | 删除意图节点   |
| POST     | /intent-tree/batch/enable     | 批量启用节点   |
| POST     | /intent-tree/batch/disable    | 批量停用节点   |
| POST     | /intent-tree/batch/delete     | 批量删除节点   |

---

## 二、意图树结构说明

意图树是 RAG 检索路由的核心配置，采用三层结构：

```
DOMAIN（领域，level=0）
  └─ CATEGORY（分类，level=1）
       └─ TOPIC（主题，level=2）← RAG/MCP 检索实际发生在此层
```

每个节点的 `kind` 字段决定路由类型：

| kind 值 | 枚举      | 说明                                  |
|--------|-----------|--------------------------------------|
| 0      | KB        | 走 RAG，从绑定的 Milvus Collection 检索 |
| 1      | SYSTEM    | 系统回复（欢迎语、自我介绍等）          |
| 2      | MCP       | 调用 MCP 工具获取实时数据               |

---

## 三、各接口详细流程

### 1. 查询完整意图树 `GET /intent-tree/trees`

**处理流程**

```
客户端 GET /intent-tree/trees
  │
  ├─ 1. 一次性从 t_intent_node 加载全部未删除节点
  │     ORDER BY sort_order ASC, id ASC
  │
  ├─ 2. 按 parentCode 分组构建 Map<parentCode, List<子节点>>
  │     parentCode 为 null 的节点归入虚拟 "ROOT" 分组
  │
  ├─ 3. 取 "ROOT" 分组作为根节点列表
  │
  ├─ 4. 对每个根节点执行递归 buildTree()，深度优先组装子树
  │
  └─ 5. 返回 List<IntentNodeTreeVO>（每个根节点携带完整 children 链）
```

**响应结构（IntentNodeTreeVO）**

| 字段              | 说明                              |
|-----------------|----------------------------------|
| id              | 节点主键 ID                       |
| intentCode      | 业务唯一标识（如 group-hr）        |
| name            | 节点展示名称                      |
| level           | 层级：0=DOMAIN，1=CATEGORY，2=TOPIC |
| parentCode      | 父节点 intentCode，根节点为 null   |
| kind            | 类型：0=KB，1=SYSTEM，2=MCP       |
| collectionName  | Milvus Collection（kind=0 有效）   |
| mcpToolId       | MCP 工具 ID（kind=2 有效）         |
| topK            | 节点级 TopK（null 时使用全局默认）  |
| enabled         | 是否启用：1=是，0=否              |
| examples        | 示例问题（JSON 数组字符串）        |
| children        | 子节点列表（递归结构）             |

---

### 2. 创建意图节点 `POST /intent-tree`

**请求参数（IntentNodeCreateRequest）**

| 字段               | 必填 | 说明                                       |
|------------------|------|--------------------------------------------|
| intentCode       | 是   | 全局唯一业务标识                            |
| name             | 是   | 展示名称                                   |
| level            | 是   | 层级：0/1/2                                |
| kind             | 否   | 类型，默认 0（KB）                          |
| kbId             | 条件 | TOPIC 且 kind=0 时必填                     |
| parentCode       | 否   | 父节点 intentCode，根节点不填               |
| description      | 否   | 描述，辅助意图分类的语义理解               |
| examples         | 否   | 示例问题列表，List<String>                 |
| topK             | 否   | 节点级 TopK，null 使用全局默认，必须为正整数 |
| sortOrder        | 否   | 排序权重，默认 0                            |
| enabled          | 否   | 是否启用，默认 1                            |
| promptSnippet    | 否   | 短规则片段，注入 Prompt 头部               |
| promptTemplate   | 否   | 完整 Prompt 模板                           |
| paramPromptTemplate | 否 | 参数提取模板（MCP 专属）                  |

**处理流程**

```
客户端 POST /intent-tree
  │
  ├─ 1. intentCode 唯一性校验（查未删除节点）
  │     → 重复则抛出 ClientException
  │
  ├─ 2. 业务规则校验
  │     TOPIC 级别 + kind=0(KB) 且 kbId 为空 → 抛出 ClientException
  │
  ├─ 3. kbId 非空时，查知识库表获取 collectionName，存入节点
  │     （避免检索时再跨表查询）
  │
  ├─ 4. 构建 IntentNodeDO，examples 序列化为 JSON 字符串
  │     默认值：kind=0，sortOrder=0，enabled=1
  │
  ├─ 5. MyBatis-Plus save() 写入 t_intent_node
  │
  ├─ 6. 清除 Redis 意图树缓存（Key: ragent:intent:tree）
  │
  └─ 7. 返回新节点 ID
```

---

### 3. 更新意图节点 `PUT /intent-tree/{id}`

**特点**：Patch 语义——请求体中非 null 的字段才更新，null 字段保持原值不变。

**处理流程**

```
客户端 PUT /intent-tree/{id}
  │
  ├─ 1. getById 加载节点，校验存在且未删除
  │     → 不存在则抛出 ServiceException
  │
  ├─ 2. 逐字段判断是否非 null，非 null 才 set 到实体
  │     examples 为 List 时，序列化为 JSON 字符串再写入
  │
  ├─ 3. updateById 更新到数据库
  │
  ├─ 4. 清除 Redis 意图树缓存
  │
  └─ 5. 无返回值（HTTP 200 OK）
```

---

### 4. 删除意图节点 `DELETE /intent-tree/{id}`

**处理流程**

```
客户端 DELETE /intent-tree/{id}
  │
  ├─ 1. MyBatis-Plus removeById()（触发 @TableLogic，逻辑删除）
  │
  ├─ 2. 清除 Redis 意图树缓存
  │
  └─ 3. 无返回值（HTTP 200 OK）
```

**注意**：单节点删除不检查子节点，建议通过 `批量删除` 接口删除包含子树的节点。

---

### 5. 批量启用节点 `POST /intent-tree/batch/enable`

**处理流程**

```
客户端 POST /intent-tree/batch/enable  { "ids": ["id1", "id2"] }
  │
  ├─ 1. listAndValidateTargetNodes：去重、校验所有 ID 存在且未删除
  │
  ├─ 2. 将目标节点 enabled 设为 1，updateBatchById 批量更新
  │
  ├─ 3. 清除 Redis 意图树缓存
  │
  └─ 4. 无返回值
```

不检查子节点状态，支持单独启用某个节点（子节点状态不受影响）。

---

### 6. 批量停用节点 `POST /intent-tree/batch/disable`

**核心保护**：父节点停用但子节点仍启用会导致意图路由判断异常，因此必须保证子孙节点一起停用。

**处理流程**

```
客户端 POST /intent-tree/batch/disable  { "ids": ["id1", "id2"] }
  │
  ├─ 1. listAndValidateTargetNodes：校验节点存在
  │
  ├─ 2. 一次性加载全部活跃节点，构建 childrenMap（parentCode → 子节点列表）
  │
  ├─ 3. 对每个目标节点，通过迭代栈收集其全部子孙节点
  │
  ├─ 4. 检查是否存在"已启用 且 未在本次操作 IDs 中"的子孙节点
  │     → 存在则抛出 ClientException，提示用户选择完整子树
  │
  ├─ 5. 校验通过：批量设 enabled=0，updateBatchById
  │
  ├─ 6. 清除 Redis 意图树缓存
  │
  └─ 7. 无返回值
```

---

### 7. 批量删除节点 `POST /intent-tree/batch/delete`

**核心保护**：禁止产生孤儿节点（parentCode 指向已删除节点的节点），删除父节点时必须连同子孙一起删除。

**处理流程**

```
客户端 POST /intent-tree/batch/delete  { "ids": ["id1", "id2"] }
  │
  ├─ 1. listAndValidateTargetNodes：校验节点存在
  │
  ├─ 2. 一次性加载全部活跃节点，构建 childrenMap
  │
  ├─ 3. 对每个目标节点，收集其全部子孙节点
  │
  ├─ 4. 检查是否存在"未包含在本次操作中"的子孙节点
  │     ├─ 若该子孙节点 enabled=1 → 抛出 ClientException（已启用子节点更醒目的提示）
  │     └─ 否则 → 抛出 ClientException（未勾选完整子树）
  │
  ├─ 5. 校验通过：removeByIds 批量逻辑删除
  │
  ├─ 6. 清除 Redis 意图树缓存
  │
  └─ 7. 无返回值
```

**事务**：`@Transactional(rollbackFor = Exception.class)`，批量删除是原子操作。

---

## 四、缓存机制

| 组件                    | 说明                              |
|------------------------|----------------------------------|
| 缓存介质                | Redis（StringRedisTemplate）      |
| 缓存 Key               | `ragent:intent:tree`             |
| 缓存有效期              | 7 天                              |
| 写入时机                | 意图树首次加载（由 IntentResolver 等调用方触发） |
| 清除时机                | 任意节点发生创建、更新、删除后立即清除 |
| 反序列化                | Jackson ObjectMapper，TypeReference<List<IntentNode>> |

所有写操作（create / update / delete / batchXxx）均在业务逻辑完成后调用
`intentTreeCacheManager.clearIntentTreeCache()`，下次读取时会从数据库重建缓存。

---

## 五、核心数据结构

### 数据库表：`t_intent_node`

| 字段                 | 类型     | 说明                               |
|--------------------|---------|------------------------------------|
| id                 | bigint  | 雪花 ID（ASSIGN_ID）                |
| kb_id              | varchar | 知识库 ID（kind=0 时关联 t_knowledge_base）|
| intent_code        | varchar | 业务唯一标识（全局不重复）           |
| name               | varchar | 节点展示名称                        |
| level              | int     | 层级：0=DOMAIN，1=CATEGORY，2=TOPIC |
| parent_code        | varchar | 父节点 intent_code，根节点为 null   |
| description        | text    | 节点描述                            |
| examples           | text    | 示例问题（JSON 数组字符串）          |
| collection_name    | varchar | Milvus Collection（冗余存储）       |
| mcp_tool_id        | varchar | MCP 工具 ID（kind=2 时有效）         |
| top_k              | int     | 节点级 TopK，null 使用全局默认       |
| kind               | int     | 类型：0=KB，1=SYSTEM，2=MCP         |
| sort_order         | int     | 同层排序权重                        |
| prompt_snippet     | text    | 短规则片段                          |
| prompt_template    | text    | 完整 Prompt 模板                    |
| param_prompt_template | text | 参数提取模板（MCP 专属）             |
| enabled            | int     | 是否启用：1=是，0=否                |
| create_by          | varchar | 创建人                              |
| update_by          | varchar | 修改人                              |
| create_time        | datetime | 创建时间（自动填充）                |
| update_time        | datetime | 更新时间（自动填充）                |
| deleted            | tinyint | 逻辑删除：0=正常，1=已删除           |

---

## 六、批量操作子节点保护规则汇总

| 操作       | 是否检查子节点 | 检查内容                                      | 不通过时行为 |
|----------|------------|---------------------------------------------|---------|
| 单节点删除 | 否          | -                                           | 直接删除  |
| 批量启用   | 否          | -                                           | 直接启用  |
| 批量停用   | 是          | 子孙节点中是否存在 enabled=1 且未在操作列表中的节点 | 拒绝操作 |
| 批量删除   | 是          | 子孙节点是否有任意节点未在操作列表中                 | 拒绝操作 |

---

## 七、异常处理规则

| 异常类型          | 触发条件                                              |
|----------------|-----------------------------------------------------|
| ClientException | intentCode 重复；TOPIC/KB 节点未指定知识库；节点不存在；拒绝批量操作（子节点保护）；topK <= 0；ids 为空 |
| ServiceException | 更新时节点不存在或已删除                              |
