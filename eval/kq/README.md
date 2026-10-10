# 知识入库质量与检索评测（kq）

对应 [`docs/iron-ore-rag/knowledge-quality-plan-2026-10-09.md`](../../docs/iron-ore-rag/knowledge-quality-plan-2026-10-09.md) 的阶段 1～3。
服务端接口是 `POST /rag/eval/replay`（`rag/eval/EvalController`，`ragent.eval.enabled=true` 时注册）：
不带 `subQuestions` 时在线改写一次并把子问题追加到 `ragent.eval.rewrite-log`；带上则回放，隔离在线改写。
返回每个子问题的候选池（id、docId、文档名、通道分、重排分、是否最终入选）与请求级最终选择。

题集、数值事实清单、原文锚点、MinerU 原始输出、运行结果都在 Git 忽略的 `local-data/kq-eval/`；
仓库只放脚本、Schema、脱敏示例、配置样例和 manifest（哈希与汇总）。

| 文件 | 作用 |
| --- | --- |
| `evalkit.py` | 归一化（NFKC、单位、空白）、LaTeX 去壳、Unicode 数字计数、空槽与噪声统计、锚点匹配、API 客户端、检索打分与汇总 |
| `normalization_cases.json` | 归一化用例，`evalkit.normalize` 必须全部通过；阶段 2 的 Java 归一化函数读同一份 |
| `pdf_textlayer.py` | `pdftotext -layout` 抽文字层并按 Unicode 类别计数（半角、全角、PUA），写 `textlayer-summary.json` |
| `mineru_probe.py` | 直接调 MinerU v4 API（`is_ocr` / `enable_formula` / `model_version`），保存 zip 与 `full.md`，输出数字数、空槽、耗时、哈希 |
| `verify_dataset.py` | 校验题集与事实清单：结构、文档哈希、分层分布、每个锚点可在原文定位（扫描件用人工核对的事实清单） |
| `prepare_kb.py` | 通过正常 API 建库、按计划的分块参数导入语料、等待入库，写 setup manifest |
| `audit_chunks.py` | 导出入库切片为 JSONL，并统计每份文档的块数、数字、空槽、噪声、含 `$` 的块 |
| `parse_metrics.py` | 解析层四指标：数值事实覆盖率（原文 / LaTeX 去壳两列）、数字保留率、每千字空槽、噪声行 |
| `run_retrieval.py` | 跑题集：首轮 `--record-sub-questions` 落盘子问题，之后回放；按题型汇总 Hit@5、锚点召回、MRR、上下文精度、文档召回、返回块数 |
| `compare_retrieval_repeats.py` | 每臂多次重复的均值与极差；`--thresholds manifests/kq-thresholds.json` 用阶段 2 开工时写定的门槛与运行有效性规则判 improved / unproven / regressed，否则以基线极差为门槛 |
| `doc_metadata.py` | 阶段 2：导出入库抽取的文档元数据与解析审计供核对（`export`），把核对后的值写回为 confirmed（`confirm`） |
| `verify_boost_beta.py` | 阶段 2：从重排头部的排序反推每次运行实际用的 boost β，核对与臂名一致，并检查空通道上限 |
| `run_stage2_remaining.py` | 阶段 2：无人值守跑完剩下的运行（自己启停实例、按臂设 β、检查并重跑作废的、对比、按规则选 β、跑测试集），`--plan` 只看计划 |
| `config/application-kq-s1.example.yaml` | 评测实例的附加配置样例（端口 9093、库 `ragent_eval_kq_s1`、Redis DB 13、独立桶与 MQ 主题） |
| `config/application-kq-s2.example.yaml` | 阶段 2 实例（端口 9094、库 `ragent_eval_kq_s2`、Redis DB 14）：闸门与归一化打开，boost 关，检索配置与阶段 1 相同 |
| `manifests/kq-thresholds.json` | 阶段 2、3 的门槛与运行有效性规则（2026-10-09 写定，`kq-s1`），对比脚本读它 |
| `tests/` | `python3 -m unittest discover -s eval/kq/tests` |

