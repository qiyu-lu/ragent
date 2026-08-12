# 取消后 trace run 悬挂修复

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-08-12 |
| 所属阶段 | 阶段 0 的工程稳定性修复，不改变铁矿领域能力 |
| 状态 | 已实施并验证 |
| 原修复分支 | `fix/cancel-trace-run-hang` |
| 原始修复 | `24bd7f8` |
| 竞态补强 | `e0d0871` |
| 合并到研究分支 | `c7e8da8` |

## 问题与原因

用户点击“停止生成”后，子节点可能已经记录为 `CANCELLED`，但 `t_rag_trace_run` 根记录仍永久停留在 `RUNNING`，没有 `end_time` 和 `duration_ms`。这会让 Trace 页面产生歧义，并使 Dashboard 指标忽略被取消的请求。

原始修复只在本机任务对象的取消分支中收尾，仍存在三个窗口：取消可能早于根记录插入；跨实例或本机任务缓存缺失时无法收尾；provider 取消或 SSE 清理异常可能中断后续上报。迟到的正常回调还可能覆盖取消终态。

## 实施内容

- 停止请求进入 `StreamTaskManager` 时即尝试按 `taskId` 幂等收尾，不再依赖本机任务缓存存在。
- 注册任务时检查 Redis 中已有的取消标记，补偿“先取消、后注册”的竞态。
- 根 trace 创建后再次检查取消状态，补偿“先取消、后插入 run”的竞态。
- 普通 `finishRun` 只允许把 `RUNNING` 更新为终态，保证 `CANCELLED` 不被迟到的 `SUCCESS` 或 `ERROR` 覆盖。
- provider 取消、取消消息持久化和 SSE 清理分别隔离异常；取消结束事件用 CAS 保证只执行一次。
- Dashboard 的成功率和错误率分母纳入 `CANCELLED`。

主要入口：

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamTaskManager.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/trace/StreamChatTraceRunner.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java`

## 验证

- 取消相关 4 个测试类共 15 项测试通过，覆盖提前取消、缺失本地任务、provider 取消异常、迟到成功回调和终态竞争。
- 排除一个与本改动无关的既有 Mockito 严格桩测试后，离线宽范围测试通过；Bootstrap 运行 232 项，0 失败、0 错误、1 跳过。
- `./mvnw -o spotless:check` 通过，合并后工作区干净。
- 本地数据库中唯一确认的历史悬挂根记录已按其 `CANCELLED` 子节点时间精确收尾；修复后根 run 状态统计中不再存在 `RUNNING`。

人工复测时必须重启 IDEA 中的后端进程，然后在生成期间点击停止，确认根 trace 为 `CANCELLED`，并且 `end_time`、`duration_ms` 均非空。

## 数据与兼容性

代码不需要新增数据库列。状态注释和 Dashboard 统计补充了既有的 `CANCELLED` 语义。现场数据只精确修复了一条已确认记录，没有批量修改其他 trace。

## 回滚

研究分支上的修复是一个 merge commit。如确需撤销且后续提交没有依赖它，使用：

```bash
git revert -m 1 c7e8da8
```

回滚代码不会自动把数据库中已经收尾的历史记录改回 `RUNNING`，也没有必要这样做。执行回滚前仍应先检查当前分支、HEAD 和工作区。

## 后续事项

下一阶段的工业样本和检索评测不依赖此问题继续扩展；若再次出现悬挂，应保存 task ID、trace ID、取消时间和服务实例日志，作为新的缺陷证据，而不是修改本阶段目标。
