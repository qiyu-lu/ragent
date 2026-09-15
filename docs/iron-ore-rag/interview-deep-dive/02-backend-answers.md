# 第二组：后端基础与工程追问参考答案

本文件对应 [问题清单](00-questions.md) 的 Q041—Q075。回答以当前 Java 17、Spring Boot 3.5.7、Spring Framework 6.2 和本仓库实现为准；线程数、索引收益和线上瓶颈都需要结合实际部署验证，不能把配置值当成压测结论。

### Q041｜P1｜Java 的 `String.length()`、字符、Unicode 码点和 UTF-8 字节数有什么区别？

**参考回答：**Java `String` 的 API 按 UTF-16 代码单元计数和索引，`length()` 返回的是 16 位代码单元数量，也就是可用 `char` 下标访问的位置数；这不代表 Java 17 底层固定给每个位置分配 2 字节，紧凑字符串实现可按内容使用 `byte[]` 加编码标识。Unicode 码点才是 `U+xxxx` 意义上的字符编号，UTF-8 字节数则是编码后的大小，三者不能互换。当前表格切片器用 `String.length()` 同时约束 Markdown 正文和向量文本，所以 `maxChars=1024` 是 UTF-16 代码单元预算；离线脚本若用 Python `len`，统计的是 Unicode 码点，含补充字符时数字会不同。

**追问①：** 一个中文字符、一个 emoji 通常怎样计数？

**追问①回答：**常用汉字通常位于基本多文种平面，在 Java 中是 1 个代码单元、1 个码点，编码成 UTF-8 通常是 3 字节；例如 `😀` 是 1 个码点，却由一对代理项表示，所以 `length()` 是 2，UTF-8 是 4 字节。“用户看到的一个字符”还可能由多个码点组合而成，因此界面字形数又是另一层概念。

**追问②：** 流式按固定长度截字符串可能损坏什么，如何避免？

**追问②回答：**如果按任意 `char` 下标 `substring`，切点可能落在高、低代理项中间，得到不完整 Unicode，后续编码时可能出现替换字符或显示异常。应按 `codePointAt` 配合 `Character.charCount` 推进，或用 `offsetByCodePoints` 计算边界；当前流事件处理器就是逐码点追加后再分批发送。它保证不拆代理对，但并不把切片预算变成 token 或 UTF-8 字节预算。

**反查：**[TableChunker.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/blockaware/TableChunker.java)、[StreamChatEventHandler.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java)、[Java 17 String 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)

### Q042｜P1｜切片去重为什么用稳定 ID 或哈希，而不是直接比较对象？

**参考回答：**去重需要先定义“同一块”的业务身份。两个检索通道可能分别构造出不同 `RetrievedChunk` 对象，即使都指向同一数据库切片，对象引用也不同；完整对象还带分数、通道归因和后补元数据，这些字段随阶段变化，用它们比较会把同一证据误判为多块。当前统一键优先取持久化 chunk ID；没有 ID 的结果才对正文做 SHA-256，再由 `LinkedHashMap.putIfAbsent` 保留第一次出现的对象和顺序。哈希是无 ID 时的退路，仍应考虑规范化口径与理论碰撞，不能替代来源身份设计。

**追问①：** `equals` 与 `hashCode` 必须满足什么约定？

**追问①回答：**Java 约定是：对象相等必须得到相同 `hashCode`；哈希相同却允许不相等。`equals` 还应满足自反、对称、传递、一致和对 `null` 返回 false。哈希容器先定位桶再用相等性确认，因此只重写其中一个方法，会出现明明相等却查不到或重复存入的问题。

**追问②：** 如果作为 HashMap 键的字段插入后被修改，会发生什么？

**追问②回答：**键放入 `HashMap` 后，如果修改了参与 `equals/hashCode` 的字段，它的新哈希可能指向另一个桶，而对象仍留在旧桶中，`get`、`containsKey`、`remove` 都可能失效，甚至再插入一个逻辑重复键。实践中应使用不可变 ID、不可变值对象或预先算好的字符串键；诊断分数、标题等可变字段放在 value 中。

**反查：**[RetrievedChunkKey.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunkKey.java)、[DeduplicationPostProcessor.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/DeduplicationPostProcessor.java)、[Java 17 Map 契约](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html)

### Q043｜P1｜ArrayList、HashMap 和 LinkedHashMap 在这个项目里各适合什么用途？

**参考回答：**三者解决的问题不同。`ArrayList` 适合保存有顺序、按下标取值且主要追加的子问题或候选列表；`HashMap` 适合按 ID 快速找到节点、文档或归因集合，但不承诺遍历顺序；`LinkedHashMap` 在哈希查找外维护插入顺序，适合“以稳定键去重并保留首次出现顺序”。当前检索链会用列表保存原子问题顺序，用 Map 建索引，再用 `LinkedHashMap.putIfAbsent` 汇总唯一块，让并发完成时序不会意外改写最终展示顺序。

**追问①：** 既要去重又要保留首次出现顺序，如何选结构？

**追问①回答：**既要去重又要保留首见顺序，可直接用 `LinkedHashMap<稳定键, Chunk>`；若只要元素，可用 `LinkedHashSet<稳定键>` 配合结果列表。不要用 `HashSet` 去重后再假设遍历顺序，也不要在列表中反复 `contains`，后者会把大量候选的去重成本推向平方级。

**追问②：** 如何估计大量切片在内存中的额外开销？

**追问②回答：**大量切片的内存不能只按正文字节数估算，还包括 `ArrayList` 后备数组中的引用和空余容量、Map 桶数组与每项节点、键字符串/对象，以及 value 继续引用的正文、元数据和向量。应以代表性块数和文本长度做容量预算，再用 Java Flight Recorder、类直方图或堆转储核实实际保留对象；预估已知规模可设置初始容量，减少扩容复制，但过大预分配也浪费堆。

**反查：**[RetrievalEngine.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalEngine.java)、[Java 17 ArrayList 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ArrayList.html)、[Java 17 LinkedHashMap 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/LinkedHashMap.html)

### Q044｜P1｜多个检索线程共同修改候选或诊断对象时，怎样保证线程安全？

**参考回答：**我会先减少共享写：每个子问题、每个通道在自己的任务里构造局部结果，完成后由父线程按预定顺序 `join` 并归并；跨线程传递时用不可变记录和 `List.copyOf/Set.copyOf` 固化阶段结果。这样比让多个线程同时向同一个 `ArrayList` 或可变诊断对象追加更容易证明。确实需要共享索引时，可按单键原子操作使用 `ConcurrentHashMap`；涉及多个容器、计数和顺序必须同时变化时，则用同一把锁或把状态收口为一次原子状态迁移。

**追问①：** ConcurrentHashMap 能否保证“先判断、再写入”的复合操作原子？

**追问①回答：**`ConcurrentHashMap` 只保证它提供的单次操作具备并发语义，`if (!map.containsKey(k)) map.put(k,v)` 仍是两个步骤，两个线程都可能通过判断。应使用 `putIfAbsent`、`computeIfAbsent` 或 `compute/merge`；若映射函数还改别的结构，就要重新设计临界区，不能因为容器是并发类就认为整段业务原子。

**追问②：** 为什么记录阶段快照要复制字段，而不只保存原对象引用？

**追问②回答：**阶段诊断是“当时发生了什么”的证据。如果只保存原对象引用，后续元数据增强、重排改分或列表追加会让旧阶段看起来也被改过，既产生可见性竞争，也失去可复现性。应复制需要的标量字段、稳定键、分数和来源，集合再做不可变副本；深层仍可变的对象还需深拷贝，而不只是复制最外层列表。

**反查：**[检索与上下文并发边界](../flow-notes/05-retrieval-and-context.md)、[RetrievalSelectionDiagnostics.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/RetrievalSelectionDiagnostics.java)、[Java 17 ConcurrentHashMap 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)

### Q045｜P0｜`volatile`、CAS 和锁分别适合解决什么问题？

**参考回答：**`volatile` 适合发布最新值和建立可见性、顺序关系，例如一个线程写停止标志，其他线程及时看见；它不把“读—改—写”变成原子操作。CAS 是“当前仍等于预期值才更新”，适合竞争不重的单变量状态机、计数或一次性门闩。锁把一段临界区排他化，适合同时维护多个字段、集合或必须整体成立的不变量，但会带来阻塞、竞争与死锁管理成本。选择依据是要保护的业务不变量，而不是哪种原语看起来更快。

**追问①：** 停止与完成同时发生时，单个原子布尔能保证哪些事？

**追问①回答：**停止与完成同时到达时，`AtomicBoolean.compareAndSet(false,true)` 能保证只有一个线程赢得“首次关闭/首次取消”的本机执行权，其他线程看见已完成后退出。它不能单独保证赢家已经完成落库、发送终态和释放句柄；这些副作用仍可能在中途失败，所以每个清理动作还要自身幂等并用 `finally` 隔离。

**追问②：** 本机 CAS 为什么不能自动保证消息表、Trace 和 SSE 终态一致？

**追问②回答：**本机 CAS 只协调一个 JVM 内的一块内存，消息表、Trace 数据库和 SSE 连接属于不同资源，多实例也不共享这个布尔值。跨边界应分别使用带旧状态条件的数据库更新、幂等键或唯一约束、可重放事件与可重试清理；若要求数据库与消息可靠交接，还要事务消息或本地待发送表，不能把 CAS 说成分布式事务。

**反查：**[StreamTaskManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java)、[Java 17 AtomicBoolean 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/atomic/AtomicBoolean.html)、[Java 语言规范：线程与锁](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html)

### Q046｜P0｜ThreadLocal 为什么必须清理，在线程池里有哪些风险？

**参考回答：**`ThreadLocal` 把值挂在当前线程上，而线程池工作线程会跨请求长期复用。若请求结束不 `remove`，下一位使用该线程的用户可能读到旧身份或 Trace，旧对象也会被长生命周期线程继续引用，形成数据串扰和内存滞留。因此设置上下文后要在 `finally` 清理，不能只依赖正常返回。当前项目用可传递线程本地变量（TTL）包装执行器，把提交时的用户和 Trace 快照带到异步任务；这解决传播，不替代源线程与工作线程的恢复、清理，更不等于传播了 Spring 数据库事务。

**追问①：** TTL 上下文传播与原线程清理为什么要分开？