## 数据格式

题集一行一题，见 [`schemas/questions.schema.json`](schemas/questions.schema.json) 与 [`examples/questions.example.jsonl`](examples/questions.example.jsonl)：
`type` ∈ numeric / procedure / survey / confusion / unanswerable，`split` ∈ tune / test，可回答题 1～3 个 `anchors`（取自原文），
`paraphrased` 标记口语或换词表述。混淆题用 `confuser_docs` 记录近领域干扰文档。

数值事实清单见 [`schemas/numeric-facts.schema.json`](schemas/numeric-facts.schema.json)：`anchor` 是原文写法，
`alt_anchors` 是允许的变形写法（如单位只写一次的 `400±20℃`），`source: manual` 表示扫描件由人工读图核对。

## 指标口径

- 检索层：Hit@5 / 锚点召回 / MRR / 上下文精度（含任一锚点的块数 ÷ 返回块数）/ 文档召回 / 平均返回块数，都在请求级最终上下文上算；
  不可回答题只报返回块数和最高重排分。重排分只对 Rerank 模型实际打分的头部有值，融合尾部为 null。
- 解析层：事实覆盖率两列（切片原文匹配 → “丢失”；LaTeX 去壳后匹配 → “变形”），数字保留率 = 切片 Unicode 数字 ÷ 文字层 Unicode 数字，
  空槽按每千非空白字符计（另报不含动词模式的“严格空槽”，即闸门审计用的那组），噪声行按出版社水印与页眉页脚模式计。
  `metric_version` 2（2026-10-09 起）：数字、字符与空槽在去掉 Markdown 图片引用后统计——图片文件名是哈希，v1 把它们算成了数字；
  `S1-base` 按 v2 重算的结果在 `runs/S1-chunks/parse-metrics-v2.json`（覆盖率不变，保留率 0.81 → 0.74）。
- 门槛：调参集上基线三次重复的极差为下限；阶段 2 开工时写定的值见 `manifests/kq-thresholds.json` 与状态文件，提升小于门槛记“未证实”。

## 阶段 1 运行顺序（用户在仓库根目录的终端跑）

