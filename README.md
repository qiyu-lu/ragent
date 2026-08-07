<p align="center">
  <a href="https://github.com/nageoffer/ragent">
    <img src="assets/ragent-ai-banner.png" alt="Ragent AI">
  </a>
</p>

<p align="center">
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-4a9b8f?style=flat-square" /></a>&nbsp;
  <img src="https://img.shields.io/badge/Spring%20AI-2.0-6DB33F?style=flat-square&logo=springboot&logoColor=white" />
</p>

> **关于本仓库**
>
> 这是 [nageoffer/ragent](https://github.com/nageoffer/ragent) 的 fork。项目主体由原作者开发，本仓库用于记录我在其基础上做的问题分析与改进，改动清单见下方 [我的改动](#-我的改动)。
>
> 想了解项目全貌、部署方式或完整文档，请访问 **[原项目仓库](https://github.com/nageoffer/ragent)** 与 **[官方文档](https://nageoffer.com/ragent)**。

## 🚀 项目简介

Ragent 是一个面向 Agentic RAG 演进的生产级 Java AI 应用平台，覆盖从文档入库到智能问答的完整链路。

- **混合检索**：向量、关键词、知识图谱、联网搜索并行召回，支持去重、RRF 融合与 Rerank。
- **问题理解**：支持查询词映射、问题重写与拆分、树形意图识别和多知识库路由。
- **模型与工具**：支持模型档位、首包探测、熔断降级，以及 MCP 工具发现、提参与校验。
- **会话记忆**：最近 N 轮消息结合持久化摘要，控制 Token 成本并保留关键上下文。
- **流量保护**：Redis 公平排队与分布式并发控制，避免突发请求压垮模型服务。
- **知识闭环**：提供可编排入库 Pipeline、远程刷新、回答溯源、用户反馈、Trace 和管理后台。

![](assets/ragent-framework.png)

后端为模块化单体，按职责分为 `framework`（通用基础能力）、`infra-ai`（模型客户端与路由）、`bootstrap`（RAG 问答、知识库、检索与管理端）、`mcp-server`（独立 MCP 工具服务）四个 Maven 模块。

## 🔧 我的改动

| 改动 | 说明 | 链接 |
|:---|:---|:---|
| trace run 取消态收尾 | 用户点击「停止生成」后，`t_rag_trace_run` 永远停在 `RUNNING`，导致管理端链路追踪显示歧义、Dashboard 成功率与时延统计失真；定位到 cancel 语义在 provider client 层被消化，改为在 `StreamTaskManager` 层将 run 幂等收尾为 `CANCELLED` | [Issue #79](https://github.com/nageoffer/ragent/issues/79) · [PR #80](https://github.com/nageoffer/ragent/pull/80) |

> 后续的检索评测与其他改进会持续补充到这里。

## 📌 说明

- 本仓库遵循原项目的 [Apache 2.0 协议](./LICENSE)，版权归原作者所有。
- 默认分支 `dev` 用于承载我的改动；`main` 保持与上游同步，不做修改。
- 本仓库不接受 issue 与 PR，项目相关问题请提至 [原项目仓库](https://github.com/nageoffer/ragent/issues)。
