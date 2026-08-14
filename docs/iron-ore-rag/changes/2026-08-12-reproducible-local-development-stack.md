# 可重复的本地开发中间件与基线验证

## 记录信息

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-08-12 |
| 状态 | 已实施并完成基线验证 |
| 上游基线 | Ragent `1.1.0`，`f64de341452c8998ebf64cd264e60ccad6a31631` |
| 开发分支 | `research/iron-ore-rag` |
| Git 提交 | `eddeec0`：项目上下文；`7da3042`：本地材料隔离；`4962aad`：中间件栈；`b3a6085`：存储加固；`bcfba62`：验证记录 |

## 改动目的

领域功能开发前，需要先证明上游基线能够在不依赖历史容器状态的情况下重复启动，并走通“上传、摄取、检索、生成、引用和来源返回”。原来的本机服务与零散配置无法清楚回答数据库如何初始化、各服务使用哪些端口、数据保存在哪里，以及重建是否会误伤其他项目。

本改动因此先建立项目独立的 PostgreSQL/PGVector、Redis、RustFS 和 RocketMQ 开发栈，并把连接参数改为可由环境变量覆盖。目标是得到一个可复现、可隔离、可安全停止的 Java RAG 开发基线，而不是搭建生产级高可用环境。

## 问题如何发现

- 基线复现需要同时准备四类中间件，但当时没有单一入口描述启动顺序、初始化脚本、端口和持久化边界。
- 本机 Redis 使用 `6380`，若继续保留散落的本地配置，其他开发环境很容易按默认 `6379` 启动后连接失败。
- 首次验证事务消息时，知识库删除操作未能成功写入 RocketMQ。Broker 日志明确显示 `message store is not writeable`；检查 Docker 所在文件系统后发现使用率为 91%。
- RustFS 的 S3 根路径在未授权访问时正常返回 `403 AccessDenied`，原健康检查却可能把它误判为服务异常。

这些现象说明主要问题是环境契约和存储状态，而不是模型 API、知识库业务代码或检索算法。

## 根因

1. 项目缺少统一 Compose，服务名称、端口、初始化方式和数据卷归属依赖本机既有状态。
2. RocketMQ 5.2.0 在存储文件系统达到 90% 时有不可绕过的硬写保护；仅调大普通清理阈值不能恢复写入。
3. RustFS 健康检查使用了不合适的 S3 根路径，混淆了“服务可用”和“匿名请求无权限”。
4. PostgreSQL 初始化脚本只在空 Volume 首次创建时运行；如果没有把该行为写入契约，旧表结构和新代码可能被误认为是一次正常启动即可自动同步。

## 方案取舍

- 使用项目专属 Compose 项目名和命名卷，避免复用其他 Ragent 实例的数据，也不依赖可能被拔除的外置硬盘。
- 保留适合本地开发的非敏感默认值，同时允许通过 `.env` 或系统环境变量覆盖；模型和文档解析服务密钥不进入 Compose、Git 或说明文档。
- PostgreSQL 只在空卷中依次执行 `schema_pg.sql` 和 `init_data_pg.sql`。已有数据库必须显式选择升级、恢复或重建，不能由启动过程静默改表。
- RocketMQ 的普通磁盘清理阈值设置为 88%，用于在硬保护前留出余量；文档仍明确要求 Docker 文件系统低于 90%，不把配置项描述成硬保护开关。
- RustFS 健康检查改用 `/health`，以服务健康端点判断状态，而不是依赖匿名 S3 访问结果。

这种方案比共享本机服务多占用一组开发卷，但换来了明确的数据归属、重建边界和故障定位依据，适合项目演示与后续评测。

## 实现

- 新增 `resources/docker/dev/ragent-dev.compose.yaml`，统一编排 PostgreSQL/PGVector、Redis、RustFS、RocketMQ NameServer、Broker 和 Dashboard。
- 新增非敏感的 `resources/docker/dev/.env.example`，真实 `.env` 由 Git 忽略。
- 调整 `bootstrap/src/main/resources/application.yaml`，使数据库、Redis、消息队列和对象存储连接均可由环境变量覆盖。
- 使用四个项目专属持久化命名卷保存 PostgreSQL、Redis、RustFS 和 RocketMQ 数据。
- 为 RocketMQ 卷初始化目录权限，并记录 88% 普通清理阈值与 90% 硬写保护的区别。
- 在 [`resources/docker/dev/README.md`](../../../resources/docker/dev/README.md) 中保留当前启动、端口、覆盖配置和破坏性清理说明。

## 验证

- `docker compose config --quiet` 和应用 YAML 解析通过。
- PostgreSQL、Redis、RustFS、RocketMQ NameServer 均达到健康状态，Broker 与 Dashboard 正常运行。
- PostgreSQL 使用 PGVector `0.8.6`，空卷初始化后共有 24 张业务表；初始化数据为 1 个用户、1 个 Agent Profile 和 6 个 Agent Prompt，活动文档与向量为 0。
- 后端全模块 `package` 通过；Framework 9 项、Infra AI 7 项、Bootstrap 90 项，共 106 项定向测试通过。
- 前端 `npm ci` 和生产构建通过。构建同时保留了既有依赖审计告警和大 Chunk 告警，不把它们误写成本改动已解决的问题。
- 无模型密钥时，后端可以完成启动并连接全部本地中间件；未运行的可选 MCP 示例按配置降级跳过。
- 在运行环境提供模型凭据后，小型 Markdown 文档两次异步摄取都成功，每次生成 5 个分块；固定问题的流式回答包含正确事实、行内引用和来源文件名。
- 清理临时会话、文档和知识库后，活动知识数据恢复到验证前状态。
- 清理磁盘使 Docker 文件系统使用率降到 83% 后，事务消息可以正常写入，知识库删除事务成功完成，Broker 不再报告写保护。

## 效果与限制

改动把原先依赖本机历史状态的启动过程收敛为一份 Compose 和一份明确的配置契约，并用真实的摄取、检索和事务消息路径证明环境可用。RocketMQ 故障也被定位为磁盘硬保护，而没有通过修改业务代码或更换模型掩盖环境问题。

当前证据只说明单机开发基线可重复运行，不代表生产级高可用、容量规划或灾备已经完成。Docker 所在文件系统仍必须长期低于 RocketMQ 的 90% 硬保护线；外部模型、Embedding、Rerank 和 MinerU 的可用性也不由本地 Compose 保证。

## 数据与回滚

- 普通停止使用 `docker compose ... down`，命名卷会保留；`down -v` 会永久删除本项目的数据库、缓存、对象和消息数据，不能作为日常停止命令。
- 数据库升级或重建前应先确认目标 Compose 项目并分别备份关系数据与对象存储。恢复时只写入已确认的目标空环境。
- 回退 `4962aad` 或 `b3a6085` 只会回退代码和配置，不会自动删除已有 Docker 卷；卷是否保留必须单独核对。
- 模型凭据继续通过系统环境变量、密钥管理器或 IDE Password Safe 注入，不写入 `.env.example`、Compose 或 Git。