**追问①回答：**TTL 需要分别看提交线程和执行线程：提交时捕获上下文，执行前安装，执行后恢复工作线程原值；原 Servlet 线程上的 `UserContext` 仍要在请求退出异步处理时清除。Spring 事务通常绑定当前执行线程持有的连接，复制一个用户 ThreadLocal 不会把原事务安全搬到新线程，新线程要按自己的事务边界工作。

**追问②：** SSE 首次释放 Servlet 线程时，应关注哪个异步生命周期钩子？

**追问②回答：**Controller 返回 `SseEmitter` 后，Servlet 首次线程会在流结束前先释放。Spring 为此提供 `AsyncHandlerInterceptor.afterConcurrentHandlingStarted`，适合在首次退出时清理线程绑定属性；超时或网络错误还要通过异步生命周期拦截器或 emitter 回调兜底。当前 `UserContextInterceptor` 只实现普通 `afterCompletion` 并跳过 ASYNC 重派发，因此要把首次释放时的清理作为需要验证和补齐的风险，而不能假定等流结束再清理就安全。

**反查：**[登录与用户上下文](../flow-notes/01-login-and-access.md)、[UserContextInterceptor.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java)、[Spring 6.2 AsyncHandlerInterceptor](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/web/servlet/AsyncHandlerInterceptor.html)

### Q047｜P0｜你怎样给模型调用和检索设置线程池？

**参考回答：**我先按依赖和资源类型分池，再用测量定大小。纯计算任务的并行度通常靠近可用核数；模型 HTTP、向量查询这类 I/O 等待可以多一些线程，但上限必须同时受 HTTP/数据库连接池、供应商并发与限流、平均在途时间和堆内存约束。当前仓库按聊天入口、子问题、检索通道、模型流、摘要和文档切分建了独立 Java 17 平台线程池，部分按 CPU 倍数配置；这些只是起始配置，没有压测和外部容量证据时，我不会宣称它就是最佳参数。

**追问①：** CPU 密集与 I/O 等待任务的线程数考虑有何不同？

**追问①回答：**CPU 密集池加线程超过核心数通常只增加切换；I/O 池可根据到达率、平均等待时间和目标利用率估算并发，再被连接数、模型配额与内存取较小值。还要分别量队列等待和执行时间，因为 CPU 很低也可能是全部线程在等连接或远端响应。

**追问②：** 核心线程、最大线程、队列容量和拒绝策略如何共同影响故障？

**追问②回答：**提交任务时一般先补到核心线程，再入队；队列满且尚未到最大线程才继续扩线程，最后才触发拒绝策略。大队列能吸收短突发，却会放大延迟和内存；`CallerRunsPolicy` 会让提交者自己执行形成反压，也可能阻塞 Servlet 或父任务；`AbortPolicy` 快速失败，必须转成明确的过载响应和指标。应同时监控活跃数、队列深度、拒绝数、等待时长以及下游连接/限额。

#### 深挖追问 Q047-D01

**评审依据：**追问①列出了到达率、等待时间和连接约束，但没有演示如何据此形成并发容量判断。

**问题：**假设稳定流量每秒 8 次模型调用，平均每次占用连接 3 秒，供应商只允许 20 个在途调用。先估算不积压所需的平均并发，再解释为什么单纯增大线程池无法承接该流量，以及你会怎样设置入口行为。

**深挖回答：**先用“到达速率×平均占用时间”估算平均在途量：`8 次/秒 × 3 秒/次 = 24 次`。也就是说，若想让这股稳定流量不积压，平均就需要 24 个同时进行的供应商调用；这里还没算耗时波动和突发。供应商上限只有 20，所以即使 20 个槽位始终满载，理论长期吞吐上限也只有 `20÷3≈6.67 次/秒`。实际系统不能长期按 100% 利用率运行，还要给慢请求、重试和网络抖动留余量，安全接纳速率应低于 6.67，而不是把它当承诺值。

若入口持续每秒进入 8 次、出口最多约 6.67 次，队列平均每秒至少增加约 `8-6.67=1.33` 个任务，等待时间会随运行时间不断增长。把模型线程从 20 增到 50，只会让更多线程在许可、连接或供应商限额前等待；它既不能制造第 21 个供应商槽位，还会多占内存并放大超时和上下文切换。只有降低到达率、缩短平均占用时间或提高已确认的下游额度，长期容量关系才会改变。

入口应做有界接纳：在供应商许可前设置容量很小且可观测的等待队列，为等待设截止时间，并按租户或用户公平分配；预测在截止时间内拿不到槽位时，立即返回明确的过载结果，例如可重试状态与 `Retry-After`，而不是无限排队。对可降级请求可以转到更快模型或缩短生成，但要显式告诉上层发生了降级。线程数只需覆盖获准执行和少量编排，并以队列等待、在途数、平均及高分位耗时、拒绝率实测调整。若 8 次/秒只是短突发，有限队列可吸收；若它是长期稳定流量，就必须限流或扩充下游容量。

**反查：**[ThreadPoolExecutorConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java)、[Java 17 ThreadPoolExecutor 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)

### Q048｜P1｜为什么会把聊天入口、检索、模型流和摘要拆成不同线程池？

**参考回答：**拆池的目的，是让不同耗时分布和优先级的工作各有容量与失败边界。聊天入口控制正在执行的请求数；子问题和检索通道负责短一些的并发 I/O；模型流可能几十秒持续阻塞读取；摘要与文档切分属于后台工作。若共用一池，慢模型或大量摘要会占满所有线程，新请求连检索都启动不了。独立池便于分别设置队列、拒绝策略和指标，但池太多也可能让总线程、连接和内存超卖，所以还需要进程级并发预算。

**追问①：** 什么情况下一个慢依赖会拖垮所有请求？

**追问①回答：**一个慢依赖拖垮全站通常表现为：调用线程一直等待其响应，池中活跃线程逐渐占满，后续任务进入长队列，最终连依赖健康的路径也排队或超时。隔离池和超时能限制影响范围，还应在供应商/数据库入口做并发门禁；仅把线程数调大，只会更快压满连接池或远端配额。

**追问②：** 父任务占线程等待子任务、子任务又排在同一满池中，会发生什么？

**追问②回答：**若父任务占着池线程，向同一满池提交子任务后同步 `join`，而所有线程都在做同样的等待，子任务永远没有线程执行，这就是线程饥饿死锁。可让父子使用不同且有界的执行器，避免在线程池任务内阻塞等待同池子任务，或用完成阶段异步组合。当前子问题池和通道检索池分开，正好降低这类依赖环风险。

**反查：**[ThreadPoolExecutorConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java)、[检索与上下文执行顺序](../flow-notes/05-retrieval-and-context.md)、[Java 17 ThreadPoolExecutor 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)

### Q049｜P0｜CompletableFuture 的并发完成顺序和最后结果顺序如何控制？

**参考回答：**并发执行顺序和汇总顺序要分开控制。先按输入顺序把全部任务提交并保存 future，任务可以任意先后完成；等待全部完成后，再按原列表顺序 `join`，结果就稳定对应原子问题。`allOf` 只产生“全部结束”的完成信号，不直接收集各自值；`join` 失败抛未检查的 `CompletionException`，需要在任务内或汇总层明确降级口径。当前检索链就是先并发提交各子问题，再按原顺序汇合，因此不会让响应快的题自动排到前面。

**追问①：** `join`、`allOf` 与逐个提交后立刻等待有何区别？

**追问①回答：**如果每提交一个任务就立刻 `join`，循环会先等第一项结束，再提交第二项，实际退化成串行。正确方式是先得到完整 futures 列表；可以 `allOf(...).join()` 后逐项取值，也可直接按列表逐个 `join`，因为任务早已同时在跑。后者遇到首个慢项时会等待，但不妨碍其他已提交任务后台完成。

**追问②：** `orTimeout` 是否终止底层 HTTP 或 SQL，如何做真正的取消传播？

**追问②回答：**`orTimeout` 只让 `CompletableFuture` 在期限到后异常完成；`CompletableFuture.cancel` 也只是把这个完成状态设为取消，因为它并不直接控制产生结果的计算，所以不会自动终止正在进行的 HTTP 或 SQL。真正取消要把信号传到资源句柄，例如调用 OkHttp `Call.cancel()`、设置 JDBC 查询超时并在可用时取消 `Statement`，同时传播共享截止时间、释放许可并丢弃迟到回调。当前检索通道的 15 秒 `orTimeout` 没有这条底层取消链。

**反查：**[检索通道超时边界](../flow-notes/05-retrieval-and-context.md)、[MultiChannelRetrievalEngine.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java)、[Java 17 CompletableFuture 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)

### Q050｜P1｜什么时候应该抛出异常，什么时候转成业务状态返回？

**参考回答：**判断标准是调用方需要怎样恢复。参数非法、不变量破坏、整个操作不能继续，或必须让事务回滚、MQ 重试时，应让异常越过相应边界；“资料格式不支持”“任务处理失败但已记录原因”这类可查询的长期结果，可以落成明确业务状态并正常返回，但接口必须让上层区分成功、失败和降级。当前 `runChunkTask` 捕获解析、切片、向量化与索引异常，写文档和日志为 `FAILED` 后正常返回，所以 MQ 通常会把这次消费视为已处理，而不会因原异常自动重投。

**追问①：** catch 后吞异常会怎样影响事务和 MQ？

**追问①回答：**在 `@Transactional` 方法里 catch 后吞掉运行时异常，代理看到的是正常返回，事务通常会提交；若这是 MQ 消费入口，Broker 也可能收到成功确认。确实要回滚就重新抛出、显式标记 rollback-only，或把失败记录放到独立事务。反过来，决定把失败当业务终态时，就应确保 `FAILED` 状态、原始错误和人工重试入口可靠，而不是既吞异常又不给状态。

**追问②：** 一个子问题失败可否降级为空，如何让上层知道发生了降级？

**追问②回答：**一个子问题失败可以在产品允许时降级为空，让其他子问题继续，但“没有命中”和“检索出错”必须可区分：在子结果中带 `status/errorCode/degraded`，Trace 和指标记录失败，最终响应可提示部分结果不可用。当前部分检索路径把异常或超时都转为空列表，捕获结构甚至以 `empty-or-failed-channel` 合并两者，这能保住可用性，却不足以支持准确的故障解释和评测。

#### 深挖追问 Q050-D01

**评审依据：**追问①说明吞异常通常提交，但尚未区分方法内部捕获与参与同一事务的内层代理已标记回滚。

