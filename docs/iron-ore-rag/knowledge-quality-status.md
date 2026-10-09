# 知识入库质量与检索改进：状态

计划：[knowledge-quality-plan-2026-10-09.md](knowledge-quality-plan-2026-10-09.md)（2026-10-09 修订版）。本文件 ≤ 40 行，每个会话结束时更新。命令全文见 [eval/kq/README.md](../../eval/kq/README.md) 的"阶段 1 运行顺序"。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-10-09（阶段 1 会话完成；等用户核对题集、入库、基线、解析实验） |
| 当前阶段 | 阶段 1 用户运行中。下个会话（阶段 2）先读 `local-data/kq-eval/runs/` 与 `mineru-raw/` 的结果、写门槛，再动解析代码 |
| 分支 / 标签 | `feat/knowledge-quality`（从 `a07e04d`）；`kq-v0-baseline` = 443e4e2；`kq-s1` 等基线与实验结果回填后再打 |
| 回归基线 | 2026-10-09 开工复跑与收工复跑均为：Python 53/53、Java 13 + 211、p2 通过；新增 `python3 -m unittest discover -s eval/kq/tests`（23 个） |
| 评测数据 | `local-data/kq-eval/`：`corpus-kq-s1.json`（7 份、SHA-256）、`questions/questions-draft.jsonl`（120 题）、`numeric-facts-draft.jsonl`（141 条，另 82 条备选在 `-extra`）、`review-draft.md`（核对视图）、`textlayer/`、`config/application-kq-s1.yaml`；库 `ragent_eval_kq_s1` 已建（schema + init，空库）；RustFS 桶 `ragent-sources-kq-s1`/`ragent-assets-kq-s1` 已由应用自动建好；`ragent_eval_kq_s2` 未建 |

## 进度
- [x] 阶段 1 会话：`POST /rag/eval/replay`（`rag/eval/`，`EvalControllerTest` 6 个用例）；`eval/kq/` 9 个脚本 + 23 个测试 + 归一化用例；120 题（45/25/20/15/15，77 题口语化，调参/测试对半）与 141 条事实全部锚点可在原文定位（`verify_dataset.py --strict` 通过）；评测实例按 `application-kq-s1.yaml` 起得来，接口链路冒烟通过（空库、假 key）
- [ ] 阶段 1 用户：核对并冻结题集与清单 → 入库 7 份 → 冒烟 1 题看重排分 → 基线 `S1-base` 调参集、测试集各 3 次 → 解析实验 a→c（d 视情况）
- [ ] 阶段 2 解析质量闸门与知识治理：写门槛；按实验结果定改法；闸门、NFKC、清洗；文档元数据、术语表、boost；重新入库；两臂评测
- [ ] 阶段 3 中文全文通道与融合：阶段 2 改动说明；通道、jieba + 术语表、tsvector 迁移、BM25；rrf / thr 两臂（blend 可选）；评测
- [ ] 收尾：阶段 3 改动说明；简历映射；ff 合并

