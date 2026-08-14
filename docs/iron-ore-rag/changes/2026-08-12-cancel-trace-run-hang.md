# 取消后 trace run 悬挂修复

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | `2026-08-12` |
| 所属阶段 | 阶段 0 的后端稳定性修复，不改变工业文档 RAG 能力 |
| 状态 | 已实施并完成针对性验证；浏览器停止操作仍属于人工验收 |
| 原修复分支 | `fix/cancel-trace-run-hang` |
| Git 提交 | `24bd7f8`（原始修复）、`e0d0871`（竞态补强）、`c7e8da8`（合并到研究分支） |

## 改动目的与发现过程

用户在流式回答期间点击“停止生成”后，子 trace 节点已经是 `CANCELLED`，但 `t_rag_trace_run` 中对应的根记录仍可能永久停在 `RUNNING`，且 `end_time`、`duration_ms` 为空。该不一致是在检查 Trace 页面和本地数据库状态时发现的；它会让一次已经结束的请求看起来仍在执行，也会让 Dashboard 的成功率和错误率分母漏掉用户取消请求。

稳定复现需要覆盖的不只是“正常生成后点击停止”，还包括三个竞态窗口：停止请求早于本机任务注册、停止请求早于根 run 插入，以及取消与正常完成回调同时到达。跨实例路由或本机任务缓存已丢失时，也不能假设处理停止请求的实例持有原任务对象。

## 根因

1. 原取消收尾依赖 `StreamTaskManager` 的本机任务缓存；跨实例或缓存缺失时，停止请求找不到可收尾的任务对象。
2. Redis 取消标记、任务注册和 `StreamChatTraceRunner.startRun` 不是一个原子操作，存在“先取消、后注册”和“先取消、后插入 `RUNNING`”两个窗口。
3. provider 取消、取消消息持久化和 SSE 清理在同一条异常传播链上，任一环节抛错都可能阻断后续收尾。
4. 正常 `SUCCESS` / `ERROR` 回调可能晚于取消到达；如果终态更新没有状态前置条件，迟到回调会覆盖 `CANCELLED`。

## 方案取舍与选择理由

这不是给某一个回调补一条更新语句就能封住的问题。取消跨越 Redis、进程内任务、SSE、模型 provider 和数据库 trace，因此采用“多入口补偿、数据库幂等收口”的方案：

- 停止入口先写带 30 分钟 TTL 的 Redis 取消标记，并立即按 `taskId` 尝试收尾根 run；这样不依赖本机缓存，也能覆盖跨实例请求。
- 任务注册后和根 run 创建后分别检查取消状态，补偿两个先后顺序竞态。
- 所有根 run 终态更新都限定为 `RUNNING -> terminal`，由数据库条件更新保证先到终态获胜；不使用内存锁假装解决跨实例竞争。
- provider 取消、trace 上报和 SSE 完成分别捕获异常；trace 是旁路观测，失败不能反向破坏用户取消。
- 进程内用 CAS 保证取消完成事件只发送一次，避免注册线程与 Pub/Sub 线程重复完成同一 SSE。

## 实现与调用链

```text
停止接口
  -> StreamTaskManager.cancel(taskId)
  -> 写 Redis 取消标记
  -> RagTraceRecordService.cancelRunByTaskId(taskId)
  -> 发布 ragent:stream:cancel
  -> 各实例 StreamTaskManager.cancelLocal(taskId)
  -> 取消 provider handle，并发送 CANCEL + DONE 后完成 SSE

流式请求
  -> StreamChatTraceRunner.startRun(RUNNING)
  -> 再检查 taskManager.isCancelled(taskId)
  -> 已取消则 finishRun(CANCELLED)
  -> 否则正常 callback 尝试 SUCCESS / ERROR
  -> RagTraceRecordServiceImpl 仅更新仍为 RUNNING 的记录
```

主要代码位于：

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/trace/StreamChatTraceRunner.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/service/impl/DashboardServiceImpl.java`

Dashboard 性能摘要的成功率和错误率分母加入 `CANCELLED`；取消不是成功或错误，但它是一次真实请求，不能从总量中静默消失。

## 验证与效果

- 取消相关 4 个测试类共 15 项测试通过，覆盖提前取消、本机任务缺失、provider 取消异常、迟到成功回调和终态竞争。
- 排除一个与本改动无关的既有 Mockito 严格桩测试后，Bootstrap 离线宽范围测试运行 232 项：0 失败、0 错误、1 跳过。
- `./mvnw -o spotless:check` 通过。
- 本地数据库中唯一确认的历史悬挂根记录，已按其 `CANCELLED` 子节点时间精确补齐终态、`end_time` 和 `duration_ms`；修复后的检查中不再存在根 run 为 `RUNNING` 的该异常记录。

| 行为 | 修复前 | 修复后 |
| --- | --- | --- |
| 缓存缺失或跨实例取消 | 可能无法收尾根 run | 停止入口按 `taskId` 幂等收尾 |
| 取消早于注册或根记录插入 | 可能留下迟到的 `RUNNING` | 注册后、插入后各有一次补偿检查 |
| 取消与正常回调竞争 | 迟到回调可能覆盖取消 | 仅允许仍为 `RUNNING` 的记录进入首个终态 |
| provider / SSE 异常 | 可能中断 trace 收尾 | 各环节隔离异常，取消事件由 CAS 防重 |
| Dashboard 性能分母 | 忽略取消请求 | `SUCCESS + ERROR + CANCELLED` |

这些证据证明了竞态处理、数据库终态约束和历史异常记录修复；它们不等同于浏览器业务 E2E。页面验收仍需在后端重启后，于生成期间点击停止并确认根 trace 为 `CANCELLED`，且 `end_time`、`duration_ms` 非空。

## 限制与停止状态

- 本改动只保证已进入停止流程的请求能可靠收尾，不处理进程强杀、数据库不可用等没有机会执行补偿的故障。
- Redis 取消标记 TTL 为 30 分钟，用于覆盖流式任务生命周期，不是永久审计数据；最终状态仍以 PostgreSQL trace 为准。
- 阶段 0 已在这里停止扩展。若再次出现悬挂，应保存 task ID、trace ID、取消时间和服务实例日志，按新缺陷调查，不在本记录中放宽终态语义。

## 数据兼容与回滚

本修复不新增数据库列，只补充既有 `CANCELLED` 状态语义和 Dashboard 统计。现场只修复了一条已确认的历史记录，没有批量修改其他 trace。

研究分支上的修复通过 merge commit `c7e8da8` 引入。如后续提交没有依赖它，可执行：

```bash
git revert -m 1 c7e8da8
```

回滚代码不会、也不应把已经正确收尾的历史记录改回 `RUNNING`。执行前需先检查当前分支、HEAD 和工作区。
