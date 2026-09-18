# P7 应用产物原文核对

S2 新批 `20260918_S2_applications_v1` 的两条 C 复测已按用户要求中止，返回 JAVA_EXIT_143，独立任务库已删除。`plan-06/C` 导出 FAILED/RESEARCH_EXECUTION_FAILED，JVM 关闭日志与该终态相邻，应按中止相关记录保留；已读 3 个证据 ID，没有已发布产物。`comparison-02/C` 已启动 worker 与检索，但没有导出终态，不称为完全未发起请求。已捕获 embedding 6 请求、3 成功/3 unknown，实际 h2；已导出模型台账仅覆盖 Max 主角色 6 次正常返回、input 28644/output 973，另一在途任务的模型调用未完整导出，不能作为整批总调用或整批 unknown=0。S3 和当前业务语义验收暂停，见[S2 清单](../../eval/agentic-research/manifests/research-s2-pause-2026-09-18.json)与最新交接第 7 节。下段“未复测”属于此前状态。

秋招 S1 兼容性批次 `20260918_S1_applications_v1` 的 `comparison-02/C` 与 `plan-06/C` 均在旧状态判定下标为 COMPLETED，但 28 次 embedding 全失败，两个产物均零章节/零引用，PLAN 无来源支持的步骤；实际是检索阻塞，不能算比较或计划质量通过。S2 已修复此类无证据执行失败的终态判定，目前经本地回归验证，未用当前源码做真实复测。强模型仍出现一次 PLAN 缺少 gaps 字段并经唯一修复恢复；不能认为换模型后无需结构校验。见[本轮清单](../../eval/agentic-research/manifests/research-s1-model-roles-2026-09-18.json)。下方 P7/R4 原文核对仍为各自历史批次。

24 个冻结任务均已真实执行：5 COMPLETED、15 PARTIAL、4 FAILED。20 个已发布产物的 85 条引用快照已由 Codex 对照正文检查；这不是独立人工盲评，也没有调用裁判模型。运行状态、引用身份合法和语义支持是不同结论。完整固定 A/B/C 结果见[对照报告](agentic-research-evaluation-report.md)，程序验证见[验证报告](agentic-research-validation-report.md)。

任务按固定 QASPER validation regression 范围中的前 24 篇不同论文选取，排除历史开发论文；比较每次允许两篇，PLAN 只允许相应首篇。查询/语料用于冻结请求，gold 不用于选任务或生成。12 个比较与 12 个计划覆盖条件、补查、缺资料、适用性、用户输入、参数缺失，每种类别两个。参考 abstract 段落 ID 是资料锚点，不能当标准答案。

检查四个请求维度（方法/程序、输入/资源、评价、限制/缺口），并对照数值、条件、步骤顺序、每字段引用、缺失参数、用户输入及真实矛盾。逐条身份与正文 hash 见[核对清单](../../eval/agentic-research/manifests/p7-application-source-review.json)；完整正文/原始草稿保留在本地批次目录。

