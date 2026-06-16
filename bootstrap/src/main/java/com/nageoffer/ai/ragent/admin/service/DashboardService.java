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

package com.nageoffer.ai.ragent.admin.service;

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;

/**
 * 管理控制台统计面板服务接口。
 *
 * <p>提供三类聚合统计查询：
 * <ul>
 *   <li>概览（overview）：用户数、会话数、消息数的 KPI 卡片数据</li>
 *   <li>性能（performance）：平均延迟、P95 延迟、成功率、无知识率、慢请求率</li>
 *   <li>趋势（trends）：指定指标在时间轴上的逐小时或逐日分布</li>
 * </ul>
 * 所有方法均接收 {@code window} 参数（如 {@code "24h"}、{@code "7d"}）控制统计时间范围；
 * 不传时各方法有自己的默认值（overview/performance 默认 24h，trends 默认 7d）。</p>
 */
public interface DashboardService {

    /**
     * 加载指定时间窗口内的总体 KPI 概览。
     *
     * @param window 时间窗口字符串（如 {@code "24h"} / {@code "7d"}），为 null 时默认 24h
     * @return 包含 6 项 KPI 和对比窗口元信息的响应 VO
     */
    DashboardOverviewVO loadOverview(String window);

    /**
     * 加载指定时间窗口内的 LLM 调用性能指标。
     *
     * @param window 时间窗口字符串，为 null 时默认 24h
     * @return 包含平均延迟、P95 延迟、成功率等指标的性能 VO
     */
    DashboardPerformanceVO loadPerformance(String window);

    /**
     * 加载指定指标在时间轴上的趋势折线图数据。
     *
     * @param metric      指标名（{@code sessions} / {@code messages} / {@code activeusers} /
     *                    {@code avglatency} / {@code quality}）
     * @param window      时间窗口，为 null 时默认 7d
     * @param granularity 时间粒度（{@code hour} / {@code day}）；为 null 时按 window 自动推断
     *                    （≤48h → hour，否则 → day）
     * @return 包含一条或多条时间序列的趋势 VO
     */
    DashboardTrendsVO loadTrends(String metric, String window, String granularity);
}
