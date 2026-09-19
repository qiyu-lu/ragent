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

P1 已移除送检、任务模拟与 ROS1 代码，P5 已用研究 artifact 替代旧草稿与审批入口。当前 `schema_pg.sql` 不再创建退役表或 `t_iron_ore_task_template`；已有表停止访问，不在启动时 DROP。过渡期的旧草稿视图也已随 P5 退役。

停用指向已删除 `iron_ore_simulate_task` 的意图节点的升级脚本 `260917_retire_execution_demo.sql` 已于 2026-09-19 在本机 `ragent` 执行后随瘦身删除（`git show slim-v0-baseline:resources/database/upgrades/v1.1.0/260917_retire_execution_demo.sql`）。

`260812_iron_ore_demo.sql` 保留为历史升级记录（`260915_task_agent.sql`、`260812_ros1_robot_mission.sql` 已随瘦身删除，由 `slim-v0-baseline` 标签留存），不能作为新环境的追加初始化流程。历史执行表停止访问。过渡期曾将 SIMULATED 草稿按已确认草稿展示；该视图已在 P5 移除，当前应用不再读取，历史数据仍原样保留。

## 2026-09-17：研究证据存储（P2）

新环境的 `schema_pg.sql` 已包含 `t_research_run`、`t_research_evidence` 和 `t_research_event`。已有环境按顺序手工执行尚未应用的 `upgrades/v1.1.0/260917_02_research_evidence.sql` 与 `upgrades/v1.1.0/260917_03_research_neighbors.sql`；已应用首批 SQL 的环境只需执行后者。脚本可重复执行，仅增加新表、索引和约束，不改写历史草稿或退役业务数据。仓库没有自动应用这些脚本的 Flyway 流程。

当前已实现任务归属检查、纯知识检索、块级/邻接快照、调度、租约/epoch、取消和事件写入。邻接快照通过 `origin_evidence_id` 关联同一运行内原候选并保留首次展开；事件序号由 run 行原子 UPDATE 分配。REPORT/PLAN artifact 和终态事件原子提交，SSE 查询不负责调度。

公开资料的稳定主键映射使用 `260917_04_research_corpus.sql`；已有环境在 02、03 之后手工应用它，新 schema 已包含对应表。导入及运行说明见研究交接。本轮只验证隔离新库/升级库，未对业务数据库执行迁移。

可运行 `bash scripts/validate-agentic-research-p2-database.sh`，在开发 PostgreSQL 容器中随机创建隔离库，验证新建 schema 与两次增量执行的列、默认值、约束及索引一致、历史草稿保留和存储约束。`P2_POSTGRES_CONTAINER` 可覆盖容器名；脚本只删除本次成功创建的测试库，不能视为已有业务环境已升级。

使用 `P2_RUN_JAVA_TESTS=true bash scripts/validate-agentic-research-p2-database.sh` 可追加运行 `ResearchEvidencePostgresIT`，验证实际 Java 存储、PGVector 文档过滤、MyBatis 来源读取和 Spring 只读 REPEATABLE READ 邻接事务。需本机 JDK 17、可离线解析的 Maven 依赖及容器映射的 PostgreSQL 端口。连接凭证由脚本临时读取为子进程环境变量，不打印到日志；测试只接受本机随机 `research_p2_` 数据库，结束后由同一脚本清理。合成向量及正文夹具不代表公开语料导入或模型效果验证。
