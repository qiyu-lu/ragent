# ROS1 任务 dry-run 可选扩展

> 历史记录：2026-09-17 的 P1 已移除本页描述的机器人服务、网关与 ROS1 运行内容。设计和当时验证保留；本页启动与体验步骤不适用于当前分支，现行入口见[执行记录](../agentic-research-execution-log.md)。

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | `2026-08-12` |
| 定位 | 工业知识闭环后的可选执行适配案例，不是 Java 后端 + AI 求职主线 |
| 状态 | 代码、数据库、ROS1 dry-run 三条运行时路径已验证；登录页面派发 E2E 未完成 |
| Git 提交 | `a016f01`（与工业知识闭环同一提交） |
| 构建与启动 | [ROS1 dry-run 网关说明](../../../robot-gateway/README.md) |

## 改动目的与问题发现

阶段 2 产生的是“有检索证据、需人工批准”的自然语言候选任务。它适合知识审核，却不是控制协议：文本字段不具备固定技能集合、参数类型、幂等标识、执行状态或设备安全约束，不能直接交给 ROS 或真实设备。

本改动的目的仅是验证后端如何把一个已批准的候选任务接到外部执行器边界：由确定性 Java 代码编译一个固定样品搬运实例，经本机网关派发到 ROS1 Action 模拟服务器，并将反馈、取消和失败写入审计表。RAG 和 LLM 的职责不因此扩展到机器人控制。

## 根因与安全风险

- 自然语言规程语义开放，若直接转成命令，模型可能生成不存在的技能、未经验证的参数或文档没有提供的工位信息。
- HTTP/ROS 调用会失败或超时；如果先调用外部系统、后记数据库，派发失败时可能没有审计记录。
- 同一候选任务被重复提交时，需要区分同参数重试与不同参数二次派发，否则无法判断实际执行了哪一份计划。
- 项目没有真实机器人型号、厂商接口、现场急停、安全责任或联锁验收，因此任何 `dryRun=false` 都必须在进入设备层之前被拒绝。

## 方案取舍与选择理由

1. **确定性编译代替第二次 LLM 生成。** `RobotMissionCompiler` 只接受 `SAMPLE_TRANSPORT`，输出固定的导航、搬运、可选返航技能；这样技能范围可审计，也避免把候选任务文本当作控制代码。
2. **白名单与强类型参数。** 标识符只允许长度不超过 64 的字母、数字、下划线和短横线；起点不得等于终点。演示中的机器人、容器和工位值是构造参数，不从 XLSX 推断。
3. **计划指纹保证幂等。** 对规范化 payload 计算 SHA-256；同一候选任务可以用相同参数恢复或重试，但不能换参数重复派发。
4. **先落库、再调用网关。** Java 后端先保存 `READY` 任务和完整计划，再请求外部网关；派发失败保留 `DISPATCH_FAILED`，不会抹掉尝试记录。
5. **Java 与 ROS 解耦。** 后端只调用本机 HTTP 网关，网关负责转换为 ROS1 `actionlib`；协议错误、ROS 生命周期和 Java 业务状态各自位于清晰边界。
6. **三层强制 dry-run。** Java 编译器、HTTP 网关和 ROS Action Server 都拒绝或不接受真实执行；关闭 Profile 时 Java 网关客户端也默认禁用。

## 实现与调用链

```text
有证据的候选任务
  -> 人工批准
  -> IronOreRobotMissionController
  -> RobotMissionService 校验所有者和批准状态
  -> RobotMissionCompiler 生成 SAMPLE_TRANSPORT + planHash
  -> t_iron_ore_robot_mission 先保存 READY 计划
  -> RobotGatewayClient 调用 127.0.0.1:18081
  -> Python HTTP gateway 校验 dryRun 与并发状态
  -> ROS1 actionlib ExecuteMission
  -> dry-run Action Server 返回 feedback / terminal status
  -> Java 查询或取消并持久化最近网关快照
  -> 前端卡片展示进度和审计字段
```

默认实例生成三个结构化步骤：

1. `NAVIGATE_TO_STATION(sampling_area)`；
2. `TRANSPORT_CONTAINER(sample_bucket_01, center_laboratory)`；
3. `NAVIGATE_TO_STATION(home)`。

`robot-demo-01`、容器和工位均为构造演示值，不是企业文档事实。计划作用域明确排除烘干、称重和设备控制。

主要实现：

