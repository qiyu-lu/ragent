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

`down -v` 会永久删除本 Compose 管理的数据库、Redis、RustFS 和默认 RocketMQ 命名卷数据；外部卷另行管理。执行前应按项目实施日志完成备份和目标核对。日常停止请使用 `stop`，不要通过删除卷排查启动问题。

## 本地覆盖

Compose 已提供与应用配置一致的开发默认值。如需覆盖，可复制 `.env.example` 为同目录 `.env`；`.env` 已被 Git 忽略。模型供应商、MinerU 等 API 密钥只通过系统环境变量、密钥管理器或 IDE Password Safe 注入，不写入 `.env` 或 IDEA Run Configuration XML。

RocketMQ 默认使用本 Compose 专属的 `rocketmq-data` 持久化命名卷；也可通过下面两个变量单独切换到外部卷。开发配置把普通消息清理阈值 `diskMaxUsedSpaceRatio` 从默认 75% 提高到 88%；RocketMQ 5.2.0 仍会在存储文件系统使用率达到 90% 时强制禁止写入，而且该硬保护不能通过把配置写得更大来绕过。因此应让 RocketMQ 实际存储所在文件系统长期保持在 90% 以下。

### 仅迁移 RocketMQ 存储

2026-09-16 本机因系统分区使用率 92% 拒绝发送分块消息，单独将 RocketMQ 存储迁到 ext4 盘：

```text
目录：/media/sd101t/LiWeishuaiA/ragent-iron-ore-dev/rocketmq-store
外部卷：ragent-iron-ore-dev_rocketmq-external-data
原卷：ragent-iron-ore-dev_rocketmq-data（保留，不再写入）
```

本机 `resources/docker/dev/.env`（Git 忽略）使用：

```dotenv
RAGENT_ROCKETMQ_VOLUME_NAME=ragent-iron-ore-dev_rocketmq-external-data
RAGENT_ROCKETMQ_VOLUME_EXTERNAL=true
```

切换前必须停 Broker，完整复制原存储目录并核对内容、稀疏文件和 UID/GID，然后用 Docker `local` 驱动的 `type=none,o=bind,device=绝对目录` 创建外部卷，最后设置上述变量。**不要只设置变量、挂一个空目录就启动旧业务。** 迁移过程和实际验证统一记录在[任务 Agent 改动记录](../../../docs/iron-ore-rag/changes/2026-09-15-task-agent.md#2026-09-16rocketmq-存储迁移)。

日后仍使用本页开头的普通 `docker compose -f ... up -d` 命令，同目录 `.env` 会选择外部卷；没有这两个覆盖项的机器继续使用默认命名卷。机制见 [Docker 外部卷与指定宿主机路径文档](https://docs.docker.com/reference/compose-file/volumes/)。

启动前确认 `mountpoint /media/sd101t/LiWeishuaiA` 成功，使用期间不要拔盘或卸载。宿主机目录必须已存在，卷绑定不会自动创建缺失的数据目录。不要直接删掉 `.env` 切回旧卷：迁移后的新消息与消费进度只在新目录里，回迁也需要停机和重新同步。

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
