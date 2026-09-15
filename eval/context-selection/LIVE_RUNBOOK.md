# CS-LIVE：真实公开语料池诊断

仅用于 `public_dev`：不是官方 fullwiki，也不是独立测试。保留 CS-DEV 负结果与 CS-POOL 离线代理结果。不要打开覆盖选择器默认路径，不使用 academic-research-suite。

## 隔离与密钥

- 专用数据库 `ragent_eval_pool_v1`、Redis DB 13、本机 `127.0.0.1:9093`。
- 桶 `ragent-cs-pool-v1-sources` / `ragent-cs-pool-v1-assets`，唯一集合 `cs_pool_v1`。
- 当前数据库已创建并初始化，恢复时不得再次初始化、覆盖或清空。新机器须自行创建新的专用库、初始化 `resources/database/schema_pg.sql` 和 `init_data_pg.sql`，并核查 Redis DB 空闲；启动器不负责这些动作。
- `start_pooled.py` 从 IDEA `RagentApplication` 配置只提取 `BAILIAN_API_KEY` 和 `SILICONFLOW_API_KEY`，直接传入 Java 进程环境；不展示值、不保存密钥副本。不可将 `.idea/workspace.xml` 加入版本控制。
- 诊断接口同时要求专用 profile、开关、管理员角色、专用库名、唯一公共集合、无其他集合向量。默认聊天入口不调用该接口。

```bash
python3 eval/context-selection/start_pooled.py --check-only
./mvnw -o -pl bootstrap -am -DskipTests -Dspotless.apply.skip=true compile
python3 eval/context-selection/start_pooled.py
```

启动前检查 9093，不能覆盖其他进程。不要在共享机器打印完整进程环境。启动器只绑定 loopback，默认种子管理员仅用于隔离本机评测，禁止对外暴露。

## 导入与续跑

```bash
python3 eval/context-selection/live_pooled.py ingest \
  --corpus local-data/eval/context-selection/datasets/CS-POOL-dev-v1/corpus.jsonl \
  --output-dir local-data/eval/context-selection/live/CS-LIVE-v1 \
  --workers 16
```

先以 `--limit 3 --workers 1` 冒烟。每个池段落成为一个带原标题的 Markdown 源文件，由实际上传 API、`KnowledgeDocumentService.executeChunk`、原解析/切块/embedding/索引内核处理。切块参数是 1024 **字符**、重叠 128 字符，不是旧选择实验的 1024 token 预算。

本机 RocketMQ broker 在 90% 磁盘保护下拒绝发送，本实验没有调高保护阈值或清理用户文件。隔离接口同步调用消费者使用的同一业务方法，仅绕过 MQ 调度，因此不验证 MQ 可靠性、异步吞吐或调度行为。专用入口使用状态条件更新防止重复执行，只处理 pending/failed 的文档。

`setup.json` 固定语料哈希和集合；`pending/` 保存上传成功 ID，`documents/` 保存完成文档及完整块分页。失败即取消尚未执行任务，在途任务可能完成；恢复时复用 ID、等待 running 或读取 success，不重新上传。不得并发启动两个导入客户端。来源/结果位于已有 Git 忽略的 `local-data/`。

## 真实阶段捕获和离线评分

仅在全部 1,988 个文档完成、块与向量计数核对后运行主评测。客户端要求同目录的 `ingest-ready.json`，并校验文档清单哈希；入库未完成时的 `partial-corpus-smoke.jsonl` 只是接口验证，不纳入正式指标。显式 `--allow-partial-corpus-smoke --limit 1` 才允许不完整语料冒烟。

```bash
python3 eval/context-selection/live_pooled.py capture \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200.jsonl

python3 eval/context-selection/score_live_pooled.py \
  --gold local-data/eval/context-selection/datasets/CS-POOL-dev-v1/gold.jsonl \
  --captures local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200.jsonl \
  --documents local-data/eval/context-selection/live/CS-LIVE-v1/documents \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-score.json
```

