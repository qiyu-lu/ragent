# 研究改进与秋招版本：会话交接（2026-09-18）

最新核对时间：2026-09-18 13:05，Asia/Shanghai。下方 1—5 节保留 11:53 的 R1—R5 历史交接，当前按主计划 8.7 推进秋招版本；本轮实际结果和接续命令见第 6 节。计划没有整体完成。

**当前接续点：S1 角色模型配置已实现并验证；S2 已修复检索失败后 COMPLETED 空产物，检索端点仍不稳定；S3 首批 32 题已固定、尚未执行。** 用户要求成组实现、集中验收，允许收益有限；不要重新实施 P0—P8 或覆盖旧 R5 的 22 条记录。优先阅读第 6 节；第 5 节的完整 R5 路线不再作为首版秋招前置条件。

主计划：[实施计划 8.7](agentic-research-implementation-plan-2026-09-17.md#87-秋招版本实施与集中验收2026-09-18)。逐阶段记录：[执行日志](agentic-research-execution-log.md)。机器状态：[本轮清单](../../eval/agentic-research/manifests/research-s1-model-roles-2026-09-18.json)及[旧 R5 清单](../../eval/agentic-research/manifests/research-r5-validation-2026-09-18.json)。运行和启动：[通用交接](agentic-research-handoff.md)。

## 1. 授权、Git 和工作区

- 用户已授权直接执行完毕改进计划、真实供应商调用及 Git 记录。切换会话前暂时停止推进，新会话收到“继续”后接续原目标。
- **不再查询价格、估算金额或请求费用确认。** 主计划 8.5 优先于历史报告/预算文档中的旧要求。实际请求、token、耗时、失败、重试和 unknown usage 必须保存；技术超时、取消、并发和有限调用次数继续生效。
- 仓库：`/home/sd101t/IdeaProjects/ragent-iron-ore-rag`；分支：`feat/agentic-research`。
- 历史 R4 运行代码提交：`0b39d31`，上一轮交接提交 `f5604ec`。本轮阶段提交按唯一标题 `feat: select research models by role and preserve retrieval failures` 定位，用 Git 核对实际 SHA。
- 关联 worktree 的 Git 公共目录在 `/home/sd101t/IdeaProjects/ragent-new/.git`，Git 写操作需要沙箱升级；用户已授权提交，没有要求 push、merge 或改写历史。
- 用户已有未跟踪目录 `docs/current-code-notes-2026-09-18/`，本轮未编辑、暂存或提交。不要使用 `git add .` 带入它。
- 交接前核实没有本轮 `ResearchRunCommand`、连接探针或串行驱动进程仍在运行。R5 临时任务库已清理；保留只读语料库及所有产物。

| 提交 | 内容 |
| --- | --- |
| `383b3de` | R1 工具嵌套契约与原生结束恢复 |
| `6353eb7` | R2 检索阶段观测、deadline/取消、有限重试、SSE 恢复 |
| `6b43e8e` | R3 阅读反馈、精简证据投影、模式交错和检索对照工具 |
| `0cfc2fb` | R4 PLAN 字段契约、执行错误与来源缺口分开保存 |
| `0b39d31` | 关闭 SDK 隐式重试、每个模型 attempt 记账，冻结 R5 配置 |

## 2. 已完成实现与验证

R2：`RequestOperation` 显式跨线程传递检索 deadline、run/task/tool 身份和取消句柄；HTTP 记录 DNS/TLS/请求写出/响应头/正文阶段，PG 查询可取消。检索错误明确返回失败，不能伪装为空结果。embedding 单次最多 12 秒、最多两次尝试；仅瞬时传输、429/5xx 可重试，成功查询按运行和模型配置缓存。PG 单连接设置/query timeout 最多 5 秒，检索内层结束早于外层工具期限；探索时长给最终生成留出时间。

浏览器 SSE：15 秒静默检测，带抖动退避，先 GET 快照再按持久 sequence 重读并去重，不重新 POST 创建任务。模型上游没有 token 续取游标；一次响应确认 finish 后才交给 Agent，断流时丢弃本次半截文字/工具 JSON，保留之前完成的工具观察再有限重试。

R3：检索后先读相关候选，补查前检查是否仍有关键证据缺口；限制同查询/文档范围重复搜索。比较任务显示各文档阅读覆盖，不强制阅读全部 distractors。证据输入保留正文/身份/章节/截断，服务器保存完整审计快照；目录、占位符和过短文本有提示，但提示不是语义验收。主/worker 提示为 v4/v3。

R4：PLAN 必填数组、步骤、参数字段显式 required；未知值使用 null，顺序和引用错误返回字段路径。`executionIssues` 保存执行错误，`gaps` 保存原文缺口；历史结果缺少新字段时兼容为空。产物提示为 `research-artifact-v5`。取消和旧 epoch 不能发布迟到结果；暂时失败/探索退出可利用已读证据形成合法 PARTIAL。

最后发现 AgentScope 2.0.1 最终生成路径可能采用 SDK 默认三次尝试。本地 503→200 复现 HTTP 两次但台账一条，已显式设置 maxAttempts=1，由应用外层统一重试和记账。SDK 无 HTTP 状态码包装的 IOException 继续检查底层原因；明确 401/403 即使包裹 IOException 仍不另起生成。实际断开的文本流、半截工具 JSON、每 attempt usage 和暂时服务失败后的证据收尾均有测试。历史真实日志未发现内部重试警告，不能据此推算历史未知 usage。

| 检查 | 结果与位置 |
| --- | --- |
| 本轮唯一相关后端用例 | 213：研究/SDK 129 + 共用问答/检索/摄取/取消 73 + 来源 PostgreSQL IT 11 |
| Python | 30 项通过 |
| 前端断流恢复 | 4 项通过，`node frontend/research-stream.test.mjs` |
| 前端构建 | 通过；app 严格类型检查仍有原有 24 项错误，没有新增研究页面错误 |
| 受控浏览器夹具 | 9 项通过，`20260918_R4_browser_v2`；首次并发编译期间启动失败记录保留 |
| 主后端套件日志 | `20260918_R5_validation/backend-final.log`，128 项通过 |
| 最后收尾测试日志 | `completion-recovery-final.log`，17 项产物测试通过，其中 1 项为新增；不把复跑相加 |
| 共用路径日志 | `shared-path-regression.log`，73 项通过 |
| 来源数据库日志 | `p2-database.log`，11 项 IT，schema/重复升级/存储检查通过，临时库删除 |

日志均在 `local-data/agentic-research/runs/`，不提交原始日志和数据。受控浏览器不是生产登录/真实来源下载/完整业务 Web E2E，引用身份校验不是事实语义正确率。

## 3. 已完成真实校准及取舍

所有批次的预测、得分、trace、usage、失败、源码快照和运行配置保留，未覆盖历史批次。

| 批次 | 范围 / 结果 | 限制 |
| --- | --- | --- |
| `20260918_R1_failure_replay_v1` | 12 原题×ABC：35 完成/1 部分/0 失败，历史同题 21/4/11 | 故障选题，不是整体质量结论 |
| `20260918_R2_failure_replay_v1` | 2 原超时题×ABC：4 完成/2 失败；65 embedding 中 63 失败 unknown，部分完成答案为空 | 保留退化，不能写成改善 |
| `20260918_R2_failure_replay_v2` | 同 6 项全部完成；16 embedding 中 2 次失败后重试恢复 | 两次失败已写出请求体、尚无响应头；不能区分供应商排队/回程网络；多跳引用链仍不完整 |
| `20260918_R3_replay_v1` | 12 原题交错 ABC：35 完成/1 失败；未读引用错误 12→5，读取 63→124 | 输入 3,269,722→1,923,294，但请求 273→298、答案变化不一致；一个 C 运行委派 3 worker |
| `20260918_R3_rerank_v1` | 同 12 题 A 重排：12/12 完成；12 次 rerank 全成功，36,887 tokens | 小样本答案 F1 上升但证据 F1 下降，不采用为主回归默认 |
| `20260918_R4_baseline_v1` | 同 12 题 B，thinking=false：12/12 完成 | R1 故障选题；冻结于最后 SDK 重试补丁前 |
| `20260918_R4_thinking_v1` | 同 12 题 B，thinking=true：10 完成/2 产物校验失败 | p50 40.938→78.964 秒；QASPER F1 0.4051→0.4430，MuSiQue 3 个可回答题 0.8667→0.7500，不采用为默认 |
| `20260918_R4_applications_v1` | `comparison-02/C`、`plan-06/C` 均完成，使用最后修正运行类 | 原文审阅仍有负例，不是全面语义通过 |

R4 应用负例：PLAN 步骤 1/3 用论文目录支撑具体操作；比较报告的方法引用没有覆盖全部权重共享、判别器位置、BERT/copy 等描述。部分数字、数据集、评价有直接支持，但不能把两篇均有引用等同事实全面受支持。

详见 [R3 清单](../../eval/agentic-research/manifests/research-r3-reading-2026-09-18.json)、[R4 清单](../../eval/agentic-research/manifests/research-r4-artifacts-2026-09-18.json)。目前尚不能宣布稳定整体答案提升或多 Agent 收益。

## 4. R5 实际停点与网络诊断

`20260918_R5_regression_v1` 仅离线准备、0 供应商调用，最后 SDK 修复前被替代；不要恢复它。

`20260918_R5_regression_v2` 已真实启动；连续 embedding 失败后只停止该评测 JVM，返回 `JAVA_EXIT_143`，正常完成汇总并删除随机任务库 `research_p3_20260918031911_992e4983`。现有 `summary.json`、`run.json`、`attempts/0000_MIXED/execution.json` 均保留。

- **22/1200 项已记录：17 COMPLETED、1 WAITING_INPUT、4 FAILED；1178 项未执行。** 失败摘要：1 `NETWORK_ERROR`、3 `RESEARCH_EXECUTION_FAILED`；其中后者可能受中止影响，需看 trace，不统一归为网络根因。
- 模型记录 129 请求、已知 input 605,078/output 18,972 tokens、usage unknown 0；embedding 89 请求、已知 total_tokens 310、unknown 51。汇总覆盖停止前所有捕获请求，不一定只属于已发布产物。
- B 的一次检索连续失败后转 ask_user/WAITING_INPUT，把临时不可访问与问题澄清混在一起，是待分析的失败表现。
- 只有少量 QASPER 前缀记录，不能把其 subset 均分与历史全部 400 题作整体效果比较。
- 当前指纹仍与 v2 冻结源码一致；每批复制 `bootstrap/framework/infra-ai/target/classes` 到 run 下 `runtime-classes`，后续编译不会污染已有运行。首批 freeze 时源码和 class 应来自同一稳定构建。

随后完成连接探针：相同公开查询 `evaluation metrics used`，并发不超过 2，12 秒单次期限，交替 HTTP/2 和 HTTP/1.1，穿插新连接。原始文件：`20260918_R5_validation/connection-probe.jsonl`。

| 探针 | 请求 | 成功 | 失败 unknown |
| --- | ---: | ---: | ---: |
| HTTP/2 复用连接 | 30 | 18 | 12 |
| HTTP/1.1 复用连接 | 30 | 26 | 4 |
| HTTP/2 新连接 | 6 | 4 | 2 |
| 合计 | 66 | 48 | 18 |

48 次成功共 192 tokens。新连接和两种协议都有失败，HTTP/1.1 成功占比更高仍受执行时段影响；不能直接认定 HTTP/2 或连接池是根因，也未修改协议默认值。失败多在请求写出后等待响应头，不能区分网络、供应商处理和排队。Python urllib 新连接设置 60 秒上限的一次请求 824 ms 成功、4 tokens，不能据此认定 12 秒上限过短。该记录为 `long-embedding-probe.json`。

探针代码在本地 `20260918_R5_validation/EmbeddingConnectionProbe.java`、`run_connection_probe.py`、`long_embedding_probe.py`。探针已结束，无需清理其他服务或业务数据库。

串行驱动 `20260918_R5_validation/run_all.py` 在 v2 汇总为 22/1200 后主动退出；**两个 repeat 批次和 R5 72 项应用尚未启动。** 不直接原样重跑该驱动，旧主目录/日志已存在，启动器禁止覆盖。

## 5. 新会话按此顺序接续

1. 阅读本文件、计划 8.5/8.6、执行日志和 R5 清单；先核对 Git、当前进程和已有运行目录。用户已授权执行完整改进与调用，不需要重新询问费用。
2. 先针对连续 embedding 超时做短批 transport 验证，必要时独立对照连接协议、并发和等待期限，保存失败/usage。目前只有相关性，避免无依据把默认改成 HTTP/1.1、无限加时或无限重试。若调整技术上限，将改变的参数和对照边界写入新配置。
3. 若无需改源码/配置且端点恢复，可以按原 v2 加 `--resume` 完成 1178 未记录项；原失败/WAITING_INPUT 留在分母，不会自动重刷。若修改研究源码、评测 Python 或配置，必须创建新的主目录（建议 `20260918_R5_regression_v3`），保留 v2 全部负记录，重新固定 400×ABC。
4. 主批完整后执行预登记 12 题×ABC 的两轮额外复跑，以及原 24 应用×ABC 共 72 项；与主批串行，保持整体真实模型并发上限 2。应用必须逐个已发布章节/PLAN 字段对照实际引用原文，缺失产物也保留。
5. 用离线比较脚本生成前后逐题变化、状态恢复/新增失败、证据和 token/耗时汇总；分别报告实际委派和语义负例。完成更新计划、执行日志、验证/评测/应用报告、交接及 R5 manifest，然后提交 Git。

运行配置：[r5.json](../../eval/agentic-research/configs/r5.json)，thinking=false、rerank=false、model `qwen3.7-flash-2026-07-15`；其他原主要限制：model16/tool24/300s/model timeout60s/tool timeout30s/input28000/output4096/final reserve2/worker concurrency2/worker total4。

运行器使用独立 `research_corpus_v1`、容器 `ragent-iron-ore-dev-postgres-1`；仅公开 collections `rs_qasper_validation_v1_full` / `rs_musique_dev_v1_full`，模型目的地 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`、embedding `https://api.siliconflow.cn/v1/embeddings`。Key 来自既有 IDE 环境或 env，仅用于认证；不要输出或提交凭证。

```bash
# v2 原指纹未变化且确认可恢复时；只执行未记录项
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/r5.json --run-dir local-data/agentic-research/runs/20260918_R5_regression_v2 --execute --resume

# 改过运行源码/配置时，新批次不覆盖 v2
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/r5.json --run-dir local-data/agentic-research/runs/20260918_R5_regression_v3 --execute

# 两轮分别使用新的 run-dir
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/r5.json --case-ids eval/agentic-research/manifests/research-r5-repeat-case-ids-2026-09-18.json --run-dir local-data/agentic-research/runs/20260918_R5_repeat_1 --execute
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/r5.json --case-ids eval/agentic-research/manifests/research-r5-repeat-case-ids-2026-09-18.json --run-dir local-data/agentic-research/runs/20260918_R5_repeat_2 --execute

python3 eval/agentic-research/evaluate_applications.py --mode all --config eval/agentic-research/configs/r5.json --run-dir local-data/agentic-research/runs/20260918_R5_applications_v1 --execute

# 完整批次执行后，将 --after 换成实际完成的主目录
python3 eval/agentic-research/analysis/compare_runs.py --before local-data/agentic-research/runs/20260917T145853_P7_regression_v4 --after local-data/agentic-research/runs/20260918_R5_regression_v3 --repeat local-data/agentic-research/runs/20260918_R5_repeat_1 --repeat local-data/agentic-research/runs/20260918_R5_repeat_2 --output local-data/agentic-research/runs/20260918_R5_validation/comparison.json
```

`analysis/compare_runs.py` 已用完整 R1/R3 的 36 项配对记录验证可运行，未用于未完成 v2；它不调用模型。两轮 repeat 每类 6 题、随机种子 20260918、排除 R1 故障选题、不使用答案/得分选题。400 题包含先前诊断题，本轮是固定开发回归，不是 unseen holdout。MuSiQue 固定 200 行仅 105 行 answerable 用于答案/支持评分；full 5839 题/17517 任务仍仅准备，生产登录、真实来源下载及已有业务库升级仍未验证。

新会话可粘贴：

> 历史 R5 路线已由下方第 6 节的秋招接续范围调整；新会话优先按第 6 节执行，保留本节旧批次证据。

## 6. 秋招版本本轮实施与下一步

用户于当前会话授权写入计划并开始实施，采用“成组实现、集中验收”，允许收益有限；旧的真实调用和 Git 授权继续有效。阶段提交标题为 `feat: select research models by role and preserve retrieval failures`。

S1 已实现主/worker/最终生成独立模型选择。在线默认注册别名为 research-max/research-flash/research-max，对应 `qwen3.7-max-2026-05-20` / `qwen3.7-flash-2026-07-15` / Max；角色配置为空时沿用旧 model-id。继续共享预算、请求配额和取消。评测配置 [career.json](../../eval/agentic-research/configs/career.json) 冻结实际模型 ID；p7/r5 未声明角色的配置会明确固定所有角色为 Flash。新批保存 runtime.json/modelsByRole，并按实际角色/模型汇总 usage。模型差异与架构差异不混作单一因果结论。

S2 已补齐失败状态：尚未恢复的检索错误进入 executionIssues，finish 保留为 PARTIAL；没有已读/合法 worker 证据且有执行错误时，运行直接 FAILED，保留研究状态并跳过空产物生成。之后一次成功检索清除本 Agent 当前未恢复的检索错误，不把已恢复故障永久算作失败。该规则判断执行状态，不证明其余章节语义完整；S2 尚未解决外部检索不稳定，也未完成全部问题清单。

| 本轮检查 | 实际结果 / 边界 |
| --- | --- |
| 集中程序回归 | 初批 208 项后端/33 Python 通过；加入失败状态修复后 30 类 211 项后端/33 Python 全通过，0 失败/错误/跳过。日志及逐类统计位于 `20260918_S1_validation`；随机测试库删除。没有新增前端/浏览器检查 |
| 真实应用 `20260918_S1_applications_v1` | 2 个 C 请求，26 次模型正常返回（main Max 13、worker Flash 10、finalization Max 3），已知 input 60065/output 2868，模型 unknown 0；comparison 创建 2 worker，plan 未委派。PLAN 首次缺少 gaps 后修复 |
| 该应用的业务结果 | 28 次 embedding 全部失败、usage unknown 28；两产物零章节/零引用，PLAN 没有来源支持的步骤。旧逻辑标成 COMPLETED，但实际检索阻塞。已保留该负例，不能算完成比较、计划质量或多 Agent 收益 |
| 独立 JSON 探针 `20260918_S1_generation_probe_v2` | 不依赖检索，合成非敏感 7 ms 文本，Max 最终角色 JSON 符合用例；单请求 1189 ms、53 input/14 output tokens。只证明 SDK/JSON 兼容，不是业务或答案质量。v1 本地编译失败，0 API 调用，原记录保留 |
| 短 transport `20260918_S2_transport_v1` | 固定公开查询、并发 2、12 秒单次；HTTP/2 与 HTTP/1.1 各 1/2 成功，合计 4 请求、2 成功/2 InterruptedIOException，成功已知 total_tokens 8，失败 unknown 2。不能证明协议根因，未改协议默认值 |

真实应用及 JSON 探针的运行代码冻结在 S2 状态修复前；角色模型创建参数之后没有再修改，当前失败状态修复已经程序回归，但未做当前源码的真实业务复测。真实应用临时库 `research_p3_20260918045532_b6098df3` 已删除。原始请求、预测、事件、每次 usage、失败、源码快照和 hash 全部留在 Git 忽略目录；[机器清单](../../eval/agentic-research/manifests/research-s1-model-roles-2026-09-18.json)引用这些文件，不覆盖旧 R5。

接下来先解决或确认检索端点恢复，只做与明确阻塞有关的有限检查，继续成组实现；不要把每个改动都拆为收益预验证，也不要在检索明显不可用时启动大量无意义质量请求。当前没有证据需要下载新数据集或继续更换主模型。S3 首批从现有固定开发集按 hash 选取 QASPER/MuSiQue 各 16 题，ABC 共 96 项，[ID 题单](../../eval/agentic-research/manifests/research-career-case-ids-2026-09-18.json)与 seed/输入 SHA 保留；未读取 gold 或历史输出选题，不是独立 holdout。

检索稳定后，新建批次执行这些同题对照，再做代表性复跑和比较/PLAN 原文核对。已经有大规模历史基线，不再把完整 R5 400×ABC 或 full 17517 作为首版秋招前置条件。先记录当前版本实际适用任务、失效样例、资源与语义支持，再写简历数字；S3/S4 尚未完成。

```bash
# 首批秋招验收，32 题/96 项；run-dir 必须是新的目录
python3 eval/agentic-research/evaluate_research.py --profile regression --config eval/agentic-research/configs/career.json --case-ids eval/agentic-research/manifests/research-career-case-ids-2026-09-18.json --run-dir local-data/agentic-research/runs/20260918_S3_regression_v1 --execute

# 检索恢复后用当前源码复测两条应用；旧 v1 留档
python3 eval/agentic-research/evaluate_applications.py --mode C --config eval/agentic-research/configs/career.json --case comparison-02 --case plan-06 --run-dir local-data/agentic-research/runs/20260918_S2_applications_v1 --execute
```

现有源码/评测 Python 已改变，禁止在旧 R5 v2 追加或使用 resume 混入新版本。之后若再次改指纹，继续使用新目录。本轮尚未启动上述 S3/S2 应用命令，不需要终止任何其他服务。

新会话可粘贴：

> 本节为暂停前交接。最新暂停约定和恢复范围以第 7 节为准。

## 7. S2 代码改进交付与用户要求暂停

最新用户指令：“改进完成后可以不用进行测评暂停任务，防止 5h 额度被用完”。按此要求，本轮交付代码与必要回归之后暂停，停止在途应用复测，不启动 32 题/96 项质量测评，不继续 S4。只有用户再次要求继续才恢复工作；历史供应商和 Git 授权仍保留，但不能据此忽略暂停指令。

起始提交 `8bbff9a`，阶段提交标题 `fix: bound repeated retrieval failures and pause career evaluation`。保留用户未跟踪的 `docs/current-code-notes-2026-09-18/`，未纳入提交。主计划 8.7、执行/验证/测评/应用记录已更新，[S2 机器清单](../../eval/agentic-research/manifests/research-s2-pause-2026-09-18.json)保存精确指纹和本地原始记录。

本轮代码补齐：

- 每个 Agent 连续两次检索工具失败后停止补查；更换查询不能绕过限制。仍允许读已有候选。没有已读证据、且所有已领取 worker 都触发相同限制时，主 Agent 也停止重新委派或重复搜索。成功检索重置当前 Agent 的失败计数和未恢复问题。
- 无待阅读候选时转入现有原生结束修复流程，最多两次模型请求，仍扣共享预算与 worker 局部额度。服务端限制搜索/委派，即使提供方忽略 tool_choice 也不能继续发起检索。
- 没有可引用证据且存在未恢复执行错误时，拒绝用 ask_user 要求用户补传资料。合法的缺少用户条件追问仍可使用，程序回归覆盖；此规则优先反馈当前检索故障，不能据它判断用户条件是否事实上完整。
- 只有执行错误时允许 finish 的 findings/gaps/conflicts 全为空；执行问题自动保存，避免为了结束伪造来源缺口。保留之前的无证据 FAILED/跳过空产物生成规则。
- 实际 embedding HTTP 记录协议、进程内连接标识和是否复用，不保存 URL、请求头、正文或远端地址。没有改变协议默认、向量模型/维度、超时或重试次数。

必要程序验证：31 类后端 217 项、Python 33 项全部通过，0 失败/错误/跳过，随机测试库删除。最后调整纯执行错误结束契约后，本地 SDK/工具/连接 30 项再次通过，不重复累加到总用例数。日志和逐类统计位于 `local-data/agentic-research/runs/20260918_S2_validation/`。没有新增前端、浏览器或生产 E2E 检查。

暂停之前的少量真实检查：

| 批次 | 实际结果与边界 |
| --- | --- |
| `20260918_S2_client_probe_v1` | 使用实际 SiliconFlowEmbeddingClient，同一公开查询、1536 维；默认协议 h2 与 HTTP/1.1 各一次都成功，约 2547/581 ms，2 请求共已知 8 tokens、unknown 0。只证明该时刻兼容，不证明稳定性或协议因果 |
| `20260918_S2_applications_v1` | 两条 C 请求已启动；用户要求暂停后只向精确 job.json 对应 JVM 发 SIGTERM，驱动返回 JAVA_EXIT_143，随机任务库 `research_p3_20260918052313_c6cc0d95` 删除。plan-06 导出 FAILED，comparison 在途未导出终态。无可验收产物，不作质量结果 |
| 该批资源 | 捕获 embedding 6 请求，3 成功/3 unknown，已知 27 tokens，均 h2。已导出模型台账是 Max 主角色 6 次、input 28644/output 973、这 6 次 unknown 0；另一在途任务模型台账不完整，不能称为整批总资源或整批 unknown 0 |

两条应用批次与客户端探针使用空 gaps 契约最后调整前的冻结构建。最终契约已通过本地测试，未再发真实请求。应用中成功与响应超时并存，仍无法确定网络/供应商处理根因；最终生成、应用语义和业务稳定性验收尚未完成。原始预测、usage、事件、日志和源码保留，不覆盖旧 S1/R5 负记录；中止终态不能统一归因为网络或模型效果。

当前已暂停，没有本轮研究/测评 JVM。S3 固定题单及 `20260918_S3_prepared_v1` 保留，0 真实调用。未来明确恢复时先按需核对外部检索可用性，再使用新的冻结目录完成集中验收、代表性复跑与原文核对；不得向当前已中止的 S2 v1 或旧 R5 v2 混入新源码。整批模型台账在 JVM 中止时可能缺少其他在途任务的导出记录，后续真实测评前应补逐次启动/结束持久记录，未捕获部分标记 unknown，不能补算为零。

新会话可粘贴：

> 阅读最新交接第 7 节、计划 8.7 与 S2 暂停清单。S1 角色配置及 S2 本轮代码改进已交付并通过必要回归；用户要求暂停、不测评以节省 5h 额度，S3/S4 未完成。先报告状态，不自行恢复调用；若用户明确要求继续，再按新冻结批次接续。保留中止相关失败、未知资源和外部检索不稳定边界，以及用户未跟踪笔记。
