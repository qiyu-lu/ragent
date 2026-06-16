# MCP Server 模块架构梳理

> 本文档基于对 `mcp-server` 模块源代码的阅读整理，描述该模块的整体架构、分层设计及请求处理流程。

---

## 一、模块定位

`mcp-server` 是一个**手写实现的 MCP（Model Context Protocol）服务端**，基于 HTTP + JSON-RPC 2.0 协议，让 AI（如 Claude）能够发现并调用服务端注册的工具（Tool）。

**没有使用** Spring AI 的 MCP SDK，而是从零手工实现了协议层、分发层、注册层和执行层。

---

## 二、整体分层架构

```
HTTP POST /mcp
      │
      ▼
 endpoint/MCPEndpoint            ← Spring MVC Controller，唯一 HTTP 入口
      │
      ▼
 endpoint/MCPDispatcher          ← JSON-RPC 方法分发器
      │
      ├── initialize             ← 握手：返回协议版本、服务器信息、能力声明
      ├── tools/list             ← 工具发现：返回所有已注册工具的 JSON Schema
      └── tools/call             ← 工具调用：找到对应执行器并运行
            │
            ▼
      core/MCPToolRegistry       ← 工具注册表（toolId → MCPToolExecutor 映射）
            │
            ▼
      core/MCPToolExecutor       ← 工具执行器接口
            │
      ┌─────┴──────────────────────────┐
      ▼              ▼                 ▼
WeatherMCPExecutor  TicketMCPExecutor  SalesMCPExecutor
(天气查询)           (工单查询)          (销售数据查询)
```

---

## 三、各包职责说明

### 3.1 `protocol` — 协议数据结构

| 类 | 职责 |
|---|---|
| `JsonRpcRequest` | JSON-RPC 2.0 请求体：`jsonrpc`、`id`、`method`、`params` |
| `JsonRpcResponse` | JSON-RPC 2.0 响应体：`result`（成功）或 `error`（失败），提供 `success()` / `error()` 静态工厂 |
| `JsonRpcError` | 错误对象：`code`（METHOD_NOT_FOUND=-32601、INVALID_PARAMS=-32602、INTERNAL_ERROR=-32603）、`message` |
| `MCPToolSchema` | `tools/list` 返回给客户端的工具描述，符合 MCP 规范：`name`、`description`、`inputSchema`（含参数 type/description/enum） |

### 3.2 `core` — 核心抽象

| 类/接口 | 职责 |
|---|---|
| `MCPToolExecutor` | 工具执行器接口，两个方法：`getToolDefinition()` 声明元数据，`execute(request)` 执行逻辑 |
| `MCPToolDefinition` | 工具内部元数据：`toolId`、`description`、参数定义 Map（含 type/required/defaultValue/enumValues） |
| `MCPToolRequest` | 执行器入参：`toolId`、`userId`、`conversationId`、`userQuestion`、`parameters` Map，提供类型安全的 `getParameter()` / `getStringParameter()` |
| `MCPToolResponse` | 执行器出参：`success` 标志、`textResult`（文本结果）、`data`（结构化数据）、`errorCode`/`errorMessage`，提供 `success()` / `error()` 静态工厂 |
| `MCPToolRegistry` | 注册表接口：`register()`、`getExecutor(toolId)`、`listAllTools()`、`listAllExecutors()` |
| `DefaultMCPToolRegistry` | 注册表默认实现（Spring Bean），`@PostConstruct` 阶段自动遍历 Spring 注入的 `List<MCPToolExecutor>` 完成批量注册，内部使用 `ConcurrentHashMap` |

### 3.3 `endpoint` — HTTP 入口与分发

| 类 | 职责 |
|---|---|
| `MCPEndpoint` | `POST /mcp` 控制器，收到请求后转交 `MCPDispatcher`；若响应为 `null`（JSON-RPC Notification）则返回 `204 No Content` |
| `MCPDispatcher` | switch 分发三个 MCP 核心方法，`tools/call` 时从注册表找执行器、构建 `MCPToolRequest`、调用执行器、将结果包装为 MCP 标准 `content` 结构 |

### 3.4 `executor` — 工具执行器实现

三个工具执行器，详见 [mcp-tool-executors.md](./mcp-tool-executors.md)。

