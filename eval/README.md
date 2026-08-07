# 检索评测

一套离线评测，用来量化检索链路的参数调整带来的实际收益。只评检索，不评生成——`/rag/eval` 返回的是纯召回证据，不含 LLM 回答。

## 文件

| 文件 | 作用 |
|:---|:---|
| `dataset.jsonl` | 评测集，每行一条带标注的问题 |
| `evalkit.py` | 锚点归一化、数据集与语料加载 |
| `verify_dataset.py` | 校验评测集自身正确性，**改完数据集必须先跑** |
| `run_eval.py` | 调接口、算指标、出报告 |
| `reports/` | 每次运行的明细，用于跨配置对比 |

## 前置条件

- 服务已启动，`app.eval.enabled: true`
- `resources/docs/knowledge/` 下 9 篇文档已入库且状态为 success
- 能用 `admin/admin` 登录（脚本自动取 token）

## 跑一次

```bash
cd eval
python3 verify_dataset.py            # 先校验数据集
python3 run_eval.py --label baseline # 跑分
```

常用参数：

```bash
python3 run_eval.py --label topk3 --tier hard      # 只跑 hard 档
python3 run_eval.py --label topk3 --concurrency 4  # 并发（别超过 rag.rate-limit.global.max-concurrent）
python3 run_eval.py --label topk3 --compare reports/20260807-153030-baseline.json
```

## 标注口径

```json
{
  "id": "hr-hard-01",
  "tier": "hard",
  "question": "今年的年假没休完，会不会白白没掉",
  "reference_docs": ["人事制度"],
  "reference_anchors": ["逾期清零"],
  "source": "人事制度.md 11.1.3 清零与结转"
}
```

- **`reference_docs`** 用「文件名去后缀」，对齐接口返回的 `retrievedDocIds`（它来自 `t_knowledge_document.doc_name` 剥后缀）
- **`reference_anchors`** 是「命中的 chunk 必须包含的关键短语」。判定时把锚点和 chunk 文本都做归一化（剥掉空白与 `*` `#` 等 Markdown 标记）再做子串匹配

**为什么用文本锚点而不是 chunk id**：chunk id 是雪花 ID（`IndexerNode.java` 用 `IdUtil.getSnowflakeNextIdStr()`），每次重新入库都会变。按 chunk id 标注的话，一改分块参数重新入库，整份标注就作废了——而分块参数正是要调的对象之一。锚点是文本，跨分块参数依然有效。

## 三个档位

| 档位 | 来源 | 作用 |
|:---|:---|:---|
| `easy` | 语料现成的 FAQ / 问答对 | 验证链路没坏。措辞与原文重合，**不指望它区分参数** |
| `hard` | 口语化改写、换同义词、去掉原文关键词、跨文档 | **真正拉开参数差异的一档** |
| `negative` | 语料里根本没答案的问题 | 暴露过度召回 |

## 指标口径

**文档级**（当前已饱和，见下方局限）

- `doc_recall` = 命中的标注文档数 / 标注文档总数
- `doc_precision` = 命中的标注文档数 / 召回的不同文档数

**锚点级**（主要看这组）

- `anchor_recall` = 命中的锚点数 / 锚点总数
- `anchor_mrr` = 1 / 首个含锚点 chunk 的排名，未命中记 0
- `anchor_hit@k` = 前 k 条里是否出现含锚点的 chunk，**区分度最高**
- `context_precision` = 含锚点的 chunk 数 / 召回 chunk 总数，衡量召回噪声

## 扫参

检索参数是 `@ConfigurationProperties(prefix = "rag.search")`，**启动时绑定**，所以每换一组配置要重启服务，用启动参数覆盖即可：

```bash
java -jar bootstrap.jar --rag.search.default-top-k=5
java -jar bootstrap.jar --rag.search.fusion.rerank-candidate-limit=20
java -jar bootstrap.jar --rag.rerank.enabled=false
```

IDEA 里在 Run Configuration 的 Program arguments 填同样的参数。`SearchChannelProperties` 实现了 `InitializingBean`，非法组合（如 `recall-budget < default-top-k`）会在启动时直接报错。

跑完用 `--compare` 对比上一次的报告。

## 当前局限

这几条是环境和接口本身的限制，不是脚本的问题，**看指标时必须记住**：

1. **`doc_recall` 已饱和在 1.000**。语料只有 9 篇、topK 取 10 条 chunk，正确文档每次都在里面。文档级指标目前无区分度，只能看锚点级。
2. **negative 档算不了指标**。`EvalResponse` 没透出相似度分数（`RetrievedChunk` 里有 `score`，接口没返回），无法判定「正确地识别为无答案」。当前只记录召回了几篇不同文档供人工观察。补一个 `retrievedScores` 字段即可解锁。
3. **意图路由参数目前空转**。意图树里只有 MCP（工单、天气）和系统交互节点，没有 KB 类型节点，所有知识库问题都走全局兜底，`intentLeafIds` 恒为 null。`min-intent-score`、`confidence-threshold`、`single-intent-supplement-threshold` 改了不会有任何变化。
4. **融合参数目前空转**。只有 vector 一个通道启用，单路召回无从融合，`rrf-k` 和 `channel-weights` 改了不会有任何变化。
5. **多库路由无法评测**。9 篇文档全在同一个知识库（`productdocs`）里。

第 3、4、5 条都是配置层面可以补的。补之前先留住 baseline，补完再跑一次，收益就是可量化的数字而不是感觉。