**问题：**假设外层事务调用另一个 Bean 的 REQUIRED（加入当前事务）方法，内层抛运行时异常并越过内层事务代理，外层 catch 后正常返回。请逐步解释此时还能否提交，以及它与异常在内层方法里就被捕获有什么不同。

**深挖回答：**在常见的 Spring 默认事务管理器、`REQUIRED` 传播和“运行时异常触发回滚”的规则下，过程是：① 外层代理开启物理事务 T；② 调用另一个 Bean 的内层代理，`REQUIRED` 发现已有 T，便加入同一个事务；③ 内层方法抛出运行时异常，异常越过内层事务代理时，代理把共享事务 T 标记为 `rollback-only`，意思是“这个事务最后只能回滚”，然后继续向外抛；④ 外层业务代码 catch 住异常并正常 `return`，只能阻止异常继续传播，不能清除 T 已有的回滚标记；⑤ 外层代理尝试提交时发现该标记，于是回滚整个 T。因为外层调用者看到业务方法正常返回、按理期待提交，Spring 通常会向它抛出 `UnexpectedRollbackException`，即“意外回滚异常”。所以不能用外层 catch 把这次共享事务变回可提交。

如果异常在内层方法自己的 `try/catch` 中就被完全处理，并以正常结果返回，它没有越过内层事务代理，代理通常不会仅凭这次异常自动标记回滚；外层 T 因而仍可能提交。这也是“在哪一层捕获”会改变事务结果的原因。但仍有边界：内层代码若显式调用 `setRollbackOnly`，或者数据库错误已使底层事务进入不可继续状态，即使异常被方法内部吞掉也不能安全提交；自调用没有经过代理时，也不会发生上述“内层代理按异常规则标记”的步骤。

若产品要“主操作失败回滚，但失败日志保留”，不应依赖 catch 猜测共享事务状态。可以让内层异常退出原事务，再由另一个 Bean 以 `REQUIRES_NEW` 写失败日志；若业务允许某个内层失败不影响主事务，则应在独立事务或调用前明确隔离，而不是先加入 T、标成 rollback-only 后试图补救。这里的结论以默认运行时异常回滚规则为前提；自定义 `rollbackFor/noRollbackFor` 或特定事务管理器会改变判定。

