# 知识库管理接口流程分析（/knowledge-base）

> 文件路径：`bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/`

---

## 一、接口总览

| HTTP 方法 | 路径                              | 功能         |
|----------|----------------------------------|------------|
| POST     | /knowledge-base                  | 创建知识库   |
| PUT      | /knowledge-base/{kb-id}          | 重命名知识库 |
| DELETE   | /knowledge-base/{kb-id}          | 删除知识库   |
| GET      | /knowledge-base/{kb-id}          | 查询知识库详情|
| GET      | /knowledge-base                  | 分页查询列表 |
| GET      | /knowledge-base/chunk-strategies | 查询分块策略 |

---

## 二、各接口详细流程

### 1. 创建知识库 `POST /knowledge-base`

**请求参数（KnowledgeBaseCreateRequest）**

| 字段            | 类型   | 说明                          |
|---------------|--------|------------------------------|
| name          | String | 知识库名称                    |
| embeddingModel | String | 嵌入模型标识，如 `qwen3-embedding:8b-fp16` |
| collectionName | String | Milvus Collection 名称        |

**处理流程**

```
客户端 POST /knowledge-base
  │
  ├─ 1. 名称唯一性校验
  │     去掉首尾及中间空白后，查 t_knowledge_base 表是否已存在同名且未删除记录
  │     → 重复则抛出 ServiceException
  │
  ├─ 2. 持久化知识库元数据
  │     构建 KnowledgeBaseDO，写入 t_knowledge_base（MyBatis-Plus ASSIGN_ID 雪花ID）
  │
  ├─ 3. 创建 S3/RestFS 对象存储桶
  │     以 collectionName 为 bucket 名，调用 S3Client.createBucket
  │     → BucketAlreadyExistsException 或 BucketAlreadyOwnedByYouException 时抛出 ServiceException
  │
  ├─ 4. 初始化 Milvus 向量空间
  │     封装 VectorSpaceSpec（logicalName=collectionName），
  │     调用 VectorStoreAdmin.ensureVectorSpace（幂等：不存在则创建）
  │
  └─ 5. 返回新知识库 ID（String 类型雪花 ID）
```

**事务**：`@Transactional`，MySQL 写入与后续存储初始化捆绑在同一事务上下文（S3/Milvus 异常会触发回滚）。

---

### 2. 重命名知识库 `PUT /knowledge-base/{kb-id}`

**请求参数（KnowledgeBaseUpdateRequest）**

| 字段  | 类型   | 说明       |
|------|--------|----------|
| name | String | 新的知识库名称 |

**处理流程**

```
客户端 PUT /knowledge-base/{kb-id}
  │
  ├─ 1. 校验知识库存在（selectById + deleted=0 判断）
  │     → 不存在则抛出 ClientException
  │
  ├─ 2. 校验新名称非空
  │     → 空则抛出 ClientException
  │
  ├─ 3. 名称唯一性校验（排除当前知识库自身）
  │     去掉空白后，查同名且 id != kbId 且未删除记录
  │     → 重复则抛出 ServiceException
  │
  ├─ 4. 更新名称和 updatedBy，执行 updateById
  │
  └─ 5. 返回空成功响应
```

---

### 3. 删除知识库 `DELETE /knowledge-base/{kb-id}`

**处理流程**

```
客户端 DELETE /knowledge-base/{kb-id}
  │
  ├─ 1. 校验知识库存在
  │     → 不存在则抛出 ClientException
  │
  ├─ 2. 前置保护：检查知识库下是否还有未删除文档
  │     查 t_knowledge_document 中 kb_id=kbId 且 deleted=0 的记录数
  │     → 文档数 > 0 则拒绝删除，提示用户先清理文档
  │
  ├─ 3. 执行逻辑删除
  │     设置 deleted=1、updatedBy，调用 deleteById（MyBatis-Plus @TableLogic）
  │
  └─ 4. 返回空成功响应
```

**事务**：`@Transactional(rollbackFor = Exception.class)`。  
**注意**：仅做逻辑删除，不清理 S3 存储桶和 Milvus Collection（由上层调用方或运维手动处理）。

---

### 4. 查询知识库详情 `GET /knowledge-base/{kb-id}`

**处理流程**

