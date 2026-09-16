# 阶段 0：项目分析与本地基线复现

## 阶段结论

- 状态：已完成，后续不再扩展本阶段范围。
- 上游基线：Ragent `1.1.0`，提交 `f64de341452c8998ebf64cd264e60ccad6a31631`。
- 本地基线标签：`checkpoint/iron-ore-rag-baseline-1.1.0`。
- 已验证：本地中间件、后端、前端、知识库创建、小型 Markdown 上传、异步摄取、检索问答、行内引用和来源返回。
- 阶段 0 当时未验证或未实施：完整领域 Excel 摄取、工业数据模型、模板审核和外部执行适配；后续状态见[改动索引](../changes/README.md)。

环境改动原因、故障定位、验证证据和回滚方式见[可重复本地开发栈](../changes/2026-08-12-reproducible-local-development-stack.md)。一般复现只需阅读本页。

## 复现步骤

### 1. 确认代码与本机条件

```bash
git switch research/iron-ore-rag
git status --short --branch
```

使用 JDK 17、Docker Compose，以及能够执行 `npm ci` 的 Node.js/npm 环境。API 密钥只通过系统环境变量、密钥管理器或 IDE Password Safe 注入；不得写入仓库或 IDEA Run Configuration XML。

### 2. 启动中间件

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml up -d
docker compose -f resources/docker/dev/ragent-dev.compose.yaml ps
```

等待 PostgreSQL、Redis、RustFS 和 RocketMQ 就绪。端口、环境变量和重建说明见[本地中间件文档](../../../resources/docker/dev/README.md)。

### 3. 启动后端

在 IDEA 中运行：

```text
bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java
```

沿用 IDEA 中已经配置的模型和 Embedding 环境变量。默认后端地址为 `http://127.0.0.1:9090/api/ragent`。

### 4. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。Vite 会把 `/api` 请求代理到本地 `9090` 后端。

### 5. 最小冒烟验证

1. 登录并创建一个临时知识库。
2. 从 `resources/docs/knowledge/` 中选择一个小型 Markdown 示例上传，不必上传全部示例。
3. 等待文档状态变为摄取成功，并确认生成分块。
4. 提出一个能由示例正文直接回答的问题。
5. 确认回答包含正文事实、引用标记和来源文件名。
6. 删除临时会话、文档和知识库，避免影响下一次复现。

## 注意事项

- Compose 默认使用项目专属 Docker 命名卷。2026-09-16 本机按需将 RocketMQ 单独迁到外部 ext4 盘；启用外部卷时必须保持目标盘挂载，具体配置和回退边界见[中间件文档](../../../resources/docker/dev/README.md#仅迁移-rocketmq-存储)。
- PostgreSQL 初始化脚本只在空 Volume 第一次创建时执行。项目升级后若表结构不匹配，应先备份，再明确选择升级或重建。
- `docker compose ... down -v` 会永久删除本项目 PostgreSQL、Redis、RustFS 和 RocketMQ 数据；它不是普通停止命令。
- RocketMQ 5.2.0 在 Docker 所在文件系统使用率达到 90% 时会强制禁止写入，应长期保留足够空间。
- 当前 Redis 宿主机端口是 `6380`，不是常见的 `6379`。
- `resources/database/examples/intent_node_tutorial.sql` 是可选教程参考，不属于基础复现，也不会自动导入。
- `local-data/source/` 中的企业研究材料只在本机使用并由 Git 忽略；阶段 0 不摄取完整文件。
- 无需向协作者发送 API 密钥。需要运行模型链路时，优先复用 IDEA 进程已有环境变量。

## 阶段停止点

能够稳定完成一次“小文档上传 → 摄取 → 检索问答 → 来源返回”即结束本阶段。后续领域改造和评测结果由 `changes/` 单项文档记录，不再向复现手册追加实验流水。
