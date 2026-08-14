# 评测运行手册

以下命令都从当前仓库根目录执行。基线后端为 `9091 / ragent_eval_baseline / Redis DB14`，当前后端为 `9092 / ragent_eval_current / Redis DB15`。只运行一个后端，切换数据库状态前必须先停止后端并确认摄取任务已经结束。

以下命令按本项目 Compose 容器名直接在容器内调用 PostgreSQL/Redis 客户端，因此不会把密码写入命令、YAML、报告或 Git。若改用宿主机客户端，则去掉 `--postgres-container` / `--redis-container`，并分别通过 `PGPASSWORD`、`REDISCLI_AUTH` 提供密码。

运行前应确认 PostgreSQL、Redis、RustFS、RocketMQ 均健康，并由运行环境提供 `BAILIAN_API_KEY`、`SILICONFLOW_API_KEY`、`MINERU_API_KEY`。缺少任一实际使用的外部服务凭据时停止该实验，不切换到其他模型补跑。

## 1. 一次性预检与空库快照

```bash
python3 eval/iron-ore/verify_dataset.py
python3 -m unittest discover -s eval/iron-ore/tests -v
```

在两个数据库仍为空时分别保存 seed：

```bash
python3 eval/iron-ore/archive_run.py \
  --output-dir local-data/eval/snapshots/baseline-seed \
  --database ragent_eval_baseline \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --checkout /home/sd101t/IdeaProjects/ragent-iron-ore-baseline-1.1.0 \
  --config local-data/eval/config/application-eval-baseline.yaml \
  --snapshot-db

python3 eval/iron-ore/archive_run.py \
  --output-dir local-data/eval/snapshots/current-seed \
  --database ragent_eval_current \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --checkout /home/sd101t/IdeaProjects/ragent-iron-ore-rag \
  --config local-data/eval/config/application-eval-current.yaml \
  --snapshot-db
```

若快照目录已经存在，工具会拒绝覆盖。应检查并保留原快照，而不是删除后悄悄重建。

## 2. 启动设置

IDEA 中清空 Active profiles。基线 `RagentApplication` 参数：

```text
--spring.config.additional-location=file:/home/sd101t/IdeaProjects/ragent-iron-ore-rag/local-data/eval/config/application-eval-baseline.yaml --mineru.ocr=false
```

当前版参数：

```text
--spring.config.additional-location=file:/home/sd101t/IdeaProjects/ragent-iron-ore-rag/local-data/eval/config/application-eval-current.yaml --mineru.ocr=false
```

OCR 实验只把最后一个参数改成 `--mineru.ocr=true`。不要启用 `iron-ore-demo` 或其他 Spring profile。

## 3. 基线 B0

启动 `bcfba62` 基线，确认 OCR 关闭，然后导入：

```bash
python3 eval/iron-ore/prepare_kb.py \
  --base http://127.0.0.1:9091/api/ragent \
  --output local-data/eval/runs/B0/setup.json

python3 eval/iron-ore/audit_chunks.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B0 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --ocr off --setup-manifest local-data/eval/runs/B0/setup.json \
  --output local-data/eval/runs/B0/parse-audit.json

python3 eval/iron-ore/run_retrieval.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B0 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --intent-mode off --ocr off \
  --setup-manifest local-data/eval/runs/B0/setup.json \
  --output local-data/eval/runs/B0/retrieval.json

python3 eval/iron-ore/run_answers.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B0 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --intent-mode off --ocr off \
  --setup-manifest local-data/eval/runs/B0/setup.json \
  --output local-data/eval/runs/B0/answers.json
```

在仍为无意图状态时保存主基线数据库：

```bash
python3 eval/iron-ore/archive_run.py \
  --output-dir local-data/eval/snapshots/baseline-B0-ingested \
  --database ragent_eval_baseline \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --checkout /home/sd101t/IdeaProjects/ragent-iron-ore-baseline-1.1.0 \
  --config local-data/eval/config/application-eval-baseline.yaml \
  --result local-data/eval/runs/B0/retrieval.json \
  --result local-data/eval/runs/B0/answers.json \
  --snapshot-db
```

## 4. 基线 B1 与 B2

B1 不重新导入。停止基线、开启意图、清 Redis，随后以 OCR 关闭状态重启：

```bash
python3 eval/iron-ore/set_intent_mode.py on \
  --database ragent_eval_baseline \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --redis-container ragent-iron-ore-dev-redis-1

python3 eval/iron-ore/run_retrieval.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B1 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --intent-mode on --ocr off \
  --setup-manifest local-data/eval/runs/B0/setup.json \
  --output local-data/eval/runs/B1/retrieval.json
```

B2 需要干净重建。停止后端，双重确认后恢复 seed：

