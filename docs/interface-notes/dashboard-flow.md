# 管理控制台 Dashboard 接口流程分析

> 文件路径：
> - Controller：`bootstrap/.../admin/controller/DashboardController.java`
> - Service：`bootstrap/.../admin/service/impl/DashboardServiceImpl.java`
> - VO：`bootstrap/.../admin/controller/vo/Dashboard*.java`

---

## 一、接口总览

| HTTP 方法 | 路径                          | 功能               | 默认 window |
|----------|-----------------------------|--------------------|------------|
| GET      | /admin/dashboard/overview    | KPI 概览（用户/会话/消息总量及环比） | 24h |
| GET      | /admin/dashboard/performance | RAG 性能指标（延迟/成功率/无知识率） | 24h |
| GET      | /admin/dashboard/trends      | 折线图趋势数据（按指标+时间粒度分桶） | 7d  |

**公共请求参数：**
- `window`（可选）：时间范围，格式为数字 + `h`/`d`，如 `24h`、`7d`、`30d`
- `trends` 额外参数：`metric`（必填）、`granularity`（可选）

---

## 二、公共机制：时间窗口解析

三个接口共用同一套 window 解析逻辑：

```
window 字符串 → parseWindow()
    "24h" → Duration.ofHours(24)
    "7d"  → Duration.ofDays(7)
    null  → fallback（overview/performance 默认 24h；trends 默认 7d）

resolveWindowRange(window, fallback)
    now       = Instant.now()
    start     = now - duration
    prevStart = now - 2 * duration
    prevEnd   = start

    当前窗口：[start,     now)      → 用于主要统计
    对比窗口：[prevStart, prevEnd)  → 用于计算环比（仅 overview 使用）
```

---

## 三、GET /admin/dashboard/overview

### 3.1 功能说明

返回 6 项 KPI（总用户数、活跃用户数、总会话数、窗口新增会话数、总消息数、窗口新增消息数），每项含当前值、窗口增量和环比百分比。

### 3.2 处理流程

```
HTTP GET /admin/dashboard/overview?window=24h
  │
  └─ DashboardServiceImpl.loadOverview(window)
       │
       ├─ Step 1 — resolveWindowRange(window, 24h)
       │    当前窗口：[now-24h, now)
       │    对比窗口：[now-48h, now-24h)
       │
       ├─ Step 2 — 10 次 DB 查询（全部为简单 COUNT）
       │    ① userMapper.selectCount()              → 全量注册用户总数
       │    ② countUsers(start, end)                → 窗口内新增用户数
       │    ③ conversationMapper.selectCount()      → 全量对话总数
       │    ④ countConversations(start, end)        → 当前窗口新增会话数
       │    ⑤ countConversations(prevStart, prevEnd)→ 对比窗口会话数（环比分母）
       │    ⑥ messageMapper.selectCount()           → 全量消息总数
       │    ⑦ countMessages(start, end)             → 当前窗口新增消息数
       │    ⑧ countMessages(prevStart, prevEnd)     → 对比窗口消息数（环比分母）
       │    ⑨ countActiveUsers(start, end)          → 当前窗口活跃用户数（COUNT DISTINCT user_id）
       │    ⑩ countActiveUsers(prevStart, prevEnd)  → 对比窗口活跃用户数
       │
       ├─ Step 3 — 组装 KPI
       │    calcPct(current, prev)
       │      → (current - prev) / prev * 100，保留 1 位小数
       │      → prev ≤ 0 时返回 null（全量指标无环比）
       │    buildKpi(value, delta, deltaPct)
       │      → DashboardOverviewKpiVO { value, delta, deltaPct }
       │
       └─ Step 4 — 返回 DashboardOverviewVO
            window       = "24h"
            compareWindow= "prev_24h"
            updatedAt    = System.currentTimeMillis()
            kpis         = DashboardOverviewGroupVO（6 项 KPI）
```

### 3.3 响应结构

```json
{
  "code": "0",
  "data": {
    "window": "24h",
    "compareWindow": "prev_24h",
    "updatedAt": 1718500000000,
    "kpis": {
      "totalUsers":    { "value": 1000, "delta": 5,   "deltaPct": null },
      "activeUsers":   { "value": 42,   "delta": 3,   "deltaPct": 7.7  },
      "totalSessions": { "value": 8000, "delta": 120, "deltaPct": null },
      "sessions24h":   { "value": 120,  "delta": -10, "deltaPct": -7.7 },
      "totalMessages": { "value": 50000,"delta": 800, "deltaPct": null },
      "messages24h":   { "value": 800,  "delta": 50,  "deltaPct": 6.7  }
    }
  }
}
```

> `deltaPct` 为 null 表示该指标为全量累计值，无环比计算（分母为 0 时也返回 null）。

---

## 四、GET /admin/dashboard/performance

### 4.1 功能说明

返回指定时间窗口内的 RAG 请求性能指标：平均延迟、P95 延迟、成功率、错误率、无知识率、慢请求率。

