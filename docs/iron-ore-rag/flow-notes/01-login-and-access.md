# 登录、身份恢复与访问条件

这篇只回答一组问题：请求是谁发起的，系统怎样认出他，以及请求进入业务后又检查了哪些对象条件。认证只能证明“这个 token 对应一个登录态”；能否读取某条会话、某份文档或某个任务，还要看业务查询有没有把当前身份放进条件。当前项目只在部分用户对象上完成了这种约束，知识管理和检索仍是登录后共享范围。

## 1. 普通登录用户和管理员实际有什么区别

- 普通用户和管理员走同一个 `/auth/login`，token 的生成方式、有效期和后续身份恢复流程没有区别。两者的差别来自 `t_user.role`，当前约定值是 `user` 或 `admin`。
  - 登录响应会带回这个 `role`；后续普通请求恢复 `UserContext` 时也会从当前用户记录重新读取它。
  - role 字段本身不会让所有 `/admin/**` 或知识管理接口自动受保护。只有代码在具体入口执行 `StpUtil.checkRole("admin")`，这次请求才真的检查管理员角色。
- 当前能确认的管理员专属入口包括用户管理的查询、创建、更新、删除，以及只在 `pooled-eval` profile 且开关启用时存在的 pooled 评测入口。角色检查写在这些 Controller 方法内部，执行在全局登录和用户上下文拦截之后。
  - `checkRole("admin")` 没有直接相信前端传来的 role，也没有读取 `UserContext.role` 作字符串判断。Sa-Token 会用当前 loginId 调用 `SaTokenStpInterfaceImpl.getRoleList`，后者按用户主键重新查 `t_user`，把数据库当前的 role 作为角色列表。
  - 当前权限列表实现始终返回空集合；源码中也没有权限码检查，因此不能描述成已经有一套细粒度 permission 授权。
- 反例能帮助确定边界：`/admin/dashboard/**`、`/admin/kg/**` 只有路径上的 `admin` 命名，Controller 没有 `checkRole`；知识库和知识文档 Controller 也没有角色检查。对这些入口，当前真正成立的公共门槛仍只是“通过全局登录检查”，再加各自业务方法中的参数或对象状态判断。

## 2. 用户提交账号密码，服务创建登录态并返回 token

这是一次独立的同步请求。用户把账号密码交给登录接口，接口完成账号查询和密码比较后让 Sa-Token 建立登录态，随后返回 token；它不会顺便执行登出、加载会话或进入 RAG 流程。

- 用户向 `POST /auth/login` 提交 JSON，请求体只有 `username` 和 `password`。`/auth/**` 被三个 MVC 拦截器共同排除，因此登录请求不会先要求已有 token，也不会建立 `UserContext`，体验环境只读拦截器也不会阻止它。
- `AuthController.login` 接收请求体，把整个 `LoginRequest` 交给 `AuthService.login`。Controller 自己不查询用户，也不生成 token；Service 返回后，Controller 用统一结果包装成 `code="0"` 和登录数据。
- `AuthServiceImpl.login` 先从请求对象取出用户名和密码，按顺序判断账号信息。
  - 用户名或密码只要是空白字符串，就抛出 `ClientException("用户名或密码不能为空")`。异常由全局异常处理器转成客户端错误结果，本次请求在这里结束。
  - 参数非空后，`findByUsername` 按 `username = 输入用户名 AND deleted = 0` 查询一条 `t_user`。这里没有先按 role、租户或部门筛选；用户名在 schema 中有唯一约束。
  - 没查到用户，或者密码不匹配，都统一抛出“用户名或密码错误”，不会向调用方区分“账号不存在”和“密码错”。
  - 当前 `passwordMatches` 是 `stored.equals(input)` 的直接字符串比较。用户创建、修改密码也直接保存字符串；现状不能写成已经使用 BCrypt、盐或自适应哈希。
  - 匹配成功后还检查用户主键是否为空；为空则返回“用户信息异常”，不创建登录态。
