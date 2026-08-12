# 阶段 0：项目分析与本地基线复现

## 阶段结论

- 状态：已完成，后续不再扩展本阶段范围。
- 上游基线：Ragent `1.1.0`，提交 `f64de341452c8998ebf64cd264e60ccad6a31631`。
- 本地基线标签：`checkpoint/iron-ore-rag-baseline-1.1.0`。
- 已验证：本地中间件、后端、前端、知识库创建、小型 Markdown 上传、异步摄取、检索问答、行内引用和来源返回。
- 未验证或未实施：完整铁矿调研 Excel 摄取、工业数据模型、模板审核、机器人和真实设备。

详细的逐项验证证据已归档在[阶段 0 实施记录](../../iron-ore-rag-implementation-log.md)。一般复现只需阅读本页。

## 复现步骤

### 1. 确认代码与本机条件

```bash
git switch research/iron-ore-rag
git status --short --branch
```

使用 JDK 17、Docker Compose，以及能够执行 `npm ci` 的 Node.js/npm 环境。API 密钥继续配置在 IDEA Run Configuration 或本机环境变量中，不写入仓库。

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

- Compose 使用项目专属 Docker 命名卷，数据保存在本机 Docker 存储中，不使用可能被拔除的 `sda1` 移动硬盘。
- PostgreSQL 初始化脚本只在空 Volume 第一次创建时执行。项目升级后若表结构不匹配，应先备份，再明确选择升级或重建。
- `docker compose ... down -v` 会永久删除本项目 PostgreSQL、Redis、RustFS 和 RocketMQ 数据；它不是普通停止命令。
- RocketMQ 5.2.0 在 Docker 所在文件系统使用率达到 90% 时会强制禁止写入，应长期保留足够空间。
- 当前 Redis 宿主机端口是 `6380`，不是常见的 `6379`。
- `resources/database/examples/intent_node_tutorial.sql` 是可选教程参考，不属于基础复现，也不会自动导入。
- `local-data/source/铁矿石人工检测流程调研V1.2.xlsx` 是本地只读研究材料并由 Git 忽略；阶段 0 不摄取完整文件。
- 无需向协作者发送 API 密钥。需要运行模型链路时，优先复用 IDEA 进程已有环境变量。

## 阶段停止点

能够稳定完成一次“小文档上传 → 摄取 → 检索问答 → 来源返回”即结束本阶段。下一阶段从原始调研材料中人工选择有限样本并建立评测基线，不在复现阶段继续堆功能。