- 后端 Controller、Service、Compiler 和网关客户端：`bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/`
- ROS1 Action、模拟服务器和 HTTP 网关：`robot-gateway/ros1_ws/src/iron_ore_robot_demo/`
- 前端进度卡片：`frontend/src/components/chat/IronOreTaskSection.tsx`
- 数据库迁移：`resources/database/upgrades/v1.1.0/260812_ros1_robot_mission.sql`

`t_iron_ore_robot_mission` 保存结构化计划、SHA-256 指纹、最近网关快照、状态、步骤进度和时间。现有候选任务、模拟执行与知识库表不变。

## 运行与人工验收

已有环境先应用 `resources/database/upgrades/v1.1.0/260812_ros1_robot_mission.sql`；该脚本只新增任务表。随后按 [网关说明](../../../robot-gateway/README.md) 构建并启动 ROS1 Action 模拟服务器与只监听本机的 HTTP 网关，后端使用 `iron-ore-demo` Profile。

页面验收流程保留为：先获得一个带 XLSX 来源的候选任务并人工批准；派发固定搬运 dry-run，检查三个结构化技能、指纹、`DRY-RUN`、逐步反馈和最终 `SUCCEEDED`；再用新候选任务验证取消为 `CANCELED`；最后停掉网关触发并保留 `DISPATCH_FAILED`，重启网关后用相同参数重试。该流程是未执行的复现协议，本阶段不再安排；它不属于下面已经完成的运行时证据。

## 验证与前后效果

### 已完成验证

- Java 编译通过；当时的 21 个铁矿相关针对性测试通过，其中编译器测试验证固定技能、稳定指纹、未知任务类型和 `dryRun=false` 拒绝，网关客户端测试验证结构化派发与禁用开关。
- 前端生产构建通过。
- ROS1 catkin 构建及 Action 消息生成通过。
- 真实本地进程链路中，三步任务得到 `DISPATCHED -> RUNNING -> SUCCEEDED` 和逐步 feedback。
- 慢速 dry-run 任务取消后得到 `CANCELED`。
- 向 HTTP 网关提交 `dryRun=false` 得到 HTTP 400。
- 本地 PostgreSQL 迁移及 JSONB、指纹、状态字段复核通过。

| 行为 | 改动前 | 改动后 |
| --- | --- | --- |
| 候选规程到执行器 | 没有协议边界 | 已批准任务由确定性代码编译为固定任务类型 |
| 技能与参数 | 自然语言，不能执行 | 白名单技能、受限标识符、固定超时 |
| 重复派发 | 无幂等依据 | SHA-256 指纹拒绝同任务不同参数 |
| 网关失败 | 无审计状态 | 先落库并保留 `DISPATCH_FAILED`，同参数可重试 |
| 运行反馈 | 无外部任务状态 | 可查询、取消并持久化网关快照 |
| 设备能力 | 未提供 | 仍未提供；只增加 dry-run 模拟链路 |

### 尚未完成的业务验证

成功、取消和拒绝非 dry-run 是后端—HTTP 网关—ROS1 模拟服务器的运行时验证，不等同于从登录页面完成整个业务 E2E。页面中的“检索回答 → 生成人工任务 → 批准 → 派发 → 查看进度”尚未完成一次登录态验收，因此不能写成完整前端用户旅程已经通过，更不能写成真实机器人集成。

## 限制与停止状态

- 后端、HTTP 网关和 Action Server 三层都不允许 `dryRun=false`。
- 技能 ID 只来自白名单；网关不接受第二个并发活动任务。
- 系统不向 `/cmd_vel`、控制器、驱动或厂商 SDK 写入任何数据。
- 当前没有感知定位、运动规划、碰撞检测、硬件急停、安全 PLC 或现场责任边界。
- 该模块只说明“已批准的后端任务如何接入外部适配层”，不是项目主线，也不作为 RAG 质量或机器人能力成果。当前阶段冻结，不继续扩展到真实设备。

## 数据兼容与回滚

- 不启用 `iron-ore-demo` Profile 时，`RobotGatewayClient` 默认关闭；不启动本机 ROS1 网关也不会产生模拟动作，查询只会返回最近一次持久化状态或网关不可用提示。
- 迁移只新增 `t_iron_ore_robot_mission`，不修改现有知识和候选任务数据。代码回滚本身不要求删表。
- 删除表前必须备份审计记录；如果只想停用扩展，优先关闭 Profile 或不启动网关，而不是删除数据。
- `a016f01` 同时包含工业知识闭环主线与本可选扩展，不能整体 revert 来单独移除 ROS1；如需拆除，应按模块和迁移依赖制定独立变更。