```bash
# 0. 一步一步跑，每步看完输出再下一步：失败的步骤不会阻止后面的命令，整块粘贴会把错误带到后面。
#    每开一个新终端先定义这两个变量
export B=http://127.0.0.1:9093/api/ragent
export QD=local-data/kq-eval/questions

# 1. 核对后冻结（改 jsonl 本身，review-draft.md 只是视图）
python3 eval/kq/verify_dataset.py --strict
cp $QD/questions-draft.jsonl $QD/questions-v1.jsonl && cp $QD/numeric-facts-draft.jsonl $QD/numeric-facts-v1.jsonl
python3 eval/kq/verify_dataset.py --questions $QD/questions-v1.jsonl --facts $QD/numeric-facts-v1.jsonl --strict \
  --write-manifest eval/kq/manifests/kq-s1-dataset.json

# 2. 评测实例：另开一个终端（这条命令会一直占着终端）。三把 key 必须在启动应用的那个进程的环境里：
#    终端启动就先 export 再 java -jar；IDEA 启动就写进运行配置的环境变量 / Password Safe。
#    key 没带上时入库会在文档的 chunk-log 里报 "MinerU api-key 未配置"。确认 docker ps 里 rocketmq-broker-1 与 nameserver-1 都在。
export BAILIAN_API_KEY=… SILICONFLOW_API_KEY=… MINERU_API_KEY=…
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:$PWD/local-data/kq-eval/config/application-kq-s1.yaml

# 3. 入库 7 份并审计切片。失败后修好原因再跑同一条命令加 --resume：成功的跳过、失败的重新分块、缺的补传
python3 eval/kq/prepare_kb.py --base $B --output local-data/kq-eval/runs/setup-s1.json --continue-on-failure
# （重跑：python3 eval/kq/prepare_kb.py --base $B --output local-data/kq-eval/runs/setup-s1-resume.json --resume --continue-on-failure）
# 审计只在 7 份都 success 后做；之前跑过要先 rm -r local-data/kq-eval/runs/S1-chunks（脚本不覆盖旧文件）
python3 eval/kq/audit_chunks.py --base $B --output local-data/kq-eval/runs/S1-chunks/chunks.jsonl
python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --chunks local-data/kq-eval/runs/S1-chunks/chunks.jsonl \
  --label S1-base --output local-data/kq-eval/runs/S1-chunks/parse-metrics.json

# 4. 先在工作终端验 key（200 = key 可用；401 = key 无效或过期；若这里 200 而应用里 401，说明应用进程没拿到 key）
curl -s -o /dev/null -w '%{http_code}\n' https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank \
  -H "Authorization: Bearer $BAILIAN_API_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3-rerank","input":{"query":"q","documents":["a","b"]},"parameters":{"top_n":1}}'
#    冒烟 1 题：run_retrieval.py 会在重排回退到 noop（头部 rerankScore 全等于 channelScore）或改写回退（子问题原样返回）时自动退出 2
python3 eval/kq/run_retrieval.py --base $B --label smoke --arm S1-base --split tune --repeat-index 0 \
  --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl \
  --sub-questions $QD/sub-questions-smoke.jsonl --record-sub-questions --ids num-si-01   # num-si-01 在 tune 集
python3 -c "import json;d=json.load(open('local-data/kq-eval/runs/smoke/retrieval.json'))['details'][0]['raw_response'];print([(c['id'],c['channelScore'],c['rerankScore'],c['rerankHead'],c['finalSelected']) for c in d['results'][0]['candidates']])"

# 5. 基线 S1-base：每集 3 次，r1 落盘子问题，r2/r3 回放同一批子问题
for s in tune test; do for r in 1 2 3; do
  python3 eval/kq/run_retrieval.py --base $B --label S1-base-$s-r$r --arm S1-base --split $s --repeat-index $r \
    --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl $([ $r = 1 ] && echo --record-sub-questions)
done; done
for s in tune test; do
  python3 eval/kq/compare_retrieval_repeats.py --arm S1-base local-data/kq-eval/runs/S1-base-$s-r{1,2,3}/retrieval.json \
    --output local-data/kq-eval/runs/S1-base-$s-summary.json
done   # 测试集的 summary 封存，阶段 2、3 调参只看 tune

# 6. 解析定位实验（一步一看；每次约 1～10 分钟 MinerU 额度）
python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+硅含量的测定+重量法.pdf" --enable-formula false --note "step a"
python3 eval/kq/mineru_probe.py --file "local-data/source/硫铁矿和硫精矿中硅含量的测定+重量法.pdf" --is-ocr true --note "step b"
python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+全铁含量的测定+三氯化钛还原法.pdf" --note "step b2"
python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+硅含量的测定+重量法.pdf" --model-version vlm --note "step c"
python3 eval/kq/mineru_probe.py --file "local-data/source/硫铁矿和硫精矿中硅含量的测定+重量法.pdf" --model-version vlm --note "step c2"
# 每步之后对 full.md 算解析层指标（--doc 用 corpus-kq-s1.json 里的 id）
python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --full-md <probe目录>/full.md --doc GBT6730.10-2014-si \
  --label step-a --output <probe目录>/parse-metrics.json
```

## 阶段 2 运行顺序（用户在仓库根目录的终端跑）

**进度：第 0～7 步已于 2026-10-10 跑完**（入库 `runs/setup-s2.json`、解析层 `runs/S2-chunks/parse-metrics.json`、元数据确认 `metadata/doc-metadata-s2-confirmed.json`、S2-gate 调参集对比 `runs/S2-gate-vs-S1-base-tune.json`，S2-gate 过了门槛）。

### 剩下的全部：一条命令（推荐，2026-10-10 11:15 更新）

