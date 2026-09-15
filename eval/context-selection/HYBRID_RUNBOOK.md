# BM25 / 向量 / RRF：真实配对比较

复用现有 ES 关键词通道、PGVector、RRF 和 qwen3-rerank，不引入另一套生产检索算法。默认聊天配置不变。未使用 academic-research-suite。

## 固定比较

每题一次向量 Top40 和 BM25 Top40，并行运行。两通道原始结果立即保存不可变快照；现有 Java 引擎等权 RRF（k=20）合并并截取候选池 Top40，再执行现有重排及最终 Top10。随后对同一批原始向量、BM25 候选分别做 metadata enrichment 和 qwen3-rerank Top10。因此一次请求产生六臂：

1. 向量；2. BM25；3. RRF；4. 向量 + rerank；5. BM25 + rerank；6. RRF + rerank。

所有方案按最终 Top5/10 **块数**比较；不是 token 数完全相同。混合方式多一路召回，两个通道各 40 条（最多 80 条合并输入），不能声称召回计算量相同；进入重排的候选均最多 40 条。没有调整模型、权重、分块参数或根据结果追加搜索。

配对请求顺序是混合生产链路、向量重排、BM25 重排，额外两个重排调用用于实验消融，不接入正常聊天。单次总耗时不等于任一独立部署方案的延迟；不得由该总耗时宣称某臂更快。

## 环境

新容器 `ragent-cs-hybrid-es-v1` 使用已有 Elasticsearch 8.15.5 镜像，仅绑定 `127.0.0.1:9201`，内存 1GB、JVM 512MB。不使用其他项目的 9200。磁盘 low/high/flood 余量分别 20/10/5GB，保留保护，不清理用户数据。两个英文数据集使用 ES `english` 分析器，不宣称完成中文 IK 评测。RRF 在 Java 侧执行，不依赖 Elasticsearch 内置 RRF 许可功能。

Hotpot 池：既有 DB `ragent_eval_pool_v1`、Redis 13、端口 9093、ES 索引 `cs_pool_hybrid_v1`。已完成的 1,991 个块按相同 ID 写入新 ES 索引，没有重做 embedding。

SciFact：新 DB `ragent_eval_pool_scifact_v1`、Redis 12（创建前为 0 keys）、端口 9094、新对象桶 `ragent-cs-scifact-v1-{sources,assets}`、ES 索引 `cs_pool_scifact_hybrid_v1`。独立库内集合名仍为 `cs_pool_v1`，不与 Hotpot 混池。通过原解析/切块/embedding/索引内核导入；同步入口仅绕过磁盘保护下不可用的 MQ 调度，详见 [LIVE_RUNBOOK](LIVE_RUNBOOK.md)。

配置采用多文档 YAML：导入的基线在第一文档，覆盖值在后续文档，避免 import 优先级把 keyword.enabled 改回 false。首次配置检查失败的单题保留于 `CS-HYBRID-v1`，正式比较使用 `CS-HYBRID-main-v1`。

## Hotpot 复现

以下已有输出不应覆盖。重新执行请使用新版本目录和空的独立 ES 索引；当前索引已有数据时不需要再次 index。

本轮结束后 ES 容器已停止但未删除，恢复既有数据先运行 `docker start ragent-cs-hybrid-es-v1`，再启动所需的 Java 评测配置。不要重新创建同名容器或重建数据库。

```bash
python3 eval/context-selection/hybrid_eval.py index \
  --documents local-data/eval/context-selection/live/CS-LIVE-v1/documents

python3 eval/context-selection/start_pooled.py \
  --config eval/context-selection/application-hybrid-eval.yaml

python3 eval/context-selection/hybrid_eval.py run \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-HYBRID-main-v1/queries --workers 4

python3 eval/context-selection/hybrid_eval.py score \
  --gold local-data/eval/context-selection/datasets/CS-POOL-dev-v1/gold.jsonl \
  --documents local-data/eval/context-selection/live/CS-LIVE-v1/documents \
  --run local-data/eval/context-selection/live/CS-HYBRID-main-v1/queries \
  --output local-data/eval/context-selection/live/CS-HYBRID-main-v1/score.json
```

运行时只读取问题与文档，没有 gold。每题结果单独落盘，可续跑；已存在的失败记录也跳过，不自动用成功重试替换。主评分为全题分母，同时报告所有臂均健康的逐题配对改善/退化。评分验证运行清单 ID、条数和每题 SHA-256。

## SciFact 复现

数据来自 [BEIR 官方项目](https://github.com/beir-cellar/beir) 发布的完整 SciFact：5,183 篇文档，官方 test 的 300 个查询；官方归档 MD5 为 `5f7d1de60b170fc8027bb7898e2efca1`。不是另挑困难题，也没有使用 test qrels 调参。原 Hotpot 冻结 400 题未打开。

```bash
python3 eval/context-selection/beir_hybrid.py prepare \
  --archive local-data/eval/context-selection/datasets/BEIR-SciFact-v1/scifact.zip \
  --output local-data/eval/context-selection/datasets/BEIR-SciFact-v1

python3 eval/context-selection/start_pooled.py \
  --config eval/context-selection/application-scifact-eval.yaml

CS_EVAL_BASE=http://127.0.0.1:9094/api/ragent \
python3 eval/context-selection/live_pooled.py ingest \
  --corpus local-data/eval/context-selection/datasets/BEIR-SciFact-v1/corpus.jsonl \
  --output-dir local-data/eval/context-selection/live/CS-SCIFACT-v1 --workers 16

CS_EVAL_BASE=http://127.0.0.1:9094/api/ragent \
python3 eval/context-selection/hybrid_eval.py run \
  --queries local-data/eval/context-selection/datasets/BEIR-SciFact-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-SCIFACT-v1/queries --workers 4

python3 eval/context-selection/beir_hybrid.py score \
  --qrels local-data/eval/context-selection/datasets/BEIR-SciFact-v1/qrels.json \
  --documents local-data/eval/context-selection/live/CS-SCIFACT-v1/documents \
  --run local-data/eval/context-selection/live/CS-SCIFACT-v1/queries \
  --output local-data/eval/context-selection/live/CS-SCIFACT-v1/score.json
```

正式查询前，确认全部文档 success，关系块/向量逐 ID 一致、ES 块 ID 一致，刷新 ES 索引。SciFact 入库会通过现有关键词同步机制写 ES，不再手工重复 bulk。

SciFact 按相同 TopK 块预算选取后映射/去重为源论文，使用官方 qrels 计算 Recall 与 nDCG。不是 Hotpot 的“全支持句完整率”，不能直接混合两个百分比。该生产分块适配也不等同于标准整文档 BEIR leaderboard 测试，不声称论文复现或排行榜成绩。没有生成最终回答，也未评价中文业务或低延迟吞吐。

## 验证与恢复

```bash
python3 -m unittest discover -s eval/context-selection/tests -p 'test_*.py'
./mvnw -o -pl bootstrap -am \
  -Dtest=HybridEvalControllerTest,PooledEvalControllerTest,RetrievalCaptureTest,KeywordSearchChannelTest,RerankPostProcessorTest,MultiChannelRetrievalEngineTest,RetrievalEngineTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.apply.skip=true test
```

密钥仍由启动器只从 IDEA 读取到进程环境，不能写入配置/结果。全部原始数据与结果位于 Git 忽略目录。API 费用按用户要求不作为门槛；应用调用数不冒充提供商账单或 token 使用量。恢复已完成入库无需重新付费向量化。不要删除/重建既有数据库、ES 索引或对象桶来重跑。
