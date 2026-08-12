# ROS1 样品搬运 Demo 网关

该目录提供独立的 ROS1 Noetic 适配层。它把 Ragent 已批准的候选任务关联到一个固定的
`SAMPLE_TRANSPORT` 搬运子任务，再通过 `/iron_ore/execute_mission` Action 执行 dry-run。

它不会解析自然语言，不会发布 `/cmd_vel`、关节角或力矩，也不会连接真实设备。

## 协议边界

- HTTP 网关默认只监听 `127.0.0.1:18081`。
- 所有任务必须包含 `dryRun=true` 和 64 位计划指纹。
- 技能白名单只有 `NAVIGATE_TO_STATION` 与 `TRANSPORT_CONTAINER`。
- 网关和 Action Server 分别校验技能、参数、顺序与标识符。
- ROS1 Action 提供反馈、结果和取消；前端每秒查询一次网关快照。
- 当前 Action Server 只模拟耗时和进度，不控制机器人。

## 构建与启动

```bash
source /opt/ros/noetic/setup.bash
catkin_make -C robot-gateway/ros1_ws
source robot-gateway/ros1_ws/devel/setup.bash
roslaunch iron_ore_robot_demo demo.launch
```

正常启动应看到：

```text
ROS1 dry-run Action server ready: /iron_ore/execute_mission
ROS1 HTTP gateway listening on http://127.0.0.1:18081
```

可用下面的命令确认 Action 类型：

```bash
rostopic type /iron_ore/execute_mission/goal
rostopic type /iron_ore/execute_mission/feedback
```

停止 `roslaunch` 后，机器人任务的数据库记录仍会保留；网关内存中的运行快照会清空。

## 替换为真实机器人前

不要直接删除 `dryRun` 检查。应另行实现真实技能适配器，把白名单技能映射到机器人已有的
`actionlib`、导航、机械臂或厂商 SDK 接口，并增加急停、占用区检查、超时、权限、人工二次确认及现场验收。
