# MCP Tool Executors — 三个工具执行器详解

> 本文档描述 `mcp-server` 模块中三个内置工具执行器的能力、参数和数据生成逻辑。
> 整体架构参见 [mcp-server-overview.md](./mcp-server-overview.md)。

---

## 一、WeatherMCPExecutor — 城市天气查询

**toolId**：`weather_query`

### 参数定义

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `city` | string | 是 | — | 城市名称，如"北京"、"上海" |
| `queryType` | string（enum） | 否 | `current` | `current`=当前天气，`forecast`=未来预报 |
| `days` | integer | 否 | `3` | 预报天数（仅 forecast 模式有效，最多 7 天） |

**支持城市**（共 20 个）：北京、上海、广州、深圳、杭州、成都、武汉、南京、西安、重庆、长沙、天津、苏州、郑州、青岛、大连、厦门、昆明、哈尔滨、三亚

### 返回内容

**current 模式**：
```
【北京 今日天气】

日期: 2026年06月16日
天气: 晴
当前温度: 28°C
最高温度: 33°C
最低温度: 20°C
相对湿度: 45%
风向: 东南风
风力: 2-3级
空气质量: 良

提示: 今日高温，注意防暑降温。
```

**forecast 模式**：
```
【北京 未来3天天气预报】

📅 今天（06-16）
   天气: 晴 | 温度: 20°C ~ 33°C
   湿度: 45% | 东南风 2-3级

📅 明天（06-17）
   ...

趋势: 未来3天气温逐渐升高，注意防暑。
```

### 数据生成逻辑

```
随机种子 = date.toEpochDay() * 31 + city.hashCode()
    ↓ 同城同日结果确定性稳定，不同日自然变化

城市经纬度 → 季节 → 基础气温
    季节划分：春(3-5月) / 夏(6-8月) / 秋(9-11月) / 冬(12-2月)
    纬度修正：纬度越高气温越低（每度约 0.3~0.8°C）

基础气温 ± 随机浮动 → highTemp / lowTemp / currentTemp
天气类型按季节池随机选取（如夏季多雷阵雨）
湿度：夏季 60~90%，冬季 20~50%，有降水时再 +20%
空气质量：AQI 随机，纬度 >35°（北方城市）基础值 +20
```

---

## 二、TicketMCPExecutor — 客户技术支持工单查询

**toolId**：`ticket_query`

### 参数定义

| 参数名 | 类型 | 必填 | 默认值 | 枚举值 |
|---|---|---|---|---|
| `region` | string | 否 | 全国 | 华东、华南、华北、西南、西北 |
| `status` | string | 否 | 全部 | 待处理、处理中、已解决、已关闭 |
| `priority` | string | 否 | 全部 | 紧急、高、中、低 |
| `product` | string | 否 | 全部 | 企业版、专业版、基础版 |
| `customerName` | string | 否 | — | 客户名称关键字（模糊匹配） |
| `queryType` | string | 否 | `summary` | summary、list、stats |
| `limit` | integer | 否 | `10` | 列表模式最多返回条数 |

### 三种视图

**summary（汇总概览）**：
- 工单总数
- 状态分布（待处理 / 处理中 / 已解决 / 已关闭）
- 解决率（已解决 + 已关闭）/ 总数
- 紧急/高优先级工单数预警
- 按产品分布 / 按地区分布

**list（工单列表）**：
- 按优先级（紧急→高→中→低）+ 创建时间倒序排列
- 每条显示：工单号、标题、客户、产品、地区、优先级、状态、分类、处理人、创建时间

**stats（统计分析）**：
- 问题分类占比（功能异常 / 性能问题 / 安装部署 / 使用咨询 / 数据问题 / 权限问题）
- 各产品解决率（已解决 + 已关闭 / 各产品总数）
- 处理人待处理工单量排名（Top 5）

### 数据生成逻辑

```
覆盖最近 30 个日历日中的工作日（跳过周末）
每天随机 2~6 条工单

工单状态按时间分布（模拟真实生命周期）：
    7 天以前：80% 已关闭，20% 已解决
    3~7 天：30% 已解决，30% 已关闭，25% 处理中，15% 待处理
    0~3 天：35% 待处理，35% 处理中，20% 已解决，10% 已关闭

优先级权重：紧急 5%，高 15%，中 40%，低 40%

日内缓存：key = "tickets_" + LocalDate.now()，同一天多次调用不重新生成
```

**预置数据维度**：
- 地区 × 客户映射（每地区 4 家）：如华东→腾讯科技/阿里巴巴/字节跳动/网易公司
- 地区 × 处理工程师映射（每地区 2 人）
- 问题标题模板池：15 种常见 SaaS 问题描述

---

## 三、SalesMCPExecutor — 软件销售数据查询

**toolId**：`sales_query`

### 参数定义

| 参数名 | 类型 | 必填 | 默认值 | 枚举值 |
|---|---|---|---|---|
| `region` | string | 否 | 全国 | 华东、华南、华北、西南、西北 |
| `period` | string | 否 | `本月` | 本月、上月、本季度、上季度、本年 |
| `product` | string | 否 | 全部 | 企业版、专业版、基础版 |
| `salesPerson` | string | 否 | 全部 | 销售人员姓名（精确匹配） |
| `queryType` | string | 否 | `summary` | summary、ranking、detail、trend |
| `limit` | integer | 否 | `10` | ranking/detail 模式最多返回条数 |

### 四种视图

**summary（汇总）**：
- 总销售额（万元）、成交订单数、平均单价
- 按产品分布（金额 + 占比）
- 按地区分布（金额 + 占比）

**ranking（排名）**：
- 按销售人员聚合销售额，降序排列
- 最多返回 limit 名

**detail（明细）**：
- 按订单金额降序，展示最多 limit 条
- 每条显示：客户名、产品、金额、销售人员、地区、日期

**trend（趋势）**：
- 按自然周（月内第几周）聚合销售额
- 展示各周销售走势

### 数据生成逻辑

```
时间段 → [startDate, endDate]
    本月：月初 ~ 今天
    上月：上月月初 ~ 上月月末
    本季度：季度初 ~ 今天
    上季度：上季度区间
    本年：1月1日 ~ 今天

遍历 [start, end] 范围内工作日，每天随机 3~8 笔订单

产品价格梯度（万元）：
    企业版：50~200 万
    专业版：10~50 万
    基础版：1~10 万

地区 × 销售人员映射（每地区 3 人）：
    华东：张三/李四/王五
    华南：赵六/钱七/孙八
    华北：周九/吴十/郑冬
    西南：陈春/林夏/黄秋
    西北：刘一/杨二/马三

日内缓存：key = period + "_" + LocalDate.now()
```

---

## 四、新增工具的步骤

得益于 Spring 集合注入 + 自动注册机制，新增工具只需三步：

```java
@Component  // 1. 加注解，Spring 自动发现
public class MyMCPExecutor implements MCPToolExecutor {  // 2. 实现接口

    @Override
    public MCPToolDefinition getToolDefinition() {
        // 3a. 声明 toolId、描述、参数
        return MCPToolDefinition.builder()
                .toolId("my_tool")
                .description("工具描述，LLM 据此理解能力并提取参数")
                .parameters(Map.of(
                        "param1", MCPToolDefinition.ParameterDef.builder()
                                .description("参数说明")
                                .type("string")
                                .required(true)
                                .build()
                ))
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        // 3b. 实现业务逻辑
        String param1 = request.getStringParameter("param1");
        return MCPToolResponse.success(getToolId(), "结果文本");
    }
}
```

无需修改 `MCPDispatcher`、`MCPToolRegistry` 或任何配置文件。
