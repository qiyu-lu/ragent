# 规程驱动的送检任务 Agent

## 目标与范围

把已有规程检索接到实际的软件业务操作：查询样品资料、选择可用工位、提出有依据的送检草稿、人工确认、预约并创建送检记录。第一版只处理这一种业务，不训练模型，不依赖机器人实机。

## Git 与进度

| 阶段 | 状态 | 提交 / 验证 |
| --- | --- | --- |
| 改造前工作区检查点 | 完成 | `5c123f4`，原样保存 85 个代码、评测和笔记文件；未推送 |
| 后端：任务状态、工具、模型适配、业务事务与规程检索 | 完成 | `a689201`；31 项定向测试通过，其中 27 项在 PostgreSQL 上复跑通过 |
| 前端：办理页面、确认、补充资料与恢复入口 | 完成 | 本文所在的 `feat: add task agent workspace and document verified boundaries` 提交；TypeScript、生产构建及兼容配置下的定向 lint 通过 |
| 回归验证、复现与交付 | 完成 | 数据库脚本验证完成；存量链路 39 项回归通过；体验步骤与后续验收边界记录在本文 |

检查点保留了原笔记 `10-my.md` 的两处空白格式问题，没有为创建检查点改写笔记。原有检索实验的负结果和默认开关保持各自原有含义。

## 架构取舍

- 参考 [AgentScope Java 的 Agent 循环](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/agent.md)和[工具确认机制](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/permission-system.md)，采用“模型选择一个工具 → 保存观察结果 → 再决定下一步”。参考本地 `0f7a4f6d`，没有复制框架源码。
- 复用现有 `LLMService`、向量检索、重排和文档元数据；第一版不引入第二套模型配置或通用 Agent 平台。当前统一模型接口没有完整原生工具调用协议，因此使用明确的 JSON 决策协议。
- 一个主 Agent，五个业务工具。工具目录由代码声明；模型不能获得任意 SQL、Shell、URL 或审批权限。
- 每次请求推进一个决策，前端连续推进；数据库保存每次观察与等待状态。关闭页面后可重新打开继续，进程中断后的运行通过过期执行租约重新领取。第一版没有后台无人值守调度器。
- 工位预约和送检记录使用同一数据库事务，与任务完成状态一起提交；任务 ID 作为业务幂等标识。恢复时核对业务记录，不将模型文字当作完成结果。
- 用户明确确认具体草稿后才执行预约。资料改变、工位被占用等业务失败返回 Agent 重新查询；修改草稿需要重新确认。
- 样品与工位是用户隔离的本地示例业务数据；正式文档来自已有知识库。示例规程会明确标为构造资料，不表示真实检测规范。

## 这次真正增加了什么

原来的主链路终点是回答或任务计划草案；新链路把终点推进到软件业务办理。例如用户提出“按该规程为样品 B 办理送检”，Agent 查询样品后发现标签尚未核对，进入等待；操作员在资料面板登记实际核对结果并补充说明，Agent 再查询规程与可用工位，提出有来源的草稿。确认期间原工位被其他任务抢占时，数据库拒绝该次预约，Agent 收到业务失败观察，再查询替代工位，重新征求确认。只有预约、样品状态、送检记录与任务完成状态一起提交，页面才显示已办理。

这不是把固定步骤分别包装为多个 Agent：只有一个决策者，工具调用次序和是否继续查询由模型根据观察选择。框架提供小型执行控制层：持久状态、工具白名单、执行租约、确认、恢复、轮数限制和事件记录。它没有扩展成通用 harness 平台，也没有声称能自动获得新的机器人技能。

### 实现位置与边界

| 环节 | 实现 | 关键行为 |
| --- | --- | --- |
| HTTP 入口 | [TaskAgentController](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/controller/TaskAgentController.java) | 从登录上下文取用户 ID，不接受调用方指定任务归属 |
| 运行与存储 | [TaskAgentService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/TaskAgentService.java)、[TaskAgentStore](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/TaskAgentStore.java) | 一步一提交；模型调用期间不持有数据库事务；旧租约结果不能覆盖取消或新执行 |
| 模型与工具 | [LlmTaskAgentPlanner](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/LlmTaskAgentPlanner.java)、[TaskAgentTools](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/TaskAgentTools.java) | 复用标准档模型；解析工具 JSON；错误成为后续决策的观察 |
| 规程证据 | [RagTaskKnowledge](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/RagTaskKnowledge.java) | 绑定文档与版本；向量召回后回查有效分块，再重排；确认时复核内容哈希及单元格位置 |
| 业务办理 | [InspectionBusinessService](../../../bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/agent/InspectionBusinessService.java) | 确定性检查资料、项目与工位；条件更新防止重复占用；任务 ID 保证重复确认不重复登记 |
| 用户界面 | [TaskAgentPage](../../../frontend/src/pages/TaskAgentPage.tsx) | `/tasks` 独立页面，展示业务状态、草稿、证据和逐步记录；从 `?run=任务ID` 恢复 |
| 数据结构 | [增量 SQL](../../../resources/database/upgrades/v1.1.0/260915_task_agent.sql) | 5 张表：任务、事件、样品、工位、送检记录；全量建库脚本同步包含这些表 |