- 用户记录有效后，Service 把数据库主键转成字符串 `loginId`，调用 `StpUtil.login(loginId)`。
  - Sa-Token 在这里建立“随机 token ↔ loginId/会话”的登录态。应用配置的 token 名为 `Authorization`，token 样式是 `simple-uuid`，声明的超时是 `2592000` 秒，即 30 天。
  - `is-concurrent=true` 允许同一账号同时登录，`is-share=false` 表示新的登录不会复用旧 token。它们是当前主配置值；部署覆盖配置时，应以实际生效配置为准。
  - Service 随即调用 `StpUtil.getTokenValue()` 取得本次请求创建的 token。这个 token 是后续认证凭证，用户 ID、用户名和角色不会由客户端再拼进 token 供业务直接信任。
- Service 组装 `LoginVO`：`userId` 来自用户主键，`role` 来自用户记录，`token` 来自 Sa-Token，`avatar` 来自用户记录；头像为空时改用代码中的默认头像 URL。
  - 登录响应本身没有 `username` 字段。当前前端先用提交的用户名补进本地用户对象，再携 token 请求 `/user/me`，用服务端恢复出的完整用户资料刷新本地状态。
  - 前端把 token 保存在 localStorage，后续 Axios 请求直接设置 `Authorization: <token>`，当前客户端没有添加 `Bearer ` 前缀。SSE 聊天请求也显式使用同名请求头。
- 到这里登录请求返回。数据库用户行没有因为登录被改写；后续请求能否继续，由 Sa-Token 登录态是否仍有效和业务对象查询共同决定。

### 登出是另一条请求

- 用户另行发送 `POST /auth/logout`。这个地址同样处于 `/auth/**` 排除范围，不经过全局 `checkLogin` 和 `UserContextInterceptor`。
- Controller 调用 `StpUtil.logout()` 注销当前请求所携 token 对应的登录态，然后返回成功。前端无论登出请求成功还是网络失败，都会清除本地 token、用户资料和聊天页面状态；因此“浏览器本地已退出”和“服务端登录态已成功注销”在网络失败时不是同一件事。
- 旧 token 被注销或超时后，再访问受保护接口会在业务 Controller 之前失败，不会靠业务 Service 自行判断登录过期。

## 3. 用户携带 token 访问业务接口，系统恢复并清理 UserContext

这一流程位于每个普通 HTTP 业务请求的最前面。它接收请求头中的 token，先判断登录态，再加载最新用户记录形成线程内身份快照；Controller 和 Service 从这个快照读取当前用户。普通同步请求完成后清除该线程的上下文，SSE 的首次线程释放与异步完成则要分开看。

- 前端从 localStorage 取得 token，放进 `Authorization` 请求头。Sa-Token 登录拦截器是当前注册顺序中的第一个拦截器，匹配所有路径，但排除 `/auth/**` 和 `/error`。
  - 对普通同步请求，它调用 `StpUtil.checkLogin()`。Sa-Token 以配置的 token 名取得凭证，再检查 token 是否对应有效 loginId、登录态是否存在且是否超时。
  - 没带 token、token 无效、已注销或已过期时，`checkLogin` 抛 `NotLoginException`。全局异常处理器把它转换为 `code="A000001"`、消息“未登录或登录已过期”；后续体验模式、用户上下文和业务方法都不执行。前端看到包含“未登录”的业务失败后清除本地认证信息并跳回登录页。
  - `OPTIONS` 预检请求直接放行。Servlet 的 `DispatcherType.ASYNC` 二次调度也跳过登录检查，因为它是原 SSE 请求的异步续段，此时 Sa-Token 的原请求上下文可能已不存在；这不是一个可供普通调用者绕过登录的新 Controller 请求。
