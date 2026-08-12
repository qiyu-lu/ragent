# 阶段 0：铁矿检测 RAG 基线复现记录（归档）

> 本文保留阶段 0 的详细实施与验证证据，阶段结束后不再追加新改动。新会话从[项目文档入口](iron-ore-rag/README.md)恢复上下文；后续改动按单项文件记录，并登记到[改动索引](iron-ore-rag/changes/README.md)。

## 文档用途

本文按检查点记录阶段 0 的实际变更、验证证据、Git 提交和回滚方式。它是历史证据快照，不再承担整个项目的持续实施日志；尚未完成的设计仍以项目入口、项目上下文或后续阶段文档为准。

固定约束：

- 代码基线锁定 Ragent `1.1.0`，上游提交 `f64de341452c8998ebf64cd264e60ccad6a31631`；
- 改造分支为 `research/iron-ore-rag`，使用独立 worktree；
- 每个阶段测试通过并提交后暂停确认，不自动推进下一阶段；
- 不向 Git 提交 API 密钥、原始调研材料、本地中间件数据或备份；
- RAG、模板审核、任务编排、机器人技能与安全控制保持独立边界。

## 本地样本登记

| 项目 | 值 |
| --- | --- |
| 文件 | `local-data/source/铁矿石人工检测流程调研V1.2.xlsx` |
| Git 状态 | 本地只读样本，已由 `.gitignore` 排除 |
| 大小 | `27067942` bytes |
| SHA-256 | `6fdc4cf3b94533faa497a85972e35fb7cab8b62d8d86139b82d8f947fec83bf8` |
| 当前用途 | 后续选择单个工作表或区域制作脱敏测试样本；阶段 0 不执行整表摄取 |

## 检查点 0A：项目上下文与材料隔离

- 日期：2026-08-12
- 状态：已完成
- 起始提交：`f64de341452c8998ebf64cd264e60ccad6a31631`
- 上下文提交：`eddeec0`（`docs: establish iron-ore RAG project context`）
- 材料隔离提交：`7da3042`（`chore: isolate local iron-ore research data`）
- 已完成：
  - 保存 1.1.0 源码分析、课题边界和阶段路线；
  - 确认原始 Excel 为 27 MB 本地研究材料，不进入 Git；
  - 保留用户已有的 Redis `6380` 本地配置修改，等待检查点 0B 转换为环境变量契约。
- 回滚：对已提交的文档检查点使用 `git revert eddeec0`；本地样本不受 Git 回滚影响。

## 检查点 0B：可重复开发环境

- 状态：配置已完成，运行验证转入检查点 0C
- 配置提交：`4962aad`（`chore: add reproducible local middleware stack`）
- 已完成：
  - 新增统一开发 Compose，纳管 PostgreSQL/PGVector、Redis、RustFS、RocketMQ Broker、NameServer 和 Dashboard；
  - 使用项目名 `ragent-iron-ore-dev` 与项目专属 Volume，避免影响其他 Docker 项目；
  - PostgreSQL 空卷按 `schema_pg.sql` → `init_data_pg.sql` 自动初始化；
  - 数据库、Redis、RocketMQ、S3 连接参数支持环境变量覆盖，并保留当前本地默认端口；
  - 增加开发环境说明和非敏感 `.env.example`，模型 API 密钥仍只由 IDEA 或系统环境变量提供。
- 静态验证：`docker compose config --quiet` 与 `application.yaml` YAML 解析通过；所需镜像已在删除旧环境前拉取完成。
- 回滚：回退本检查点提交即可撤销 Compose 和配置变更；中间件数据恢复方式记录在检查点 0C。

## 检查点 0C：基线验证

- 状态：已完成；暂停在领域改造之前
- 中间件修复提交：`b3a6085`（`fix(dev): harden local middleware storage`）
- 本地检查点标签：`checkpoint/iron-ore-rag-baseline-1.1.0`
- 旧环境备份：
  - PostgreSQL：`local-data/backups/20260812-pre-baseline-ragent.dump`，`1105398` bytes，SHA-256 `45e9fd3bf0f5eae024547157d0a76d87b8a60ec82ee7109423b5eb5c8e242fac`；
  - RustFS：`local-data/backups/20260812-pre-baseline-rustfs-data.tar.gz`，`154528` bytes，SHA-256 `233f0140b61078314ccc1b8ac8c1734b2f08109b5720dc5b4512c0da2314b0c1`；
  - 两份归档均已通过目录读取验证，且均由 `.gitignore` 排除。