五个工具分别是 `search_procedure`、`inspect_sample`、`list_stations`、`propose_submission`、`ask_user`。预约和登记不是模型可调用的工具，只在独立确认接口中执行。样品资料核对也由操作员更新，模型不能用一句“已完成”代写业务状态。

### 检索与预算

- 当前实现面向项目默认 PostgreSQL/PGVector。指定文档的 `doc_id` 条件使用绑定参数，放在 SQL 排序和 LIMIT 之前；普通问答未传该条件时，原有检索 SQL 语义不变。
- 新任务链路直接复用向量检索和重排服务，不经过普通问答的改写、意图与多通道编排，也不修改原有实验开关。每次查询召回 20 条、重排至 5 条，任务保留最多 12 条证据，每条模型可见正文最多 1,600 字符；哈希针对完整分块正文计算。
- 模型上下文保留最近 12 个观察，完整工具事件仍在数据库中。每个任务最多领取 16 轮模型决策，单次输出上限 1,800 token；底层供应商重试与回退可能产生额外请求，这不是全链路费用或输入 token 的硬上限。
- `Milvus` 未在本次验证范围内；业务适配器仍回查分块所属文档，但不能声称它具有同样的召回前过滤效果。

### 状态、确认与恢复

```text
READY → RUNNING → READY / WAITING_INPUT / WAITING_APPROVAL / FAILED
WAITING_INPUT / FAILED → 用户补充或重试 → READY
WAITING_APPROVAL → 确认具体 revision → COMPLETED
WAITING_APPROVAL → 业务条件变化 → 清除草稿和证据 → READY
非终态 → 用户取消 → CANCELLED
```

- `/advance` 同步完成一个模型决策及只读工具调用；前端循环请求，遇到等待、失败或完成时停止。关闭页面停止发出后续请求，不等于撤回已发出的模型调用。
- 数据库记录 `RUNNING` 的执行租约为 180 秒。服务中断后，用户重新打开任务并在租约过期后继续；新执行使用不同令牌，旧执行即使返回也不能落入新状态。没有后台自动巡检或心跳续租。
- 确认必须携带当前草稿的 `revision`。修改要求会废弃草稿；旧 revision 不能批准新草稿。确认结果丢失后重试同一任务，返回已保存的同一条送检记录。
- 预约工位、标记样品已送检、插入送检记录和标记任务完成处于同一事务。两个任务同时读到空闲工位时，只有一个条件更新成功；失败事务先回滚，再记录重新规划原因。
- 确认时资料、工位或证据发生变化，会废弃旧草稿并回到查询阶段；文档版本已变化时，固定版本任务不能静默切换，需新建任务。

## 实际验证记录（2026-09-15）

| 验证 | 实际结果 | 证明范围 |
| --- | --- | --- |
| 任务与业务服务 | 18 项通过 | 成功办理、缺资料恢复、错误工具、伪造引用、能力不匹配、重复确认、并发预约、回滚、跨用户、取消、租约接管、轮数上限、模型失败重试 |
| 规程适配器 | 6 项通过 | 限定文档、过滤失效分块、使用当前正文重排、版本/内容/单元格位置变更、无证据时不重排；向量和 rerank 使用桩 |
| HTTP 绑定与办理 | 3 项通过 | MockMvc 调用真实业务服务和 JDBC，验证参数校验、登录上下文归属、确认与回执；没有启动真实登录中间件 |
| 模型协议适配 | 2 项通过 | 使用既有 `LLMService` 的参数与 JSON 解析；模型响应为 mock，未调用供应商 |
| PGVector 回归 | 2 项通过 | 原多库总 LIMIT 不变，新增文档过滤条件及参数绑定；JdbcTemplate/embedding 为 mock |
| 实际 PostgreSQL | 上述业务、规程、HTTP 共 27 项再次全部通过 | 临时 PostgreSQL 16.14，每用例独立 schema；并发用例用同步点保证两个任务都先读到可用工位 |
| 全量及增量 SQL | 通过 | 临时 PostgreSQL/PGVector 0.8.6 上执行全量 schema，再重复执行增量脚本；新表共 5 张，无覆盖已有数据语句 |
| 存量链路回归 | 39 项通过 | 原任务模板、版本差异、机器人计划编译，以及向量通道、检索编排、请求级去重预算的定向回归；不含实机或远程业务 E2E |
| 前端 | 类型检查及生产构建通过 | 页面代码可编译、可打包；尚未浏览器点击验收，保留现有大 bundle 警告 |
| 前端定向 lint | 兼容配置下 2 个新文件，0 问题 | 原 ESLint 8 配置引用了 flat-config 形式的 react-refresh 推荐项，直接执行在加载配置时失败；只在检查进程中展开同一推荐规则，未修改原配置 |