---

## 四、请求处理全流程

```
客户端（AI / Claude）
    │
    │  POST /mcp
    │  Body: {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"weather_query","arguments":{"city":"北京"}}}
    ▼
MCPEndpoint.handle(JsonRpcRequest)
    │
    ▼
MCPDispatcher.dispatch(request)
    │
    ├─ method = "initialize"
    │       └─ 返回 protocolVersion="2026-02-28"、capabilities、serverInfo
    │
    ├─ method = "tools/list"
    │       └─ toolRegistry.listAllTools() → 转换为 MCPToolSchema 列表 → 返回 {"tools":[...]}
    │
    └─ method = "tools/call"
            │
            ├─ 校验 params.name 非空，否则返回 INVALID_PARAMS 错误
            ├─ toolRegistry.getExecutor(name)，未找到返回 METHOD_NOT_FOUND 错误
            ├─ 解析 params.arguments → Map<String, Object>
            ├─ 构建 MCPToolRequest（toolId + parameters）
            ├─ executor.execute(request) → MCPToolResponse
            └─ 包装返回：{"content":[{"type":"text","text":"..."}],"isError":false}
                        （异常时 isError=true，text 为异常消息）
```

---

## 五、关键设计点

### 5.1 执行器自动注册

`DefaultMCPToolRegistry` 利用 Spring 的集合注入：

```java
// Spring 自动收集所有 MCPToolExecutor 类型的 Bean 注入为列表
private final List<MCPToolExecutor> autoDiscoveredExecutors;

@PostConstruct
public void init() {
    for (MCPToolExecutor executor : autoDiscoveredExecutors) {
        register(executor);  // toolId → executor 存入 ConcurrentHashMap
    }
}
```

**新增一个工具只需**：实现 `MCPToolExecutor` 接口，加 `@Component` 注解，无需修改任何注册代码。

### 5.2 JSON-RPC Notification 处理

JSON-RPC 规范中，无 `id` 字段的请求为"通知"，服务端不应返回响应体：

```java
// MCPDispatcher
if (id == null) {
    log.debug("MCP notification received: {}", method);
    return null;  // 返回 null
}

// MCPEndpoint
if (response == null) {
    return ResponseEntity.noContent().build();  // 204 No Content
}
```

### 5.3 工具 Schema 转换

`MCPDispatcher.toSchema()` 将内部的 `MCPToolDefinition` 转换为 MCP 规范要求的 `MCPToolSchema`：
- `toolId` → `name`
- `parameters` 中 `required=true` 的参数名收集到 `required` 列表
- `enumValues` 字段通过 `@SerializedName("enum")` 序列化为 `"enum"` 键

---

## 六、核心类索引

| 类名 | 路径 | 职责 |
|---|---|---|
| `MCPServerApplication` | `mcp/` | Spring Boot 启动入口 |
| `MCPEndpoint` | `mcp/endpoint/` | HTTP POST /mcp 入口 |
| `MCPDispatcher` | `mcp/endpoint/` | JSON-RPC 方法分发 |
| `DefaultMCPToolRegistry` | `mcp/core/` | 工具注册表，启动自动注册 |
| `MCPToolExecutor` | `mcp/core/` | 工具执行器接口 |
| `MCPToolDefinition` | `mcp/core/` | 工具元数据（内部使用） |
| `MCPToolRequest` | `mcp/core/` | 执行器入参 |
| `MCPToolResponse` | `mcp/core/` | 执行器出参 |
| `JsonRpcRequest` | `mcp/protocol/` | JSON-RPC 2.0 请求结构 |
| `JsonRpcResponse` | `mcp/protocol/` | JSON-RPC 2.0 响应结构 |
| `JsonRpcError` | `mcp/protocol/` | JSON-RPC 2.0 错误对象 |
| `MCPToolSchema` | `mcp/protocol/` | tools/list 返回的工具 Schema |
| `WeatherMCPExecutor` | `mcp/executor/` | 工具：城市天气查询 |
| `TicketMCPExecutor` | `mcp/executor/` | 工具：客户技术支持工单查询 |
| `SalesMCPExecutor` | `mcp/executor/` | 工具：软件销售数据查询 |
