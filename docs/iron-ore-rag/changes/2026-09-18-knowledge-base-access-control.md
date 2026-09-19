# 企业资料的权限隔离：知识库可见性、召回前过滤与越权矩阵

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | `2026-09-18` |
| 所属阶段 | 秋招冲刺 W4：企业资料的权限隔离 |
| 状态 | 已实施；回归通过；越权矩阵 299 项与召回前过滤的 PostgreSQL 集成测试通过 |
| 分支 | `feat/llm-backend-hardening` |
| Git 提交 | 可见性与授权 `cd20e6b`（含迁移）；检索范围 `71c4389`；研究范围 `35552f5`；停止归属 `785a7de`；运维接口 `412ec2b`；测试 `a21beb5`；标签 `career-w4` |

## 改动目的与发现过程

企业知识库的第一条安全需求是不同的人看到不同的资料，出错就是数据泄露。只读核对（计划 E4）确认当时知识库全局共享：

- 知识库、文档、分块的 Controller 都没有权限校验，登录用户可以按 ID 读、改、删任何库；`t_knowledge_base` 只有审计用的 `created_by`；
- 问答检索的“全局范围”就是全部有效库；研究入口的注释写明“当前知识库是全局共享”；
- `POST /rag/v3/stop?taskId=` 不校验任务归属，任何人拿到任务 ID 就能停掉别人的流。

实施中顺着调用链又发现四处：

- 空范围被当成“不限”：ES 关键词检索在库列表为空时不加 `collection_name` 过滤，直接检索整个共享索引。以前没有调用方传空，但权限裁剪之后，空范围就成了正常情况；
- 图谱通道只能在结果侧过滤，定向检索时把所有“未命中库”的结果都放进补充路，其中包括别的库和无法归属的结果；
- XLSX 版本比较按 `document_key + version` 在全库里找文档，其他库里有同名文档时会报“存在多份记录”，泄露了文档是否存在；
- 前端只在路由上对非管理员隐藏 `/admin` 页面，后端只校验登录：链路追踪里有其他用户的问题和召回片段，评测与图谱接口绕过知识库直接读内容，摄取任务可以写入任意库。

## 方案取舍与选择理由

- **模型**：参考 Dify 的知识库可见性（仅自己 / 全员 / 指定成员）和 Azure AI Search 的 security trimming。可见性分 `PUBLIC / PRIVATE / RESTRICTED`，授权表 `t_knowledge_base_grant(kb_id, subject_type USER|ROLE, subject_id, permission READ|MANAGE)`，另加 `owner_user_id`。PUBLIC 对全体登录用户开放 READ；授权在 PUBLIC 与 RESTRICTED 上生效，在 PRIVATE 上忽略（“仅自己”不应被一条遗留授权打破）。MANAGE 蕴含 READ，admin 拥有全部权限。
- **唯一判定入口**：`KnowledgeAccessService` 用一条 SQL 算出可访问集合；Controller、检索、研究、版本比较都调用它，不各写一套规则。身份缺失（没有登录用户、接管线程找不到用户、用户已删除）时没有任何权限，不会退回全局可见。
- **不缓存结论**：每次请求现算，靠授权表的索引撑住开销；撤销授权后下一次请求立即生效，没有缓存过期窗口。
- **过滤放在召回之前**：检索范围解析时先把有效库与可读库求交，定向、全局、补充范围都从交集里取。如果放在召回之后，TopK 名额会先被不可读库的高分块占掉，过滤后结果变少甚至为空，而且中间结果已经进过内存和日志。
- **提示不泄露存在性**：不可读与不存在返回同一条提示，只有“能读、不能管”时才提示“无权管理”。
- **存量兼容**：存量库迁移为 PUBLIC，读取行为不变；按 `created_by` 用户名回填所有者。数据库默认值是 PRIVATE，任何绕过应用层直接插入的行默认不可见，出错时偏向拒绝。

## 实现与调用链

```text
请求 → Sa-Token 登录校验 →（/admin、/rag/traces、/rag/eval、/ingestion 另需 admin 角色）
  → 知识库 / 文档 / 分块 Controller：requireKb / requireDocument(READ|MANAGE) → 业务服务
  → 列表与文档搜索：accessibleKbIds(READ) 作为 SQL 条件
问答：RetrievalScopeResolver.resolve
  有效库 ∩ readableCollections(当前用户)（意图树已于 2026-09-19 瘦身 B10 移除，检索范围恒为该交集）
  → 向量（SQL WHERE collection_name IN …）
研究：创建时每个库都要 READ；每次领取（含接管、恢复）按 owner 重新算权限，不满足则 FAILED(KB_ACCESS_REVOKED)，不调用模型
停止：流开始时把 taskId → userId 写入 Redis（停止请求可能落到任一实例），stop 只允许所有者或 admin
```

迁移 `resources/database/upgrades/v1.1.0/260918_02_knowledge_base_access.sql`，同步修改 `schema_pg.sql`；新增接口 `PUT /knowledge-base/{id}/visibility`、`GET|POST /knowledge-base/{id}/grants`、`DELETE /knowledge-base/{id}/grants/{grant-id}`。前端表单未改（计划允许砍掉）。

## 泄露通道清点

