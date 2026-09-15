# 上下文选择评测

本目录实现 `CS-*` 独立评测协议，目标是在固定候选池和固定上下文预算下，区分候选召回、最终选择和回答利用三个阶段。它不修改或覆盖 `eval/iron-ore/` 的 B0～D2 历史实验。

数据分成两类文件：带 `answer` 和 `evidence_requirements` 的数据集只供校验、诊断和评分；候选快照不含答案、支持事实标签或 gold 位置，是 Java 选择器唯一允许读取的回放输入。原始公开数据、快照、模型响应和人工表均写入已被 Git 忽略的 `local-data/eval/context-selection/`。

当前离线快照可用 BM25 和 signed hashing 产生明确标记的代理特征，用来打通 P0/P1 和做可重复的机制冒烟。它们不是线上重排模型分数、支持概率或模型 embedding，正式延迟与费用也不能从离线耗时推断。

具体命令和阶段门槛见 [RUNBOOK](RUNBOOK.md)，当前执行状态见[状态文件](../../docs/iron-ore-rag/context-selection-status.md)。
