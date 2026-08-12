# ROS1 机器人任务 Demo

## 问题与目标

阶段 2 的候选任务具有文档证据和人工批准，但仍是自然语言规程，不能直接作为机器人控制协议。本改动增加一个独立适配层，只把人工选择的样品搬运演示实例编译为固定 ROS1 技能，不扩大 RAG 的控制责任。

## 范围

- 新增 `RobotMissionCompiler`，不调用 LLM，只产生 `SAMPLE_TRANSPORT`。
- 技能白名单固定为导航到工位和搬运容器，参数经过类型与字符约束。
- 每份计划保存 SHA-256 指纹，同一候选任务不能用不同参数重复派发。
- 新增 ROS1 Noetic Action、模拟服务器和本机 HTTP 网关。
- 新增任务派发、状态查询、取消接口，以及前端进度卡片。
- 网关失败保留 `DISPATCH_FAILED` 记录；同参数可以重试。
- 全链路强制 `dryRun=true`，不连接真实设备。

## 关键文件

- 后端：`bootstrap/src/main/java/com/nageoffer/ai/ragent/ironore/`。
- ROS1：`robot-gateway/ros1_ws/src/iron_ore_robot_demo/`。
- 前端：`frontend/src/components/chat/IronOreTaskSection.tsx`。
- 数据库：`resources/database/upgrades/v1.1.0/260812_ros1_robot_mission.sql`。
- 运行说明：[阶段 3 文档](../stages/03-ros1-robot-mission-demo.md)。

## 数据影响

新增 `t_iron_ore_robot_mission`，保存结构化计划、指纹、最近网关快照、状态和时间。现有候选任务表、模拟执行表与知识库数据不变。本机迁移已经执行。

## 验证

- Maven 编译和 21 个铁矿针对性测试通过。
- 前端生产构建通过。
- ROS1 catkin 构建通过。
- ROS1 成功、取消和拒绝非 dry-run 三条运行时路径通过。

## 回滚边界

- 不启动 ROS1 网关时，不会产生机器人侧动作；已记录任务可能显示网关不可用。
- 不启用 `iron-ore-demo` Profile 时，后端默认关闭机器人网关。
- 删除数据库表前必须先备份审计记录；代码回滚本身不要求删除表。
- 真实机器人集成必须新增明确的技能适配器和安全验收，不能把模拟 Action Server 原地改成任意命令转发器。

## Git 状态

实现提交：`a016f01`（`feat(iron-ore): complete industrial RAG and ROS1 demo`）。本次收尾只建立本地提交与标签，没有推送远端。页面登录态派发验收仍按阶段 3 文档保留为下一次运行任务。