### 4.2 处理流程

```
HTTP GET /admin/dashboard/performance?window=24h
  │
  └─ DashboardServiceImpl.loadPerformance(window)
       │
       ├─ Step 1 — resolveWindowRange(window, 24h)
       │    当前窗口：[now-24h, now)（无需对比窗口）
       │
       ├─ Step 2 — listDurations(start, end)
       │    SELECT duration_ms FROM rag_trace_run
       │    WHERE start_time IN [start, end) AND status='SUCCESS'
       │    过滤 duration_ms ≤ 0 的异常值 → List<Long>
       │
       ├─ Step 3 — 内存计算延迟指标
       │    average(durations)    → SUM/COUNT 四舍五入（空列表返回 0）
       │    percentile(durations) → 升序排列后取 ceil(n*0.95)-1 位置（P95）
       │
       ├─ Step 4 — 统计调用次数（2 次 DB 查询）
       │    countTraceRuns(start, end, "SUCCESS") → successCount
       │    countTraceRuns(start, end, "ERROR")   → errorCount
       │    total = successCount + errorCount
       │    （RUNNING 等其他状态不计入分母）
       │
       ├─ Step 5 — 统计消息数（2 次 DB 查询）
       │    countAssistantMessages(start, end)  → assistantCount（role='assistant'）
       │    countNoDocMessages(start, end)       → noDocCount（content='未检索到与问题相关的文档内容。'）
       │
       ├─ Step 6 — 内存计算慢请求数（无额外 DB 查询）
       │    slowCount = durations 中 > 20000ms 的元素个数
       │
       ├─ Step 7 — 计算各比率（保留 1 位小数）
       │    successRate = total==0 ? 0 : successCount / total * 100
       │    errorRate   = total==0 ? 0 : errorCount   / total * 100
       │    noDocRate   = assistantCount==0 ? 0 : noDocCount / assistantCount * 100
       │    slowRate    = durations.isEmpty() ? 0 : slowCount / durations.size() * 100
       │
       └─ Step 8 — 返回 DashboardPerformanceVO
```

### 4.3 响应结构

```json
{
  "code": "0",
  "data": {
    "window": "24h",
    "avgLatencyMs": 3420,
    "p95LatencyMs": 8900,
    "successRate": 97.3,
    "errorRate": 2.7,
    "noDocRate": 5.1,
    "slowRate": 1.2
  }
}
```

### 4.4 各指标计算细节

| 指标 | 数据来源 | 计算方式 | 说明 |
|-----|--------|---------|------|
| avgLatencyMs | rag_trace_run（SUCCESS） | SUM/COUNT，四舍五入 | 算术平均延迟（ms） |
| p95LatencyMs | rag_trace_run（SUCCESS） | 升序后取 ceil(n×0.95)-1 位 | 95% 请求的延迟上限 |
| successRate | rag_trace_run | success/total×100 | total 仅含 SUCCESS+ERROR |
| errorRate | rag_trace_run | error/total×100 | 同上 |
| noDocRate | conversation_message | noDocCount/assistantCount×100 | 值越高说明知识库命中率越低 |
| slowRate | rag_trace_run（SUCCESS） | (>20000ms 的数量)/total×100 | 慢请求阈值：20 秒 |

---

## 五、GET /admin/dashboard/trends

### 5.1 功能说明

返回指定指标在时间窗口内按粒度（小时/天）分桶的折线图数据，支持 5 种指标，`quality` 指标返回两条时间序列。

### 5.2 请求参数

| 参数 | 必填 | 可选值 | 默认值 | 说明 |
|-----|------|-------|-------|------|
| metric | 是 | sessions / messages / activeusers / avglatency / quality | — | 指标名，不区分大小写 |
| window | 否 | 24h / 48h / 7d / 30d 等 | 7d | 时间范围 |
| granularity | 否 | hour / day | 自动推断 | ≤48h → hour，否则 → day |

### 5.3 处理流程

