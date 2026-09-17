# PostgreSQL 数据库脚本

## 全量初始化

- `schema_pg.sql`：最新版本的完整表结构
- `init_data_pg.sql`：最新版本的初始化数据

新环境直接按顺序执行这两个文件，不需要再执行历史升级脚本

## 增量升级

本仓库固定在 Ragent `1.1.0` 基线，`upgrades/v1.1.0/` 保存该基线及本 fork 后续功能的增量脚本。空数据库直接使用最新全量脚本；已有环境只执行尚未应用、且与目标功能对应的升级脚本。

目录内的脚本使用首次提交日期和变更含义命名：

- 默认格式：`yyMMdd_变更含义.sql`
- 同一天有多个脚本时：`yyMMdd_两位顺序号_变更含义.sql`

已有环境执行前必须备份，并按文件名顺序逐个应用所需脚本，不合并过程脚本。后续版本在 `upgrades/` 下新建对应目录并继续使用相同命名规则。

已经被执行过的升级脚本保持不变，新的数据库变更继续追加独立脚本。

## 2026-09-17：旧执行演示退役

P1 已移除送检、任务模拟与 ROS1 代码。新环境的 `schema_pg.sql` 不再创建对应表；过渡期仍保留 `t_iron_ore_task_template`，供旧草稿生成、查询和人工确认使用，P5 替代后再移除其新建结构。

已有环境手工应用 `upgrades/v1.1.0/260917_retire_execution_demo.sql`，停用指向已删除 `iron_ore_simulate_task` 的意图节点，再清除当前应用 Redis 的意图树缓存 `ragent:intent:tree`（使用应用实际连接的实例与 database）。也可在管理页面停用该节点，由现有服务清除缓存。仅重启后端不会清除 Redis 缓存；下一次分类在缓存缺失时从数据库重建。该脚本可重复执行，不删除任何表或业务数据。仅把 SQL 放入此目录不会自动迁移；本轮没有对已有数据库执行此脚本或清除其缓存。

`260915_task_agent.sql`、`260812_ros1_robot_mission.sql` 与 `260812_iron_ore_demo.sql` 保留为历史升级记录，不能作为新环境的追加初始化流程。历史执行表停止访问；已有 SIMULATED 草稿只在过渡期视图中按已确认草稿展示，不回读模拟记录，也不改写历史数据。

## 2026-09-17：研究证据存储（P2 首批）

新环境的 `schema_pg.sql` 已包含 `t_research_run`、`t_research_evidence` 和 `t_research_event`。已有环境手工执行 `upgrades/v1.1.0/260917_02_research_evidence.sql`；脚本可重复执行，仅增加新表、索引和约束，不改写历史草稿或退役业务数据。仓库没有自动应用这个脚本的 Flyway 流程。

本批仅接入任务归属检查、纯知识检索与块级证据快照读写；任务调度、租约、取消、事件写入和模型调用在 P3/P4 实现。`event_sequence` 为后续原子序号分配预留，事件表主键不能代替并发分配器。

可运行 `bash scripts/validate-agentic-research-p2-database.sh`，在开发 PostgreSQL 容器中随机创建隔离库，验证新建 schema 与两次增量执行的结构一致、历史草稿保留和存储约束。`P2_POSTGRES_CONTAINER` 可覆盖容器名；脚本只删除本次成功创建的测试库，不能视为已有业务环境已升级。

## 示例与参考

`examples/` 只保存可选教程或功能参考，不参与全量初始化和增量升级：

- `examples/intent_node_tutorial.sql`：教程中的闲聊、情感反馈和 MCP 意图节点示例。
- `examples/iron_ore_demo_intents.sql`：工业知识闭环的幂等意图示例，只在创建唯一目标知识库后按文件头说明手工执行。

示例脚本可能包含固定主键、外部工具 ID 或非幂等 `INSERT`。执行前必须阅读文件头说明，并核对目标数据库现有数据；基础 RAG 复现不需要执行这些脚本。
