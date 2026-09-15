# 中文技术文档诊断题草案

状态：10 道来源核查后的 AI 草案，全部 `human_review=pending`，仅可作开发诊断。不是新增工业 gold，也不是独立测试集。当前不计入原计划 20 条中文人工复核开发题。

目的：用户不必从零编题；先用互联网技术文档验证中文直接问答、多要点覆盖和配置混淆。由于只有两个同源文档组，不能据此声称跨来源泛化。后续扩充独立来源，再建立按文档组隔离的测试集。

资料按 Python 3.13 文档限定，不混入其他版本行为；2026-09-05 通过官网核查。表中答案均为释义，不是原文摘录。网页仍可能更新，正式入库前须保存 HTML/文本和 SHA-256，并验证每个 URL 锚点。

## 来源 A：argparse

[官方中文文档](https://docs.python.org/zh-cn/3.13/library/argparse.html)。来源组 `python313-argparse`，全部开发使用。

| ID | 问题 | 必需事实点 | 证据章节 | 禁止结论 |
| --- | --- | --- | --- | --- |
| ZH-01 | 如何把 `--config` 设为必须提供的命令行选项？省略它会怎样？ | `required=True`；缺失时报错 | [required](https://docs.python.org/zh-cn/3.13/library/argparse.html#required) | 默认允许缺失 |
| ZH-02 | `--mode` 仅允许 `fast`、`safe`，应使用什么参数？传入其他值会怎样？ | 设置 choices；非法值解析报错 | [choices](https://docs.python.org/zh-cn/3.13/library/argparse.html#choices) | 自动选最接近值 |
| ZH-03 | 为参数同时设置 `type=int` 和整数 choices 时，是先转换还是先检查选项？ | 先做 type 转换再检查 choices | [choices](https://docs.python.org/zh-cn/3.13/library/argparse.html#choices) | 先按字符串比较 |
| ZH-04 | 必需选项配置了 default，用户不传该选项时能否直接使用默认值？ | required 参数忽略 default，仍须提供 | [default](https://docs.python.org/zh-cn/3.13/library/argparse.html#default)、required | 默认值免除必需约束 |
| ZH-05 | 某必需参数只能取两种值，需要同时指定哪些约束？漏传和传错值分别怎样？ | required 与 choices；分别缺失报错、非法值报错 | required、choices | 只设 choices 即变必需 |

## 来源 B：logging

[官方中文文档](https://docs.python.org/zh-cn/3.13/library/logging.html)。来源组 `python313-logging`，全部开发使用。

| ID | 问题 | 必需事实点 | 证据章节 | 禁止结论 |
| --- | --- | --- | --- | --- |
| ZH-06 | 根 logger 已有 handler，再调用 basicConfig 默认会重新配置吗？ | 默认不做操作 | [basicConfig](https://docs.python.org/zh-cn/3.13/library/logging.html#logging.basicConfig) | 总会覆盖配置 |
| ZH-07 | 已有根 handler 时如何强制重新配置？旧 handler 会怎样？ | `force=True`；移除并关闭已有根 handler | basicConfig 的 force | 保留所有旧 handler |
| ZH-08 | basicConfig 能同时指定 filename 和 stream 吗？冲突会怎样？ | 不能；抛 ValueError | basicConfig 的 stream | 同时输出两个位置 |
| ZH-09 | logger 的 propagate 为 False 时，其消息还会继续交给祖先 handler 吗？ | 不会继续传给祖先 handler | [propagate](https://docs.python.org/zh-cn/3.13/library/logging.html#logging.Logger.propagate) | 关闭所有本地处理 |
| ZH-10 | 已有根 handler 想强制重配，并阻止某子 logger 向祖先传递，分别设置什么？ | 根 basicConfig 的 force；子 logger 的 propagate | basicConfig、propagate | force 同时关闭子 logger 传播 |

## 人工复核表与使用边界

每题需要记录 `reviewer/date/accepted/reason`；代理不能自动填写 reviewer。复核重点是题目是否清楚、答案是否完整、文档范围是否匹配。脚本行为也可通过 Python 3.13 本地验证，但执行验证不等于人工已复核。

这些题没有不可回答负例。未对整个来源范围做缺失性核查前，不制造“文档无答案”标签。后续负例须说明相对于哪个冻结语料池不可回答。

正式数据导出应分成：仅问题文件、仅正文索引文件、独立 gold 文件。表中答案、禁止结论和证据链接标记不能与问题一起发送给检索器。该 Markdown 是审阅材料，不能整份直接入知识库。