默认定向测试共 31 项，不把 PostgreSQL 重跑重复计为 58 个独立用例。失败场景测试中故意触发的日志不是测试失败。测试报告保留在本地 `bootstrap/target/surefire-reports/`，不提交构建产物。

### 可重复的测试命令

在仓库根目录执行；`-o` 使用本机 Maven 缓存，首次缺依赖时去掉它：

```bash
./mvnw -o -pl bootstrap -am \
  -Dtest=TaskAgentServiceTest,RagTaskKnowledgeTest,TaskAgentControllerTest,LlmTaskAgentPlannerTest,PgVectorRetrieverServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -o -pl bootstrap -am \
  -Dtest=TaskTemplateValidatorTest,WorkbookDiffServiceTest,RobotMissionCompilerTest,VectorSearchChannelTest,RetrievalEngineTest,MultiChannelRetrievalEngineTest,RequestLevelChunkSelectorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

可选 PostgreSQL 复跑使用专用测试数据库；测试代码为每个用例创建随机 schema 并在结束时清理，仅保留外部数据库本身：

```bash
./mvnw -o -pl bootstrap -am \
  -Dtest=TaskAgentServiceTest,RagTaskKnowledgeTest,TaskAgentControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DtaskAgentTest.postgresUrl=jdbc:postgresql://127.0.0.1:32768/task_agent_test \
  -DtaskAgentTest.postgresUser=task_agent_test \
  -DtaskAgentTest.postgresPassword=task-agent-local-test test
```

上面的端口和口令仅为本次隔离容器的临时测试配置，不是应用配置。复跑须先准备自己的测试实例并替换地址；不能把这个地址当作现存服务。本次测试不连接、不迁移现有 `ragent` 业务数据库。验证结束后已停止并自动移除 `ragent-task-agent-verify-20260915` 容器及内存卷中的可重建测试数据，现有服务与数据未清理。Maven 生命周期包含 Spotless apply，本次只为新增 Java 文件补入项目许可证头，提交前检查了工作区范围。

前端在 `frontend` 目录执行：

```bash
./node_modules/.bin/tsc --noEmit
npm run build
```

本次定向 lint 用下面的进程内兼容方式运行；它保留原推荐规则，只将 react-refresh 的 flat-config 推荐规则展开为 ESLint 8 接受的形式：

```bash
node <<'NODE'
const { ESLint } = require("eslint");
const base = require("./.eslintrc.cjs");
const refresh = require("eslint-plugin-react-refresh");
base.extends = base.extends.filter(value => value !== "plugin:react-refresh/recommended");
base.plugins.push("react-refresh");
Object.assign(base.rules, refresh.configs.recommended.rules);
(async () => {
  const lint = new ESLint({ useEslintrc: false, baseConfig: base });
  const results = await lint.lintFiles(["src/pages/TaskAgentPage.tsx", "src/services/taskAgentService.ts"]);
  process.stdout.write((await lint.loadFormatter("stylish")).format(results));
  process.exitCode = results.some(result => result.errorCount || result.warningCount) ? 1 : 0;
})();
NODE
```

## 本地体验步骤

1. 沿用[本地开发栈](../../../resources/docker/dev/README.md)和既有模型配置。需要标准档聊天模型、Embedding、Rerank；不需要 ROS、机器人、AgentScope SDK 或新的模型密钥配置。
2. 新数据库的 `schema_pg.sql` 已包含新表。已有数据库先核对目标并备份，只执行新增的 `260915_task_agent.sql`；不要对已有库重跑全量建库或初始化数据。后端不会自动迁移。
3. 在知识库管理页面上传[构造的示例规程](../../../resources/examples/task-agent/送检办理规程V1.0-demo.md)，等入库成功并保持启用。创建任务时从下拉框明确选择这份文档，版本以入库元数据为准；无版本时显示“未标注版本”，仍检查分块哈希。
4. 按项目原方式运行 `RagentApplication` 和前端。登录后点击侧栏“送检任务”，或打开 `http://127.0.0.1:5173/tasks`；点击“准备示例样品与工位”。这只为当前用户补齐两个样品和三个工位，不重置已有状态。
5. 先选择资料不完整的样品 B，选择示例规程，建立任务并点击“继续执行”。期望 Agent 查询后要求核对标签；在资料面板勾选标签已核对，填写补充说明并保存，再继续执行。
6. 核对草稿中的样品、工位和引用原文，点击“确认预约并创建送检记录”。完成后检查送检编号、工位预约状态和逐步记录。页面上的“已办理”仅指数据库登记。
7. 用两个浏览器标签页可体验“确认前条件变化”：分别为两个未送检样品建立草稿，目标要求同一个可用水分工位；先确认一个，再确认另一个。第二个应返回重新规划，改选剩余工位后需要再次确认。该步骤与上一个成功用例共享样品，请在独立测试数据环境安排顺序。
8. 在等待补充或待确认时关闭页面，重新从最近任务打开，检查状态和引用仍在。进程中断恢复、并发和回滚优先使用上面的自动化用例，不必为演示手工破坏正在使用的数据库。