在**有三把 key 的终端**里（就是之前启动实例的那个），先按 Ctrl-C 停掉 9094 上的实例，然后执行：

```bash
python3 eval/kq/run_stage2_remaining.py
```

不用改任何参数，大约 40 分钟。脚本按下面的顺序自动完成，每一步之间不用人工操作：

1. β = 0.2、0.3 各启动一次实例，各在调参集跑 3 次（β = 0.1 的 3 次已经有效，跳过）。
2. 和 S2-gate 对比，按状态文件写好的规则选 β。
3. 不带 boost 启动实例，补跑 S2-gate 测试集的 r2，再和 S1-base 对比。
4. 第 2 步选出了 β 的话，再用它跑测试集 3 次并对比。
5. 停掉实例，写出汇总 `runs/stage2-remaining-summary-*.json`。

每次运行都自动检查 boost 设置、实际 β、空通道是否 ≤ 8：超时作废的自动挪开重跑；嵌入通道整体没响应或 key 没带上，就直接报原因并停下。中途断了，重新执行同一条命令会接着跑，已有效的运行不会重跑。想先看它要跑什么：`python3 eval/kq/run_stage2_remaining.py --plan`。

已挪开作废的：`S2-boost-0.2` 三次（实例当时没开 boost）、`S2-gate-test-r2`（18 个空通道），以及 β = 0.1 第一次的 r1（24 个空通道，已补跑有效）。

下面是脚本内部做的各步，留作说明；只有脚本出问题时才需要手动照做。

通用约定（每一步都适用）：

- 两个终端都在仓库根目录。**终端 A** 只跑实例（`java -jar …`，会一直占着），三把 key 必须在它的环境里；**终端 B** 跑评测。终端 B 每次新开都先执行：
  `export B2=http://127.0.0.1:9094/api/ragent QD=local-data/kq-eval/questions`
- 换配置 = 在终端 A 按 Ctrl-C 停掉实例，再用该步给的命令启动，等日志出现 `Started RagentApplication` 后才在终端 B 开跑。
- 某次运行作废（`verify_boost_beta.py` 打印 `INVALID`，或对比时打印 `invalid runs: …; rerun it`）：把被点名的那次挪开，只重跑那一次（把循环改成 `for r in <那个 r>`），再重新检查、重跑对比：
  `L=<被点名的 label>; mv local-data/kq-eval/runs/$L local-data/kq-eval/runs/invalid-$L-$(date +%m%d%H%M)`
- `run_retrieval.py` 以退出码 2 中止（重排回退成 noop）= 实例进程没拿到 key：在终端 A 重新 export key、重启实例，再重跑这一次。

### 第 0～7 步（已完成，留作复现）

