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

package com.nageoffer.ai.ragent.admin.controller;

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理控制台统计面板控制器。
 *
 * <p>提供三个只读聚合查询接口，均需登录且只应由管理员角色调用（鉴权由上层统一配置保证）：
 * <ul>
 *   <li>{@code GET /admin/dashboard/overview}  — 用户/会话/消息 KPI 概览卡片</li>
 *   <li>{@code GET /admin/dashboard/performance} — LLM 调用性能指标</li>
 *   <li>{@code GET /admin/dashboard/trends}    — 指标随时间的趋势折线图</li>
 * </ul>
 * </p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取指定时间窗口内的 KPI 总览数据。
     *
     * <p>返回用户总数、活跃用户数、对话总数、窗口新增对话、消息总数、窗口新增消息共 6 项指标，
     * 每项指标均携带与上一个等长窗口的环比变化量和变化百分比。</p>
     *
     * @param window 时间窗口，如 {@code "24h"}、{@code "7d"}；缺省时默认 24h
     * @return 包含 6 项 KPI 及窗口元信息的统一响应
     */
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadOverview(window));
    }

    /**
     * 获取指定时间窗口内的 LLM 调用性能指标。
     *
     * <p>包含平均延迟（ms）、P95 延迟（ms）、成功率、错误率、无知识率（未命中文档比例）和慢请求率。</p>
     *
     * @param window 时间窗口；缺省时默认 24h
     * @return 性能指标响应 VO
     */
    @GetMapping("/performance")
    public Result<DashboardPerformanceVO> performance(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadPerformance(window));
    }

    /**
     * 获取指定指标在时间轴上的趋势数据（折线图）。
     *
     * <p>支持的指标：{@code sessions}、{@code messages}、{@code activeusers}、
     * {@code avglatency}、{@code quality}（quality 返回两条折线：错误率 + 无知识率）。</p>
     *
     * @param metric      指标名（不区分大小写）
     * @param window      时间窗口；缺省时默认 7d
     * @param granularity 时间粒度 {@code hour} 或 {@code day}；
     *                    缺省时按 window 自动推断（≤48h → hour，否则 → day）
     * @return 包含一条或多条 {@link DashboardTrendsVO} 时间序列的响应
     */
    @GetMapping("/trends")
    public Result<DashboardTrendsVO> trends(@RequestParam String metric,
                                            @RequestParam(required = false) String window,
                                            @RequestParam(required = false) String granularity) {
        return Results.success(dashboardService.loadTrends(metric, window, granularity));
    }
}
