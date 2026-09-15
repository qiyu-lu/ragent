# 开发集公共语料池诊断

这是新增的 `CS-POOL` 实验，与旧 CS-DEV gate fail 并存。只使用 public_dev 的问题及候选正文构建全局语料池，不读取 public_test 来生成候选或计算策略结果。不是官方 HotpotQA fullwiki 实验。

本手册记录的 CS-POOL 后端为离线 BM25；Java 选择器沿用已有实现。后续新增真实向量召回/重排诊断另见 [LIVE_RUNBOOK](LIVE_RUNBOOK.md)，不覆盖本次代理结果。下方恢复条件是启动 CS-LIVE 前的历史检查记录。

## 已执行的准备、检索与评分

仓库根目录执行，输出已存在会拒绝覆盖；复跑请整体改用新版本目录。

```bash
python3 eval/context-selection/pooled_retrieval.py prepare \
  --dataset local-data/eval/context-selection/datasets/hotpot-cs-v1.jsonl \
  --output-dir local-data/eval/context-selection/datasets/CS-POOL-dev-v1

python3 eval/context-selection/pooled_retrieval.py retrieve \
  --corpus local-data/eval/context-selection/datasets/CS-POOL-dev-v1/corpus.jsonl \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl

python3 eval/context-selection/validate_snapshots.py \
  --snapshots local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl \
  --output local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.validation.json

python3 eval/context-selection/pooled_retrieval.py diagnose \
  --gold local-data/eval/context-selection/datasets/CS-POOL-dev-v1/gold.jsonl \
  --snapshots local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl \
  --output local-data/eval/context-selection/runs/CS-POOL-dev-v1/retrieval-diagnosis.json
```

来源去重键使用标题与句子序列，保留同标题不同正文的变体。所有原始 gold candidate ID 映射到稳定池内 ID；gold 和仅问题文件分开存储。检索器只接受白名单字段的问题文件与正文语料。

Top40 在整个池中计算 BM25，忽略无匹配的零分候选。随后复用旧快照构建逻辑，使用 Top40 内的 BM25 作为局部重排代理。这两阶段分数的索引范围不同，分别存于 source_scores 和 original_question_score，不混作同一分数。句级 gold 只在独立评分程序中读取。

```bash
python3 eval/context-selection/run_selection.py \
  --input local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl \
  --output local-data/eval/context-selection/runs/CS-POOL-dev-v1/CS-R.jsonl \
  --strategy CS-R --token-budget 1024 --max-chunks 10

python3 eval/context-selection/run_selection.py \
  --input local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl \
  --output local-data/eval/context-selection/runs/CS-POOL-dev-v1/CS-M-mu0.jsonl \
  --strategy CS-M --mu 0 --token-budget 1024 --max-chunks 10 --skip-compile

python3 eval/context-selection/score_selection.py \
  --dataset local-data/eval/context-selection/datasets/CS-POOL-dev-v1/gold.jsonl \
  --snapshots local-data/eval/context-selection/snapshots/CS-POOL-dev200-v1.jsonl \
  --arm CS-R=local-data/eval/context-selection/runs/CS-POOL-dev-v1/CS-R.jsonl \
  --arm CS-M-mu0=local-data/eval/context-selection/runs/CS-POOL-dev-v1/CS-M-mu0.jsonl \
  --output local-data/eval/context-selection/runs/CS-POOL-dev-v1/selection-score.json

python3 eval/context-selection/pooled_retrieval.py classify \
  --score local-data/eval/context-selection/runs/CS-POOL-dev-v1/selection-score.json \
  --output local-data/eval/context-selection/runs/CS-POOL-dev-v1/stage-attribution.json
```

`stage-attribution.json` 的分类互斥：先检查候选缺失，再检查 gold 是否可放入预算，之后检查选择遗漏，最后为证据完整。证据完整不意味着回答正确，answer_correctness 固定记为 not_evaluated。

## 本次检查与真实链路恢复条件

```bash
python3 -m unittest discover -s eval/context-selection/tests -p 'test_*.py'
git diff --check
```

2026-09-05 核查：Postgres、Redis、对象存储等依赖容器在运行，但 `127.0.0.1:9092` 当前评测后端拒绝连接（沙箱外只读复查同样失败）。本轮在线费用为 0。

已向用户提出本轮外部模型费用上限问题；尚未获得答复时不调用付费模型。预算到位后继续以下工作，而非再跑代理实验充当在线结果：

1. 核查模型提供商、计费和可用模型，预估 1,988 段正文 embedding、200 次查询 embedding/重排与可选回答费用；按实际 tokenizer 和调用接口计算，不将离线 token 估算当成服务账单。
2. 启动隔离的公共评测配置与新知识库，禁止覆盖旧 B0～D2 库。现有上传流程需要建立入库块与原池内段落/句子范围映射，不能直接比较入库生成的新 chunk ID 与 gold ID。
3. 增加当前服务候选导出和来源范围适配，捕获真实改写、召回、重排、最终上下文；重分块引起的证据遗漏单独归入解析/映射阶段。
4. 先跑固定开发小批次确认费用、证据映射，再完成 200 题。回答另行计分；无预算时保存“在线未完成”。

该真实链路适配尚未实现；不能仅凭此手册标记端到端完成。中文资料题草案见 [中文诊断题](../../docs/iron-ore-rag/chinese-diagnostic-drafts-2026-09-05.md)。
