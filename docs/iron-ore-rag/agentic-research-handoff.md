# 统一研究工作流：运行与交接

当前用户入口是聊天页的“普通问答 / 深入分析 / 生成计划”。普通问答继续使用现有 RAG；后两者共享研究运行器、证据服务和最终生成，以 REPORT/PLAN 区分产物。送检、工位预约、草稿批准、执行模拟和 ROS 运行代码已退役。

P0—P8 已完成实现与本轮约定验证。实际实现、逐阶段验证和公开数据结果分别见[实施计划](agentic-research-implementation-plan-2026-09-17.md)、[执行日志](agentic-research-execution-log.md)、[验证报告](agentic-research-validation-report.md)、[固定对照](agentic-research-evaluation-report.md)与[应用原文核对](agentic-research-application-review.md)。[最终交接清单](../../eval/agentic-research/manifests/research-p8-handoff-2026-09-18.json)保存配置、源码、日志和演示引用 SHA。程序用例、受控浏览器和真实供应商评测是不同证据。

## 1. 环境与启动

需要 JDK 17、Maven Wrapper、Node.js/npm 和 Docker Compose。开发栈包含 PostgreSQL/PGVector、Redis、RustFS、RocketMQ，端口和已有卷配置见[开发栈说明](../../resources/docker/dev/README.md)。已有本机配置包含外部 RocketMQ 卷，启动时沿用其 `.env`，不要为了重建研究环境删除业务卷。

```bash
docker compose -f resources/docker/dev/ragent-dev.compose.yaml up -d
./mvnw -o -pl bootstrap -am -DskipTests package
npm --prefix frontend ci
npm --prefix frontend run dev
```

首次依赖未缓存时移除 Maven 命令的 `-o` 下载依赖；`-o` 用于本机已有缓存的复现。

后端在 IDE 中运行 `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`，默认端口 9090；前端默认 5173。模型 key 经环境或 IDE 密钥设施注入：研究模型复用 `BAILIAN_API_KEY`，query embedding 使用 `SILICONFLOW_API_KEY`。运行普通文档摄取时仍需现有解析、对象存储等配置；研究 Agent 不依赖送检种子或 ROS。

研究配置位于 [application.yaml](../../bootstrap/src/main/resources/application.yaml)。当前研究项 `research-flash` 指向 `qwen3.7-flash-2026-07-15`，thinking=false、temperature=0，无静默回退；普通聊天的模型档位和回退保持独立。AgentScope Java 固定 2.0.1，仅 core 与 OpenAI 模型扩展。

## 2. 数据库与资料

新环境使用当前 `schema_pg.sql` 和原有通用初始化方式，详见[数据库说明](../../resources/database/README.md)。已有数据库只手工执行尚未应用的增量脚本：

1. `260917_retire_execution_demo.sql` 停用退役意图；随后通过管理页面或针对实际 Redis database 清除意图树缓存。
2. `260917_02_research_evidence.sql` 创建 run/evidence/event。
3. `260917_03_research_neighbors.sql` 创建首次邻接快照的关联与唯一索引。
4. `260917_04_research_corpus.sql` 创建公开语料的来源文档映射。

这些文件不会被当前项目自动迁移；旧表停止访问，应用不自动 DROP 历史业务数据。阶段验证只作用于随机隔离库，不能称为已有业务环境完成迁移。

公开 QASPER/MuSiQue 的下载、转换、幂等导入见[评测工具说明](../../eval/agentic-research/README.md)。本机已导入 `research_corpus_v1` 的训练/开发语料；它是独立评测库，在线生产 RAG 不会自动搜索这个库。MuSiQue 保留 AVAILABLE_EXCERPT 和每题 distractor 范围，不称为 Wikipedia 全文检索。答案和支持标注仅进入离线评分器。

## 3. 一个研究任务怎样运行

用户选择知识库和输出类型，POST `/rag/research/runs` 提交 clientRequestId、goal、outputType、allowedKbIds，可附文档限制和会话 ID。首次幂等创建后由后端自行调度；缺省会话时在同一短事务建会话。知识库仍采用现有共享规则，运行、事件、证据和会话按 owner 隔离。

主 Agent 用原生 search/read 工具查证据，观察后可补查。独立比较方向可通过 `conduct_research` 分派 worker；worker 只注册 search/read/finish，有独立上下文，不接收兄弟完整历史。缺少关键用户条件时 `ask_user` 进入 WAITING_INPUT，用户通过带 revision 的 input 请求继续。

最终生成共用 brief、主/子发现和已读证据快照；REPORT 生成引用段落，PLAN 生成结构化草稿。结构、引用错误最多修复一次。引用编号由程序映射，未知参数保持 null/待确认，用户限制保存为 user_input。合法 artifact 与 ARTIFACT/终态事件在同一 epoch/lease 保护的短事务提交。

GET 查询与 SSE 都只读。SSE 的 after/Last-Event-ID 是事件游标；断线后任务继续，刷新先取快照再订阅，不新增模型任务。来源面板使用实际读过的快照，最终编号以 artifact.citations 为准。

## 4. 额度、取消和失败