```bash
python3 eval/iron-ore/restore_eval_database.py \
  --database ragent_eval_baseline \
  --confirm-database ragent_eval_baseline --yes-replace \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --redis-container ragent-iron-ore-dev-redis-1 \
  --dump local-data/eval/snapshots/baseline-seed/ragent_eval_baseline.dump
```

以 `--mineru.ocr=true` 重启基线，然后：

```bash
python3 eval/iron-ore/prepare_kb.py \
  --base http://127.0.0.1:9091/api/ragent \
  --output local-data/eval/runs/B2/setup.json

python3 eval/iron-ore/audit_chunks.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B2 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --ocr on --setup-manifest local-data/eval/runs/B2/setup.json \
  --output local-data/eval/runs/B2/parse-audit.json

python3 eval/iron-ore/run_retrieval.py \
  --base http://127.0.0.1:9091/api/ragent \
  --label B2 --variant baseline --server-commit bcfba6201fc5a7b73bf6b2ae743ed1fef6c2afb6 \
  --intent-mode off --ocr on --family native_pdf --family scan_pdf \
  --setup-manifest local-data/eval/runs/B2/setup.json \
  --output local-data/eval/runs/B2/retrieval.json
```

B2 完成后停止后端，并用同一个恢复工具恢复 `baseline-B0-ingested/ragent_eval_baseline.dump`。基线数据库最终停留在 B0 状态，此后比较读取冻结 JSON，不再进入基线源码。

## 5. 当前版 C0、C1、C2

当前版使用端口 `9092`、commit `b375a7e5493a09d35af498b6c241536440ecdd1c`。流程与基线相同：

1. 恢复 `current-seed`，OCR 关，导入并保存 `local-data/eval/runs/C0/{setup,parse-audit,retrieval}.json`。
2. 将 `run_retrieval.py` 参数设为 `--label C0 --variant current --intent-mode off --ocr off`，运行完整 24 题。
3. 用 `archive_run.py --snapshot-db` 保存 `local-data/eval/snapshots/current-C0-ingested`。
4. 用与上文相同的两个 `--*-container` 参数执行 `set_intent_mode.py on --database ragent_eval_current`，重启后运行 C1 的完整 24 题检索。
5. 恢复 `current-seed`，以 OCR 开重启、重新导入，运行 C2 的解析审计和 12 道 PDF 检索。
6. 保存 `local-data/eval/snapshots/current-C2-ingested`，以便 C-final 选择 OCR 后直接恢复对应状态。

C0/C1/C2 的 `--server-commit` 均使用完整 `b375a7e5493a09d35af498b6c241536440ecdd1c`。除端口、数据库、标签和 OCR/意图状态外，所有命令参数与 B0/B1/B2 相同。

## 6. 决定并运行 C-final

```bash
python3 eval/iron-ore/select_final_config.py \
  --current-default local-data/eval/runs/C0/retrieval.json \
  --current-intent local-data/eval/runs/C1/retrieval.json \
  --parse-ocr-off local-data/eval/runs/C0/parse-audit.json \
  --parse-ocr-on local-data/eval/runs/C2/parse-audit.json \
  --output local-data/eval/runs/C-final/config-decision.json
```

工具不会挑最好看的结果，而按以下固定门槛输出：

- 意图：Top-1 ≥ 90%，三个正向文档族 Hit@5 都不下降，路由纯度不下降。
- OCR：扫描 PDF 至少多恢复 1/5 锚点，原生 PDF 不损失锚点。

根据输出恢复 `current-C0-ingested` 或 `current-C2-ingested`。恢复和意图切换均带上文相同的两个 `--*-container` 参数；若意图为 `on`，执行 `set_intent_mode.py on --database ragent_eval_current`，否则执行 `off`。用相同 OCR 参数重启后，运行 C-final 的 24 题检索和 24 个完整回答。

## 7. 盲评与汇总

B0 与 C-final 均完整成功后：

```bash
python3 eval/iron-ore/prepare_review.py \
  --arm B0=local-data/eval/runs/B0/answers.json \
  --arm C-final=local-data/eval/runs/C-final/answers.json \
  --output local-data/eval/review/review-v1.jsonl \
  --key local-data/eval/review/review-v1-key.json
```

不要打开 key。逐行填写布尔字段；不可回答题只需填写 `refusal_correct` 与 `no_forbidden_claim`，其他题填写四个回答字段。完成后：

```bash
python3 eval/iron-ore/score_review.py \
  --review local-data/eval/review/review-v1.jsonl \
  --key local-data/eval/review/review-v1-key.json \
  --output local-data/eval/review/score-v1.json
```

若有任何必填字段仍是 `null`，评分程序会拒绝出分。最终报告保留逐题结果和分文档族结果，再从中制作不含企业原文、标准长摘录或密钥的简历摘要。

