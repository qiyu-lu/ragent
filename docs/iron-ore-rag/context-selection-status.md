# 上下文选择执行状态

最新实验：BM25 / 向量 / RRF 及各自重排的 500 题六臂比较已完成。Hotpot 最终完整证据为向量重排 187/200、混合重排 185/200；SciFact 全 5,183 篇、300 题，混合重排 Recall@10=91.66%、nDCG@10=0.7856，略高于向量重排 90.42%/0.7786。默认向量方案不变，保留混合检索可选评测配置；无中文工业或回答准确率收益声明。详见 [混合检索记录](changes/2026-09-05-hybrid-retrieval-comparison.md) 与 [HYBRID_RUNBOOK](../../eval/context-selection/HYBRID_RUNBOOK.md)。本轮临时服务与 ES 容器已停止，数据保留。

最新补充：2026-09-05 CS-LIVE 真实检索诊断已完成 200 题。1,988 篇公开正文入库为 1,991 块及同 ID 向量，481 个支持句全部可定位；Top40 完整证据 192/200，最终 Top10 为 182/200（91%）。互斥失败为选取丢失 10、召回缺失 3、服务超时 5，所有首次失败均保留；10 个选取丢失均为 bridge 多跳题。改写仅完成 5 题冒烟，不生成回答，不宣称新增算法收益。临时 9093 服务已停止，隔离库和数据保留；IDEA 密钥只经进程环境复用。已发生在线调用，实际费用尚未对账。结果见 [真实诊断记录](changes/2026-09-05-live-pooled-retrieval.md)，恢复见 [LIVE_RUNBOOK](../../eval/context-selection/LIVE_RUNBOOK.md)。下方 P0～P4 是已冻结的历史 CS-DEV 状态，不代表新增 CS-LIVE。

此前 CS-POOL 离线诊断为 Top40 完整证据 173/200、CS-R 最终 141/200、相关性/token 贪心 132/200；10 道中文来源题草案人工复核仍 pending。历史结果见 [公共池诊断记录](changes/2026-09-05-pooled-retrieval-diagnosis.md) 和 [POOLED_RUNBOOK](../../eval/context-selection/POOLED_RUNBOOK.md)。

更新日期：2026-09-05。分支：`research/iron-ore-rag`。起始 HEAD：`05b9137d841e139cd0457af95369a14d16736404`。

## 保护边界

- 保留执行前 WIP：`README.md`、`docs/iron-ore-rag/README.md` 的修改，以及未跟踪的 `project-study-guide.md` 和执行计划。
- `local-data/` 已整体 Git ignore；历史 B0～D2 报告、快照、数据库备份和原始材料不改名、不覆写。
- 新产物使用 `CS-*` 标签。不提交、不推送，不恢复旧数据库，不触碰机器人执行链。
- 不使用 `academic-research-suite` skill。

## P0：冻结与最小诊断（公共层完成，中文层缺人工 gold）

已完成：

- 核对分支、HEAD、WIP、Java 17、Maven Wrapper 3.9.11、Python 3 环境；仓库及上级目录未发现适用 `AGENTS.md`。
- 核对真实默认预算：请求总 `contextTopK=10`、每通道 `recallBudget=20`、融合候选 `candidateLimit=40`、D2 公平回填默认关闭。
- 确认历史 `local-data/eval/runs/` 与快照仍在，仅作为只读回归输入。
- 新建与旧 24 题协议分离的 `eval/context-selection/`：数据 schema、候选快照 schema、确定性 HotpotQA 抽样、来源/标签/划分校验、gold 隔离快照和最小测试。
- 核对 HotpotQA 官方协议：distractor 每题提供候选段落与句级 supporting facts，数据许可 CC BY-SA 4.0。官方 CMU 下载地址在本环境 30 秒连接超时，改用 `hotpotqa/hotpot_qa` 镜像 revision `1908d6a`；3 个 Parquet 的 SHA-256 均与源记录一致。
- 按种子 `20260905` 生成并校验 200 public_dev + 400 public_test。开发/测试支持文档重叠为 0；抽样前排除 22 条 train、1 条 validation 的越界 supporting-fact 标注。
- 30 题初诊中来源前缀 CS-P 为 1/30、问题重排 CS-R 为 3/30 目标失败，均未达 10 例门槛；扩到完整 200 题后，CS-R 有 16/200（8.0%）目标失败，公共层门槛通过。CS-P 另有 21/200，但不把原始候选顺序作为唯一排序基线。

