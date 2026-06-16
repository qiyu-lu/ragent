# 关键词映射管理接口流程分析（/mappings）

> 文件路径：
> - Controller：`bootstrap/.../rag/controller/QueryTermMappingController.java`
> - Service：`bootstrap/.../rag/service/impl/QueryTermMappingAdminServiceImpl.java`
> - 运行时缓存：`bootstrap/.../rag/core/rewrite/QueryTermMappingService.java`
> - 替换工具：`bootstrap/.../rag/core/rewrite/QueryTermMappingUtil.java`

---

## 一、接口总览

| HTTP 方法 | 路径             | 功能           |
|----------|-----------------|--------------|
| POST     | /mappings        | 创建映射规则   |
| PUT      | /mappings/{id}   | 更新映射规则   |
| DELETE   | /mappings/{id}   | 删除映射规则   |
| GET      | /mappings/{id}   | 查询规则详情   |
| GET      | /mappings        | 分页查询规则列表|

---

## 二、核心概念：查询术语归一化

关键词映射（Query Term Mapping）是 RAG 查询改写阶段的术语归一化机制：
用户的原始查询在进入向量检索之前，先由 `QueryTermMappingService.normalize()` 按优先级顺序将 **sourceTerm** 替换为标准化的 **targetTerm**，从而提升检索召回率。

```
用户输入 → QueryTermMappingService.normalize()
               │
               ├─ 遍历内存缓存中的映射规则（按优先级+长词优先排序）
               │   └─ 每条规则调用 QueryTermMappingUtil.applyMapping()
               │       └─ 安全子串替换：已是目标词时跳过，避免重复替换
               │
               └─ 归一化后的查询 → 向量检索
```

**每次增删改后立即调用 `queryTermMappingService.loadMappings()` 重建内存缓存，无需重启服务。**

---

## 三、各接口详细流程

### 1. 创建映射规则 `POST /mappings`

**请求参数（QueryTermMappingCreateRequest）**

| 字段        | 必填 | 默认值 | 说明                                      |
|-----------|------|-------|------------------------------------------|
| sourceTerm | 是   | -     | 用户原始短语（自动 trim，不能为纯空白）     |
| targetTerm | 是   | -     | 归一化目标短语（自动 trim，不能为纯空白）   |
| matchType  | 否   | 1     | 匹配类型（当前仅 1=精确匹配 有效）         |
| priority   | 否   | 0     | 优先级，数值越小越优先；长词建议设置更小值  |
| enabled    | 否   | true  | 是否生效                                  |
| remark     | 否   | null  | 备注                                      |

**处理流程**

```
客户端 POST /mappings
  │
  ├─ 1. sourceTerm/targetTerm trim，断言非 null/blank
  │
  ├─ 2. 构建 QueryTermMappingDO，设置默认值
  │     matchType=1（精确匹配），priority=0，enabled=1
  │
  ├─ 3. insert 写入 t_query_term_mapping
  │
  ├─ 4. queryTermMappingService.loadMappings() 重载内存缓存
  │     从数据库加载全部 enabled=1 的规则，按优先级+长词优先排序缓存
  │
  └─ 5. 返回新规则的 ID
```

---

### 2. 更新映射规则 `PUT /mappings/{id}`

**更新语义**：Patch——所有字段均可选，非 null 才写库。

| 字段        | 更新条件              | 额外校验                            |
|-----------|---------------------|------------------------------------|
| sourceTerm | 非 null             | trim 后不能为空（纯空白等同于 null） |
| targetTerm | 非 null             | trim 后不能为空                     |
| matchType  | 非 null             | 无额外校验                         |
| priority   | 非 null             | 无额外校验                         |
| enabled    | 非 null             | Boolean → Integer（true=1，false=0）|
| remark     | 非 null（包括 ""）   | trim 后为 "" 时存 null（清空备注）  |

**处理流程**

```
客户端 PUT /mappings/{id}
  │
  ├─ 1. loadById()：selectById 校验规则存在，不存在抛 ClientException
  │
  ├─ 2. 对非 null 字段逐一更新实体（Patch 语义）
  │
  ├─ 3. updateById 写库
  │
  ├─ 4. queryTermMappingService.loadMappings() 重载内存缓存
  │
  └─ 5. 返回空成功响应
```

---

### 3. 删除映射规则 `DELETE /mappings/{id}`

**注意**：t_query_term_mapping 表无逻辑删除字段，执行物理删除。

**处理流程**

```
客户端 DELETE /mappings/{id}
  │
  ├─ 1. loadById()：校验规则存在
  │
  ├─ 2. deleteById 物理删除数据库记录
  │
  ├─ 3. queryTermMappingService.loadMappings() 重载内存缓存，规则即刻失效
  │
  └─ 4. 返回空成功响应
```