| 固定案例 | 运行状态 | 原文核对与边界 |
| --- | --- | --- |
| comparison-01 | PARTIAL | 无事实章节/引用；只读格式命令，没有完成两篇比较。缺口中“内容完全缺失”仅适用于本次读取，不能推广为语料缺失。 |
| plan-01 | FAILED | 两次产物引用校验失败。未发布计划；研究阶段对整个知识库缺乏实验说明的判断超出实际碎片读取范围。 |
| comparison-02 | PARTIAL | 两篇资料均被引用；QANet 源/目标域、嵌入层判别器和 Seq2Seq 摘要方法有原文支持。2% EM/1.5% F1 是语音问答论文自身结果，不构成跨任务胜负；摘要论文评价维度仍缺失，部分章节引用可进一步贴近直接支持段。 |
| plan-02 | PARTIAL | 先决条件把 QASPER/qasper-v0.3 当作论文实验数据，所引结果段没有此要求；实际实验为 Text/Spoken-SQuAD。其余可见 96 维、2 头、batch 20、WGAN 配置有来源；学习率占位符保留为待确认。 |
| comparison-03 | PARTIAL | 两条残缺 LSTM 公式和残缺 CRF 表达式不能支持完整架构/目标，且把 additive cell update 误称 input gate。一个 worker 成功、另一个局部预算不足被保留，但运行失败被写为 source conflict，不能视为资料矛盾。 |
| plan-03 | FAILED | RESEARCH_EXECUTION_FAILED，未发布产物。保留失败及调用轨迹；不能计入有依据步骤的成功案例。 |
| comparison-04 | PARTIAL | 只引用 PolyResponse 的语音/文本 I/O 段，两篇方法/评价比较未完成。论文标题能识别来源，但该段不足以证明具体排序算法；没有制造两论文冲突。 |
| plan-04 | PARTIAL | 原文 S1—S6 支持餐厅 R 集合、top N、exp(a*s)、q_e 累加及阈值 t；N/a/t 未指定，保留 null 和待确认。Edinburgh 396 家/4225 图片/6725 模板响应/125830 评论句及 SGD 500 配对来自原文；尚不具备完整复现实验说明。 |
| comparison-05 | COMPLETED | Agatha 本体事件/链接数据流程与 Bayesian SRI 跨语言潜变量均由两篇原文支持。全文处理链段仅明确 Portuguese，另一段列三种语言，不能把所有实现步骤推广为三语均已验证；实验数字缺失，没有填造跨论文胜负。 |
| plan-05 | FAILED | 真实 ask_user / WAITING_INPUT / INPUT_RECEIVED，2 小时回复保存为 user_input；随后 ARTIFACT_VALIDATION_FAILED，未发布计划。用户回复成功不代表产物成功。 |
| comparison-06 | PARTIAL | 只有 AMR 论文目录说明，足以支持章节指引，不能完成算法/评价或两篇比较；第二篇未读，应保留为本次证据缺口。 |
| plan-06 | COMPLETED | AMR Bank 298/33、non-anonymized CNN-Dailymail 约 300k、JAMR/Lead-n-AMR/人评有对应原文；batch_size/window_size 保留 null。提纲不是完整算法；详细抽图和生成程序留作待确认。保留原文 ROGUE 拼写，不宣称复现实验已执行。 |
| comparison-07 | COMPLETED | 英语与俄语 ELMo 词元/词形差异、另一论文英文数据集 10%/21% relative improvement 及 BLEU/NIST/ROUGE-4 有支持，条件分别保留；具体数据与限制维度不足，不能跨任务比较效果。 |
| plan-07 | COMPLETED | Senseval-3 nouns、UDPipe/UD 2.3、top-layer ELMo/WSD 步骤由已读文本支持。保留用户 2 小时约束且明确无法证明满足；完整俄语数据/训练配置仍待确认，没有把用户预算写作论文事实。 |
| comparison-08 | PARTIAL | Nepali NER 的 2015—2016 报纸、PER/LOC/ORG、预处理和 CoNLL-2003 指标由原文支持；评价补查提供了可用事实，但没有读到第二篇，未完成比较。 |
| plan-08 | PARTIAL | 大部分 P100/PyTorch/torchtext/dropout 0.5/POS 数据及调参候选有支持；原文 learning-rate 候选首项为“0,1”，产物未声明歧义便改成 0.1，不能按精确原文数字保留计为通过。最优表值仍待确认。 |
| comparison-09 | PARTIAL | Stateology 197527 用户/4600465 博文及 PA SVM/Logistic/ILP 方法分别有支持；不混合两任务评价，没有捏造 2035 GPU-hour 或碳排放。完整量化评价与限制仍缺。 |
| plan-09 | PARTIAL | 历史 Blogger 搜索、21 篇下载、清理及 LIWC/Roget 分布处理有原文支持；未来部署成本留作缺口。这是历史论文流程整理，没有验证 2035 服务可用性或执行任何采集。 |
| comparison-10 | PARTIAL | 100k iterations/batch 64/Adam 0.001、另一论文 Yelp 500K 与关键词保留率有支持且场景分开；“greedy decoding 可能漏掉最优路径”为自身推断，不能包装成论文已述限制。GTD 是 protocol 的原文不自动证明该文所有限制。 |
| plan-10 | PARTIAL | ERG/DMRS/pydmrs、数据规模/validation/test 分工和 10 references 有支持；缺口把本次 MODEL_CALL_BUDGET 解释为源文未披露计算预算，是运行问题与资料缺口混淆。部分训练设置没读到仍待确认。 |
| comparison-11 | PARTIAL | 只有“main contributions”引导句，不能证明具体方法；缺口声称另一论文不在允许文档或知识库，超出本次未读取的事实，两个输入文档均在固定允许范围。 |
| plan-11 | FAILED | 真实用户澄清回复为 2 小时并保存；两次产物校验失败，未发布计划。不能把有回复当作满足时间限制的证据。 |
| comparison-12 | PARTIAL | Paper A 引用只有敏感内容提示，标题不支持 computational methodology；Paper B pronoun/translation 挑战段有效，但仍不足以完成两篇比较，未编造实验数值。 |
| plan-12 | COMPLETED | 1986—2015 NYT、word2vec 100/context 10/min 5/negative 10/iterations 10/1e-4 均有支持，batch_size null；“Connotation Frames sparsity” caution 引用的段只论缺少 ground-truth，不能支持该断言。源文在训练后归一化，计划把归一化前移到年度初始化前，顺序需核实。 |