## 用户要跑的命令（每个会话结束时写，跑完清空；`B=http://127.0.0.1:9093/api/ragent`，`QD=local-data/kq-eval/questions`，全文与循环写法见 eval/kq/README.md）
1. 核对 `$QD/review-draft.md`，改 `questions-draft.jsonl` / `numeric-facts-draft.jsonl` 本身；`python3 eval/kq/verify_dataset.py --strict` 通过后复制为 `questions-v1.jsonl` / `numeric-facts-v1.jsonl`，再 `python3 eval/kq/verify_dataset.py --questions $QD/questions-v1.jsonl --facts $QD/numeric-facts-v1.jsonl --strict --write-manifest eval/kq/manifests/kq-s1-dataset.json`（冻结，哈希进仓库）
2. 导出 `BAILIAN_API_KEY`、`SILICONFLOW_API_KEY`、`MINERU_API_KEY`；`docker ps` 确认 `rocketmq-broker-1` 与 `nameserver-1` 都在；启动评测实例：`--spring.config.additional-location=file:/home/sd101t/IdeaProjects/ragent-iron-ore-rag/local-data/kq-eval/config/application-kq-s1.yaml`（9093 / `ragent_eval_kq_s1` / Redis DB 13 / MQ 主题后缀 `_kq_s1`）
3. 入库并审计：`python3 eval/kq/prepare_kb.py --base $B --output local-data/kq-eval/runs/setup-s1.json`；`python3 eval/kq/audit_chunks.py --base $B --output local-data/kq-eval/runs/S1-chunks/chunks.jsonl`；`python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --chunks local-data/kq-eval/runs/S1-chunks/chunks.jsonl --label S1-base --output local-data/kq-eval/runs/S1-chunks/parse-metrics.json`
4. 冒烟 1 题：`python3 eval/kq/run_retrieval.py --base $B --label smoke --arm S1-base --split tune --repeat-index 0 --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl --sub-questions $QD/sub-questions-smoke.jsonl --record-sub-questions --ids num-si-02`，看 `raw_response.results[0].candidates` 里 `rerankHead=true` 的候选都有 `rerankScore`，且不等于 `channelScore`（相等说明回退到了 rerank-noop）
5. 基线：调参集与测试集各 3 次，r1 带 `--record-sub-questions`，r2/r3 回放（README 里有循环）；汇总 `python3 eval/kq/compare_retrieval_repeats.py --arm S1-base local-data/kq-eval/runs/S1-base-tune-r1/retrieval.json …r2… …r3… --output local-data/kq-eval/runs/S1-base-tune-summary.json`，测试集同理后封存不再看
6. 解析定位实验，一步一看：a `python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+硅含量的测定+重量法.pdf" --enable-formula false --note "step a"`；b `… --file "local-data/source/硫铁矿和硫精矿中硅含量的测定+重量法.pdf" --is-ocr true --note "step b"` 与 `… --file "local-data/source/铁矿石+全铁含量的测定+三氯化钛还原法.pdf" --note "step b2"`；c 硅含量与硫铁矿各加 `--model-version vlm`。每步后 `python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --full-md <probe目录>/full.md --doc <corpus id> --label <step> --output <probe目录>/parse-metrics.json`；H3 排除：`probe.json` 的 `markdown.digits_nd` 与 `S1-chunks/chunks-summary.json` 同文档 `digits_nd` 应接近

## 门槛（阶段 1 基线之后、阶段 2 开工前填写，填写后不改）
- 阶段 2 闸门：（待填）　阶段 2 boost：（待填）　阶段 3：（待填）
## 解析定位实验结论（阶段 1 用户跑完后填）
- a 硅含量关公式：（待填）　b 硫铁矿 OCR / 全铁默认：（待填）　c vlm：（待填）　H3 排除：（待填）
## 结果摘要（只填实测值，带实验臂、样本量和条件）
- （无）

## 遗留问题与计划外发现
- RocketMQ：nameserver 的 unhealthy 只是健康检查 grep 的启动日志被轮转；真正停的是 broker（2026-09-22 退出）。本会话 `docker restart` nameserver 后 `docker compose -f resources/docker/dev/ragent-dev.compose.yaml up -d rocketmq-broker`，broker 已注册。根分区仍 96%，docker 在根盘，入库前留意
- `IngestionReuseCommand` 走 Tika 而非 MinerU，不能当入库兜底（计划 §4.1 的备选作废）；入库只走正常 API（`prepare_kb.py`）
- 重排分已写回 `RetrievedChunk.score`（BaiLian 客户端 `toBuilder().score()`），但只写头部对象，融合尾部仍带通道分；接口按每题 Rerank topN 截头部报 `rerankScore`，尾部为 null（`request-level-refill-enabled=false` 时 topN 按均分，与 `RetrievalEngine` 同一算法）。评测实例的 YAML 列表按下标合并、不覆盖 `ai.*.candidates`，模型回退只能从 `rerankScore` 识别（见命令 4）
- `pdftotext -layout` 在 GB/T 6730.10 的双栏处会打乱拆行小数的顺序（`1.\n00%~\n00%。\n15.`），锚点校验同时接受 PyMuPDF 读法；数字计数仍以 `-layout` 为准。硫铁矿文字层是乱码，其事实清单由读图人工写入（`source: manual`），题目锚点只能对清单匹配
- 会话里没有模型 key，冒烟只验证了接口链路；"候选块里有重排分"由用户的命令 4 核对。`docs/iron-ore-rag/notes/面试问答.md` 有用户未提交的修改，任何提交都不要带上
