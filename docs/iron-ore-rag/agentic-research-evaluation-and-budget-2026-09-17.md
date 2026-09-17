# 研究工作流：评测留档与预算补充

日期：2026-09-17。配合[实施计划](agentic-research-implementation-plan-2026-09-17.md)使用。

本文件记录已核实的数据与配置、模型建议、费用假设及后续评测要求。P2 已完成四个训练/开发 split 的转换、完整真实 embedding 摄取与 search/read 验收；P3 已接入独立 research-flash 配置并完成小规模真实原生工具联调，未执行问答质量评分或 A/B/C。下文生成费用仍为预算场景，P3 实际 usage 与失败请求见[联调清单](../../eval/agentic-research/manifests/research-p3-smoke-2026-09-17.json)，不是结算账单。

## 1. 模型与 API key

用户已确认以 `qwen3.7-flash-2026-07-15` 作为首期研究模型。官方列出工具调用与结构化输出支持，适合接入计划中的检索、原文读取和任务委派工具；接入后仍需验证本项目的实际工具协议和任务效果。[官方模型能力表](https://help.aliyun.com/en/model-studio/text-generation-model/)

| 用途 | 当前配置 | 本轮建议 |
| --- | --- | --- |
| 研究主 Agent、worker、最终回答/计划生成 | 主 Agent、worker、最终产物统一使用 research-flash / qwen3.7-flash-2026-07-15 | 首期统一使用 qwen3.7-flash-2026-07-15，复用 BAILIAN_API_KEY |
| A/B/C 架构对照 | 现有普通问答和 FAST 路由使用不同模型候选 | 用独立评测配置固定相同生成模型，记录实际调用；保留生产默认行为的历史成绩为单独一组 |
| 少量难题的模型对照 | 已有 qwen3-max 候选 | 按需要增加小规模、单独标记的模型对照，不混入 Flash 主实验成绩 |
| 向量化 | SiliconFlow 的 Qwen/Qwen3-Embedding-8B，1536 维 | 沿用 SILICONFLOW_API_KEY；相同语料与配置复用向量 |
| 重排 | 百炼 qwen3-rerank | 沿用 BAILIAN_API_KEY，记录实际输入 usage |

配置依据为 `bootstrap/src/main/resources/application.yaml`。当前 `qwen-flash` 不等于 `qwen3.7-flash`，仅选择旧 FAST 路由不会自动换成研究模型；当前没有 DeepSeek 提供方配置。使用上述方案无需新增 DeepSeek 账户充值。P2 已验证 SiliconFlow embedding，P3 已调用百炼研究模型并记录供应商 usage；本批未调用 rerank，余额和金额未核对。

评测固定模型 ID、区域、thinking 设置、采样参数及各角色提示词版本。首次比较可统一关闭 thinking；如开启，单独记录并计入输出费用。架构之间允许职责对应的提示词不同，但每个模板必须版本化。评测配置不应静默回退到 Max 或其他高价模型；失败、重试和实际使用的模型都应进入结果记录。

## 2. 价格与估算口径

2026-09-17 核对的百炼中国内地北京价格，单位为元/百万 token：

| 模型 | 单次 API 输入范围 | 输入 | 输出 |
| --- | --- | ---: | ---: |
| qwen3.7-flash-2026-07-15 | 不超过 32K | 0.2 | 0.8 |
| 同上 | 超过 32K、不超过 256K | 0.6 | 2.4 |
| 同上 | 超过 256K、不超过 1M | 1.2 | 4.8 |
| qwen3-max | 不超过 32K | 2.5 | 10 |

低输入档的 Flash 输入、输出单价均为 Max 的 1/12.5。输出价格包含启用 thinking 后的相应计费 token。百炼 qwen3-rerank 输入为 0.5 元/百万 token。正式运行按当时实际区域和价目重新记录；不预先计入免费额度、缓存优惠或 Batch 折扣。[官方价格表](https://help.aliyun.com/en/model-studio/model-pricing)

下表假设每个 API 请求都处于不超过 32K 输入的档位。表中的 token 是完成一道题时**所有模型调用累计量**，包括重复发送的对话上下文、主 Agent 和 worker，不是单次输入窗口，也不是只统计最终回答。

| 模式 | 每题累计输入 token | 每题累计输出 token | Flash 每题估算 |
| --- | ---: | ---: | ---: |
| A：一次检索 RAG | 8,000 | 1,000 | 0.0024 元 |
| B：单研究 Agent | 40,000 | 6,000 | 0.0128 元 |
| C：主 Agent 按需委派 | 80,000 | 10,000 | 0.0240 元 |

计算方式为逐次请求的 `输入 token × 对应单价 / 1,000,000 + 输出 token × 对应单价 / 1,000,000`，再汇总到题目和批次。本表的 token 数只是预算场景，不能作为系统性能数据。P3 五批开发联调实际累计 136 个研究模型请求，已知输入 1,058,680 / 输出 25,248 token，4 次取消请求 usage unknown；未用这组反复调试样例校准正式质量评测的每题成本。P2 完整摄取的 embedding 留档为 5,884 个请求、19,958,704 个已知供应商 total_tokens，另有 28 个 usage unknown 请求；小批与连通性探测单独保留，金额尚未账单核对，见[导入清单](../../eval/agentic-research/manifests/imported-development-2026-09-17.json)。

- 固定 400 题各跑 A/B/C，共 1,200 次完整任务：Flash 生成费约 **15.68 元**；同样 token 假设下 Max 约 **196 元**。一次任务可能调用模型多次。
- QASPER validation 1,005 题加 MuSiQue Full dev 4,834 题全部跑 A/B/C，共 17,517 次完整任务：Flash 生成费约 **228.89 元**。
- 以上均未加入首次向量化、重排、额外重试、模型辅助评分和开发调试。上下文进入更高价格档，或实际调用量增加时，费用也会增加。

首笔充值建议合计约 **100 元：百炼 80 元、硅基流动 20 元**，已有可用余额可抵扣。20 元是向量化的预留金额，并非已测得的导入账单；SiliconFlow 国内账户实际单价需在实施时核对，不能套用其国际站价格。该分配用于启动开发与真实回归，不承诺所有完整 split 和反复试验总共只花 100 元。

先跑约 20 题真实 smoke，记录每题输入/输出、重排、重试和耗时，以实际消耗更新 400 题及 full 预算，再执行固定回归。smoke 用于接通与校准，不替代正式评测；费用估计也不是指标下降后终止项目的条件。余额和预算说明不等于无限追加消费授权。

## 3. 数据与比较范围

实际数据位于 `local-data/agentic-research/raw/`，不是 `local_data/`。下载清单已写入 [source-inventory-2026-09-17.json](../../local-data/agentic-research/manifests/source-inventory-2026-09-17.json)，包括原始文件大小、SHA-256 和条数。文件属于本地忽略目录，其他机器需按清单获取数据。

| 数据集 | train | validation/dev | test |
| --- | --- | --- | --- |
| QASPER | 888 篇 / 2,593 题 | 281 篇 / 1,005 题 | 416 篇 / 1,451 题 |
| MuSiQue Ans | 19,938 题 | 2,417 题 | 2,459 题，无答案字段 |
| MuSiQue Full | 39,876 题 | 4,834 题 | 4,918 题，无答案字段 |

MuSiQue Full 包含 Ans 的可回答样本，两个版本不能累计成独立样本数。公开参考答案、证据标签和 gold decomposition 仅供评分，不能导入检索正文。QASPER test 留待最终配置确定后使用。

首期固定回归沿用主计划的约 400 题：QASPER validation 与 MuSiQue Full dev 各约 200 题。保存抽样种子和题目 ID，按可回答性、问题类型/跳数记录分布；A/B/C 使用完全相同的集合与语料快照。英文公开集与中文业务演示分开报告。完整数据导入、固定题目抽样、完整 split 问答评分是三个不同规模，报告分别注明。

有公开参考答案的短答案任务采用相应数据集的官方评分逻辑或经过对齐的实现；EM/F1、不可回答识别和证据指标不需要每题额外调用一个“裁判模型”。计划和比较任务保留原文核对依据，可辅助进行模型评分，但不能仅靠生成答案的同一个模型给自己打分。这样仍然执行真实问答和完整计分，不将 mock 结果当作模型效果。

## 4. 每批必须保留的记录

每次运行创建独立 `runId`，例如 `20260917T140000_flash_regression_C`，结果落在 `local-data/agentic-research/runs/<runId>/`。以下是评测记录约定；P2 摄取已生成 traces/usage/source-probes 与批次摘要，不是预测/评分结果：

| 文件 | 必需内容 |
| --- | --- |
| run.json | 开始/结束时间、Git commit 和工作区修改标记、命令、环境、数据清单 hash、语料/索引版本、题目 ID/种子、A/B/C 模式、模型及提示词版本、工具和运行额度、计价日期/区域 |
| predictions.jsonl | 每题状态、最终答案或计划、引用证据 ID、不可回答标记、耗时、错误原因；失败题也占一条记录 |
| traces.jsonl | runId/questionId/taskId/callId、父子任务关系、工具名及参数、返回来源 ID、观察结果或可校验的内容引用、起止时间、重试、取消和超时 |
| usage.jsonl | 每次实际模型/embedding/rerank 请求的提供方、模型 ID、角色、requestId、输入/输出及可用缓存/推理 token 分类、价格档位、估算费用、成功/失败；避免重复计算 reasoning token |
| scores.jsonl | 每题及各指标的值、评分器版本、评分错误；与 predictions 对齐，保留失败样本 |
| summary.json / report.md | 样本及完成/失败数、分组质量指标、实际 token、估算成本、耗时分布、具体失败案例、与上一批的差异及解释 |

供应商未返回 usage 时标为 unknown，不能填 0 来冒充免费；失败请求是否计费按账单核对。估算费用与供应商账单分别标识。不保存 API key、Authorization 头或用户凭证；记录可审计的工具行为和业务输出即可。

原始 trace 和大体积结果保留在本地忽略目录；每阶段提交脚本、配置模板、固定样本 ID、清单摘要和精简结果报告，报告标明原始结果位置及 hash。完整上下文通过版本化模板、语料/观察结果引用和配置还原，不只留下终端截图。P2 建立数据清单和记录目录，P3 已补齐原生工具与逐请求 usage 记录器并开展小规模真实联调；正式批量质量评分在 P7 接续。

每次调整保留前后两批记录，区分单元测试、故障模拟、真实 API 联调和数据集评测。若效果下降，保留负结果并修复相关环节；不得只发布成功题，或覆盖历史结果后声称持续提升。实现和评测阶段继续遵循主计划的分支与阶段提交约定。


## 5. P4 付费开发联调留档

用户在 P3 交付后明确要求执行 P4，并说明已充值、可消费。本批仍使用已配置的百炼 `qwen3.7-flash-2026-07-15`，thinking=false，AgentScope Java 2.0.1；全部主/子研究共用 16 次模型、24 次工具、300 秒活动预算，2 次生成预留不消耗。默认 worker 2 个并行/累计 4 个，各最多 6 次模型调用、180 秒含排队，额度从全局扣除；一次主整合调用额外受保护。

三批开发 probe 累计 **83 个模型请求记录，已知输入 496671 / 输出 22935 token，2 个取消请求 usage unknown**。query embedding 按 call_id 去重为 25 次，23 次供应商已知 total_tokens 合计 196，2 次超时 usage unknown；没有 rerank。A 保留比较/PLAN 的预算退出和 embedding 超时，B 当前 worker-v2 比较/PLAN 闭环，C 补查压力保留一成功一预算退出并形成 PARTIAL。三批名称不是架构 A/B/C，不用于校准正式每题成本、语义支持率或效果增益。

金额和余额未核对，前文价格/预算仍是制定时假设，不能用 token 留档冒充账单。详细状态、原始路径及指纹见[执行记录](agentic-research-execution-log.md)和 [P4 manifest](../../eval/agentic-research/manifests/research-p4-smoke-2026-09-17.json)。P5 生成/修复仍应使用同一预算和模型配额，正式质量计分从 P7 接续。


## 6. P5/P6 真实产物开发联调用量

用户在明确公开语料外发和付费范围后授权 REPORT/PLAN 两条开发样例及受影响复测。三批共 79 个研究模型请求，已知输入 321707 / 输出 26002 token，模型 usage unknown 为 0；query embedding 按 call_id 去重 30 个请求，已知 total_tokens 60，23 请求 usage unknown。失败和修复调用都包含在内，unknown 不是零费用，金额/余额未核对。

A 两类产物均校验失败；B 形成报告和计划，但报告只有一篇论文引用，计划含 ID 合法而原文不支持的负例；C 收紧原文核对提示并对齐 30 秒读取超时后，报告因 embedding 超时失败，计划形成 4 条引用支持的 PARTIAL 概述，未知 batch_size/window_size 保留 null，500 条为 user_input。不能把 B 的 COMPLETED 或 C 的部分结果称为完整质量验收，也不据反复调试样例估计正式评测每题成本。逐请求状态、定性原文核对、源码和产物指纹见[真实产物联调清单](../../eval/agentic-research/manifests/research-p5-artifact-smoke-2026-09-17.json)；P7 A/B/C 与质量计分未开展。