| 通道 | 结论 |
| --- | --- |
| Redis | 只有提示词、术语映射两份全局配置缓存，以及停止标记和任务归属，没有检索结果或答案缓存（意图树缓存已随意图树移除） |
| 会话记忆与历史消息 | 按会话所有者读取；已生成的回答和来源快照在撤销授权后仍对提问者可见（与已下载的副本同理），未做回收 |
| SSE 事件 | 只发给发起请求的连接；研究事件流按 owner 读取 |
| 链路追踪、评测、图谱、摄取 | 改为只允许 admin（`412ec2b`） |
| 研究证据与产物 | 按 run 所有者隔离（W2 之前已有）；已保存的证据快照在撤销授权后仍对所有者可见，新的检索和成文会在下次领取时被拒 |
| W5 的 embedding 缓存 | 计划只按内容哈希寻址，不带库信息，也不返回任何访问结论，命中与否不能用来判断某库里有没有某段内容 |
| Agent 配置、示例问题等写接口 | 仍只校验登录。这些是全局配置，不含资料正文。未收紧（意图树已移除） |

## 验证与效果

- 回归 `bash scripts/validate-agentic-research-p7.sh`：Python 41/41，Java 13 + 247（W2 后 13 + 227），两条新集成测试已加入回归列表。结构校验 `validate-agentic-research-p2-database.sh`：新建库与升级库（迁移连跑两次）的表结构一致；存量行变为 PUBLIC 并回填所有者，迁移后新插入的行为 PRIVATE。
- **越权矩阵**（`KnowledgeAccessMatrixPostgresIT`，真实 PostgreSQL + 真实判定，业务服务用 mock）：所有者 A、用户 B、admin × PUBLIC / PRIVATE / RESTRICTED × 23 个按 ID 操作的接口（知识库 7、文档 10、分块 6）；再加三个阶段：B 在 RESTRICTED 上获得 READ、在 PRIVATE 上获得 MANAGE（应被忽略），按角色授予 MANAGE，撤销。**共 299 项全部符合预期，其中 173 项允许、126 项拒绝**。判定为“拒绝”必须同时满足两条：抛出 `ClientException`，且业务服务没有被调用。另外覆盖：列表与文档搜索的范围（6 项），身份缺失、用户删除、库删除（4 项），停止归属（4 项）。
- **研究**（`ResearchRunPostgresIT`）：创建时指定不可读的库会被拒，提示与库不存在时相同；该库改为 PUBLIC 后可以创建。排队中的任务在所有者失去权限后，下次领取时 FAILED(KB_ACCESS_REVOKED)，模型调用 0 次。
- **召回前过滤的证明**（`KnowledgeRetrievalIsolationPostgresIT`，真实 pgvector）：查询向量 e0；私有库的块与查询完全相同（相似度 1.0），公开库的块相似度 0.6。所有者 TopK=1 命中私有库；其他用户 TopK=1 得到公开库的块（0.6）。如果过滤发生在召回之后，唯一的名额会被私有库的块占掉，过滤后结果为空。
- 单元测试：范围解析 +4（全局只含可读库、意图指向不可读库时回退、补充范围只含可读库、无可读库时为空范围），图谱补充路 +1，ES 空范围 +1，版本比较 +1。

## 限制与停止状态

- 图谱（LightRAG 单实例单图）只能在结果侧过滤，不可读库的结果会占用图谱自身的 top_k。已保证这些结果不进入结果，但不是召回前过滤；真正的隔离需要按库分实例。
- 研究运行中途被撤销授权时，当前这次执行要到下一次领取才会被拒，最长为一次运行时限。已保存的证据和产物不回收。
- 管理员能读所有库，但不能读其他用户的研究运行（运行按所有者隔离，这是既有设计）。
- 授权变更没有写进业务变更日志（`t_biz_change_log`）。前端没有可见性和授权界面。
- 越权矩阵在服务端 Java 层驱动 Controller 方法，不经过 HTTP 与 Sa-Token 拦截器；admin 路由的拦截只做了编译与规则审阅，没有端到端的 HTTP 测试。
- `bootstrap` 全量单测中有 11 个类报错：10 个是依赖完整环境的 `@SpringBootTest` 上下文加载失败（测试夹具 `ResearchBrowserFixture` 的 Controller 构造时要连数据库，这在 W4 之前就是如此），1 个是 Mockito 严格桩报错。在 `b1dd5b9` 的临时工作树上抽查了其中 3 个类，同样失败，与本改动无关。

## 数据兼容与回滚

迁移只做加法，可以重复执行。本地已有的库在运行新代码前要手动执行一次 `260918_02`，否则判定 SQL 会因缺少列报错：开发库必须执行；`research_corpus_stub` 在重跑 X2 或接管演示前执行；`research_corpus_v1` 上的 X1 评测命令不经过判定，可以不执行。执行后存量库为 PUBLIC，行为不变。

整体回滚：

```bash
git revert a21beb5 412ec2b 785a7de 35552f5 71c4389 cd20e6b
```

执行前先检查当前分支、HEAD 和工作区。数据库列和授权表可以保留，旧代码不会读取它们。只想恢复全员可见，可以执行 `UPDATE t_knowledge_base SET visibility = 'PUBLIC'`，代码不用回滚。