待完成及门槛：

- 新增中文业务开发集目前为 0/20。历史 24 题只可用于接入回归，不能替代新增样本；执行代理未代签人工复核。

## P1：候选回放与评测（离线机制链完成）

- 候选快照与带 gold 数据集物理分离。快照保存稳定 ID、正文/rankingText 哈希、元数据、来源顺序、原问题与要点评分矩阵、评分执行状态、向量、token、身份/哈希/降级和耗时字段。
- Java 输入类型没有 answer、expected facts 或 evidence requirements；Python 单独加载 gold 评分。快照校验器还拒绝这些顶层字段。
- 当前只完成 HotpotQA 固定候选的离线特征回放：BM25、signed hashing 和本地 token 估算。未捕获真实线上 rerank/embedding 分数，也未声称离线耗时是在线延迟。
- `CS-P1-public-dev200-timed.jsonl` 补记了逐题离线评分、向量和 token 计数耗时；与主开发使用的 P0 快照逐题候选/分数特征 200/200 等价，未改写既有运行。

## P2：选择模块（完成并保持未接入默认产品）

- 新增唯一的 Java 选择实现和 JSONL 批量入口，支持 CS-P/R/M/C、no-cover 映射和 no-diversity 消融；Python 不复制选择算法。
- 9 个 Java 测试覆盖互补证据、重复挤占、单要点、空候选、预算装不下、确定性并列、缺失评分、多要点归因、版本/数字差异和 gold 输入隔离。
- 选择模块没有接到聊天默认路径。开发结果不支持上线，legacy 行为保持不变，也没有复用 D2 开关语义。

## P3：开发实验（完成，gate fail）

- 冻结 12 组 CS-C 和 4 组 MMR 参数后运行 18 臂。1024 预算下：CS-P `179/200`，CS-R `184/200`，最强简单基线 CS-M μ=0 `198/200`，最佳 CS-C `198/200`。
- CS-C 相对最强简单基线逐题改进 0、退化 0，差值 0 个百分点；低于 +5 个百分点门槛。μ=0.3 时覆盖降至 `126/200`，说明冗余惩罚会过早停止。
- 512 预算为简单基线 `159/200`、CS-C `160/200`（+0.5 个百分点）；2048 两者均 `200/200`。结论仍不支持继续。
- 按 gate 未运行 400 道 public_test，未查看其逐题策略结果，也未调参回看冻结测试。

## P4：端到端与费用（按 gate 跳过）

- 未调用在线 rerank、embedding 或回答模型，费用和服务端 token 均为 0；未上传非公开工业材料。
- 未运行 1440 条三臂回答或 360 条稳定性追加回答。中文人工盲评也未启动。

## 最终验证

- `python3 -m unittest discover -s eval/context-selection/tests -p 'test_*.py'`：4 项通过。
- `python3 eval/context-selection/validate_dataset.py ...`：600/600 有效；两个 200 题开发快照均通过 gold-free schema 校验。
- `./mvnw -o -pl bootstrap -am -Dtest=DeterministicContextSelectorTest,RequestLevelChunkSelectorTest,RetrievalEngineTest,RerankPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.apply.skip=true test`：26 项通过，0 失败。
- `./mvnw -o -pl bootstrap spotless:check`、Markdown 本地链接检查和 `git diff --check` 均通过；`git ls-files local-data` 为空。

## 本地产物与下一步

- 数据与校验：`local-data/eval/context-selection/datasets/hotpot-cs-v1*`。
- 无 gold 快照：`local-data/eval/context-selection/snapshots/CS-P0-public-dev200.jsonl`。
- 带离线阶段耗时的等价快照：`local-data/eval/context-selection/snapshots/CS-P1-public-dev200-timed.jsonl`。
- 主开发结果：`local-data/eval/context-selection/runs/CS-DEV-main-v1/`；决策文件为 `development-decision.json`。
- 敏感性结果：`local-data/eval/context-selection/runs/CS-DEV-sensitivity-v1/`。
- 结束条件为“验证无增益而保持关闭”。若以后重启，先由人工完成按来源组隔离的 20 条中文开发 gold，并创建新标签；不得回调本轮 public_test 或覆写这些产物。