- 重建边界：
  - 已删除旧 PostgreSQL `pgdata`、Redis 容器数据和 RocketMQ 容器数据；
  - 旧 `rustfs-data` 中还包含 `insurance`、`finance`、`productdocs` 等非当前 Ragent 桶，因此没有删除，作为额外恢复副本保留；
  - 新环境使用 `ragent-iron-ore-dev_postgres-data`、`ragent-iron-ore-dev_redis-data`、`ragent-iron-ore-dev_rustfs-data` 和 `ragent-iron-ore-dev_rocketmq-data` 四个项目专属卷。
- 中间件验证：
  - PostgreSQL、Redis、RustFS、RocketMQ NameServer 健康，Broker 与 Dashboard 正常运行；
  - RustFS 健康检查修正为 `/health`，避免把 S3 根路径的正常 `403 AccessDenied` 误判为故障；
  - 初次事务消息失败的根因是 Docker 根分区使用率为 91%，触发 RocketMQ 5.2.0 的 90% 硬写保护，Broker 明确记录 `message store is not writeable`；不是模型 API 或业务代码故障；
  - 按用户要求未使用可能拔除的移动硬盘；曾用于定位的移动硬盘临时目录已解除挂载并完整删除；
  - 用户清理磁盘后根分区降至 83%（约 148 GiB 可用），最终 RocketMQ 使用本机持久化命名卷；一次性初始化服务只负责设置卷根目录的 UID/GID 和权限；
  - 普通清理阈值 `diskMaxUsedSpaceRatio` 设为 88%，RocketMQ 实际加载值为 88；5.2.0 的 90% 硬写保护无法通过更大的配置值绕过，因此仍要求 Docker 文件系统保持低于 90%；
  - PostgreSQL 为 `ragent` 数据库，PGVector `0.8.6`，共 24 张业务表；
  - 初始化数据为 1 个用户、1 个 Agent Profile、6 个 Agent Prompt，旧文档和旧向量均为 0。
- 构建与离线测试：
  - `./mvnw spotless:check` 通过，后端全模块 `package` 通过，Spotless 未产生意外 Java 修改；
  - Framework 9 项、Infra AI 7 项、Bootstrap 选定测试 90 项，共 106 项通过；
  - 前端 `npm ci` 和生产构建通过；现有依赖报告 21 个审计漏洞，产物存在大 Chunk 警告，本阶段不擅自升级依赖。
- 应用冒烟：
  - 无模型密钥时后端可正常启动，并连接 PostgreSQL、Redis、RocketMQ 和 RustFS；MCP 示例端口 `9099` 未启动时按设计降级跳过；
  - 登录、当前用户、空知识库列表、临时知识库创建和 Markdown fixture 上传通过；
  - 在 IDEA 已配置密钥的后端上，`merchant-manual.md` 异步摄取两次均达到 `success`，每次生成 5 个分块；
  - 提问“资质提交后的审核时效是多少？”后，SSE 回答包含“3 个工作日”和行内引用 `[1](#cite-1)`，`finish` 事件返回来源 `merchant-manual.md`，消息状态为 `NORMAL`；
  - 另以“创建后事务删除空知识库”验证最终 Broker 可写：事务主题成功创建并写入，知识库进入 `deleted=1`，Broker 未再次出现磁盘写保护；
  - 临时知识库、文档和问答会话均已通过应用 API 清理；活动知识库、活动文档、活动分块、向量和活动会话计数均为 0；
  - 验证过程只复用 IDEA 进程已有环境变量，没有读取、复制或写入任何模型 API 密钥。
- 恢复：PostgreSQL 使用 `pg_restore` 恢复自定义格式备份；RustFS 停止新服务后将 tar 归档恢复到目标空卷。恢复前必须再次核对目标卷。

## 阶段 0 结论与暂停点

Ragent 1.1.0 的本地开发基线已经可重复启动，并通过了从上传、异步摄取、Embedding、向量检索、LLM 生成到来源返回的完整小样本闭环。当前没有实施铁矿领域元数据、文档版本、精确证据锚点、候选任务模板或审核流，也没有摄取 27 MB 原始 Excel。

下一阶段先从原始调研材料中人工选择一个小工作表或有限区域，制作可提交、可复现、必要时脱敏的最小样本与验收问题集；在用户确认前不进入领域数据模型或业务代码改造。
