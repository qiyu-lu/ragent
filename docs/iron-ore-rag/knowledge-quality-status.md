# 知识入库质量与检索改进：状态

计划：[knowledge-quality-plan-2026-10-09.md](knowledge-quality-plan-2026-10-09.md)（2026-10-09 修订版）。本文件 ≤ 40 行，每个会话结束时更新。命令全文见 [eval/kq/README.md](../../eval/kq/README.md) 的"阶段 1 运行顺序"。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-10-09 深夜（阶段 1：入库、冒烟、基线 6 次已完成；解析定位实验待用户跑） |
| 当前阶段 | 阶段 1 收尾：用户跑解析实验 a→c。之后开阶段 2 会话：先读本文件、`runs/S1-chunks/parse-metrics.json`、`mineru-raw/*/probe.json`，写门槛，再按 §5 定闸门设计 |
| 分支 / 标签 | `feat/knowledge-quality`；`kq-v0-baseline` = 443e4e2；基线跑在 59b858c；`kq-s1` 等解析实验结论回填后打 |
| 回归基线 | 2026-10-09 开工与收工复跑均为：Python 53/53、Java 13 + 211、p2 通过；`python3 -m unittest discover -s eval/kq/tests`（25 个） |
| 评测数据 | `local-data/kq-eval/`：题集冻结为 `questions/questions-v1.jsonl`（120）+ `numeric-facts-v1.jsonl`（141），哈希在 `eval/kq/manifests/kq-s1-dataset.json`；子问题 `questions/sub-questions-v1.jsonl`（120，r1 在线改写记录，47 条与原句相同）；库 `ragent_eval_kq_s1` 已入 7 份 224 块（`runs/setup-s1-resume.json`）；切片导出与审计 `runs/S1-chunks/`；基线 `runs/S1-base-{tune,test}-r{1,2,3}/` 与 `-summary.json`；无效首轮在 `runs/invalid-2026-10-09-noop-rerank/` |

## 进度
- [x] 阶段 1 会话：`POST /rag/eval/replay`；`eval/kq/` 脚本与测试；库与桶；120 题 + 141 条事实（用户已核对冻结）；评测实例起得来
- [x] 阶段 1 用户（部分）：入库 7 份全部 success；冒烟通过（重排头部有模型分，改写在线）；基线 `S1-base` 调参集、测试集各 3 次，0 失败，无 noop 回退
- [ ] 阶段 1 用户（剩余）：解析定位实验 a→c（d 视情况）与 H3 排除 → 阶段 2 会话写门槛与实验结论、打 `kq-s1`
- [ ] 阶段 2 解析质量闸门与知识治理；[ ] 阶段 3 中文全文通道与融合；[ ] 收尾（改动说明、简历映射、ff 合并）

## 用户要跑的命令（每个会话结束时写，跑完清空；先 `export B=http://127.0.0.1:9093/api/ragent QD=local-data/kq-eval/questions`，评测实例带 `MINERU_API_KEY` 不必运行——探针直接调 MinerU）
1. 解析定位实验，一步一看，每步一次 MinerU 额度：a `python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+硅含量的测定+重量法.pdf" --enable-formula false --note "step a"`；看数字是否回到约 1,250、空槽是否消失。b `… --file "local-data/source/硫铁矿和硫精矿中硅含量的测定+重量法.pdf" --is-ocr true --note "step b"` 与 `… --file "local-data/source/铁矿石+全铁含量的测定+三氯化钛还原法.pdf" --note "step b2"`。c 硅含量与硫铁矿各加 `--model-version vlm --note "step c"`。a 到 c 说不清再做 d（6 份 × ocr × formula）
2. 每步之后：`python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --full-md <probe目录>/full.md --doc <corpus id> --label <step> --output <probe目录>/parse-metrics.json`（corpus id 见 `local-data/kq-eval/corpus-kq-s1.json`）；H3 排除：`probe.json` 里 `markdown.digits_nd` 与 `runs/S1-chunks/chunks-summary.json` 同文档的 `digits_nd` 应接近（当前默认参数下硅含量切片 587、取样 5862）

## 门槛（阶段 1 基线之后、阶段 2 开工前填写，填写后不改）
- 阶段 2 闸门：（待填）　阶段 2 boost：（待填）　阶段 3：（待填）
## 解析定位实验结论（阶段 1 用户跑完后填）
- a 硅含量关公式：（待填）　b 硫铁矿 OCR / 全铁默认：（待填）　c vlm：（待填）　H3 排除：（待填）
## 结果摘要（只填实测值，带实验臂、样本量和条件）
- `S1-base` 检索层（59b858c，默认解析与检索配置，固定子问题回放 3 次，均值｜极差）。调参集 61 题（可答 53）：Hit@5 0.642｜0.000，MRR 0.589｜0.022，上下文精度 0.095｜0.004，文档召回 0.950｜0.038，返回块 9.18；数值题 22 题 Hit@5 0.530｜0.045，混淆题 8 题 0.667｜0.125，调研题 10 题 0.967｜0.100；不可答 8 题返回块 9.58、最高重排分 0.645｜0.010。测试集 59 题（可答 52，封存）：Hit@5 0.622｜0.058，MRR 0.518｜0.048，上下文精度 0.078，数值题 23 题 Hit@5 0.507，混淆题 7 题 0.429
- `S1-base` 解析层（141 条事实，`runs/S1-chunks/parse-metrics.json`）：覆盖率 69.5%（原文匹配）/ 73.0%（LaTeX 去壳后），数字保留率 0.81，空槽 1.14/千字，噪声行 52。分文档：硅含量 2/24 → 5/24，保留率 0.47，空槽 8.95/千字；取样制样 3/21 → 4/21，保留率 0.73，43 块含 `$`；全铁 22/24；YS/T 360.2 18/18；360.3 17/17；调研表 22/22；硫铁矿 14/15（MinerU 默认参数已对扫描页 OCR，切片 427 个数字，文字层只有 68）

## 遗留问题与计划外发现
- 基线里嵌入接口有快速失败：6 次共 22 个子问题的向量通道 <1 s 内空返回（约 3%），每次 2～5 题零块且题目各不相同，这是极差的主要来源之一；另有每次 11～15 个子问题通道耗时 5～14 s，p95 约 15 s。阶段 2 开工前决定是否给嵌入调用加重试（属基础设施，不算检索改动），加了要重跑基线
- 取样制样（GB/T 10322.1，76 页、多表格）丢数字程度仅次于硅含量，计划 §1.1 没料到；a 步实验建议也对它跑一次 `--enable-formula false`
- RocketMQ 之前停的是 broker（非 nameserver），已 `compose up -d rocketmq-broker`；根分区 96%。`IngestionReuseCommand` 走 Tika 不走 MinerU，不能当入库兜底
- 评测实例的 key 必须在启动它的进程环境里；首轮基线因 Bailian 401 回退 noop/原句而作废，`run_retrieval.py` 现在会在两种回退时退出 2。`docs/iron-ore-rag/notes/面试问答.md` 有用户未提交的修改，任何提交都不要带上