运行时只读取无 gold 问题和正文；评分器单独加载 gold。线上 capture 不覆盖已有输出，逐题 flush，默认失败/降级行保留后停止。评分器默认验证完成清单的条数和 SHA-256，诊断残缺输出必须显式 `--allow-incomplete`。`--rewrite` 为可选单独实验，默认不改写；调用真实改写服务时，它内部的规则兜底目前没有独立结构化状态，不能仅凭 `degraded=false` 宣称改写模型调用成功。

本轮原问题第 57 题向量通道触发 30 秒超时。从 `--offset 57` 续跑，第 150～152 题连续超时再次停批；之后第 153 题恢复探测成功，再从 `--offset 153` 继续最后 47 题。失败题不重试、不替换；`--max-consecutive-failures 3` 仅改变停批保护，不改变检索参数。所有首次尝试合并为完整 200 题，其中失败仍计入分母：

```bash
python3 eval/context-selection/live_pooled.py capture \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part2.jsonl \
  --offset 57 --max-consecutive-failures 3

python3 eval/context-selection/live_pooled.py capture \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part3.jsonl \
  --offset 152 --limit 1

python3 eval/context-selection/live_pooled.py capture \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part4.jsonl \
  --offset 153 --max-consecutive-failures 3

python3 eval/context-selection/merge_live_pooled.py \
  --queries local-data/eval/context-selection/datasets/CS-POOL-dev-v1/queries.jsonl \
  --parts local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200.jsonl \
    local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part2.jsonl \
    local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part3.jsonl \
    local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-part4.jsonl \
  --output local-data/eval/context-selection/live/CS-LIVE-v1/raw-dev200-first-attempts.jsonl
```

正式评分将上述命令中的 `--captures` 改为 `raw-dev200-first-attempts.jsonl`。合并程序要求与原始 200 题 ID 顺序完全一致，并拒绝重复题、丢题、改写结果和问题正文变化。旧 `raw-pilot5-score.json` 是采集未结束时误读的 4 题诊断，已保留但不可使用；完整五题结果为 `raw-pilot5-complete-score.json`。

实际链路为 SiliconFlow Qwen3-Embedding-8B（1536维）、PGVector Top40、原处理器链、百炼 qwen3-rerank、原请求级 Top10。意图/MCP、关键词、图谱、联网通道关闭。召回预算 40 不等同于默认生产参数；不做吞吐或线上延迟改善声明。Rerank 返回头部后会回填融合尾部，后 30 条不可宣称全部经过重排模型排序。

源文件名为稳定的 `pool-<hash>.md`，原标题保留在正文一级标题中；不是中文工业文件的真实文件名分布。指标仅适用于该公开文本池与此配置。

`RetrievalCapture` 显式随请求传递、跨线程收集，保存不可变标量快照，避免后续修改 chunk 分数污染早期阶段。

评分将原支持句与对应源文档的实际块做 NFC + 去空白后的完整包含匹配。不同源文档的同名/相同句子不能代替原证据。跨块断裂或解析变形造成无法匹配，单独记 `ingestion_or_mapping_unavailable`；不静默排除分母，不直接归因给召回。其余依次为服务失败、召回缺证据、选择丢证据、完整。分数不是回答正确性，未生成回答。

## 验证

```bash
python3 -m unittest discover -s eval/context-selection/tests -p 'test_*.py'
./mvnw -o -pl bootstrap -am \
  -Dtest=PooledEvalControllerTest,RetrievalCaptureTest,RetrievalEngineTest,MultiChannelRetrievalEngineTest,RerankPostProcessorTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.apply.skip=true test
git diff --check
```

费用与服务端 token 尚无自动账单对账：不能再写为 0，也不能将本地字符/token 代理当作真实账单。恢复新增付费批次前应检查调用范围及预算；当前不自动生成 200 题回答或进行参数搜索。
