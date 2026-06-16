/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewGroupVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewKpiVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendSeriesVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理控制台统计面板服务实现。
 *
 * <p>三类接口的实现均基于"时间窗口（window）"概念：
 * <ul>
 *   <li>先通过 {@link #resolveWindowRange} 将 {@code window} 字符串解析为
 *       当前窗口 [start, end) 和上一个等长对比窗口 [prevStart, prevEnd)。</li>
 *   <li>再对四张业务表（user / conversation / conversation_message / rag_trace_run）
 *       执行 MyBatis-Plus 条件查询，拿到各类计数或聚合值。</li>
 *   <li>最终计算环比变化量 / 百分比后组装响应 VO 返回。</li>
 * </ul>
 * 所有查询均为同步 DB 调用，无缓存层。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** rag_trace_run 表中表示调用成功的状态值。 */
    private static final String STATUS_SUCCESS = "SUCCESS";
    /** rag_trace_run 表中表示调用失败的状态值。 */
    private static final String STATUS_ERROR = "ERROR";
    /** conversation_message 表中 ASSISTANT 角色的 role 字段值。 */
    private static final String ROLE_ASSISTANT = "assistant";
    /** 未检索到知识文档时 ASSISTANT 回复的固定文本，用于统计"无知识率"。 */
    private static final String NO_DOC_REPLY = "未检索到与问题相关的文档内容。";
    /** 趋势图按天粒度的标识字符串。 */
    private static final String GRANULARITY_DAY = "day";
    /** 趋势图按小时粒度的标识字符串。 */
    private static final String GRANULARITY_HOUR = "hour";
    /** 判定为"慢请求"的延迟阈值（毫秒），超过此值的成功调用计入慢请求率。 */
    private static final long SLOW_LATENCY_THRESHOLD_MS = 20000L;
    /** 按小时聚合时 SQL {@code to_char} 输出格式，须与此 formatter 保持一致。 */
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用于查询注册用户总数及窗口内新增用户数。 */
    private final UserMapper userMapper;
    /** 用于查询对话总数及窗口内新增会话数。 */
    private final ConversationMapper conversationMapper;
    /** 用于查询消息总数、窗口内新增消息数、活跃用户数及无知识消息数。 */
    private final ConversationMessageMapper messageMapper;
    /** 用于查询 LLM 调用成功/失败次数、延迟分布等性能指标。 */
    private final RagTraceRunMapper traceRunMapper;

    /**
     * 加载概览 KPI。
     *
     * <p>查询步骤（共 8 次 DB 查询，全部为简单 count 或 count(distinct)）：
     * <ol>
     *   <li>全量注册用户数 + 窗口内新增用户数</li>
     *   <li>全量对话数 + 当前窗口新增 + 上一窗口新增（用于环比）</li>
     *   <li>全量消息数 + 当前窗口新增 + 上一窗口新增</li>
     *   <li>当前窗口活跃用户数 + 上一窗口活跃用户数（去重 user_id）</li>
     * </ol>
     * 最终通过 {@link #buildKpi} 和 {@link #calcPct} 封装带环比的 KPI 结构。</p>
     */
    @Override
    public DashboardOverviewVO loadOverview(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));

        long totalUsers = userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class));
        long usersInWindow = countUsers(range.start, range.end);

        long totalSessions = conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class));
        long sessionsInWindow = countConversations(range.start, range.end);
        long sessionsPrevWindow = countConversations(range.prevStart, range.prevEnd);

        long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class));
        long messagesInWindow = countMessages(range.start, range.end);
        long messagesPrevWindow = countMessages(range.prevStart, range.prevEnd);

        long activeUsers = countActiveUsers(range.start, range.end);
        long activeUsersPrev = countActiveUsers(range.prevStart, range.prevEnd);

        DashboardOverviewGroupVO group = DashboardOverviewGroupVO.builder()
                .totalUsers(buildKpi(totalUsers, usersInWindow, null))
                .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrev, calcPct(activeUsers, activeUsersPrev)))
                .totalSessions(buildKpi(totalSessions, sessionsInWindow, null))
                .sessions24h(buildKpi(sessionsInWindow, sessionsInWindow - sessionsPrevWindow, calcPct(sessionsInWindow, sessionsPrevWindow)))
                .totalMessages(buildKpi(totalMessages, messagesInWindow, null))
                .messages24h(buildKpi(messagesInWindow, messagesInWindow - messagesPrevWindow, calcPct(messagesInWindow, messagesPrevWindow)))
                .build();

        return DashboardOverviewVO.builder()
                .window(range.windowLabel)
                .compareWindow(range.compareLabel)
                .updatedAt(System.currentTimeMillis())
                .kpis(group)
                .build();
    }

    /**
     * 加载性能指标。
     *
     * <p>从 {@code rag_trace_run} 表中取当前窗口内所有成功调用的 {@code duration_ms}，
     * 计算平均延迟和 P95 延迟；再统计成功/错误次数、ASSISTANT 消息数、无知识消息数、慢请求数，
     * 最终输出各比率（保留 1 位小数）。</p>
     */
    @Override
    public DashboardPerformanceVO loadPerformance(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));
        List<Long> durations = listDurations(range.start, range.end);
        long avgLatency = average(durations);
        long p95Latency = percentile(durations);

        long success = countTraceRuns(range.start, range.end, STATUS_SUCCESS);
        long error = countTraceRuns(range.start, range.end, STATUS_ERROR);
        long total = success + error;
        long assistantCount = countAssistantMessages(range.start, range.end);
        long noDocCount = countNoDocMessages(range.start, range.end);
        long slowCount = durations.stream().filter(duration -> duration > SLOW_LATENCY_THRESHOLD_MS).count();

        double successRate = total == 0 ? 0.0 : round1((success * 100.0) / total);
        double errorRate = total == 0 ? 0.0 : round1((error * 100.0) / total);
        double noDocRate = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
        double slowRate = durations.isEmpty() ? 0.0 : round1((slowCount * 100.0) / durations.size());

        return DashboardPerformanceVO.builder()
                .window(range.windowLabel)
                .avgLatencyMs(avgLatency)
                .p95LatencyMs(p95Latency)
                .successRate(successRate)
                .errorRate(errorRate)
                .noDocRate(noDocRate)
                .slowRate(slowRate)
                .build();
    }

    /**
     * 加载趋势折线图数据。
     *
     * <p>按粒度（hour / day）分两个分支，各指标通过 SQL {@code GROUP BY to_char(...)} 按时间分桶，
     * 再用 {@link #buildPoints} / {@link #buildPointsByHour} 将稀疏 DB 结果补全为连续时间点序列
     * （缺失的桶填 0），保证前端折线图横轴连续。</p>
     *
     * <p>{@code quality} 指标返回两条序列（错误率 + 无知识率），其余指标各返回一条。</p>
     */
    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        String normalizedMetric = metric == null ? "" : metric.trim().toLowerCase();
        Duration windowDuration = parseWindow(window, Duration.ofDays(7));
        WindowRange range = resolveWindowRange(window, Duration.ofDays(7));
        String resolvedGranularity = resolveTrendGranularity(granularity, windowDuration);
        ZoneId zoneId = ZoneId.systemDefault();
        List<DashboardTrendSeriesVO> series = new ArrayList<>();

        if (GRANULARITY_HOUR.equals(resolvedGranularity)) {
            LocalDateTime endHourExclusive = toLocalDateTime(range.end, zoneId)
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1);
            LocalDateTime startHour = endHourExclusive.minusHours(Math.max(1, windowDuration.toHours()));

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countConversationsByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countMessagesByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countActiveUsersByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDateTime, Double> averages = averageLatencyByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> successMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_SUCCESS);
                Map<LocalDateTime, Long> errorMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_ERROR);
                Map<LocalDateTime, Long> assistantCountMap = countAssistantMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Long> noDocCountMap = countNoDocMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Double> errorRate = new HashMap<>();
                Map<LocalDateTime, Double> noDocRate = new HashMap<>();
                for (LocalDateTime hour = startHour; hour.isBefore(endHourExclusive); hour = hour.plusHours(1)) {
                    long total = successMap.getOrDefault(hour, 0L) + errorMap.getOrDefault(hour, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(hour, 0L);
                    long error = errorMap.getOrDefault(hour, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(hour, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(hour, err);
                    noDocRate.put(hour, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, noDocRate))
                        .build());
            }
        } else {
            LocalDate startDay = toLocalDate(range.start, zoneId);
            LocalDate endExclusiveDay = toLocalDate(range.end, zoneId).plusDays(1);

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countConversationsByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countMessagesByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countActiveUsersByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDate, Double> averages = averageLatencyByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDate, Long> successMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_SUCCESS);
                Map<LocalDate, Long> errorMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_ERROR);
                Map<LocalDate, Long> assistantCountMap = countAssistantMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Long> noDocCountMap = countNoDocMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Double> errorRate = new HashMap<>();
                Map<LocalDate, Double> noDocRate = new HashMap<>();
                for (LocalDate day = startDay; day.isBefore(endExclusiveDay); day = day.plusDays(1)) {
                    long total = successMap.getOrDefault(day, 0L) + errorMap.getOrDefault(day, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(day, 0L);
                    long error = errorMap.getOrDefault(day, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(day, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(day, err);
                    noDocRate.put(day, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, noDocRate))
                        .build());
            }
        }

        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.windowLabel)
                .granularity(resolvedGranularity)
                .series(series)
                .build();
    }

    /** 统计 [start, end) 内新注册的用户数。 */
    private long countUsers(Date start, Date end) {
        return userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                .ge(UserDO::getCreateTime, start)
                .lt(UserDO::getCreateTime, end));
    }

    /** 统计 [start, end) 内新建的对话数。 */
    private long countConversations(Date start, Date end) {
        return conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                .ge(ConversationDO::getCreateTime, start)
                .lt(ConversationDO::getCreateTime, end));
    }

    /** 统计 [start, end) 内产生的消息数（含所有角色）。 */
    private long countMessages(Date start, Date end) {
        return messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class)
                .ge(ConversationMessageDO::getCreateTime, start)
                .lt(ConversationMessageDO::getCreateTime, end));
    }

    /** 统计 [start, end) 内发过消息的去重用户数（通过 {@code COUNT(DISTINCT user_id)} 实现）。 */
    private long countActiveUsers(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct user_id) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    /** 统计 [start, end) 内指定 status 的 RAG 链路追踪记录数；{@code status} 为 null 时不过滤。 */
    private long countTraceRuns(Date start, Date end, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.ge("start_time", start).lt("start_time", end);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return traceRunMapper.selectCount(wrapper);
    }

    /** 统计 [start, end) 内 role=assistant 的消息数，作为计算无知识率的分母。 */
    private long countAssistantMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT);
        return messageMapper.selectCount(wrapper);
    }

    /** 统计 [start, end) 内内容等于 {@code NO_DOC_REPLY} 的 ASSISTANT 消息数（无知识率分子）。 */
    private long countNoDocMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY);
        return messageMapper.selectCount(wrapper);
    }

    /** 查询 [start, end) 内所有成功 RAG 调用的 {@code duration_ms} 列表，用于计算平均延迟和 P95。 */
    private List<Long> listDurations(Date start, Date end) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("duration_ms")
                .ge("start_time", start)
                .lt("start_time", end)
                .eq("status", STATUS_SUCCESS);
        List<Object> results = traceRunMapper.selectObjs(wrapper);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> durations = new ArrayList<>();
        for (Object value : results) {
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    durations.add(duration);
                }
            }
        }
        return durations;
    }

    /** 从 MyBatis-Plus {@code selectMaps} 返回的单行结果中提取 {@code cnt} 字段值。 */
    private long extractCount(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return 0L;
        }
        Object value = maps.get(0).get("cnt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /** 计算环比变化百分比 {@code (current-prev)/prev*100}，保留 1 位小数；prev≤0 时返回 null。 */
    private Double calcPct(long current, long prev) {
        if (prev <= 0) {
            return null;
        }
        return round1(((current - prev) * 100.0) / prev);
    }

    /** 构造单项 KPI VO，封装绝对值、变化量和变化百分比。 */
    private DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    /** 按天聚合统计 [start, endExclusive) 内各天新建对话数，返回 LocalDate → count 映射。 */
    private Map<LocalDate, Long> countConversationsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(conversationMapper.selectMaps(wrapper));
    }

    /** 按天聚合统计 [start, endExclusive) 内各天消息数，返回 LocalDate → count 映射。 */
    private Map<LocalDate, Long> countMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /** 按天聚合统计 [start, endExclusive) 内各天 ASSISTANT 消息数（quality 指标分母）。 */
    private Map<LocalDate, Long> countAssistantMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /** 按天聚合统计 [start, endExclusive) 内各天无知识回复消息数（quality 指标分子）。 */
    private Map<LocalDate, Long> countNoDocMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /** 按天聚合统计 [start, endExclusive) 内各天活跃用户数（COUNT DISTINCT user_id）。 */
    private Map<LocalDate, Long> countActiveUsersByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /** 按天聚合计算 [start, endExclusive) 内各天成功调用的平均延迟（ms），保留 1 位小数。 */
    private Map<LocalDate, Double> averageLatencyByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("d");
        List<Map<String, Object>> maps = traceRunMapper.selectMaps(wrapper);
        Map<LocalDate, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(date, round1(avg));
        }
        return result;
    }

    /** 按天聚合统计 [start, endExclusive) 内各天指定 status 的 trace_run 记录数。 */
    private Map<LocalDate, Long> countTraceRunsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("d");
        return mapLongResults(traceRunMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时新建对话数，返回 LocalDateTime(整点) → count 映射。 */
    private Map<LocalDateTime, Long> countConversationsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(conversationMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时消息数。 */
    private Map<LocalDateTime, Long> countMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时 ASSISTANT 消息数。 */
    private Map<LocalDateTime, Long> countAssistantMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时无知识回复消息数。 */
    private Map<LocalDateTime, Long> countNoDocMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时活跃用户数（COUNT DISTINCT user_id）。 */
    private Map<LocalDateTime, Long> countActiveUsersByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /** 按小时聚合计算 [start, endExclusive) 内各小时成功调用平均延迟（ms）。 */
    private Map<LocalDateTime, Double> averageLatencyByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("h");
        return mapDoubleResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    /** 按小时聚合统计 [start, endExclusive) 内各小时指定 status 的 trace_run 记录数。 */
    private Map<LocalDateTime, Long> countTraceRunsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("h");
        return mapLongResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    /** 将 selectMaps 按天分桶的结果（字段 "d" + "cnt"）转换为 LocalDate → Long 映射。 */
    private Map<LocalDate, Long> mapLongResults(List<Map<String, Object>> maps) {
        Map<LocalDate, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(date, value);
            }
        }
        return result;
    }

    /** 将 selectMaps 按小时分桶的结果（字段 "h" + "cnt"）转换为 LocalDateTime(整点) → Long 映射。 */
    private Map<LocalDateTime, Long> mapLongResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(dateTime, value);
            }
        }
        return result;
    }

    /** 将 selectMaps 按小时分桶的结果（字段 "h" + "avg"）转换为 LocalDateTime(整点) → Double 映射。 */
    private Map<LocalDateTime, Double> mapDoubleResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(dateTime, round1(avg));
        }
        return result;
    }

    /** 将按天的稀疏统计结果补全为连续折线点序列（缺失的天填 0），按天步进遍历 [start, endExclusive)。 */
    private List<DashboardTrendPointVO> buildPoints(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    /** 与 {@link #buildPoints} 相同，但值类型为 Double（用于延迟、比率等小数指标）。 */
    private List<DashboardTrendPointVO> buildPointsDouble(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    /** 将按小时的稀疏统计结果补全为连续折线点序列（缺失的小时填 0），按小时步进遍历。 */
    private List<DashboardTrendPointVO> buildPointsByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    /** 按小时连续点序列，值类型为 Double（用于延迟均值、比率趋势等）。 */
    private List<DashboardTrendPointVO> buildPointsDoubleByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    /** 计算 Long 列表的算术平均值，四舍五入到整数毫秒；空列表返回 0。 */
    private long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return Math.round(sum / (double) values.size());
    }

    /** 计算 Long 列表的 P95 分位值（排序后取第 95% 位置元素）；空列表返回 0。 */
    private long percentile(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** 将 double 四舍五入保留 1 位小数。 */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 将 SQL {@code to_char} 返回的日期字符串（"yyyy-MM-dd"）解析为 LocalDate。 */
    private LocalDate parseLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /** 将 SQL {@code to_char} 返回的小时字符串（"yyyy-MM-dd HH:mm:ss"）解析为 LocalDateTime。 */
    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value), HOUR_FORMATTER);
    }

    /** 将 MyBatis 返回的 Number 或字符串类型计数值安全转为 Long，无法解析时返回 null。 */
    private Long toLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Date toDate(LocalDate date, ZoneId zoneId) {
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }

    private Date toDate(LocalDateTime time, ZoneId zoneId) {
        return Date.from(time.atZone(zoneId).toInstant());
    }

    private LocalDate toLocalDate(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }

    /**
     * 将 window 字符串解析为时间范围对象。
     *
     * <p>以 {@code Instant.now()} 为结束点，向前推 duration 作为当前窗口起点；
     * 再向前推同等 duration 得到上一个对比窗口 [prevStart, prevEnd)，用于计算环比。</p>
     */
    private WindowRange resolveWindowRange(String window, Duration fallback) {
        Duration duration = parseWindow(window, fallback);
        Instant now = Instant.now();
        Instant start = now.minus(duration);
        Instant prevStart = start.minus(duration);
        return new WindowRange(Date.from(start), Date.from(now), Date.from(prevStart), Date.from(start),
                window == null ? formatDuration(fallback) : window, "prev_" + (window == null ? formatDuration(fallback) : window));
    }

    /** 将 "24h"/"7d" 格式字符串解析为 Duration；格式不匹配或为空时返回 fallback。 */
    private Duration parseWindow(String window, Duration fallback) {
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String normalized = window.trim().toLowerCase();
        if (normalized.endsWith("h")) {
            long hours = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toHours());
            return Duration.ofHours(hours);
        }
        if (normalized.endsWith("d")) {
            long days = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toDays());
            return Duration.ofDays(days);
        }
        return fallback;
    }

    /** 自动推断趋势粒度：显式传入 hour/day 时直接采用；否则 ≤48h 用 hour，更长用 day。 */
    private String resolveTrendGranularity(String granularity, Duration windowDuration) {
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toLowerCase();
            if (GRANULARITY_HOUR.equals(normalized) || GRANULARITY_DAY.equals(normalized)) {
                return normalized;
            }
        }
        return windowDuration.toHours() <= 48 ? GRANULARITY_HOUR : GRANULARITY_DAY;
    }

    /** 安全解析长整型字符串，解析失败时返回 fallback。 */
    private long parseNumber(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 将 Duration 格式化为 "Nh" 或 "Nd" 字符串，用于响应 VO 的 window 标签。 */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        return hours + "h";
    }

    private static class WindowRange {
        private final Date start;
        private final Date end;
        private final Date prevStart;
        private final Date prevEnd;
        private final String windowLabel;
        private final String compareLabel;

        WindowRange(Date start, Date end, Date prevStart, Date prevEnd, String windowLabel, String compareLabel) {
            this.start = start;
            this.end = end;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
            this.windowLabel = windowLabel;
            this.compareLabel = compareLabel;
        }
    }
}