```bash
export B2=http://127.0.0.1:9094/api/ragent
export QD=local-data/kq-eval/questions
PG=ragent-iron-ore-dev-postgres-1

# 0. 本阶段代码会读 t_knowledge_document.doc_metadata：已有库先补列（只加列、可重复执行），否则查文档就报错。
#    ragent 是平时开发用的库（按 resources/database/README.md 的约定可先备份）；阶段 3 要在 ragent_eval_kq_s1 上跑，它也要补
for db in ragent ragent_eval_kq_s1; do
  docker exec -i $PG sh -c 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1' sh $db \
    < resources/database/upgrades/v1.1.0/261009_knowledge_document_metadata.sql
done

# 1. S2 库与实例配置（桶在应用启动时自动建）
docker exec $PG sh -c 'exec createdb -U "$POSTGRES_USER" ragent_eval_kq_s2'
for f in schema_pg init_data_pg; do
  docker exec -i $PG sh -c 'exec psql -U "$POSTGRES_USER" -d ragent_eval_kq_s2 -v ON_ERROR_STOP=1 -q' < resources/database/$f.sql
done
cp eval/kq/config/application-kq-s2.example.yaml local-data/kq-eval/config/application-kq-s2.yaml

# 2. 约 15 秒：确认 MinerU 接受显式的 model_version=pipeline（S2 配置固定了它；报错就把配置里的 model-version 删掉）
python3 eval/kq/mineru_probe.py --file "local-data/source/铁矿石+全铁含量的测定+三氯化钛还原法.pdf" --model-version pipeline --note "s2 pin check"

# 3. S2 实例：另开终端，三把 key 在这个进程的环境里；确认 rocketmq-broker 与 nameserver 都在
./mvnw -o -pl bootstrap -am -DskipTests clean package
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:$PWD/local-data/kq-eval/config/application-kq-s2.yaml

# 4. 入库 7 份（约 5～8 分钟：硅含量、取样预计不合格，各多一次 OCR 解析）
python3 eval/kq/prepare_kb.py --base $B2 --kb-name kq-s2 --collection-name kq_s2_v1 \
  --output local-data/kq-eval/runs/setup-s2.json --continue-on-failure

# 5. 解析层四指标（与 S1 同为 metric_version 2）
python3 eval/kq/audit_chunks.py --base $B2 --kb-name kq-s2 --output local-data/kq-eval/runs/S2-chunks/chunks.jsonl
python3 eval/kq/parse_metrics.py --facts $QD/numeric-facts-v1.jsonl --chunks local-data/kq-eval/runs/S2-chunks/chunks.jsonl \
  --label S2-gate --output local-data/kq-eval/runs/S2-chunks/parse-metrics.json

# 6. 元数据与术语表核对（约 0.5 小时）：export 后只改 standardNo / replaces / objects / components / methods，再 confirm。
#    术语表 bootstrap/src/main/resources/kq/terms.csv 的 note 列标了待核对的项；改了术语表就重启 S2 实例（配置从文件读）
python3 eval/kq/doc_metadata.py export --base $B2 --setup local-data/kq-eval/runs/setup-s2.json \
  --output local-data/kq-eval/metadata/doc-metadata-s2.json
python3 eval/kq/doc_metadata.py confirm --base $B2 --setup local-data/kq-eval/runs/setup-s2.json \
  --input local-data/kq-eval/metadata/doc-metadata-s2.json

# 7. S2-gate 臂：调参集 3 次，回放与 S1 相同的子问题（默认 sub-questions-v1.jsonl，不加 --record-sub-questions）
for r in 1 2 3; do
  python3 eval/kq/run_retrieval.py --base $B2 --label S2-gate-tune-r$r --arm S2-gate --split tune --repeat-index $r \
    --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl
done
python3 eval/kq/compare_retrieval_repeats.py --thresholds eval/kq/manifests/kq-thresholds.json \
  --arm S1-base local-data/kq-eval/runs/S1-base-tune-r{1,2,3}/retrieval.json \
  --arm S2-gate local-data/kq-eval/runs/S2-gate-tune-r{1,2,3}/retrieval.json \
  --output local-data/kq-eval/runs/S2-gate-vs-S1-base-tune.json
```

### 第 8 步：S2-gate 测试集（实例不换配置）

终端 A 保持第 3 步启动的实例（不带 boost 参数）。不确定就 Ctrl-C 后重跑第 3 步的 `java -jar` 命令。

```bash
for r in 1 2 3; do
  python3 eval/kq/run_retrieval.py --base $B2 --label S2-gate-test-r$r --arm S2-gate --split test --repeat-index $r \
    --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl
done
# 三行都应是 OK（boost=off、空通道 ≤ 8）
python3 eval/kq/verify_boost_beta.py S2-gate-test-r{1,2,3}
# 测试集只报告：门槛是测试集基线自己的极差，所以不加 --thresholds，只单独查空通道上限
python3 eval/kq/compare_retrieval_repeats.py --max-empty-channel 8 \
  --arm S1-base local-data/kq-eval/runs/S1-base-test-r{1,2,3}/retrieval.json \
  --arm S2-gate local-data/kq-eval/runs/S2-gate-test-r{1,2,3}/retrieval.json \
  --output local-data/kq-eval/runs/S2-gate-vs-S1-base-test.json
```