```
客户端 GET /knowledge-base/{kb-id}
  │
  ├─ 1. selectById 查询，过滤逻辑删除记录
  │     → 不存在则抛出 ClientException
  │
  └─ 2. BeanUtil 映射 KnowledgeBaseDO → KnowledgeBaseVO，返回
```

**响应字段（KnowledgeBaseVO）**

| 字段           | 类型   | 说明               |
|--------------|--------|------------------|
| id           | String | 知识库 ID          |
| name         | String | 知识库名称          |
| embeddingModel | String | 嵌入模型标识       |
| collectionName | String | Milvus Collection |
| documentCount | Long  | 文档数量（详情接口固定为 null，仅分页接口填充）|
| createdBy    | String | 创建人             |
| createTime   | Date   | 创建时间           |
| updateTime   | Date   | 更新时间           |

---

### 5. 分页查询知识库列表 `GET /knowledge-base`

**请求参数（KnowledgeBasePageRequest extends Page）**

| 字段    | 类型   | 说明               |
|--------|--------|--------------------|
| current | long  | 当前页（从 1 开始）  |
| size    | long  | 每页条数            |
| name    | String | 名称关键字（模糊，可选）|

**处理流程**

```
客户端 GET /knowledge-base?current=1&size=10&name=xxx
  │
  ├─ 1. 构建 LambdaQueryWrapper
  │     条件：deleted=0；name 非空时 LIKE '%name%'；ORDER BY update_time DESC
  │
  ├─ 2. MyBatis-Plus selectPage 执行分页查询，返回 IPage<KnowledgeBaseDO>
  │
  ├─ 3. 批量统计文档数量（避免 N+1）
  │     提取当前页所有 kbId，一次 GROUP BY 查询 t_knowledge_document：
  │     SELECT kb_id, COUNT(1) FROM t_knowledge_document
  │     WHERE kb_id IN (...) AND deleted=0 GROUP BY kb_id
  │     → 结果转换为 Map<kbId, docCount>
  │
  └─ 4. IPage.convert 将每条 DO 转为 VO，从 docCountMap 填充 documentCount（无则默认 0）
```

---

### 6. 查询分块策略列表 `GET /knowledge-base/chunk-strategies`

**处理流程**

```
客户端 GET /knowledge-base/chunk-strategies
  │
  ├─ 遍历 ChunkingMode 枚举，过滤 visible=true 的策略
  │
  └─ 每条策略映射为 ChunkStrategyVO（value, label, defaultConfig）返回
```

**当前支持的策略（ChunkingMode 枚举）**

| value           | label               | 默认配置                                                |
|----------------|---------------------|---------------------------------------------------------|
| fixed_size     | 固定大小            | chunkSize=512, overlapSize=128                          |
| structure_aware | 语义感知（Markdown友好）| targetChars=1400, overlapChars=0, maxChars=1800, minChars=600 |

---

## 三、核心数据结构

### 数据库表：`t_knowledge_base`

| 字段           | 类型    | 说明                         |
|--------------|---------|------------------------------|
| id           | bigint  | 雪花 ID（MyBatis-Plus ASSIGN_ID）|
| name         | varchar | 知识库名称（唯一）            |
| embedding_model | varchar | 嵌入模型（有向量文档后禁改）  |
| collection_name | varchar | Milvus Collection（创建后禁改）|
| created_by   | varchar | 创建人（取自 UserContext）    |
| updated_by   | varchar | 修改人                       |
| create_time  | datetime | 创建时间（自动填充）         |
| update_time  | datetime | 更新时间（自动填充）         |
| deleted      | tinyint | 逻辑删除标志：0=正常，1=已删除 |

---

## 四、关键依赖说明

| 依赖组件       | 角色               | 涉及接口         |
|-------------|------------------|----------------|
| MySQL（MyBatis-Plus） | 知识库元数据持久化，逻辑删除 | 全部接口        |
| S3/RestFS   | 对象存储，保存原始文档文件  | 创建知识库       |
| Milvus（VectorStoreAdmin） | 向量数据库，存储文档分块向量 | 创建知识库       |
| UserContext | 从请求上下文中获取当前用户名 | 创建、重命名、删除 |

---

## 五、异常处理规则

| 异常类型          | 触发条件                         |
|----------------|--------------------------------|
| ServiceException | 知识库名称重复；S3 存储桶被占用  |
| ClientException  | 知识库不存在；知识库下有文档时删除；名称为空；已有向量文档时修改嵌入模型 |