- 登录通过后，第二个拦截器是 `DemoModeInterceptor`。正常模式直接继续；体验模式下只允许普通 GET 查询，`/rag/v3/chat` 虽是 GET 也会被当作 SSE 写操作拒绝，其他写请求返回“体验环境仅支持查询操作”。如果它返回 `false`，后面的 `UserContextInterceptor` 和 Controller 不执行。
- 前两关通过后，`UserContextInterceptor.preHandle` 恢复业务身份。
  - 它对 `OPTIONS` 和 `DispatcherType.ASYNC` 也直接返回，不查询用户、不新建 `UserContext`。普通同步业务请求才继续执行下面的恢复步骤；SSE 后续工作依靠入口已经捕获或显式保存的身份接续。
  - 它先调用 `StpUtil.getLoginIdAsString()`，取得登录时传给 `StpUtil.login` 的用户主键字符串。这个 userId 来自服务端登录态，不取自 URL、请求体或前端保存的 role。
  - 接着执行 `userMapper.selectById(loginId)`，按主键重新读取用户记录。MyBatis-Plus 对带逻辑删除字段的实体会排除已删除行；当前方法没有对查询结果为空做显式判断，随后直接读取字段。因此“token 仍有效，但用户已经被删除或记录异常缺失”不会被整理成标准的登录过期分支，而可能落入全局未捕获异常并返回通用服务错误。这是当前实现边界。
  - 查询成功后，它构造 `LoginUser`：`userId` 取记录主键，`username` 取记录用户名，`role` 取记录角色，`avatar` 取记录头像或默认头像。随后调用 `UserContext.set` 保存。
- `UserContext` 的底层是 `TransmittableThreadLocal<LoginUser>`。当前请求线程里的业务代码可以调用 `getUserId()`、`getUsername()`、`getRole()`、`getAvatar()`；需要强制存在身份时可以调用 `requireUser()`。
  - 会话查询常把 `getUserId()` 放进 SQL，对象归属因此按不可由请求参数替换的当前用户 ID 判断。
  - 知识管理写入常用 `getUsername()` 填 `created_by/updated_by`；这个字段记录操作者名字，本身没有参与当前对象授权。
  - 审计记录会读取 userId、username 和 role 形成操作者快照；保存了审计身份也不等于入口已经做角色或对象权限检查。