**反查：**[消费者失败边界](../flow-notes/02-document-lifecycle.md#4-消费者执行恢复操作者调用固定摄取内核再记录终态)、[检索降级边界](../flow-notes/05-retrieval-and-context.md)、[Spring 6.2 回滚规则](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html)

### Q051｜P0｜Spring 的 `@Transactional` 是怎样生效的，哪些调用方式会失效？

**参考回答：**在常见代理模式下，Spring 给 Bean 包一层事务代理：外部调用经过代理时，拦截器从事务管理器取得或加入事务，把数据库连接绑定到当前线程，方法正常返回提交，满足回滚规则的异常越过代理则回滚。因此注解写在方法上不等于任何调用都生效；对象必须受 Spring 管理，调用必须进入代理，数据库操作还要使用同一事务管理器管理的资源。新线程也不会继承原事务，TTL 只传业务上下文。

**追问①：** 同类自调用、异常被捕获、受检异常各需检查什么？

**追问①回答：**`this.inner()` 这类同类自调用绕过代理，内部方法单独标的事务或 `REQUIRES_NEW` 通常不会触发；可拆到另一个 Bean、从代理调用，或改用 `TransactionTemplate`。异常被方法自己 catch 后正常返回，代理看不到失败，默认会提交；需重新抛出或标记 rollback-only。Spring 默认对运行时异常和 `Error` 回滚，受检异常要用 `rollbackFor` 等规则明确配置，同时检查异常有没有在边界外被包装或吞掉。

**追问②：** 程序式事务为何适合索引整体替换这类边界？

**追问②回答：**程序式事务把起止位置写在控制流里，适合“旧索引删除与所有新切片写入要么全成、要么全回滚”这种动态扇出边界。当前 `ChunkIndexWriter` 用 `TransactionOperations.executeWithoutResult` 包住有序的全部 `ChunkSink`；以后增减落点，原子范围仍清楚，也避开同类调用是否经过代理的歧义。不过只有参与同一关系数据库事务的落点能一起回滚，远端 Milvus、对象存储或 HTTP 不能因此自动获得原子性。

**反查：**[ChunkIndexWriter.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/ingest/sink/ChunkIndexWriter.java)、[Spring 6.2 声明式事务](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)、[Spring 6.2 回滚规则](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html)

### Q052｜P0｜事务传播与隔离级别分别控制什么？

**参考回答：**事务传播控制“调用链碰到已有事务时怎么办”：例如 `REQUIRED` 默认加入现有事务、没有则新建；`REQUIRES_NEW` 暂停外层并独立提交；`SUPPORTS` 有就加入、没有就非事务执行。隔离级别控制并发事务彼此能看见哪些版本以及允许哪些异常。在 PostgreSQL 中，读已提交（Read Committed）每条语句取新的快照；可重复读（Repeatable Read）维持事务级快照并且不允许幻读，但仍可能因写偏差等序列化异常得到非串行结果；可串行化（Serializable）还需准备重试序列化失败。

**追问①：** 外层摄取失败时，希望失败日志仍能留下，应怎样划事务？

**追问①回答：**若外层摄取必须回滚、失败日志又必须保留，可让主索引替换处于一个短事务，让另一个 Spring Bean 用 `REQUIRES_NEW` 记录 `FAILED`，或在外层失败退出后再用独立 `TransactionTemplate` 写日志。不要在同类里自调一个注解方法，也不要让失败日志参与必定回滚的原事务；记录失败时若又异常，应保留并上报原始异常，把日志异常作为附加信息。

**追问②：** 模型调用持续几十秒却放在数据库事务中，会占住哪些资源？

**追问②回答：**把几十秒模型调用放在数据库事务中，会长期占用连接池连接和事务快照；若此前已更新数据或加锁，还会延长行锁持有时间，阻塞其他请求，并让旧版本更久不能被清理。更合理的顺序是先做远程模型调用与纯计算，拿到结果后开启短事务校验当前状态并批量落库；若远端结果可能过期，用版本号或状态条件更新处理竞争，而不是用长事务包住网络等待。

#### 深挖追问 Q052-D01

**评审依据：**主回答提到写偏差与可串行化，却没有说明事务级快照为什么仍可能破坏跨行约束。

**问题：**假设两条记录表示两名值守人员，规则要求至少一人值守。两个可重复读事务都读到两人在岗，然后分别把自己改成离岗。请按交错时序解释为何没有更新同一行也会违反规则，并说明一种能够保护该规则的设计及重试边界。

**深挖回答：**设人员 A、B 初始都为在岗。T1 和 T2 几乎同时以可重复读开始，各自取得同一个事务级快照：T1 读到 A、B 都在岗，判断“我把 A 改成离岗后仍有 B”；T2 也读到两人在岗，判断“我把 B 改成离岗后仍有 A”。随后 T1 只更新 A 行，T2 只更新 B 行。两次写入没有碰同一行，因此没有普通的行级写冲突；各事务在自己的旧快照里也始终看见另一个人在岗。若两者都提交，最终 A、B 都离岗，跨两行的“至少一人”约束被破坏。这就是写偏差：每个局部更新都合理，组合结果却无法对应任何合法的串行执行顺序。

一种直接的保护设计是把“读取值守集合、检查至少留一人、更新本人状态”整个业务事务改为 PostgreSQL `SERIALIZABLE`。数据库发现两笔事务形成相互依赖时，会让其中至少一笔以序列化失败结束。应用必须把整个逻辑作为重试单元：开启新事务、重新读取当前两人状态、重新判断约束，再决定是否更新；不能只重放最后一条 `UPDATE`，也不能沿用旧快照得出的“还有一人”结论。例如 T1 先成功离岗后，T2 的完整重试会看到只剩 B 在岗，于是拒绝让 B 离岗。重试应有次数上限、短暂退避和明确失败响应，因为高竞争下不保证某一次立刻成功，外部副作用也要放在提交后或做幂等保护。

另一种设计是为班次设置一条共同的“约束/班次”记录，所有离岗操作先锁住它，再读取该班次人员并更新；这样原本写不同人员行的事务也会在共同记录上串行。但锁后必须取得足够新的数据视图，例如在 `READ COMMITTED` 下先锁共同记录、再查询人员状态。不能在已经建立陈旧快照的可重复读事务里拿到一把锁，就断言后续旧快照自动变新；若保留可重复读，应让冲突触发失败并从新事务重读。共同锁或可串行化任选其一，都要覆盖检查与写入的完整事务边界。

**反查：**[文档摄取事务边界](../flow-notes/02-document-lifecycle.md)、[Spring 6.2 事务传播与隔离配置](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)、[PostgreSQL 事务隔离](https://www.postgresql.org/docs/current/transaction-iso.html)

### Q053｜P1｜Spring Boot 为什么能按配置替换向量后端或注册不同解析器？

**参考回答：**Spring Boot 先把配置属性解析进环境，再根据条件创建 Bean，并按接口类型完成依赖注入。当前 `rag.vector.type=pg` 时装配 PostgreSQL 向量存取实现；值为 `milvus` 或缺省时，匹配带 `matchIfMissing=true` 的 Milvus 实现。上层只依赖 `VectorRetrieverService`、`VectorStoreService` 接口，所以切换实现不用改调用链。解析器则不靠单个开关：Spring 收集全部 `DocumentParser` Bean，`ParserRegistry` 在启动时建立“真实 MIME × 解析档位”表，冲突或声明缺失直接失败。

**追问①：** 条件装配、接口实现和有序处理器分别起什么作用？

**追问①回答：**条件装配决定某个实现是否进入容器；接口把后端差异挡在稳定契约之后；注入 `List<ChunkSink>` 或处理器时，`@Order` 给扇出顺序一个确定规则。三者分别解决“选谁、怎么调用、多个都执行时先后怎样”。还要防止条件重叠导致同接口出现多个无主 Bean，以及条件空档导致根本无法注入。

**追问②：** 字段默认值、主 YAML、profile 与外部覆盖冲突时，怎样确认实际值？

**追问②回答：**不能只看某个字段的 Java 默认值或主 YAML。Spring Boot 属性源有明确优先级，profile 文件、外部配置、环境变量和命令行参数都可能覆盖较早的值。排查时应同时看活动 profile、启动参数与环境变量，用配置属性绑定/Actuator `configprops` 或启动条件报告确认最终属性，并直接核对容器中实际 Bean 类型；这比根据仓库里的 YAML 推断线上后端可靠。

**反查：**[ParserRegistry.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/registry/ParserRegistry.java)、[PgVectorRetrieverService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java)、[Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)

### Q054｜P1｜Filter、Interceptor 和 AOP 的职责与执行位置有什么区别？

**参考回答：**过滤器（Filter）属于 Servlet 容器链，在进入 Spring MVC 和选择 Controller 前处理请求、响应，适合编码、跨域、上传限流和基于请求的安全入口；拦截器（Interceptor）在 Spring MVC 已找到处理器后执行，能看到 Handler，适合登录态转换、用户上下文和接口层规则；面向切面编程（AOP）围绕 Spring Bean 方法代理执行，适合事务、幂等锁、耗时追踪等方法级横切逻辑。当前上传限流是最高优先级过滤器，登录及用户上下文在 MVC 拦截器，RAG Trace 则由切面包业务方法。

**追问①：** 登录检查、用户上下文、角色授权和审计分别适合放哪层？

**追问①回答：**登录检查宜在安全过滤链或早期拦截器统一拒绝；认证完成后，用户上下文可由拦截器建立，但 SSE 异步释放首个 Servlet 线程时也要清理。角色授权适合集中在安全规则或方法授权，并在服务层继续校验对象归属；审计应在拿到操作者和业务结果后由 AOP/服务事件记录。它们可以协作，不能用“已经登录”代替角色和对象级权限。

**追问②：** 接口路径带 `/admin` 为什么本身没有授权效果？

**追问②回答：**`/admin` 只是 URL 文本，不会自动产生权限语义。当前全局 `SaTokenConfig` 主要检查是否登录，部分管理接口另有显式角色校验，但项目尚没有覆盖所有知识库、文档和流任务的完整对象权限。应建立集中路径/方法规则，并在读取或修改对象时用 `owner_user_id`、成员关系或授权表缩小查询范围；尤其停止任务还需验证 task 属于当前用户。

**反查：**[登录、角色与对象权限现状](../flow-notes/01-login-and-access.md)、[SaTokenConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenConfig.java)、[UploadRateLimitFilter.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/filter/UploadRateLimitFilter.java)

### Q055｜P1｜MyBatis-Plus 的逻辑删除会怎样影响查询、唯一约束和数据体积？

**参考回答：**MyBatis-Plus 识别实体上的 `@TableLogic` 后，会把框架生成的删除改成更新删除标记，并在生成的查询中附加“未删除”条件；被删行仍在 PostgreSQL 表和索引里。因此逻辑删除便于审计和恢复，却会增加表、索引与 vacuum 的负担。唯一约束也不会因为业务上“看不见”旧行就自动释放：若允许同一业务键删除后重建，可把删除标记纳入合适的唯一设计，或在 PostgreSQL 使用只约束活动行的部分唯一索引，并明确恢复语义。

**追问①：** 关系切片逻辑删除、向量物理删除为什么不能混称清表？

**追问①回答：**当前关系切片实体 `KnowledgeChunkDO.deleted` 是逻辑删除，框架删除后旧行仍存在；PGVector 实现的 `deleteDocumentVectors` 却显式执行 `DELETE FROM t_knowledge_vector`，属于物理删除。二者可以在 PostgreSQL 的同一事务里协调，但不能笼统说成“清表”：实际范围是某文档，保留策略、回滚表现和空间回收都不同，更不能暗示 `TRUNCATE` 整张共享表。

**追问②：** 手写 JDBC SQL 会自动遵守实体的逻辑删除注解吗？

**追问②回答：**不会自动遵守。实体注解影响 MyBatis-Plus 能识别并生成的 SQL；手写 `JdbcTemplate`、XML 或注解 SQL 是作者自己定义的语句，必须显式加入 `deleted=0` 或把规则封装进视图/公共查询层。审查时既要找 `@TableLogic`，也要搜索所有直连 SQL，否则容易出现管理页隐藏、后台任务却读到已删除数据的口径分裂。

**反查：**[KnowledgeChunkDO.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeChunkDO.java)、[PgVectorStoreService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)、[MyBatis-Plus 逻辑删除指南](https://baomidou.com/en/guides/logic-delete/)

### Q056｜P0｜为文档列表、会话消息和到期任务扫描设计索引，你会怎样做？

**参考回答：**我先从真实 `WHERE`、`ORDER BY`、返回量和数据分布反推，而不先背索引模板。文档页常按 `kb_id`、逻辑删除、可选状态过滤并按 `create_time DESC`，可评估 `(kb_id, deleted, status, create_time DESC, id)`，若状态可选且区分度低也可省略；`LIKE '%词%'` 不能指望普通 B-tree，可另评估 trigram 或全文索引。消息按会话和用户筛选、时间或 ID 排序，可评估 `(conversation_id,user_id,deleted,create_time DESC,id)`。到期任务是 `enabled`、`next_run_time`、`lock_until` 的范围/空值组合，适合评估活动行部分索引或重写查询后建立复合索引，而不是盲目叠加现有两个单列索引。

**追问①：** 多条件过滤加排序时，复合索引列顺序怎样取舍？

**追问①回答：**B-tree 复合索引通常先放稳定的等值前缀，再放范围和排序列，让数据库既缩小扫描区间又尽量免排序；低选择性列是否放入前缀，要结合它能否配合部分索引及查询频率。分页排序还应追加唯一 `id` 保证同时间值下顺序稳定。每多一个索引都会增加写入、vacuum 和缓存成本，所以要合并重复索引并覆盖高频查询，不追求把每个条件都塞进去。

**追问②：** 为什么必须结合 `EXPLAIN (ANALYZE, BUFFERS)` 与实际数据分布判断？

**追问②回答：**优化器选择受行数、值倾斜、相关性和统计信息影响，同一索引在小表上输给顺序扫描很正常。应在接近真实规模的数据上运行 `EXPLAIN (ANALYZE, BUFFERS)`，看估算与实际行数、扫描方式、过滤掉的行、排序、循环次数及缓存命中/读取；再结合写入开销比较前后方案。`ANALYZE` 会真实执行语句，验证写 SQL 时要放在可回滚或安全环境。

#### 深挖追问 Q056-D01

**评审依据：**主回答给出含可选 status 的复合索引，但未展开省略该过滤条件时对排序的影响。

**问题：**假设索引为 (kb_id, deleted, status, create_time DESC, id)，查询只固定 kb_id 与 deleted，并要求所有状态混合按时间倒序。请用两个状态、四条记录说明索引中的排列为何未必直接满足该排序，再说明你会依据哪类查询频率决定是否保留 status。

**深挖回答：**固定同一个 `kb_id` 且 `deleted=0`，设四条记录是：`SUCCESS/10:00/id=1`、`FAILED/11:00/id=2`、`SUCCESS/08:00/id=3`、`FAILED/09:00/id=4`。在索引 `(kb_id, deleted, status, create_time DESC, id)` 中，前两列相同后，第三列先按 `status` 分组；若字典顺序先放 FAILED，索引片段就是 `FAILED 11:00、FAILED 09:00、SUCCESS 10:00、SUCCESS 08:00`。每个状态组内部确实按时间倒序，但跨状态并不是全局的 `11:00、10:00、09:00、08:00`。即使数据库反向扫描，只会改变整组和组内方向，也不能把两个状态的时间自然交错起来。因此未限定 status 的查询通常还需要额外排序，不能因为索引里出现了 `create_time DESC` 就认为已满足 `ORDER BY`。

是否保留 status 取决于实际查询组合，而不是字段看起来有用。若大多数高流量页面都固定一个状态，例如频繁查看某知识库的 `RUNNING` 或 `FAILED` 文档，并且每个状态能显著缩小行数，这个位置有价值：等值前缀一直延伸到 status，随后可直接按时间取前 N 条。若主流请求是不选状态的“全部文档”时间流，status 放在时间前会阻断全局排序；更合适的主索引可能是 `(kb_id, deleted, create_time DESC, id)`，状态筛选再接受过滤、使用另一条经过收益证明的索引，或为少数活跃状态建部分索引。

最终要统计两类查询的调用占比、各状态行数和倾斜、分页深度、返回量及写入频率，并分别看实际计划中的扫描行数和排序代价。只有当带状态查询的累计收益超过额外索引的写放大、缓存和维护成本，才同时保留两条；否则优先服务占比和延迟更关键的查询。示例只解释 B-tree 的顺序性质，不代表当前数据分布已经证明某一方案更优。

**反查：**[PostgreSQL 表结构与现有索引](../../../resources/database/schema_pg.sql)、[KnowledgeDocumentScheduleJob.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/KnowledgeDocumentScheduleJob.java)、[PostgreSQL 多列索引](https://www.postgresql.org/docs/current/indexes-multicolumn.html)

### Q057｜P1｜PostgreSQL 的 MVCC 怎样支持读写并发？

**参考回答：**多版本并发控制（MVCC）让更新产生新的行版本，并用事务可见性规则决定某个快照能看到哪一版。普通查询读取符合自己快照的已提交版本，写事务生成新版本，因此读通常不会阻塞写，写也通常不会阻塞普通读；真正修改同一行、显式加锁或约束冲突时仍会等待。隔离级别决定快照多久更新，锁则处理 MVCC 不能单独维护的写写冲突，二者要一起理解。

**追问①：** Read Committed 下同一事务两次查询一定看到相同数据吗？

**追问①回答：**不一定。PostgreSQL 的 Read Committed 在每条命令开始时获取快照，因此同一事务第一条 `SELECT` 后，另一个事务提交了新增或更新，第二条相同 `SELECT` 可能看到不同结果，也就是允许不可重复读和幻读。若业务必须基于同一视图连续计算，可用 Repeatable Read，并准备处理并发更新/序列化异常；若还需排除写偏差，则考虑 Serializable 和重试。

**追问②：** 长事务和大量逻辑删除会怎样影响清理、索引与查询性能？

**追问②回答：**长事务长期保留旧快照，会让 `VACUUM` 暂时不能移除它仍可能看见的死元组。逻辑删除本质是 `UPDATE`，既留下旧版本又保留一条 deleted 行；持续累积会膨胀堆和索引、增加扫描与缓存压力，还可能拖慢自动清理。应缩短事务，监控 `pg_stat_activity`、死元组与 autovacuum，按保留期归档或物理清理历史逻辑删除行，并用实际执行计划验证影响。

**反查：**[PostgreSQL MVCC 简介](https://www.postgresql.org/docs/current/mvcc-intro.html)、[PostgreSQL 事务隔离](https://www.postgresql.org/docs/current/transaction-iso.html)、[PostgreSQL 日常 VACUUM](https://www.postgresql.org/docs/current/routine-vacuuming.html)

### Q058｜P0｜“先查不存在，再插入”为什么不等于并发幂等？

**参考回答：**因为检查和插入是两个时间点。两个并发请求都可在对方提交前查到“不存在”，随后各自插入；Java 的 `if`、普通事务乃至 Read Committed 都没有把业务键上的竞争串成一个原子动作。当前候选任务草案会先按“来源消息、文档、用户”查已有项再构建插入，数据库同时用活动行部分唯一索引兜底，但 `createDraft` 目前没有实现冲突后的回读。正确心智是：前置查询用于快速返回与友好提示，唯一约束才是最终并发裁判，服务还需把冲突转换成幂等成功而非随机 500。

**追问①：** 两个任务草案请求怎样同时通过前置查询？

**追问①回答：**例如请求 A、B 同时调用 `createDraft`：A 查无结果后被调度暂停，B 也查无结果；两者都完成模型/证据校验并执行 `INSERT`。没有约束就产生两条草案；有唯一索引时通常一个提交，另一个在约束检查时等待后失败。前置查询缩短常见路径，却无法封住查完到插入前的竞态窗口。

**追问②：** 唯一约束、冲突后回读和幂等键各解决什么问题？

**追问②回答：**唯一约束保证同一业务键最多存在一条活动记录；幂等键则显式定义哪些重试属于同一操作。实现稳定回读时，可用 `INSERT ... ON CONFLICT DO NOTHING` 后查询赢家，或等唯一冲突所在事务回滚后在新事务回读；不能在 PostgreSQL 已因约束异常进入失败状态的原事务里直接 catch 再查。Repeatable Read 下还要按整个事务重试。无论哪种写法，都要明确活动行的冲突范围和已有结果的返回语义。

**反查：**[IronOreTaskTemplateService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/service/IronOreTaskTemplateService.java)、[任务草案部分唯一索引](../../../resources/database/upgrades/v1.1.0/260812_iron_ore_demo.sql)、[PostgreSQL INSERT 与 ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)

### Q059｜P1｜metadata 为什么使用 JSONB，什么字段更适合独立列？

**参考回答：**JSONB 适合不同解析器产生、字段集合会演进且大多随证据展示的扩展元数据，例如章节路径、页码、工作表、单元格范围。PostgreSQL 会校验 JSON，并以可处理的二进制形式保存，支持路径操作符和 GIN/表达式索引。独立列更适合每条必有、类型与约束稳定、频繁过滤/关联/排序的字段，因为它们更容易做 `NOT NULL`、外键、唯一约束和精确统计；JSONB 是弹性边界，不应变成逃避建模的万能袋子。

**追问①：** 文档 ID、版本、工作表和扩展字段如何选择？

**追问①回答：**文档 ID 是切片归属和删除条件，应该是独立列；当前关系切片就是 `doc_id` 列，而向量表把它放在 metadata 中，删除只能走 `metadata->>'doc_id'`，若成为高频路径可考虑提升为列。文档版本若参与版本选择、唯一性或排序也应列化。工作表和单元格范围主要用于来源展示、只在部分格式出现，保留 JSONB 较合适；新增解析器的可选字段先放 JSONB，稳定成核心查询维度后再迁移。

**追问②：** 对 JSONB 路径过滤很慢时，有哪些索引与建模选项？

**追问②回答：**先看具体谓词：包含查询可用合适操作符类的 GIN；固定路径等值或范围查询可建表达式索引，例如对 `(metadata->>'doc_id')` 建 B-tree，并把文本显式转换成正确数值/时间类型。若该路径高频、必须非空或与别表关联，生成列或正式独立列通常更清晰；再用 `EXPLAIN (ANALYZE, BUFFERS)` 验证选择性。当前 schema 有整列 JSONB GIN，但不代表所有 `->>` 谓词都会自动高效。

**反查：**[PostgreSQL 向量表结构](../../../resources/database/schema_pg.sql)、[PgVectorStoreService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)、[PostgreSQL JSON 类型与索引](https://www.postgresql.org/docs/current/datatype-json.html)

### Q060｜P0｜连接池为什么会让 `SET hnsw.ef_search` 后的查询未必用到该参数？

**参考回答：**PostgreSQL 的普通 `SET` 修改当前数据库会话，也就是当前物理连接的参数；连接池借出和归还的正是这些会话。当前 `PgVectorRetrieverService` 连续调用两次 `jdbcTemplate.execute(SET ...)` 再 `jdbcTemplate.query(...)`，方法没有事务来绑定连接，因此三次独立操作都可能各自从池中取连接，查询不一定落在执行过 `SET` 的那条连接上。即使碰巧同连接，session 级参数归还池后还可能影响下一位请求，所以“源码写了 SET”不能证明执行计划使用了它。

**追问①：** 连续三次 JdbcTemplate 调用是否保证同一物理连接？

**追问①回答：**不保证。代码顺序只保证 Java 调用先后，不保证连接身份；通常只有 Spring 事务同步把一个数据源连接绑定到当前线程，或显式在一次 `DataSource`/`JdbcTemplate.execute(ConnectionCallback)` 回调中复用同一 `Connection`，才能证明语句在同一会话。验证时可临时查询 `pg_backend_pid()`、`current_setting(...)`，并用 `EXPLAIN` 核对索引和实际计划。

**追问②：** 用事务、`SET LOCAL` 或同连接回调时，如何避免参数泄漏到下一个请求？

**追问②回答：**推荐在一个只读、短事务中先执行 `SET LOCAL hnsw.ef_search=...`、`SET LOCAL hnsw.iterative_scan=...`，再执行向量 SQL；事务保证同连接，`LOCAL` 在提交或回滚时自动失效。也可用同连接回调并在 `finally` 执行 `RESET`，但异常路径更容易泄漏。注意 `SET LOCAL` 在事务块外没有有效作用，所以“换两个词”仍不够，必须同时建立同一连接和明确事务边界。

**反查：**[PgVectorRetrieverService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java)、[PGVector 检索连接边界](../flow-notes/05-retrieval-and-context.md)、[PostgreSQL SET](https://www.postgresql.org/docs/current/sql-set.html)

### Q061｜P1｜批量插入、分页和回表查询如何避免性能问题？

**参考回答：**核心是减少网络往返和无界工作量。插入切片、向量时按批使用预编译语句和 `batchUpdate`，把一次事务控制在可恢复范围；列表查询必须有稳定排序和页大小上限。检索后的回表先收集、去重 ID，再用 `IN`/`selectByIds` 批量取切片和文档，最后在内存按 ID 映射回原候选顺序。当前 `ChunkMetadataResolver` 正是先批量查切片、再批量查文档，避免每个候选各发 SQL；`PgVectorStoreService` 也批量写向量。

**追问①：** 元数据增强逐片查库为什么会形成 N+1 查询？

**追问①回答：**若 40 个候选循环执行“查 chunk，再查 document”，就会从一次候选查询膨胀成最多 81 次数据库往返，这就是 N+1。即使单条 SQL 很快，连接获取、解析、网络时延也会累加。解决方法是批量收集 chunkId，批查后再收集不同 docId 二次批查；还可让原查询连接必要表一次取齐，但要避免连接导致重复行和大字段被反复搬运。

**追问②：** 深分页何时改用游标，批次过大又会带来什么代价？

**追问②回答：**页码很深时，`OFFSET` 跳过的行仍要在服务端计算，应改用基于稳定复合排序键的游标，例如 `(create_time,id) < (?,?)` 再 `LIMIT`；游标需携带全部排序键并保证唯一顺序。批次也不能无限大：会扩大 SQL 参数、请求包、堆中对象、事务/WAL、锁持有时间和失败重试范围。批大小应按行宽、数据库参数限制和压测调整，并在每批间保留幂等边界。

**反查：**[ChunkMetadataResolver.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/ChunkMetadataResolver.java)、[PgVectorStoreService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)、[PostgreSQL LIMIT 与 OFFSET](https://www.postgresql.org/docs/current/queries-limit.html)

### Q062｜P0｜术语映射和意图树采用缓存旁路模式，更新时怎样保持一致？

**参考回答：**缓存旁路（cache-aside）的读路径是先查 Redis，未命中再查 PostgreSQL并回填；写路径以数据库为真相源，常用“先更新数据库，再删除缓存”。当前术语映射和意图树都是读未命中后整表/整树加载并缓存 7 天，管理服务增删改成功后删除固定 key。先写库可避免先删缓存后写库失败时，其他读者把数据库旧值重新装回；删除而非直接写缓存，也减少同时维护两种对象表示和提交顺序的复杂度。

**追问①：** 为什么常见做法是先更新数据库再删除缓存？

**追问①回答：**若更新有事务，缓存删除最好注册在数据库提交成功之后：提交失败就不应让缓存代表一个并不存在的新状态。删除失败也不能静默当成一致；当前缓存管理器会记录 Redis 异常但不让数据库写失败，意味着旧值可能持续到 7 天 TTL，因此至少要有失败指标、重试/失效事件，并为这类配置数据设置可接受的陈旧上限。

**追问②：** 并发读写仍可能留下旧缓存吗，怎样按业务容忍度处理？

**追问②回答：**仍可能出现旧缓存：读者 R 先 miss 并从数据库读到旧值，写者 W 随后更新数据库并删 key，最后 R 才把旧值写回。低频管理配置可用较短 TTL 加延迟二次删除降低窗口；要求更强时，可给数据加单调版本并只发布最新版本 key、用事务后 Outbox 广播失效、或用互斥重建加写入前版本复核。选择取决于是否允许数秒陈旧，不能把 cache-aside 宣称成强一致。

**反查：**[QueryTermMappingCacheManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingCacheManager.java)、[IntentTreeCacheManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeCacheManager.java)、[Redis 官方 cache-aside 教程](https://redis.io/learn/howtos/solutions/microservices/caching)

### Q063｜P1｜缓存穿透、击穿和雪崩有什么区别？

**参考回答：**缓存穿透是反复查询本来就不存在的数据，每次都绕到数据库；缓存击穿是一个极热 key 失效，大量请求同时重建它；缓存雪崩是许多 key 在相近时间失效，或 Redis 整体不可用，流量成片落向数据库。三者都表现为回源增多，但触发范围不同，监控时应同时看 key 命中分布、miss 后数据库结果、同一 key 并发和 Redis 错误，而不能只看总命中率。

**追问①：** 热门意图树过期与大量不存在文档 ID 查询分别属于哪类？

**追问①回答：**热门意图树只有一个全局 key，7 天 TTL 到点后大量分类请求同时查库并回填，属于热点击穿；大量随机、无效文档 ID 每次 miss 后数据库也查不到，属于穿透。若大量缓存被部署时一起写入并用相同 TTL，集中到期是雪崩；若 Redis 故障让所有 key 同时失效，也按雪崩的系统性后果处理。

**追问②：** 缓存空值、随机过期、互斥重建各有什么边界？

**追问②回答：**缓存空值能挡住确实不存在的 key，但 TTL 要短，并且对象新建时要主动失效，权限敏感结果不能跨用户误共享；布隆过滤器可提前挡大多数不存在项，却有假阳性和删除维护成本。随机化过期只能打散集中到期，解决不了节点故障。互斥重建让一个请求回源，其他请求等待或读短期旧值，但要处理锁过期、重建失败和等待上限，并在拿锁后再次检查缓存。

**反查：**[IntentTreeCacheManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeCacheManager.java)、[QueryTermMappingService.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java)、[Redis 官方 cache-aside 教程](https://redis.io/learn/howtos/solutions/microservices/caching)

### Q064｜P0｜Redis 分布式锁为何需要过期时间、持有者标识和原子释放？

**参考回答：**过期时间解决持锁进程崩溃后 key 永不释放的问题；唯一持有者标识证明“这把锁还是我取得的”；原子释放把“值仍等于我的 token”与删除合成一步。否则客户端 A 读到自己的值后锁恰好过期，B 获得新锁，A 再 `DEL` 就会删掉 B 的锁。单 Redis 实例的常见获取是 `SET key token NX PX ttl`，释放用比较 token 后删除的 Lua/条件命令。锁保护一个资源的互斥；信号量允许固定数量持有者，保护的是总并发槽位，不能混称一把锁。

**追问①：** 线程暂停超过租期后恢复，怎样避免覆盖新执行者结果？

**追问①回答：**线程 A 若停顿超过租期，B 已获得锁并开始执行；A 恢复后即使因为 token 不同无法删除 B 的锁，仍可能继续写业务结果。要保护外部资源，应由锁服务发放单调递增的执行代次，也就是 fencing token，把代次随写请求带到数据库或下游，由资源端拒绝小于已见最大代次的旧持有者写入；只有所有关键写都校验它，才能挡住过期执行者。

**追问②：** 续租与 fencing token（执行代次校验）分别解决什么问题？

**追问②回答：**续租是在持有者仍健康时延长租期，减少正常长任务中途过期；网络分区、长暂停或续租线程失效时仍可能丢锁。fencing token 不延长锁，而是让下游识别谁更新；它解决旧执行者恢复后的安全性。实践中还要限定等待时间、让业务操作幂等，并确认使用的 Redisson API究竟是看门狗自动续租还是显式租期，不能把客户端锁等同数据库最终写入权。

**反查：**[IdempotentSubmitAspect.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/idempotent/IdempotentSubmitAspect.java)、[Redis 官方分布式锁说明](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)

### Q065｜P0｜有序集合、信号量和 Lua 怎样实现跨实例排队限流？

**参考回答：**当前公平限流器先用全局递增序号作为分数，把 requestId 放入 Redis 有序集合形成跨实例队列；每项另有带 TTL 的存活 key，实例崩溃后可识别僵尸。抢占时 Lua 在 Redis 内一次完成“清理队头僵尸、判断请求是否处在可用许可对应的队头窗口、移出队列”，避免多个实例用分开的查询和删除同时认领。随后用可过期信号量获取 permit，限制已获准任务数；Pub/Sub 只负责加速唤醒，本地定时轮询仍兜底。队列项和 permit 都会过期，但含义分别是等待者存活与并发槽位租期。

**追问①：** 按请求数限速和按在途任务数限并发有什么区别？

**追问①回答：**按请求数限速约束一段时间内进入多少次，例如每秒 10 次，即使请求立刻完成也计数；按在途任务数限并发约束某时刻同时占用资源多少个，完成后归还 permit。模型调用时长差异大，后者更接近保护连接、线程或供应商并发；两者可叠加，分别挡持续速率和慢请求堆积。

**追问②：** 如果许可在首包后就释放，配置并发 10 是否覆盖完整模型生成？

**追问②回答：**不能。`FairDistributedRateLimiter` 在 `onAcquired().run()` 返回的 `finally` 释放许可；当前 Runnable 会完成历史、改写、检索并等待模型首个有效包，拿到取消句柄并绑定后即返回，而后续 token 在模型流线程继续产生。所以配置 10 主要覆盖前置编排与首包阶段，不是完整生成并发 10。若目标是限制完整生成，应把 permit 的幂等释放绑定到完成、错误、超时和取消终态，并配套足够租期或续租。

#### 深挖追问 Q065-D01

**评审依据：**主回答分别描述 Lua 出队与随后获取许可，尚未说明这两个阶段之间的失败窗口。

**问题：**仅按回答中的“先 Lua 出队、后获取 permit（许可）”顺序，假设请求已出队却未拿到许可，或者实例在两步之间崩溃，会留下哪些状态？若设计恢复机制，应如何区分重新排队、放弃请求与回收槽位，并界定这里的公平性保证？

**深挖回答：**只按题目给出的两步推演，Lua 已把请求 R 从有序集合移除时，R 就不再是队列成员；随后 permit 获取失败，表示它也没有成为槽位持有者。此时可能出现“既不在队列、也没有许可”的中间状态。若实例恰在两步之间崩溃，队列里看不到 R，等待者存活 key 可能还会留到 TTL，但仅凭它不能知道 R 应继续、已取消还是已执行。现有叙述没有证明已经为这个窗口做了补偿，所以不能断言系统必然自动重排。也不能调用 release 来“补偿”：R 根本没拿到 permit，盲目释放可能把别人的槽位归还或把计数放大。

改进时可为请求保存显式状态和所有者令牌，例如 `QUEUED → CLAIMED → PERMIT_HELD → RUNNING → DONE`，其中 CLAIMED 带短租约。出队后没拿到许可，如果客户端仍存活、取消标记不存在且截止时间未到，就用原始序号或明确的重排规则把它原子地转回 QUEUED；若客户端已断开、已取消或超时，则转为 `CANCELLED/EXPIRED` 并放弃，不能再次执行。拿到 permit 后要记录唯一 owner token 和租约；只有同一 token 才能续租或幂等释放。实例崩溃时由租约到期回收真正已占用的槽位，恢复器再依据任务是否可安全重试决定重排或终止。对于“已出队但未持有许可”的 R，只恢复队列状态，不回收槽位；对于确已持有且租约过期的请求，才处理槽位回收。

更稳的方案是让“确认队头、检查可用槽位、授予带 owner 的许可、移出队列”在同一个 Redis 原子脚本或状态机提交中完成，从根上缩短窗口。即便如此，`ZSET + 独立 semaphore` 也不能自动证明严格 FIFO：网络延迟、失败重排、租约过期及两步间抢占都可能让后来的请求先拿到许可。现有机制最多支持“按序号决定当次可出队窗口”的公平意图。若产品要求严格 FIFO，就必须定义崩溃重排的位置，并验证许可授予顺序；通常还要接受队头慢请求可能阻塞后续请求的代价。

**反查：**[FairDistributedRateLimiter.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ratelimit/FairDistributedRateLimiter.java)、[聊天排队与许可边界](../flow-notes/06-chat-stream-and-cancel.md#22-全局队列决定何时进入编排或怎样拒绝)

### Q066｜P1｜Redis Pub/Sub 为什么不能作为可靠取消命令的唯一载体？

**参考回答：**Redis Pub/Sub 是至多一次的瞬时广播：消息只推给当时在线且正常处理的订阅者，不持久化、没有确认和重放。订阅实例断线时取消会永久错过；取消先到、任务稍后才在目标 JVM 登记，本地 map 也找不到句柄。当前实现因此先写一个 TTL 30 分钟的取消标记，再发布 taskId；各实例用 Pub/Sub 尽快取消本机句柄，任务登记和句柄迟到绑定时再查本地状态/Redis 标记。它封住短时先后竞态，但仍是尽力同步，且本机句柄不能跨实例直接共享。

**追问①：** 订阅者离线、消息先到任务后登记时各会怎样？

**追问①回答：**订阅者离线会丢广播，恢复后不会补收；消息先到任务后登记时，监听器的 `tasks.getIfPresent` 为空就返回。短期 key 让后登记者看见“这个 task 已取消”，也给跨实例请求一个共享事实；TTL 则避免取消墓碑无限增长。运行超过 TTL、网络分区中的既有任务以及删除标记失败等边界，还需由模型超时、连接回调和状态条件更新兜底。

**追问②：** Pub/Sub 配合短期键与使用 Redis Stream 各适合什么需求？

**追问②回答：**“Pub/Sub + 短期 key”适合低延迟、短生命周期、允许尽力执行的控制信号；key 是状态，广播是唤醒提示。若要求每条取消可审计、离线恢复、按消费者确认和重放，可用 Redis Stream 保存带 ID 的命令并用消费组确认；代价是积压、重试、trim、幂等与毒消息治理。即使用 Stream，也仍要校验任务归属并把命令转换为目标实例可执行的取消句柄。

#### 深挖追问 Q066-D01

**评审依据：**追问②提出消费组保存可靠取消命令，但没有解释命令怎样到达持有本机句柄的实例。

**问题：**假设任务句柄只在实例 A，取消命令却被同一 Redis Stream（消息流）消费组中的实例 B 取得。消费组的分发是否等于广播？请提出一种让命令到达 A 的路由或状态协调设计，并说明 B 在什么条件下才可以确认这条命令。

**深挖回答：**不等于广播。同一 Stream 消费组是竞争消费：一条消息通常只分配给组内一个消费者，B 取得后，A 不会再自动收到同一条命令。由于模型取消句柄只存在 A 的内存，B 既不能在本机调用它，也不能因为 `get(taskId)` 为空就把消息确认掉；那样会把“路由到了错误实例”误写成“取消已处理”。

一种改进设计是任务注册时持久化 `(taskId, generation, ownerInstance=A, ownerLease)`，停止入口先校验用户归属，再持久化带命令 ID 的 `CANCEL_REQUESTED`。命令生产者据 owner 把消息写到 A 专属的可靠队列，A 只消费发给自己的命令，按 `(taskId,generation,commandId)` 幂等执行本机取消并记录结果。若消息已经先被公共组的 B 取到，B 只能充当路由器：重新读取当前 owner，把命令可靠地写入 A 的队列并记录已转交。B 只有在“面向投递”的协议下确认这条公共消息——即转交记录和 A 队列写入已经持久化成功；此时只能说已交付，不能说模型已停止。若这条消息的确认语义定义为“执行完成”，则还必须等 A 持久化 `CANCELLED`、`ALREADY_FINISHED` 等终态后才确认，不能只凭找不到句柄确认。

还要处理 A 宕机：ownerLease 过期后，协调器把任务保持为 `CANCEL_REQUESTED`，禁止旧代迟到注册或继续写成功；若任务可迁移，新 owner 注册时先读取消意图并立即终止。已经在 A 内存中发出的远端流无法凭 Redis 重建句柄，只能依靠连接断开、供应商超时、回调和最终状态对账收敛。系统可以保证自己的任务状态最终不再当作正常完成，但不能据此承诺供应商端已立刻停止计算或停止计费。路由、确认、幂等和 owner 租约都属于改进设计，不能从“换成 Stream”本身推导出来。

**反查：**[StreamTaskManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java)、[聊天停止跨实例边界](../flow-notes/06-chat-stream-and-cancel.md)、[Redis Pub/Sub 投递语义](https://redis.io/docs/latest/develop/pubsub/)

### Q067｜P0｜RocketMQ 至少一次投递意味着消费者要承担什么责任？

**参考回答：**至少一次表示 Broker 会努力让消息被处理，但同一消息可能再次交给消费者，因此消费端必须把重复当正常输入。典型窗口是业务已经提交，消费者返回成功的确认却丢失或进程在确认前崩溃，Broker 只能重投。消费者应使用稳定 eventId/业务键、唯一约束或带旧状态的条件更新先认领执行权，让数据库修改幂等，并把不可重复的外部调用单独防重；确认只能在所需副作用完成后发生。

**追问①：** 消费成功但确认丢失时为什么可能重复？

**追问①回答：**第一次消费可能已完成关系表替换，随后网络断开，Broker 没观察到成功便再次投递。第二次不能靠“我大概做过”猜测，应查消费记录或业务执行代次决定跳过、继续或补偿。当前 `KnowledgeDocumentChunkConsumer` 直接调用 `executeChunk`，没有在入口用消息 ID 建完成记录；而 `runChunkTask` 主体异常会写 `FAILED` 后正常返回，所以该业务失败通常也不会自动进入 MQ 重试。

**追问②：** 整文替换结果相似是否就等于完整幂等，模型费用和并发旧任务如何处理？

**追问②回答：**整文删除再插入即使最终内容相似，也不等于完整幂等：重复 Embedding 会产生费用，并发旧任务可能在新版本之后写回旧切片、覆盖状态或增加日志。应给每次摄取生成 runId/文档版本，数据库原子认领指定代次，只允许当前代次提交索引和终态；向量写入用稳定 chunkId/upsert。外部模型若已接收、本地却在记成功前宕机，仍有不确定窗口；provider 不提供幂等键或结果查询时，本地防重不能保证零重复调用或计费。数据库条件 `UPDATE` 只保护那一行迁移，不能宣称消费端恰好一次。

**反查：**[KnowledgeDocumentChunkConsumer.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java)、[文档消费失败与重复边界](../flow-notes/02-document-lifecycle.md)、[RocketMQ 4.x Push Consumer 与重试](https://rocketmq.apache.org/docs/4.x/consumer/02push/)

### Q068｜P0｜如果不使用事务消息，怎样用 Outbox（本地待发送表）衔接数据库与 MQ？

**参考回答：**本地待发送表（Outbox）的做法是：在同一个 PostgreSQL 本地事务里更新文档状态，并插入一行待发事件，包含唯一 eventId、业务键、类型、载荷、状态和创建时间；事务提交后，轮询器或变更数据捕获再把未发送事件投递 RocketMQ。这样应用不会出现“数据库提交了但消息根本没有可恢复记录”的空窗。它提供可靠交接和最终一致性，不把数据库与 Broker 变成一个原子提交，也不消除消费重复。

**追问①：** 业务记录和待发事件怎样原子提交？

**追问①回答：**若业务更新或 outbox 插入任一步失败，整个本地事务回滚，事件与新状态都不可见；提交成功后，即使服务立即宕机，后台发布器仍可扫描。多发布器可用状态条件更新或 `FOR UPDATE SKIP LOCKED` 租约认领，发送时以 eventId 作为消息 key，成功后再把 outbox 标为 sent，并保存尝试次数和最近错误。

**追问②：** 发送成功但标记失败时如何处理重复，如何监控积压？

**追问②回答：**若消息已被 Broker 接收，而发布器在标记 sent 前崩溃，它会再次发送；因此消费端仍须按 eventId/业务键幂等，不能依赖 outbox 获得恰好一次。应监控未发送数量、最老事件年龄、重试次数和持续失败率，超过阈值转人工或隔离。当前项目使用 RocketMQ 事务消息把 RUNNING 本地事务与消息可见性衔接；Outbox 是可选替代设计，不是当前已实现链路。

**反查：**[当前文档事务消息链](../flow-notes/02-document-lifecycle.md)、[RocketMQ 事务消息工作机制](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)

### Q069｜P1｜文档消费积压时，你先看什么，怎样扩容？

**参考回答：**先定位积压在哪一段，而不是先加线程：看消费组每个队列的 lag、最老消息年龄、在途/重试/死信数量和消费成功率，再把单条任务拆成下载、解析/OCR、切片、Embedding、索引写入的耗时与错误。随后对齐消费者线程活跃度/队列、CPU、堆和 GC、数据库连接池、对象存储带宽、模型并发配额及 429。只有确认容量瓶颈和可并行边界后，才增加实例或线程，并用吞吐、失败率和下游饱和度验证。

**追问①：** 分区/队列数量、消费线程、模型限额哪一个可能是瓶颈？

**追问①回答：**消息队列数量限制消费组内可独立分配给实例的并行单元，实例多于可分配队列时继续扩实例收益很小；单实例消费线程能提高并发，但最终受解析 CPU/内存、数据库连接和 Embedding 服务的较小容量约束。若模型限额已经打满，加消费者只会制造更多 429、重试和内存占用。扩容时要一起调整队列数、实例数、线程数和下游配额，不能只改一项。

**追问②：** 永久格式错误与临时限流如何分类重试，何时进入死信或人工处理？

**追问②回答：**不支持的格式、损坏文件和稳定校验失败属于永久错误，应一次标记 `FAILED`，保存原因并进入死信/人工处理；网络抖动、数据库短超时和供应商临时限流可有限次数退避重试，尊重 `Retry-After` 并加随机抖动。RocketMQ 官方也不建议用消费失败充当常态限流。当前 `runChunkTask` 把主体异常捕获后正常返回，要让临时错误自动重投，必须先做错误分类并让可重试异常越过消费边界，或显式发布延迟重试事件。

**反查：**[KnowledgeDocumentServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)、[ThreadPoolExecutorConfig.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java)、[RocketMQ 消费重试策略](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)

### Q070｜P0｜SSE、WebSocket 与轮询有什么区别，为什么聊天使用 SSE？

**参考回答：**服务器发送事件（SSE）是在一个 HTTP 响应中持续发送带 `event/data/id/retry` 字段的文本事件，主要是服务端到浏览器单向推送；WebSocket 建立双向消息通道，适合双方都高频发事件；轮询则反复建立请求询问新结果，简单但有额外延迟和请求开销。聊天的主要数据方向是服务端连续吐 token，SSE 能复用 HTTP 鉴权、代理和事件语义，复杂度较低。当前项目是 `GET /rag/v3/chat` 建流，并非原生 EventSource 自动管理的一切能力。

**追问①：** 单向推送与用户停止请求怎样配合？

**追问①回答：**单向不代表用户不能停止：流连接只负责服务端推回答，前端拿到 `meta.taskId` 后另发 `POST /rag/v3/stop`。服务端用 Redis 短期标记和 Pub/Sub 找到持有模型句柄的实例，再尽力调用 `Call.cancel()` 并完成 SSE。停止接口是独立控制请求，还必须校验 task 归属；返回成功也不等于远端模型已确定停止推理或计费。

**追问②：** 携带 Authorization 时，原生 EventSource 和 fetch 读取流有哪些差异？

**追问②回答：**浏览器原生 `EventSource` 构造参数主要是 URL 和是否携带凭据，请求使用 GET，不能任意设置 `Authorization` 头；适合 cookie 鉴权。当前前端用 `fetch` 发 GET，可自定义 `Authorization`、使用 `AbortController`，再手工读取并解析响应流与重试。它的重试会重新发聊天请求，当前协议没有服务端事件 ID/`Last-Event-ID` 续传，因此重连不等于从断点续传，还要防止创建重复任务和消息。

**反查：**[聊天流与停止流程](../flow-notes/06-chat-stream-and-cancel.md)、[useStreamResponse.ts](../../../frontend/src/hooks/useStreamResponse.ts)、[WHATWG Server-sent events](https://html.spec.whatwg.org/dev/server-sent-events.html)

### Q071｜P0｜生产代理后 SSE 迟迟不显示、最后一次性吐出全文，你如何排查？

**参考回答：**我会给“模型收到首包、应用调用 `SseEmitter.send`、客户端读到事件”分别打时间点，并用同一请求先直连应用、再经过代理执行 `curl -N`。若直连也慢，查排队、检索和模型首包；若应用早已连续发送、直连正常而代理出口成批出现，重点查代理/CDN/网关缓冲。响应必须保持 `Content-Type: text/event-stream`，Nginx 路径通常关闭 `proxy_buffering`，也可让上游按约定返回 `X-Accel-Buffering: no`；同时排查应用、代理或 CDN 的压缩与客户端自身缓冲。

**追问①：** 缓冲、压缩、flush、空闲超时分别可能影响什么？

**追问①回答：**缓冲会攒到缓冲区满或响应结束才下发，最符合“一次性吐全文”；压缩器也可能等待足够数据才产出压缩块。应用每个完整 SSE 事件要以空行结束并及时交给响应输出，当前 `SseEmitterSender` 每块调用一次 `emitter.send`。`proxy_read_timeout` 约束的是两次上游读取之间的空闲时长，并非整个响应总时长；模型可能长时间只思考或无 token 时，可增加 SSE 注释心跳并让各层空闲超时大于心跳间隔。

**追问②：** 如何区分模型首包慢与代理把已产生的事件攒住？

**追问②回答：**比较三组时间即可：provider 首个有效包晚，说明模型或其前置链慢；provider/应用日志早、客户端经代理晚，说明事件已产生但传输层积压；直连和代理都早而页面晚，则查前端读取/渲染。当前 Trace 的 `user-first-packet` 从 Pipeline 开始到第一个正文回调，包含改写、意图、检索和模型首包，但不含获准前的全局排队，也不能单独证明代理到浏览器的耗时，所以还需入口、队列和出口时间戳。

**反查：**[SseEmitterSender.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/web/SseEmitterSender.java)、[聊天首包与流事件](../flow-notes/06-chat-stream-and-cancel.md)、[Nginx proxy_buffering 文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)

### Q072｜P0｜浏览器断开、SSE 超时、用户停止和模型报错为什么不能用同一结果解释？

**参考回答：**它们来自不同控制面，也有不同事实含义。浏览器断开表示传输连接没了；SSE 超时表示外层连接生命周期到期；用户停止是明确业务命令；模型报错才表示生成依赖失败。当前连接 completion/timeout/error 回调主要把 sender 置为 closed，并撤销仍在排队的 ticket；任务一旦获准，它们不会自动调用 `StreamTaskManager.cancel`。用户 stop 才写 Redis 标记、广播、尽力取消本机 provider 句柄并写 Trace 取消；模型错误则走 handler 的 error 收尾。

**追问①：** 哪些路径保存部分回答，哪些可能让模型继续生成？

**追问①回答：**用户 stop 时，若已有非空正文，当前会保存 `INTERRUPTED` 助手消息和已累计内容；还没有正文则可能不建助手消息。模型报错不会保存已累计的部分 answer，只注销任务并异常关闭 SSE。浏览器断开或 5 分钟 SSE 超时后，已获准的模型可能继续生成；sender 不再发送，但若最终正常回调，handler 仍可能保存完整 `NORMAL` 消息并把 Trace 收敛为成功。因此“前端没收到”不能推导“模型停了”或“数据库没保存”。

**追问②：** 如果要统一资源回收，怎样保证回调幂等和清理失败互不阻断？

**追问②回答：**可建立一个终态协调器，用原子状态从 RUNNING 迁移到 SUCCESS/CANCELLED/ERROR/DISCONNECTED/TIMEOUT，只有首次迁移者执行收尾；随后按策略取消 provider、保存部分消息及状态、更新 Trace、释放许可、删除本机任务/Redis 标记并关闭 emitter。每一步都要自身幂等，并用独立 `try/finally` 或安静清理隔离，让落库失败不阻断句柄取消，发送失败也不阻断释放；数据库仍用条件更新防止终态互相覆盖。

**反查：**[连接、超时与模型回调边界](../flow-notes/06-chat-stream-and-cancel.md#4-触发关系三连接和模型各自怎样回调)、[StreamChatEventHandler.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java)、[StreamTaskManager.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java)

### Q073｜P0｜为什么 Controller 上的防重复提交锁不能保证整段 SSE 期间用户只运行一个任务？

**参考回答：**AOP 锁保护的是被代理方法的同步执行区间。当前聊天 Controller 创建 `SseEmitter`、调用 service 把任务放入异步队列，然后立即返回 emitter；`IdempotentSubmitAspect` 的 `finally` 此时就解锁，而模型仍在后台检索和生成，所以第二个请求随后仍可进入。并且主 `application.yaml` 当前把 `ragent.eval.enabled=true`，切面会直接放行，连这段短锁也不执行；即使外部配置开启锁，它仍不覆盖流生命周期。

**追问①：** 返回 SseEmitter 后切面何时释放锁？

**追问①回答：**锁从 `joinPoint.proceed()` 前取得，到 Controller 方法返回 `SseEmitter` 时释放。HTTP 响应可继续由异步线程写数分钟，这不会延长 Java 方法栈或切面 `try/finally`。因此它最多抑制同一用户几乎同时进入的请求初始化，不能证明“一名用户或一个会话只有一个活动生成任务”。

**追问②：** 若要限制同会话一个活动任务，应把占用与释放绑定到什么生命周期？

**追问②回答：**若要求同会话单活动任务，应在共享存储用 `(userId,conversationId)` 占用一个 active 记录，value 保存 taskId/执行代次；在决定把排队也算活动时于入队前占用，否则在获准时占用。释放必须绑定模型完成、错误、用户取消、连接策略性终止和排队拒绝的统一终态，并用“value 仍为当前 taskId 才删除”的原子操作，避免旧任务回调删掉新任务占用；同时设置租期与恢复扫描，防实例崩溃留下永久占用。

**反查：**[RAGChatController.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java)、[IdempotentSubmitAspect.java](../../../framework/src/main/java/com/nageoffer/ai/ragent/framework/idempotent/IdempotentSubmitAspect.java)、[当前 SSE 锁边界](../flow-notes/06-chat-stream-and-cancel.md#idempotentsubmit-在这里实际管到哪里)

### Q074｜P1｜上传大文件或同时处理很多工作簿出现 OOM，你怎样定位？

**参考回答：**先确认 OOM 类型和进程边界：是 Java heap space、GC overhead、Metaspace、direct buffer、无法创建线程，还是容器被系统 OOM Kill。当前摄取会先 `readAllBytes` 保留整份文件 `byte[]`，XLSX 又由 POI `WorkbookFactory` 展开压缩 XML、共享字符串、单元格和图片对象；解析后还同时保留块正文、`embedding_text`、供应商返回的 `List<List<Float>>` 盒装向量以及复制出的 `float[]`。单文件峰值已可能远大于压缩文件，并发工作簿会近似叠加，批次越大保留越久。

**追问①：** 一次读成 byte[]、POI 对象、向量数组和并发批次各占什么内存？

**追问①回答：**我会按这些对象验证假设：`byte[]` 对应整文件和图片，POI 的 workbook/sheet/cell/shared-string 对象对应表格展开，字符串与块列表保留两份文本，`Float`/数组对应向量，HTTP JSON 和 JDBC 批参数又产生临时对象。修复方向是流式读取对象存储、对 XLSX采用 POI 事件/SAX 模式、限制解压后规模和图片、分批向量化后写入暂存区并释放内存，全部验证后再切换可见版本，避免半成品进入检索；同时用有界队列和并发门禁把峰值乘数压住。

**追问②：** 堆转储、GC 日志、线程栈各能帮助排查哪类问题？

**追问②回答：**GC 日志看分配速率、停顿和 Full GC 后存活堆是否持续上升；`jcmd <pid> GC.class_histogram` 看哪些类占量，堆转储再用支配树和到 GC Root 路径找是谁持有。线程栈不直接说明对象大小，但能看到同时有多少解析/向量任务、是否都卡在下游导致对象长期存活。Java 17 官方把 class histogram 和 heap dump 都标为高影响，大堆生产抓取可能触发 Full GC、停顿并占大量磁盘，应先用持续 GC/JFR 和受控复现，必要时再在有容量窗口抓 dump。

**反查：**[KnowledgeDocumentServiceImpl.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)、[ExcelDocumentParser.java](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/excel/ExcelDocumentParser.java)、[Java 17 jcmd 官方文档](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html)

### Q075｜P0｜线上 P95 突然变高，但 CPU 很低，你会按什么顺序排查？

**参考回答：**CPU 低通常提示请求在排队、等锁、等连接、等磁盘或等远端。我先确认影响时间、接口/实例/模型范围和发布变更，并把端到端延迟拆为入口排队、历史加载、改写、意图、召回、元数据回表、重排、模型首包、首包后完整生成及代理传输。再对最慢阶段查饱和度：线程池活跃/队列/拒绝，Hikari 活跃/等待/超时，Redis/HTTP 连接，供应商延迟和 429，PostgreSQL 活动会话、锁、`wait_event`、慢 SQL 与计划。最后只对已有证据的瓶颈扩容或降级。

**追问①：** 怎样拆开排队、改写、召回、重排、首包和完整生成耗时？

**追问①回答：**入口收到请求就记时间，获得分布式 permit 时结束“排队”；当前 Trace 是获准后才创建，`user-first-packet` 从 Pipeline 开始到首个正文，不能补出此前排队。Pipeline 内已有改写、意图、检索和模型节点，可再细分向量 Embedding/SQL、重排；provider 第一个有效包与用户第一个正文还要分开，因为思考包可能先到。模型流完成回调结束完整生成，再对照浏览器接收与服务端发送记录分析代理和网络耗时；跨端时钟未校准时不能直接相减，应使用时钟校准或同侧耗时测量。

**追问②：** 线程池等待、连接池耗尽、依赖限流与数据库慢查询如何区分？

**追问②回答：**线程池等待的特征是 active 到上限、队列和任务等待升高，而任务真正执行耗时可能正常；连接池耗尽则 Hikari pending/timeout 上升，线程栈停在取连接，数据库本身未必繁忙；依赖限流会出现 429、`Retry-After`、退避或供应商并发用满；数据库慢查询则 SQL 计时、`pg_stat_activity` 等待事件、锁或执行计划/缓冲读异常能对上。还要记住检索 `orTimeout` 不会停止底层 HTTP/SQL，迟到任务可能在表面超时后继续占资源。

**反查：**[检索分段与超时边界](../flow-notes/05-retrieval-and-context.md)、[聊天 Trace 与首包口径](../flow-notes/06-chat-stream-and-cancel.md)、[PostgreSQL 监控统计](https://www.postgresql.org/docs/current/monitoring-stats.html)

**深挖评审范围：**Q041—Q075；已逐项评审主回答与两项追问回答；追加6条深挖问题。