应用批次：`local-data/agentic-research/runs/20260917T142538_P7_applications/`，生成模板 v3。实际仅 comparison-03 创建两个 worker：一个完成、一个局部预算不足，成功发现与失败缺口保留。plan-05/11 真实进入 WAITING_INPUT，自动回复 2 小时后存为 user_input；两者随后产物校验失败，没有发布计划。不能把追问接口成功解释为时间约束满足。

多个案例只读目录、引导句、格式命令或公式碎片，再退出于调用预算。“本次没有读到内容”不能改写为“知识库或论文没有内容”。比较未完成和省略事实可以诚实保留为缺口，但不构成成功比较。量化结果属于各论文自己的任务/语言/数据和评价设置，不能跨任务排名。

已针对语料版本被误用为实验数据的问题收紧最终生成输入：模型证据投影仅保留正文、引用身份、extent/截断和原文章节/表格上下文，服务器保留原来源/版本引用快照。v4 同时要求对残缺公式/抽取占位符保持缺口；实际遵从仍须原文检查。

| v4 原题原范围复测 | 结果 | 仍存在的问题 |
| --- | --- | --- |
| comparison-03 | COMPLETED，引用两篇 | 仍将残缺 LSTM/CRF 表达式写成完整公式，监督信号由结构推断；不能计语义通过。 |
| plan-02 | FAILED | 原始未发布草稿不再把 QASPER 当实验数据，但两次遗漏 step.parameters 必填数组；ARRAY_REQUIRED_OR_TOO_LARGE，未发布 PLAN。 |

两例新批次为 `local-data/agentic-research/runs/20260917T145737_P7_application_retest_v4/`，原 24 项目录/问题/负结果未覆盖。173 项程序回归包含真实 SDK HTTP 输入检查：托管元数据不进入 evidence 投影，正文和结构上下文保持，公开引用版本仍完整。这只证明输入/身份行为，不能替代语义检查。

后续优先改进检索/阅读对有效正文的选择、生成对碎片和证据不支持项的拒绝、字段位置清晰的结构修复诊断、运行问题与资料缺口的区分。先针对这些失败案例做新批次复测，再考虑增加更多框架/工具或宣称多 Agent 收益。PLAN 始终为草稿，没有批准、执行或机器人下发。

P8 随后的四请求演示独立留档于 `20260917T192824_P8_demo_v4`，没有覆盖上表。此次 comparison-05 只有关系短句/一篇引用，plan-06 形成主要步骤有来源的草稿，但均因预算退出而为 PARTIAL；batch/window 只有待确认项，没有生成请求要求的 null 条目。10 条演示引用也由 Codex 原文检查，具体负结果和 SHA 见[P8 清单](../../eval/agentic-research/manifests/research-p8-handoff-2026-09-18.json)。不同批次/模板/调用时段的状态不能作受控改进效果比较。