默认一个运行累计 16 次模型、24 次工具、300 秒活动时间，预留 2 次最终生成；人工等待不计活动时间。主和 worker 共用模型配额 2；worker 最多 2 个并行、累计 4 个、每个最多 6 次模型调用和 180 秒。输入窗口按 28000 token 估算裁剪，输出最多 4096；估算不是供应商计费 token。

主动 cancel 先写 CANCELLED 并撤销租约，再传播到父/子 SDK 与 HTTP。旧 epoch 和取消后的迟到结果不能发布产物；本地 HTTP 结束不能证明远端停止计算或计费。单 JVM 重启将失去执行者的任务标为 INTERRUPTED，保留证据；重新发起使用新 clientRequestId，当前不恢复模型中间 token。

部分 worker 失败或预算退出可保留其他成功发现，合法产物落 PARTIAL。最终生成失败落 FAILED，保留研究摘要与失败诊断；非法产物不会公开。COMPLETED 表示正常形成合法产物，不证明资料完整、引用语义正确或计划可执行。

## 5. 复现与演示

```bash
# 程序与隔离 PostgreSQL 故障回归，无供应商调用
bash scripts/validate-agentic-research-p7.sh

# 浏览器夹具：真实 React/研究 HTTP/SDK/PG，登录、检索、模型与普通问答受控
./mvnw -o -pl bootstrap -am -DskipTests test-compile
./mvnw -o -pl bootstrap dependency:build-classpath -Dmdep.outputFile=/tmp/research-browser-classpath.txt
python3 eval/agentic-research/browser_research.py --classpath /tmp/research-browser-classpath.txt --run-dir local-data/agentic-research/runs/<new-browser>

# 固定公开样本：不加 --execute 只准备请求
python3 eval/agentic-research/evaluate_research.py --profile smoke --run-dir local-data/agentic-research/runs/<new-smoke>
python3 eval/agentic-research/evaluate_research.py --profile regression --run-dir local-data/agentic-research/runs/<new-regression> --execute

# 完整带标注 split 的执行能力；费用和规模更大，不代表本轮已执行
python3 eval/agentic-research/evaluate_research.py --profile full --run-dir local-data/agentic-research/runs/<new-full>

# 固定 12 比较 / 12 计划；产物引用原文供独立核对
python3 eval/agentic-research/evaluate_applications.py --run-dir local-data/agentic-research/runs/<new-applications> --execute

# 两道一次检索问题 + 一个双资料比较 REPORT + AMR 摘要 PLAN（要求未披露 batch/window 保持 null/待确认），默认仅生成请求
bash scripts/demo-agentic-research.sh
# 指定真实执行，会消耗供应商额度
bash scripts/demo-agentic-research.sh --execute
```

每次真实运行外发公开问题、检索片段和已读正文，并消耗配置供应商额度。每题预算与批次生成费用估算上限分别生效；embedding 金额和 unknown usage 需单列，不把估算当账单。`--resume` 仅执行同目录下未记录完成的任务，要求源码、配置和数据指纹一致；已经失败的任务也保留，不自动重刷。需要修复后重测时创建新批次。

A 对照复用项目的一次知识检索和固定命中块，统一生成模型；没有包含生产聊天的改写、意图/MCP、会话历史和回退。B 关闭委派工具，C 按需委派；报告记录实际是否创建 worker。QASPER 使用多标注者最大答案/段落匹配，MuSiQue 答案/支持只评分可回答行，并单列全部行的可回答性；固定抽样不报告完整成对 sufficiency 指标。

生产登录、真实来源下载预览、完整 Web 栈和现场业务效果的验证边界以验证报告为准；受控浏览器通过不能替代这些结论。

演示 REPORT 使用冻结 comparison-05（Agatha 链接数据与 multilingual Bayesian SRI）；PLAN 使用 plan-06（AMR Bank/CNN-Dailymail 摘要复现）。这些资料来自完整 24 项应用核对，演示本身不代替固定回归。真实运行可能 PARTIAL/FAILED，应查看每项状态及引用正文。当前 v4 生成证据输入隔离语料元数据，但合法引用仍可能对应错误推断；残缺公式、字段缺失与运行预算/资料缺口混淆均有实际负例。

本轮实际演示 `20260917T192824_P8_demo_v4` 记录四个合法产物：两道 A 题 COMPLETED，比较/计划均 PARTIAL，因模型调用预算退出。10 条引用快照由 Codex 对照原文检查；不是独立人工盲评。一次检索的 Partridge Lake 题只回答了 Bering Sea 中间实体；English datasets 题没有遵守 Yes/No 极短格式。比较只读到一个 Organization/event 短句，没有完成两论文比较。计划主要步骤有引用，但训练集/AMR Bank 限定及全流程评价仍需核对；batch/window 仅列待确认，未生成请求要求的 null 参数条目。演示不是语义正确率或完整复现执行证明。

后续先处理原生 finish 稳定性、有效正文阅读、字段修复定位、运行问题与资料缺口区分，再以新批次复测。固定评测中 C 的两个实际委派请求均为不可回答行，不能将其可回答题 F1 写成多 Agent 收益。full 17517 任务、生产登录、真实来源下载预览和已有业务库迁移仍未执行。