```bash
python3 eval/iron-ore/render_summary.py \
  --b0-retrieval local-data/eval/runs/B0/retrieval.json \
  --c0-retrieval local-data/eval/runs/C0/retrieval.json \
  --c-final-retrieval local-data/eval/runs/C-final/retrieval.json \
  --human-score local-data/eval/review/score-v1.json \
  --decision local-data/eval/runs/C-final/config-decision.json \
  --output local-data/eval/review/evidence-summary-v1.md
```

只有用户确认摘要没有敏感信息后，才把其中的聚合数字转写到简历或项目文档。

## 8. D2 请求级公平回填固定回放

D2 是 D1 之后的单变量诊断，不重跑在线改写，也不重建索引。保存的六份报告由服务端提交 `fa1bc3f001d0926e0b1a9ba4dea954fc8ecf9c70` 产生；随后 `d2f0b1f` 修正配对恢复门槛，`37fc9d0` 将目标锚点原文移出 Git，两者都不改变服务端检索行为。以下命令复现这组已保存证据；若以后用其他提交重跑，必须如实替换 `--server-commit` 并保存到新目录，不能混入本组结果。

先停止当前后端，确认 `ragent_eval_current` 仍是 `current-D1-chunk-purity`。若状态已经改变，只在 D2 开始前恢复一次 D1 快照：

```bash
python3 eval/iron-ore/restore_eval_database.py \
  --database ragent_eval_current \
  --confirm-database ragent_eval_current --yes-replace \
  --postgres-container ragent-iron-ore-dev-postgres-1 \
  --redis-container ragent-iron-ore-dev-redis-1 \
  --dump local-data/eval/snapshots/current-D1-chunk-purity/ragent_eval_current.dump
```

之后 off/on 必须复用这一个数据库，禁止再次恢复、导入或调用 `prepare_kb.py`。固定回放加载器会校验 dataset、corpus、D1 setup manifest 三个 SHA，并逐题校验问题 ID、文本和非空去重的 `subIntents`；预期值见[D2 详情](../../docs/iron-ore-rag/changes/2026-08-14-request-level-fair-refill.md)。D1 回放源是：

```text
local-data/eval/runs/D1-chunk-and-purity/retrieval.json
```

以 OCR 关闭、意图关闭状态启动当前后端。每次切换实验臂都要停止并重启后端，在第 2 节当前版参数后追加对应值：

```text
--rag.search.request-level-refill-enabled=<false|true>
```

先定义单轮命令；`mode` 必须和当前后端开关一致：

```bash
run_d2() {
  mode="$1"
  repeat_index="$2"
  python3 eval/iron-ore/run_retrieval.py \
    --base http://127.0.0.1:9092/api/ragent \
    --label "D2-refill-${mode}-r${repeat_index}" --variant current \
    --server-commit fa1bc3f001d0926e0b1a9ba4dea954fc8ecf9c70 \
    --intent-mode off --ocr off --concurrency 1 \
    --setup-manifest local-data/eval/runs/D1-chunk-and-purity/setup.json \
    --fixed-rewrites-from local-data/eval/runs/D1-chunk-and-purity/retrieval.json \
    --refill-mode "${mode}" --repeat-index "${repeat_index}" \
    --output "local-data/eval/runs/D2-request-level-refill/${mode}-r${repeat_index}.json"
}
```

保存证据时实际采用以下交错顺序，减少运行时段只偏向某一臂。每个注释处都要按标注值重启后端，六轮之间不恢复数据库、不重新入库：

```bash
# 后端 false
run_d2 off 1
# 后端 true
run_d2 on 1
run_d2 on 2
# 后端 false
run_d2 off 2
run_d2 off 3
# 后端 true
run_d2 on 3
```

比较六份报告并执行预注册 gate：

```bash
python3 eval/iron-ore/compare_retrieval_repeats.py \
  --off local-data/eval/runs/D2-request-level-refill/off-r1.json \
  --off local-data/eval/runs/D2-request-level-refill/off-r2.json \
  --off local-data/eval/runs/D2-request-level-refill/off-r3.json \
  --on local-data/eval/runs/D2-request-level-refill/on-r1.json \
  --on local-data/eval/runs/D2-request-level-refill/on-r2.json \
  --on local-data/eval/runs/D2-request-level-refill/on-r3.json \
  --output local-data/eval/runs/D2-request-level-refill/comparison.json
```

工具会拒绝覆盖已有报告；复跑时新建带日期或序号的目录，不删除既有证据。比较器要求 off/on 各恰好三份 24 题报告、重复编号为 1～3、固定 `subIntents` 完全一致，并校验每题 `retrievalDiagnostics` 与开关标签相符。

本次结果为 gate fail：on 虽在每次重复中补满原先留空的 19 题，但总体 Hit@5、Context Precision 和路由纯度越过退化门槛。完成后把后端恢复为：

```text
--rag.search.request-level-refill-enabled=false
```

无需重建知识库。本阶段按 stop gate 结束；不要通过增加重复次数、放宽门槛或增大 TopK 回填成绩。