- 对普通同步请求，Controller 和 Service 处理结束、MVC 完成本次请求后，`UserContextInterceptor.afterCompletion` 调用 `UserContext.clear()`。
  - 清理使用 `remove()`，目的是让容器线程被下一次请求复用时不再看到上一个用户。比如线程先处理用户 A，随后处理用户 B；若没有清理，B 的业务可能误读 A 的身份。
  - SSE 返回 `SseEmitter` 并进入异步处理时，最初 Servlet 线程退出本次调度不会调用 `afterCompletion`；Spring 在这里调用的是异步拦截器的 `afterConcurrentHandlingStarted`。当前身份拦截器只实现 `HandlerInterceptor`，没有该异步清理钩子，因此不能把同步请求的清理保证直接套到 SSE 首次线程释放上。
  - SSE 后续异步调度完成时可以进入 `afterCompletion`，但它清理的是当时执行回调的线程，不能保证清掉最初 Servlet 线程残留的身份。普通后续业务请求会重新加载并设置身份，所以这项缺口不等于已经证明发生越权。跨线程如何继续使用身份还取决于任务提交方式，具体交接见[第 7 节](#7-异步执行时身份怎样交接)。

## 4. 读取会话消息：先验证当前用户拥有会话，再读取消息

这里选择 `GET /conversations/{conversationId}/messages` 作为用户对象读取例子。它接收 URL 中的会话 ID 和拦截器恢复的当前 userId，先验证这两个值能否找到会话，再用相同用户条件查消息。当前接口返回消息列表，没有单独的“按 messageId 读取一条消息”步骤。

- 请求先走第 3 节的三个拦截器。通过后，`ConversationController.listMessages` 从路径取 `conversationId`，从 `UserContext.getUserId()` 取当前用户 ID，把两者和默认参数交给 `ConversationMessageService.listMessages`。
  - Controller 传入 `limit=null` 和升序 `ASC`，所以本接口不加 `LIMIT`，最终按创建时间和消息 ID 从早到晚返回。
- Service 先检查 `conversationId` 或 `userId` 是否为空；任一为空就返回空列表，不继续查询。
- 参数有效时，Service 先查询会话表，条件是：

  ```text
  conversation_id = 路径中的 conversationId
  AND user_id = UserContext 中的当前 userId
  AND deleted = 0
  ```

  - 这一步是对象归属检查和存在性检查的合并：只有“这个会话存在并且属于当前用户”才会命中。
  - 如果用户 B 把用户 A 的 `conversationId` 换进 URL，数据库里虽然有该 ID，但 `(conversationId, B.userId)` 查不到，Service 直接返回空列表，不再查消息。
  - 真正不存在的 ID 也返回同一个空列表。当前返回语义没有向调用者区分“不存在”和“不属于你”，同时也没有抛“会话不存在”。外层仍包装成功结果 `code="0", data=[]`。
- 会话命中后，Service 再查消息表，条件是同一个 `conversationId`、同一个当前 `userId` 和 `deleted=0`，并按 `create_time`、`id` 升序排列。
  - 归属条件同时出现在会话查询和消息查询中。即使出现错误数据，把另一用户的消息写进同名 conversationId，这一层仍不会把它返回。
  - 没有消息时返回空列表。查询有结果时，Service 只收集其中 assistant 消息的 ID，再按当前 userId 批量查该用户自己的反馈票；别人的点赞或点踩不会变成本用户的 `vote`。
  - 最后逐条组装 `ConversationMessageVO`，包括消息 ID、角色、正文、思考内容、来源、推荐问题、状态和时间等，Controller 将列表作为成功响应返回。
- 这条链检查的是用户会话对象，不检查 admin role。管理员如果请求别人的 conversationId，也仍使用管理员自己的 userId 作为 SQL 条件，不会因 role=admin 自动跨用户读取。

## 5. 访问知识文档：当前只要求登录，没有对象所有权检查

这里选择 `GET /knowledge-base/docs/{docId}` 与上一节比较。请求同样先完成登录和 `UserContext` 恢复，但业务查询只接收 `docId`，没有把当前 userId、username 或 role 放进查询条件，所以它代表当前登录后共享知识管理面的真实边界。

- 请求通过全局拦截器后，`KnowledgeDocumentController.get` 只从路径取得 `docId`，调用 `KnowledgeDocumentService.get(docId)`。Controller 不读取 `UserContext`，也没有 `checkRole("admin")`。
- Service 调用 `documentMapper.selectById(docId)`。查询只以文档主键定位记录；逻辑删除配置会排除已删除记录，但没有追加以下任何条件：
  - 当前用户 ID；
  - 当前用户名与 `created_by` 相等；
  - 管理员角色；
  - tenant、group 或资源 ACL。
- 查不到文档时，Service 抛 `ClientException("文档不存在")`，全局异常处理器返回 `code="A000001"` 和该消息。它与会话消息接口的空列表语义不同。
- 只要文档存在，任何已登录用户都能得到转换后的 `KnowledgeDocumentVO`。即使 `created_by` 是另一个用户名，也不会进入“不属于自己”的错误分支，因为当前根本没有这项比较；换言之，此入口没有“资源存在但不属于自己”的独立返回结果。
  - 文档预览和源文件读取最终也先按同样的 `get(docId)` 取得记录，所以继承这项共享边界。
  - 文档分页按 `kb_id`、删除状态、可选关键字和状态过滤；全局搜索按删除状态和文档名过滤，同样没有用户条件。知识库详情和列表也没有用户条件。

### `created_by`、意图 scope 和资源授权不是一回事

- `created_by` 是创建知识库、文档或 chunk 时记录的操作者用户名，主要用于展示和审计追踪。当前读写 Service 没有普遍拿它与 `UserContext.username` 比较，因此它不是 owner ACL。
- 意图 scope 回答“这次问题应该检索哪些知识集合”。程序先读取所有 `deleted=0` 知识库的 `collection_name`；KB 意图分数足够高时，把意图绑定的集合与有效集合求交，并可从未命中集合留补充额度。意图为空、低置信或绑定失效时，主配置的 `fallback-mode=global` 会回到所有有效知识库；`iron-ore-demo` profile 则覆盖为 `empty`。
- 实际 PGVector 查询把上述集合放进 `WHERE collection_name IN (...)`，再按向量距离取总 TopK；这个 SQL 没有 userId、created_by、tenant 或 ACL 条件。其他检索通道也共读这份 collection scope。意图分类看到了哪个主题，只是在组织相关性范围，不是在证明当前用户获准阅读这些集合。
- 因此，当前普通登录用户访问共享知识管理面时，直接文档读取和问答检索都没有完整资源 ACL。已登录、文档由谁创建、意图命中哪个库，是三个不同事实，不能互相替代。

## 6. 管理接口的角色检查发生在哪里

角色判断应沿具体入口说明，不能由表中有 role 字段或 URL 带 `/admin` 推断。

- 以 `GET /users` 为例，请求先通过登录、体验模式和 `UserContext` 三个拦截器，进入 `UserController.pageQuery` 后第一条业务动作才是 `StpUtil.checkRole("admin")`。
  - Sa-Token 取得当前 token 对应的 loginId，调用项目的角色提供器。
  - 角色提供器要求 loginId 非空且为数字字符串，再 `selectById(loginId)` 读取用户；用户不存在或 role 为空就返回空角色列表，否则返回只含数据库 role 的列表。
  - 列表不含 `admin` 时抛 `NotRoleException`。全局异常处理器把它转成 `code="A000001"`、消息“权限不足”，用户查询 Service 不执行。
  - 检查通过后，Controller 才进入用户分页业务。`POST /users`、`PUT /users/{id}`、`DELETE /users/{id}` 重复同样的入口检查。
- pooled 评测的两个 POST 入口也在各自方法开头显式 `checkRole("admin")`，之后还检查专用 profile、启用开关和隔离数据库条件。这里的管理员身份不能替代评测环境隔离条件。
- `DashboardController` 和 `GraphController` 没有同样的调用，知识库/文档管理入口也没有。因此普通登录用户能通过这些入口的公共认证层；能否成功只取决于后续参数、配置和对象状态。若要把它们变成管理员接口，需要在真实执行入口或统一授权策略中增加检查，改路径名没有授权效果。

## 7. 异步执行时身份怎样交接

HTTP 拦截器只在请求线程建立 `UserContext`。聊天线程池和文档 MQ 使用了两种不同交接方法：前者捕获上下文快照，后者把有限身份字段写进消息再由消费者重建。不能把其中一种机制推演到所有异步代码。

### 7.1 聊天：TTL 受管线程池捕获请求身份快照

- `/rag/v3/chat` 进入业务时，请求线程已经有完整 `LoginUser`。`RAGChatServiceImpl` 先确定 conversationId 和 taskId，并创建流式回调；`StreamChatEventHandler` 的构造过程当场把 `UserContext.getUserId()` 保存到实例字段，后续异步回调不必重新依赖请求线程取这个 ID。
- 聊天入口把真正执行 pipeline 的 `onAcquire` 任务交给 `ChatQueueLimiter`。
  - 未启用全局限流时，任务提交给 `chatEntryExecutor`；这个 Executor 由 `TtlExecutors.getTtlExecutor` 包装，会在提交时捕获 `TransmittableThreadLocal` 快照，在工作线程执行时安装快照。
  - 启用限流时，`onAcquire` 和超时回调还先经过 `TtlRunnable.get(...)` 包装，再由受管入口执行器运行。这样即使排队和请求返回之间隔了一段时间，获准执行时仍使用提交该任务的用户快照。
- 工作线程随后构造 `StreamChatContext`，其中 `userId` 取自该线程已恢复的 `UserContext`；pipeline 后续把显式的 `ctx.userId` 用于会话记忆和业务处理。聊天内部的检索、模型流和记忆线程池也由项目配置成 TTL 包装执行器。
- 任务提交时已经捕获身份快照，此后即使清理提交线程的上下文，也不会把快照中的值改成空。这只说明经过 TTL 包装提交的任务能够接续身份，不说明 SSE 最初 Servlet 线程已经完成清理；后者存在第 3 节所述的异步钩子缺口。新建原生线程池、第三方回调或遗漏包装的异步入口，仍需要显式传 userId 或补充上下文装饰与清理。

### 7.2 文档摄取：MQ 事件只传 operator，消费者手动恢复并清理

- 已登录用户请求开始文档分块时，`startChunk(docId)` 先按 docId 读取文档并检查存在，但没有做文档 owner 或角色检查。它创建 `KnowledgeDocumentChunkEvent`；对身份交接真正有用的字段只有 `operator=UserContext.getUsername()`，业务定位字段则有 docId。
- 事务消息提交后，RocketMQ 消费者在自己的消费线程收到事件。MQ 不会携带生产线程的 ThreadLocal；消费者也没有尝试从 Sa-Token 恢复登录态。
- `KnowledgeDocumentChunkConsumer` 显式构造只含 `username=event.operator` 的 `LoginUser`，调用 `UserContext.set`，再执行 `documentService.executeChunk(docId)`。
  - 这个消费上下文的 `userId`、`role` 和 `avatar` 都是空。消费内部若读取 `getUsername()`，得到生产事件传来的操作者名字；若要求 userId 或管理员角色，则当前事件没有足够信息。
  - 无论执行成功还是抛异常，消费者都在 `finally` 调用 `UserContext.clear()`，避免 MQ 消费线程复用时把上一个操作者带给下一条消息。
- 这说明异步身份是一份按交接点选择的快照，而不是持续绑定的在线用户对象。聊天交接了完整 `LoginUser` 的 TTL 快照并另存 userId，摄取消息只交接用户名；用户后来改名、改角色或被删除，不会自动重写已经排队消息里的 operator。摄取的解析、分块、嵌入和终态处理属于相应流程篇，本篇只确认身份交接边界。

## 8. 停止请求当前没有验证 task owner

- 用户另行发送 `POST /rag/v3/stop?taskId=...`。它先经过全局登录和用户上下文恢复，因此匿名或过期 token 不能进入 Controller。
- Controller 收到 taskId 后调用 `RAGChatService.stopTask(taskId)`，Service 再直接调用 `StreamTaskManager.cancel(taskId)`。
- 这条调用没有把 `UserContext.userId` 传入，也没有先按 `(taskId, userId)` 查询 trace、会话或任务记录。因此当前只证明“请求者已登录”，没有证明“这个 task 属于请求者”。知道另一个有效 taskId 的登录用户可以触发同一取消入口。
- 取消信号如何跨节点发布、如何与句柄绑定和终态竞争，属于停止流程 F；本篇的结论到 owner 检查缺失为止。若补授权，校验必须发生在发布取消信号之前，并使用服务端当前 userId 与任务持久记录共同判断。

## 9. 四个业务情形怎样推到结果

| 情形 | 实际经过的条件 | 当前结果 |
| --- | --- | --- |
| 登录过期 | `StpUtil.checkLogin()` 找不到有效登录态 | Controller 不执行，返回 `A000001 / 未登录或登录已过期`，前端清本地认证并跳登录页 |
| 用户 B 换成 A 的 conversationId 读取消息 | 会话查询同时要求 `conversation_id=A的ID` 和 `user_id=B` | 查不到会话，返回成功空列表；不泄露 A 的消息，也不区分不存在与越权 |
| Web 或 MQ 线程被复用 | 普通同步 HTTP 的 `afterCompletion`、MQ 的 `finally` 清理各自线程；SSE 首次线程释放缺少异步清理钩子 | 同步请求与 MQ 有对应清理路径；TTL 快照传播不能证明 SSE 原线程已清理，也不能仅凭这项缺口断言已发生越权 |
| 普通用户访问共享知识管理面 | 只经过登录检查；文档按 docId、知识库按 id/查询条件读取 | 现有对象可返回，没有依据 `created_by` 拒绝；当前不是完整多租户 ACL |

## 10. 当前边界与 ACL/RLS 扩展

- 当前已经存在三层不同强度的条件：全局 token 认证；会话、消息和部分铁矿任务的 userId 对象约束；少数 Controller 的显式 admin 角色检查。知识库、文档、chunk 和检索集合没有形成贯穿式 owner/tenant/ACL。
- 若以后扩展 ACL，请先得到当前用户可读集合，再与意图得到的 collection scope 求交，最终让 PGVector、关键词和图检索都使用同一授权后的范围。低置信回退也只能回到“该用户可见的全局”，不能回到系统全部知识库。
- PostgreSQL RLS 可以给关系表增加纵深防御，但它不会自动覆盖 Milvus、搜索索引、图服务、对象存储和缓存；应用层对象授权与跨后端 scope 仍需统一。当前 `schema_pg.sql` 没有 tenant/ACL 表和 RLS policy，所以这些属于后续设计，不是已实现能力。

## 11. 影响理解的旧笔记修正

- “管理员可以访问管理面”只能落到已有显式 `checkRole("admin")` 的方法；不能按 `/admin` 路径、前端菜单或 `role` 字段把所有管理接口算成管理员专属。
- “有 `created_by` 就有知识对象归属”不成立。当前字段记录用户名，知识文档详情、列表、搜索和检索 SQL 都没有拿它做访问过滤。
- `UserContext` 虽使用 TTL，也只会沿受支持的任务捕获和包装方式传播。RocketMQ 消费者当前靠事件中的 operator 手动重建，而且只恢复 username。
- 当前登录密码是明文字符串等值比较。带盐自适应哈希是应做的生产改进，不能写成现状。

## 12. 源码反查

- [AuthController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/AuthController.java) 与 [AuthServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AuthServiceImpl.java)：登录、账号查询、当前密码比较、创建登录态和独立登出请求。
- [SaTokenConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenConfig.java)、[UserContextInterceptor.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java) 与 [SaTokenStpInterfaceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenStpInterfaceImpl.java)：拦截器顺序、身份恢复和显式角色查询。
- [UserContext.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContext.java) 与 [ThreadPoolExecutorConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java)：线程内身份结构和 TTL 包装的受管执行器。
- [ConversationController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/ConversationController.java) 与 [ConversationMessageServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java)：会话归属先验查询、消息用户条件和空列表语义。
- [KnowledgeDocumentController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java) 与 [KnowledgeDocumentServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)：共享文档读取、`created_by` 写入和分块事件生产。
- [RetrievalScopeResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java) 与 [PgVectorRetrieverService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java)：意图集合范围和只按 collection 过滤的向量 SQL。
- [ChatQueueLimiter.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/ChatQueueLimiter.java) 与 [KnowledgeDocumentChunkConsumer.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java)：聊天 TTL 快照和 MQ 手动身份交接。
- [RAGChatController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java) 与 [RAGChatServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java)：停止入口只把 taskId 交给取消管理器。
- [GlobalExceptionHandler.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/web/GlobalExceptionHandler.java) 与 [schema_pg.sql](../../../resources/database/schema_pg.sql)：认证/角色/业务异常的统一结果，以及当前用户、会话、知识对象字段和无 RLS 的 schema 边界。