本地 Compose 已运行 PostgreSQL 时，增量脚本可从仓库根目录执行：

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml exec -T postgres \
  sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < resources/database/upgrades/v1.1.0/260915_task_agent.sql
```

若使用宿主机 PostgreSQL，则用数据库客户端对选定库执行同一个普通 SQL 文件，不依赖 `psql` 专用 include 指令。

### API 入口

实际后端路径带既有 `/api/ragent` 前缀。以下路径相对 `/iron-ore/task-agent`，沿用登录认证和原项目 `Result` 响应协议：

| 方法及路径 | 用途 |
| --- | --- |
| `GET /documents`、`GET /samples`、`GET /stations` | 选择规程、读取当前业务资料 |
| `POST /demo-data`、`PUT /samples/{id}` | 操作员初始化示例数据、更新标签及交接资料状态 |
| `POST /runs` | 请求体 `{goal, documentId, sampleId}`，创建持久任务 |
| `GET /runs`、`GET /runs/{id}` | 最近 30 个任务、任务详情与完整事件 |
| `POST /runs/{id}/advance` | 推进一步；前端请求超时 180 秒 |
| `POST /runs/{id}/reply` | 请求体 `{message}`，补充资料或要求并作废旧草稿 |
| `POST /runs/{id}/approve` | 请求体 `{revision}`，确认当前草稿并提交业务事务 |
| `POST /runs/{id}/cancel` | 停止后续推进；不撤销已提交的业务记录 |

## 未覆盖的能力与下一步

- 当前是本地样品/工位示例业务，不是已经接入 LIMS、MES 或真实企业预约系统；只有预置样品和工位，没有通用样品管理、检测完成、释放工位或对外执行适配。重复初始化不会清空预约，重复完整演示应使用独立测试环境。
- 没有调用真实模型完成“登录—上传—检索—草稿—确认”的整页 E2E，也没有量化模型完成率、调用成本或延迟。这应当是下一步验收，不应把可控决策桩通过率写成 Agent 智能水平。
- 引用成员关系、有效性、内容哈希和来源位置可以由代码检查；引用文字是否真正支持模型提出的要求、是否漏掉规程条件，仍需人工核对和后续带答案标注的真实模型评测。不能据此宣称解决幻觉。
- 样品、工位和任务按用户隔离；规程沿用项目当前共享的已启用知识库范围，没有新增文档 ACL 或工厂级多租户权限。
- 未改变旧计划草案/ROS1 dry-run 链路；没有实机动作或物理反馈，因此不能声称机器人泛化或控制性能提升。
- 下一步先用构造规程进行少量真实模型页面验收，固定成功、缺资料、无依据、工位冲突四类任务，记录实际工具序列、误办情况、重复登记、人工确认和模型成本；通过后再决定是否接入实际业务系统，不继续堆 Agent 数量。

## 回退方式

代码按本次后端、前端阶段提交分别回退，保留改造前检查点 `5c123f4`。停用新页面与新接口即可不再推进新任务；已建的五张表和送检记录可以保留。不要通过删除数据库或开发卷回滚代码，也不要自动撤销用户已经确认的预约。本次无远程推送。

## 2026-09-16：RocketMQ 存储迁移

页面验收进行到文档分块时，`knowledge-document-chunk_topic` 事务消息发送被 Broker 拒绝：`CODE: 14`，`CL/CQ/INDEX = 0.92`。实际检查确认 Broker 的数据卷位于系统分区 `/dev/nvme1n1p3`，使用率为 92%；不是文档解析、模型调用或 Agent 决策失败。

用户授权仅迁移报错服务的存储，提供 `LiWeishuaiA` 和“新加卷”两个位置。选用 `/media/sd101t/LiWeishuaiA`：宿主机为可写 ext4，约 214 GiB 可用、使用率 43%；“新加卷”为 NTFS/FUSE，约 101 GiB 可用、使用率 80%。工具沙箱最初显示前者只读，提升到宿主机权限后已确认是隔离视图，不是硬盘被系统保护为只读；没有执行重新挂载或磁盘修复。

迁移映射：

| 对象 | 位置 |
| --- | --- |
| 原 RocketMQ 卷 | `ragent-iron-ore-dev_rocketmq-data`，保留作迁移前备份 |
| 新数据目录 | `/media/sd101t/LiWeishuaiA/ragent-iron-ore-dev/rocketmq-store` |
| 新外部卷 | `ragent-iron-ore-dev_rocketmq-external-data`，Docker local bind 指向上述目录 |
| 容器内路径 | 仍为 `/home/rocketmq/store`，端口和 Broker 名称不变 |

只停止并重建本项目 Broker、与其共享网络的 Dashboard 和存储权限初始化容器；NameServer、PostgreSQL、Redis、RustFS 及其他项目容器不迁移、不清空。原数据约 7.8 MiB 实际占用、1.6 GiB 逻辑长度，包含稀疏文件；迁移意图是让 MQ 使用低占用分区，不是借此大量释放系统盘。

已完成：停 Broker 后只读挂载原卷，使用 `cp -a --sparse=always` 复制，`diff -qr` 逐文件比较无差异；两侧实际占用均约 7.8 MiB、逻辑长度均约 1.6 GiB，目录 UID/GID 均为 `3000:3000`。没有删除、清空或覆盖原卷。停止 Broker 等待 30 秒后退出码为 137，新实例日志识别到上次异常退出，随后成功加载并恢复存储，未把该过程记作正常停机。

Compose 仅增加可选卷名和 external 配置，本机通过忽略的 `.env` 启用，其他机器默认行为不变。新卷创建命令如下（仅作为实施记录，数据复制和校验必须先完成）：

```bash
docker volume create --driver local \
  --opt type=none --opt o=bind \
  --opt device=/media/sd101t/LiWeishuaiA/ragent-iron-ore-dev/rocketmq-store \
  --label ragent.purpose=rocketmq-external-store \
  ragent-iron-ore-dev_rocketmq-external-data