不用判定，跑完直接下一步。

### 第 9～11 步：boost 三个 β（每个 β 重启一次实例）

β 依次取 0.1、0.2、0.3，三步的做法相同，只换 `BETA`。

终端 A（Ctrl-C 停掉旧实例后）：

```bash
BETA=0.1   # 第 10 步改成 0.2，第 11 步改成 0.3
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:$PWD/local-data/kq-eval/config/application-kq-s2.yaml \
  --rag.search.metadata-boost.enabled=true --rag.search.metadata-boost.beta=$BETA
```

终端 B（`BETA` 与终端 A 一致）：

```bash
BETA=0.1   # 第 10 步改成 0.2，第 11 步改成 0.3
for r in 1 2 3; do
  python3 eval/kq/run_retrieval.py --base $B2 --label S2-boost-$BETA-tune-r$r --arm S2-boost-$BETA --split tune --repeat-index $r \
    --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl
done
# 三行都应是 OK（boost=<BETA>、空通道 ≤ 8）；打印 INVALID 就按通用约定挪开重跑那一次
python3 eval/kq/verify_boost_beta.py S2-boost-$BETA-tune-r{1,2,3}
```

### 第 12 步：boost 对比与选 β（三个 β 都跑完才跑）

```bash
python3 eval/kq/compare_retrieval_repeats.py --thresholds eval/kq/manifests/kq-thresholds.json --baseline S2-gate \
  --arm S2-gate local-data/kq-eval/runs/S2-gate-tune-r{1,2,3}/retrieval.json \
  --arm S2-boost-0.1 local-data/kq-eval/runs/S2-boost-0.1-tune-r{1,2,3}/retrieval.json \
  --arm S2-boost-0.2 local-data/kq-eval/runs/S2-boost-0.2-tune-r{1,2,3}/retrieval.json \
  --arm S2-boost-0.3 local-data/kq-eval/runs/S2-boost-0.3-tune-r{1,2,3}/retrieval.json \
  --output local-data/kq-eval/runs/S2-boost-vs-S2-gate-tune.json
```

判定（门槛写定在状态文件，按输出里每个 `S2-boost-<β> vs S2-gate` 段看）：

- 某个 β **过门槛** = `overall mrr` 那行是 `improved`，且 `overall hit@5` 与 `numeric hit@5` 两行都不是 `regressed`。
- 有几个 β 过门槛，就选 `overall mrr` 那行箭头右边的数（候选臂均值）最大的那个，进第 13 步。
- 一个都没过：跳过第 13 步，boost 保持默认关闭（负结果照样写改动说明），直接第 14 步。

### 第 13 步：选中的 β 跑测试集（只有第 12 步选出了 β 才跑）

终端 A：Ctrl-C 后用第 9～11 步的启动命令，`BETA` 设成选中的值。终端 B：

```bash
BETA=<选中的值>
for r in 1 2 3; do
  python3 eval/kq/run_retrieval.py --base $B2 --label S2-boost-$BETA-test-r$r --arm S2-boost-$BETA --split test --repeat-index $r \
    --server-commit $(git rev-parse HEAD) --questions $QD/questions-v1.jsonl
done
python3 eval/kq/verify_boost_beta.py S2-boost-$BETA-test-r{1,2,3}
python3 eval/kq/compare_retrieval_repeats.py --max-empty-channel 8 --baseline S2-gate \
  --arm S2-gate local-data/kq-eval/runs/S2-gate-test-r{1,2,3}/retrieval.json \
  --arm S2-boost-$BETA local-data/kq-eval/runs/S2-boost-$BETA-test-r{1,2,3}/retrieval.json \
  --output local-data/kq-eval/runs/S2-boost-$BETA-vs-S2-gate-test.json
```

### 第 14 步：收工

终端 A 按 Ctrl-C 停掉实例。阶段 2 的运行到此结束；结果都在 `local-data/kq-eval/runs/`，由阶段 3 会话读取、写改动说明并打 `kq-s2`。
