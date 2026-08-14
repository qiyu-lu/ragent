# Ragent 本地开发中间件

该 Compose 只用于本地开发，统一启动 Ragent 需要的 PostgreSQL/PGVector、Redis、RustFS 和 RocketMQ。默认端口与 `application.yaml` 的本地默认值一致，不包含模型 API 密钥。

## 启动

在项目根目录执行：

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml up -d
docker compose -f resources/docker/dev/ragent-dev.compose.yaml ps
```

首次创建 PostgreSQL Volume 时，会按顺序执行：

1. `resources/database/schema_pg.sql`
2. `resources/database/init_data_pg.sql`

已有 Volume 不会重复执行初始化脚本。若需要彻底重建本开发栈：

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml down -v
docker compose -f resources/docker/dev/ragent-dev.compose.yaml up -d
```

`down -v` 会永久删除本 Compose 项目的数据库、Redis、RustFS 和 RocketMQ 数据。执行前应按项目实施日志完成备份和目标核对。

## 本地覆盖

Compose 已提供与应用配置一致的开发默认值。如需覆盖，可复制 `.env.example` 为同目录 `.env`；`.env` 已被 Git 忽略。模型供应商、MinerU 等 API 密钥只通过系统环境变量、密钥管理器或 IDE Password Safe 注入，不写入 `.env` 或 IDEA Run Configuration XML。

RocketMQ 使用本 Compose 专属的 `rocketmq-data` 持久化命名卷，不依赖外置硬盘。开发配置把普通消息清理阈值 `diskMaxUsedSpaceRatio` 从默认 75% 提高到 88%；RocketMQ 5.2.0 仍会在存储文件系统使用率达到 90% 时强制禁止写入，而且该硬保护不能通过把配置写得更大来绕过。因此应让 Docker 所在文件系统长期保持在 90% 以下。

应用从宿主机启动时使用以下端点：

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `127.0.0.1:5432/ragent` |
| Redis | `127.0.0.1:6380` |
| RustFS S3 | `http://127.0.0.1:9000` |
| RustFS Console | `http://127.0.0.1:9001` |
| RocketMQ NameServer | `127.0.0.1:9876` |
| RocketMQ Dashboard | `http://127.0.0.1:8082` |

该配置不包含 Elasticsearch、LightRAG、Milvus 或真实机器人服务；这些能力不是当前知识闭环基线的前提。