---

### 4. 查询规则详情 `GET /mappings/{id}`

```
客户端 GET /mappings/{id}
  │
  ├─ loadById()：selectById 校验存在
  │
  └─ toVO()：DO → VO（enabled 字段：Integer 1/0 → Boolean true/false）
```

---

### 5. 分页查询规则列表 `GET /mappings`

**请求参数（QueryTermMappingPageRequest extends Page）**

| 参数      | 说明                                        |
|---------|---------------------------------------------|
| current | 当前页码（从 1 开始）                         |
| size    | 每页条数                                     |
| keyword | 关键词（可选），同时模糊匹配 sourceTerm 或 targetTerm |

**处理流程**

```
客户端 GET /mappings?current=1&size=10&keyword=xxx
  │
  ├─ keyword trim，为 null 时不加过滤条件
  │
  ├─ 构建 LambdaQueryWrapper：
  │   keyword 非空时：
  │     sourceTerm LIKE '%keyword%' OR targetTerm LIKE '%keyword%'
  │   ORDER BY priority ASC, update_time DESC
  │
  ├─ selectPage 执行分页查询
  │
  └─ result.convert(toVO) 逐条转换返回
```

---

## 四、运行时缓存机制（QueryTermMappingService）

| 属性              | 说明                                                       |
|-----------------|----------------------------------------------------------|
| 存储形式          | `volatile List<QueryTermMappingDO> cachedMappings`（JVM 内存）|
| 初始化时机         | Spring 启动时 `@PostConstruct` 触发                        |
| 刷新时机          | 每次增删改后手动调用 `loadMappings()`                       |
| 缓存内容          | 仅加载 `enabled=1` 的规则                                 |
| 缓存排序          | priority 高（数值小）在前；sourceTerm 更长的在前（防短词先匹配打断长词）|
| 并发安全          | `volatile` 保证可见性；整个列表一次性替换，无锁（Copy-on-Write）|

### loadMappings() 缓存排序逻辑

```java
dbList.sort(
    Comparator.comparing(priority, nullsLast).reversed()  // priority 大的（即数值小）在前 ← 注意 reversed
    .thenComparing(sourceTerm.length, reverseOrder)        // 更长的词在前
)
```

> **注意**：`reversed()` 作用于 priority 比较器，使 priority 数值小（即优先级高）的规则排在前面。

---

## 五、替换算法（QueryTermMappingUtil.applyMapping）

实现了**安全子串替换**，解决"已是目标词时不重复替换"的问题：

```
text = "保险公司提供保险服务"
sourceTerm = "保险公司"
targetTerm = "保险公司（PICC）"

扫描过程：
  idx=0 → 找到 "保险公司" at 0
    检查 text[0..15] 是否已经是 "保险公司（PICC）"？ → 否
    → 替换为 targetTerm，idx 跳到 0+4=4
  idx=4 → 继续扫描 "提供保险服务"
    → 未找到 "保险公司"，直接拷贝
结果："保险公司（PICC）提供保险服务"
```

**防重复替换示例**：若 text 中已包含 `targetTerm`，命中位置直接跳过 `targetLen`，不会再次替换。

---

## 六、数据库表：t_query_term_mapping

| 字段         | 类型     | 说明                                          |
|-----------|---------|---------------------------------------------|
| id        | bigint  | 雪花 ID（ASSIGN_ID）                         |
| domain    | varchar | 业务域标识（如 biz、group），可选              |
| source_term | varchar | 用户原始短语                                |
| target_term | varchar | 归一化目标短语                              |
| match_type | int    | 匹配类型：1=精确，2=前缀，3=正则，4=整词      |
| priority  | int     | 优先级，数值越小越优先；默认 0                |
| enabled   | int     | 是否生效：1=生效，0=禁用                     |
| remark    | varchar | 备注                                         |
| create_by | varchar | 创建人                                       |
| update_by | varchar | 修改人                                       |
| create_time | datetime | 创建时间                                  |
| update_time | datetime | 更新时间                                  |

> **无逻辑删除字段**：删除操作为物理删除。

---

## 七、异常处理规则

| 异常类型          | 触发条件                                                  |
|----------------|----------------------------------------------------------|
| ClientException | 请求为空；sourceTerm/targetTerm 为空或纯空白；规则 ID 不存在 |

---

## 八、与 RAG 检索的集成位置

QueryTermMappingService 被 RAG 查询改写（Rewrite）阶段调用，在用户问题进入向量检索前执行术语归一化，对应代码链路：

```
RAGChatController
  └─ QueryTermMappingService.normalize(userQuery)  ← 映射规则在此处生效
       └─ 归一化后的 query → 向量检索（RetrievalEngine）
```
