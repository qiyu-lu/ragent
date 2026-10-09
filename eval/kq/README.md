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
| `compare_retrieval_repeats.py` | 每臂多次重复的均值与极差；候选臂相对基线的变化以基线极差为门槛，给出 improved / unproven / regressed |
| `config/application-kq-s1.example.yaml` | 评测实例的附加配置样例（端口 9093、库 `ragent_eval_kq_s1`、Redis DB 13、独立桶与 MQ 主题） |
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
  空槽按每千非空白字符计，噪声行按出版社水印与页眉页脚模式计。
- 门槛：调参集上基线三次重复的极差；提升小于极差记“未证实”。

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