```

本机 `.env` 仅包含两个卷选择变量，不包含模型密钥，已确认被 Git 忽略。原有启动命令无需增加第二份 Compose 文件；默认卷模式和外部卷模式的 `docker compose config` 均验证通过。

实际验证：

- 初始化容器退出码 0，Broker 和 Dashboard 重新运行，Dashboard HTTP 检查返回 200；其他服务未重启。
- Broker 内 `df -h /home/rocketmq/store` 显示 `/dev/sda1`，使用率 43%；`brokerStatus` 的 `commitLogDiskRatio` 和 `consumeQueueDiskRatio` 均为 `0.43`。
- 独立诊断 Topic `ragent-storage-migration-probe-20260916` 发送 1 条无业务内容消息，返回 `SEND_OK`，消息 ID `AC1600050188266474C24D59184D0000`；随后 `putMessageTimesTotal=1`、`putMessageFailedTimes=0`。没有向业务分块 Topic 发送伪造任务。
- 测试后仅清理上述新建诊断 Topic 的元数据，删除命令分别确认 Broker 和 NameServer 成功；业务 Topic、原卷和外部数据目录均保留。
- 原失败文档 `2100015238547021824` 仍为 `pending`、`chunk_count=0`；没有手工改数据库状态，也没有自动重试分块或调用付费模型。由用户回到页面直接重试“分块”。
- 系统分区仍约 92%，这里只解除 RocketMQ 使用该分区导致的拒写，不代表整机磁盘空间问题已经消失。

后续必须保持外部盘已挂载、使用期间不拔盘。原卷是迁移时点备份；新消息产生后，回迁需停止 Broker 并将最新数据同步回选定目标，不能直接切回旧卷。当前验证证明存储切换和普通消息发送恢复，不代替文档分块、Embedding 或 Agent 的完整页面验收。
