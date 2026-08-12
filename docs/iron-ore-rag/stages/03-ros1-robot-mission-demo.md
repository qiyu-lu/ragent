# 阶段 3：ROS1 机器人任务 Demo

## 阶段状态

- 状态：代码、数据库和 ROS1 dry-run 运行时闭环已实现；待在页面完成一次登录态联调。
- 目标：把“有规程证据、已人工批准”的候选任务，关联到受严格约束的 ROS1 搬运子任务。
- 明确不做：自然语言直接控制、任意技能生成、真实底盘/机械臂控制、强化学习策略训练和设备联动。

## 实际链路

```text
RAG 检索证据
  -> 候选规程任务
  -> 人工批准
  -> 确定性 RobotMissionCompiler
  -> SAMPLE_TRANSPORT + 固定技能白名单 + SHA-256 指纹
  -> 本机 HTTP 网关
  -> ROS1 actionlib
  -> dry-run Action Server
  -> DISPATCHED / RUNNING / SUCCEEDED | FAILED | CANCELED
  -> 前端进度与数据库审计记录
```

RAG 仍只负责生成供人审核的规程任务。机器人任务不是第二次自由生成，而是用户点击“派发 ROS1 搬运仿真”后，由普通 Java 代码根据固定任务类型和固定参数编译得到。

当前页面使用的演示实例为：

| 字段 | 固定值 |
| --- | --- |
| 机器人 | `robot-demo-01` |
| 容器 | `sample_bucket_01` |
| 起点 | `sampling_area` |
| 终点 | `center_laboratory` |
| 返回点 | `home` |
| 执行方式 | `dryRun=true` |

这些值是构造的机器人演示实例，不是从 XLSX 推断出的真实工位或设备配置。

## 首次运行

### 1. 数据库迁移

当前本机数据库已经应用并复核此迁移。其他已有环境执行：

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml exec -T postgres \
  psql -U postgres -d ragent -v ON_ERROR_STOP=1 \
  < resources/database/upgrades/v1.1.0/260812_ros1_robot_mission.sql
```

它只新增 `t_iron_ore_robot_mission`，不修改或删除已有任务和知识数据。

### 2. 构建并启动 ROS1

本机已安装 ROS1 Noetic：

```bash
source /opt/ros/noetic/setup.bash
catkin_make -C robot-gateway/ros1_ws
source robot-gateway/ros1_ws/devel/setup.bash
roslaunch iron_ore_robot_demo demo.launch
```

`roslaunch` 会同时启动 ROS Master、模拟 Action Server 和只监听本机的 HTTP 网关。详细协议见 [`robot-gateway/README.md`](../../../robot-gateway/README.md)。

### 3. 重启后端与前端

保持 IDEA Profile 为 `iron-ore-demo`，重启后端，使新增 Controller、Mapper 和网关配置生效。前端重新执行：

```bash
cd frontend
npm run dev
```

## 页面验收

1. 按阶段 2 流程获得一个带 XLSX 来源的回答并生成候选任务。
2. 点击“人工批准”。未批准任务不能派发。
3. 点击“派发 ROS1 搬运仿真”。
4. 检查卡片显示三个结构化技能、计划指纹、`DRY-RUN` 和运行进度。
5. 正常等待时，状态最终变为“已完成”。
6. 再用一个新候选任务测试“取消任务”，状态应变为“已取消”。
7. 停止 ROS 网关后重试派发，数据库应保留 `DISPATCH_FAILED`，重新启动网关后可用相同参数重试。

## 安全停止线

- 后端、HTTP 网关和 Action Server 三层都不接受 `dryRun=false`。
- 技能 ID 只能来自白名单，参数只能是有限长度标识符。
- 网关不接受第二个并发活动任务。
- 不向 `/cmd_vel`、控制器、驱动或厂商 SDK 写入任何数据。
- 没有真实机器人型号、接口、安全责任和现场急停验证前，不把本 Demo 描述为真实设备控制。

## 已完成验证

- Java 编译通过；21 个铁矿相关针对性测试通过。
- 前端生产构建通过。
- ROS1 catkin 编译及 Action 消息生成通过。
- 三步任务实测得到 `DISPATCHED -> RUNNING -> SUCCEEDED`，并返回逐步 feedback。
- 慢速任务实测取消后得到 `CANCELED`。
- `dryRun=false` 实测被 HTTP 400 拒绝。
- 本地 PostgreSQL 新表迁移及 JSONB/指纹/状态字段复核通过。