```
HTTP GET /admin/dashboard/trends?metric=sessions&window=7d
  │
  └─ DashboardServiceImpl.loadTrends(metric, window, granularity)
       │
       ├─ Step 1 — 参数标准化
       │    normalizedMetric     = metric.trim().toLowerCase()
       │    range                = resolveWindowRange(window, 7d)
       │    resolvedGranularity  = granularity 显式传入时直接用
       │                           未传时：windowDuration ≤ 48h → "hour"，否则 → "day"
       │
       ├─ Step 2 — 按粒度分支确定时间桶边界
       │   ┌── HOUR 分支 ────────────────────────────────────────┐
       │   │  endHour  = now 截断到整点 + 1h                     │
       │   │  startHour= endHour - max(1, windowDuration.hours)  │
       │   │  SQL: to_char(time, 'YYYY-MM-DD HH24:00:00') 分桶  │
       │   └─────────────────────────────────────────────────────┘
       │   ┌── DAY 分支 ─────────────────────────────────────────┐
       │   │  startDay        = range.start 转 LocalDate         │
       │   │  endExclusiveDay = range.end 转 LocalDate + 1d      │
       │   │  SQL: to_char(time, 'YYYY-MM-DD') 分桶              │
       │   └─────────────────────────────────────────────────────┘
       │
       ├─ Step 3 — 按 metric 路由执行 DB 查询
       │    sessions     → countConversationsByHour/Day()           → 1 条序列
       │    messages     → countMessagesByHour/Day()                → 1 条序列
       │    activeusers  → countActiveUsersByHour/Day()             → 1 条序列
       │                   （SQL: COUNT(DISTINCT user_id) GROUP BY 时间桶）
       │    avglatency   → averageLatencyByHour/Day()               → 1 条序列
       │                   （SQL: AVG(duration_ms) WHERE status='SUCCESS'）
       │    quality      → countTraceRunsByHour/Day(SUCCESS+ERROR)
       │                   + countAssistantMessagesByHour/Day()
       │                   + countNoDocMessagesByHour/Day()
       │                   → 按桶计算 errorRate + noDocRate         → 2 条序列
       │
       ├─ Step 4 — buildPoints / buildPointsByHour 补全连续时间点
       │    cursor = startDay（或 startHour）
       │    while cursor < end:
       │        value = DB结果.getOrDefault(cursor, 0)  ← 无数据的桶填 0
       │        points.add({ ts: cursor转毫秒, value })
       │        cursor += 1d（或 1h）
       │    → 保证折线图横轴连续，无断点
       │
       └─ Step 5 — 返回 DashboardTrendsVO
            metric      = 原始参数（原样回显）
            window      = "7d"
            granularity = "day" / "hour"
            series      = 1~2 条 DashboardTrendSeriesVO
```

### 5.4 响应结构

**单序列（metric=sessions，granularity=day）：**

```json
{
  "code": "0",
  "data": {
    "metric": "sessions",
    "window": "7d",
    "granularity": "day",
    "series": [
      {
        "name": "会话数",
        "data": [
          { "ts": 1718208000000, "value": 42.0 },
          { "ts": 1718294400000, "value": 0.0  },
          { "ts": 1718380800000, "value": 55.0 }
        ]
      }
    ]
  }
}
```

**双序列（metric=quality，granularity=day）：**

```json
{
  "data": {
    "metric": "quality",
    "series": [
      { "name": "错误率",   "data": [{ "ts": 1718208000000, "value": 2.3 }] },
      { "name": "无知识率", "data": [{ "ts": 1718208000000, "value": 5.1 }] }
    ]
  }
}
```

### 5.5 各指标数据来源

| metric | DB 表 | 聚合逻辑 | 序列数 |
|--------|------|---------|-------|
| sessions | conversation | COUNT(*) GROUP BY 时间桶 | 1 |
| messages | conversation_message | COUNT(*) GROUP BY 时间桶 | 1 |
| activeusers | conversation_message | COUNT(DISTINCT user_id) GROUP BY 时间桶 | 1 |
| avglatency | rag_trace_run（status=SUCCESS） | AVG(duration_ms) GROUP BY 时间桶 | 1 |
| quality | rag_trace_run + conversation_message | 每桶：errorRate + noDocRate | 2 |

**quality 桶内计算：**

```
errorRate[桶]  = errorCount / (successCount + errorCount) × 100（total=0 时为 0.0）
noDocRate[桶]  = noDocCount / assistantCount × 100（assistantCount=0 时为 0.0）
```

---

## 六、数据库表依赖

| 表名 | 用途 |
|-----|------|
| `user` | overview：注册用户总数、窗口内新增用户数 |
| `conversation` | overview：对话总数、窗口内新增会话数；trends sessions |
| `conversation_message` | overview：消息总数、活跃用户数；performance：无知识率；trends messages/activeusers/quality |
| `rag_trace_run` | performance：延迟分布（P95/avg）、成功/失败次数；trends avglatency/quality |

---

## 七、核心类索引

| 类名 | 路径（admin 包） | 职责 |
|-----|--------------|------|
| DashboardController | controller/ | HTTP 入口，overview / performance / trends 三个方法 |
| DashboardService | service/ | 三个方法的接口定义 |
| DashboardServiceImpl | service/impl/ | 核心实现：窗口解析、DB 查询、指标计算、VO 组装 |
| DashboardOverviewVO | controller/vo/ | overview 响应体（window + kpis） |
| DashboardOverviewGroupVO | controller/vo/ | 6 项 KPI 分组 |
| DashboardOverviewKpiVO | controller/vo/ | 单项 KPI（value + delta + deltaPct） |
| DashboardPerformanceVO | controller/vo/ | performance 响应体（7 项性能指标） |
| DashboardTrendsVO | controller/vo/ | trends 响应体（metric + series 列表） |
| DashboardTrendSeriesVO | controller/vo/ | 单条折线序列（name + data 点列表） |
| DashboardTrendPointVO | controller/vo/ | 折线图单点（ts 毫秒时间戳 + value） |
