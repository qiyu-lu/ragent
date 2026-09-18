# P7 固定公开数据对照结果

当前 S2 代码改进已完成必要程序回归。用户要求为节省 5h 额度暂停、不再测评，32 题/96 ABC 项没有启动；两条应用兼容性复测已受控中止，未形成可验收产物。当前版本没有新增答案 F1、语义支持率或多 Agent 收益结论，暂停后的工作须等用户再次要求继续。详见[S2 暂停清单](../../eval/agentic-research/manifests/research-s2-pause-2026-09-18.json)与[交接第 7 节](agentic-research-resume-2026-09-18.md#7-s2-代码改进交付与用户要求暂停)。

秋招版本（计划 8.7）已新增 Max/Flash/Max 角色配置及检索失败状态修复。当前只有供应商/SDK 兼容性记录，32 题/96 ABC 项的新固定开发验收尚未执行；两条真实应用零引用且检索失败，不能据此更新答案 F1、证据质量或多 Agent 收益。下方及 R1—R5 数字仍属于各自全 Flash 历史配置，详见[最新交接](agentic-research-resume-2026-09-18.md#6-秋招版本本轮实施与下一步)与[本轮清单](../../eval/agentic-research/manifests/research-s1-model-roles-2026-09-18.json)。

固定 regression：QASPER validation 200 个问题与 MuSiQue Full dev 200 行，A/B/C 共 1200 个任务均已记录；失败和超时保留在相应分母。主/worker 为 research-main-v3/research-worker-v2，最终生成 v4，模型 qwen3.7-flash-2026-07-15、temperature=0、thinking=false；并发 2、PGVector、rerank 关闭、recall 20/candidate 40，A 固定 top 10。每运行共享 16 模型/24 工具/300 秒活动预算并预留 2 次最终生成。

本批共 932 COMPLETED、93 PARTIAL、175 FAILED，未执行 0。QASPER 的答案、证据与可回答性均以 A 较高；MuSiQue 的 C 答案 F1 较高，但证据与全部行可回答性仍低于 A。B/C 的 P50 约 35 秒，A 约 5 秒；已知生成费估算分别为 A 0.4662、B 9.6053、C 11.4266 元。当前结果不支持统一的效果提升或成本收益结论，优先处理原生结束、有效正文选择和已记录的语义错误。

A 是项目组件的一次 scoped 检索与固定块上下文，B 单研究者没有 conduct_research，C 按需委派。A 不包含生产聊天的改写、意图/MCP、历史与回退。B 同时移除协调/委派提示，C 可选择串行；差异不能单独归因于 worker。A toolCalls 是一次固定检索阶段，B/C 为原生工具调用，两者不是相同数据库 I/O 粒度。

| 数据 / 模式 | 记录 | 答案评分分母 | EM | Answer F1 | Evidence F1 | 可回答性 | 已读段落 Recall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| musique/A | 200 | 105 | 0.1524 | 0.3325 | 0.5897 | 0.6750 | 0.8825 |
| musique/B | 200 | 105 | 0.1810 | 0.3269 | 0.4834 | 0.5100 | 0.5730 |
| musique/C | 200 | 105 | 0.2476 | 0.3918 | 0.5790 | 0.6450 | 0.6722 |
| qasper/A | 200 | 200 | 0.0850 | 0.3365 | 0.4616 | 0.9100 | 0.6670 |
| qasper/B | 200 | 200 | 0.0650 | 0.2338 | 0.3745 | 0.6950 | 0.6049 |
| qasper/C | 200 | 200 | 0.0750 | 0.2442 | 0.3601 | 0.6750 | 0.6446 |

QASPER 按作者归一化、多标注答案 F1/段落证据集合分别取最大值；EM/可回答性/已读 Recall 是本报告诊断项。MuSiQue 答案/证据只评分 105 行 answerable，可回答性评分全部 200 行；200 行对应 196 个原问题组，抽样不保证完整成对，不报告 paired sufficiency。未映射证据保留在分母，选中块映射段落只代表段落选择，不能证明模型看到全文或引用语义正确。公式对齐使用独立保存的作者脚本，196 对答案/49 对支持集合通过：[QASPER evaluator](https://raw.githubusercontent.com/allenai/qasper-led-baseline/main/scripts/evaluator.py)、[MuSiQue answer](https://raw.githubusercontent.com/StonyBrookNLP/musique/main/metrics/answer.py)、[support](https://raw.githubusercontent.com/StonyBrookNLP/musique/main/metrics/support.py)。

| 模式 | COMPLETED / PARTIAL / FAILED / 其他 | P50 秒 | P95 秒 | 委派运行 | worker 数 |
| --- | --- | ---: | ---: | ---: | ---: |
| A | 400 / 0 / 0 / 0 | 4.98 | 14.52 | 0 | 0 |
| B | 243 / 58 / 99 / 0 | 35.29 | 72.65 | 0 | 0 |
| C | 289 / 35 / 76 / 0 | 35.17 | 70.08 | 2 | 4 |

| 模式 | 模型调用记录 | 原生/检索阶段 toolCalls | 已知输入 / 输出 token | usage unknown | 已知生成费估算（元） |
| --- | ---: | ---: | --- | ---: | ---: |
| A | 456 | 400 | 1741508 / 147336 | 0 | 0.4662 |
| B | 3486 | 3134 | 38034997 / 623671 | 0 | 9.6053 |
| C | 3836 | 3438 | 44267376 / 685404 | 1 | 11.4266 |

耗时包含失败/超时，是每个请求开始执行后的 wall time，包含检索与生成；不包含批次建库/Maven 准备或 executor 排队。实际委派数单列；程序具备并发 worker 机制不等于本批实际使用或产生收益。

两个实际委派请求都属于 MuSiQue 不可回答行：`mq-de485266…` 的两个 worker 结束于空 findings/资料缺口，父运行最终 MODEL_CALL_BUDGET 失败；`mq-fc1d0aa6…` 的两个 worker 也返回空 findings，父运行形成合法不可回答报告。四个 worker 的 COMPLETED 表示正常结束研究，不表示查到了支持事实。105 行可回答题没有委派，不能把 C 的答案 F1 差值写成多 Agent 收益。完整 ID、子状态、已读数量与得分在机器清单。

资源、费用和逐题失败诊断见[机器清单](../../eval/agentic-research/manifests/research-p7-evaluation-2026-09-17.json)。完整预测、每题得分、源文快照、trace/usage、执行库清理和源码快照位于 `local-data/agentic-research/runs/20260917T145853_P7_regression_v4`。

本批 SDK 模型调用记录 7778，已知输入 84043881 / 输出 1456411 token，usage unknown 1；已知生成费估算 21.4981 元，含未知请求预留的预算估算 21.5281 元。Embedding 3989 个请求、已知 total_tokens 41939.0、unknown 127，金额/账户账单未核对。价格使用 2026-09-17 北京快照，不计缓存折扣；调用记录不是供应商 HTTP/账单的独立核对，估算不是实际扣款：[阿里云价格](https://help.aliyun.com/zh/model-studio/model-pricing)。

先前 smoke 为 20+20 题×3 的 120 任务，v3 全部记录；A 40 完成，B 26 完成/8 部分/6 失败，C 27 完成/5 部分/8 失败且零委派。MuSiQue 20 行仅 6 行 answerable。smoke 是固定 regression 前缀，调试和应用选取也使用开发资料，不能称独立 test 或把 v3/v4 比作受控改进效果。

24 项比较/PLAN 原文核对与两例针对性 v4 复测见[应用报告](agentic-research-application-review.md)。24 项为 5 完成/15 部分/4 失败，85 条引用快照由 Codex 检查；合法引用仍出现错误推断。没有使用裁判 API 或独立盲评。

full 已冻结 5839 个问题、17517 个任务并具备执行命令，本轮未付费执行。默认 30 元估算上限也作用于 full；全量需要先核对价格/规模并使用明确预算配置。公开英文开发集结果不能写成工业中文准确率。程序、迁移、页面夹具与未验证业务边界见[最终验证](agentic-research-validation-report.md)，启动、模式与复现见[交接](agentic-research-handoff.md)。

## 失败与覆盖提示

| 数据 / 模式 | 执行错误 | 答案格式错误 | 零 F1：无标注已读覆盖 | 零 F1：有标注已读覆盖 |
| --- | ---: | ---: | ---: | ---: |
| musique/A | 0 | 0 | 2 | 40 |
| musique/B | 54 | 0 | 5 | 20 |
| musique/C | 23 | 0 | 3 | 23 |
| qasper/A | 0 | 0 | 31 | 8 |
| qasper/B | 45 | 1 | 19 | 9 |
| qasper/C | 53 | 0 | 19 | 10 |

执行错误计数：NATIVE_FINISH_REQUIRED 90；RESEARCH_TIMEOUT 72；MODEL_CALL_BUDGET 12；RESEARCH_EXECUTION_FAILED 1。

分类来自保留的状态、得分和 trace，不重新调用供应商。已读覆盖不足不能单独证明检索遗漏，已有覆盖也不能单独证明生成错误；段落映射不等于全文已读或语义支持。空支持集合按公式约定处理，不可把不可回答题的覆盖数当有效事实命中。MuSiQue 不可回答行不进入答案 F1，其错误须看全部行可回答性，不能从零 F1 分类排除。NATIVE_FINISH_REQUIRED 表示未以有效原生 finish 结束；RESEARCH_TIMEOUT 须结合工具/模型事件判断阶段，不能统一写成耗尽 300 秒或源文缺资料。

A、B、C 按模式顺序执行，处于不同时间段，供应商/网络负载未交叉平衡；B/C 提示与可用工具也不同。当前数据描述这一固定批次，不能只凭差值作委派、并行或上下文压缩的因果判断。
