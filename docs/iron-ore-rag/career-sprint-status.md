# 秋招冲刺状态

计划：[career-sprint-plan-2026-09-18.md](career-sprint-plan-2026-09-18.md)。本文件 ≤ 40 行，每个会话结束时更新。

| 项 | 值 |
| --- | --- |
| 更新时间 | 2026-09-18（S0 完成） |
| 当前工作项 | W1 未开始 |
| 分支 / 提交 | `feat/llm-backend-hardening`，自 `65c99c7`（标签 `career-v0-baseline`）拉出 |
| 基线回归通过数 | `LC_ALL=en_US.UTF-8 bash scripts/validate-agentic-research-p7.sh`：Python 33/33，Java 217/217（infra-ai 10 + bootstrap 207），约 20 s |
| 最近的运行目录 | 无 |

## 进度

- [x] S0 分支、基线标签、提交计划、基线回归
- [ ] W1 缓存友好的上下文布局 + X1
- [ ] W3 模拟上游与故障注入基准 + X3
- [ ] W2 心跳租约、跨实例接管、断点续跑、优雅停机 + X2
- [ ] W4 权限隔离 + 越权矩阵
- [ ] W5 内容寻址的 embedding 复用 + X5
- [ ] S6 简历条目、README、面试卡

## 下一步

W1 第一项：读供应商缓存文档、做协议探针、查明 C 模式命中率为何接近 0。

## 已知事实与遗留问题

- 缓存命中率基线：P7 回归 B 5.2%、C 0.02%，R3 复测混合批 9.2%（由各运行目录的 `usage.jsonl` 统计）。C 为何更低未查明，列入 W1 第一项。
- 用户自己的未跟踪笔记（如 `docs/current-code-notes-*`）出现时不修改、不提交；2026-09-18 15:42 起该目录已不在工作区，非计划会话所为。
- 回归必须在英文语言环境下跑：本机默认 `LANG=zh_CN.UTF-8` 时，JSON Schema 校验报错被本地化为中文（如“未找到所需属性“statement””），`ResearchNativeToolsTest` 的 3 个断言英文措辞的用例失败。这也意味着发给模型的参数错误反馈语言随宿主机语言环境变化；未修，待用户决定是否固定校验器语言。
