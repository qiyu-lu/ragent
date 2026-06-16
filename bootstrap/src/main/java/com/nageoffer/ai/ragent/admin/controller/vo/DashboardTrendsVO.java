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

package com.nageoffer.ai.ragent.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * {@code GET /admin/dashboard/trends} 接口的响应体。
 *
 * <p>包含指标元信息（metric / window / granularity）和一条或多条时间序列数据。
 * 前端根据 {@code granularity} 决定 X 轴刻度格式（小时 or 天），
 * 根据 {@code series} 列表渲染单折线或多折线图。</p>
 */
@Data
@Builder
public class DashboardTrendsVO {

    /**
     * 本次查询的指标名（原样回显请求参数），如 {@code "sessions"}、{@code "quality"}。
     * 前端可据此判断图表标题和单位。
     */
    private String metric;

    /** 统计时间窗口标签，如 {@code "7d"}、{@code "24h"}，由请求参数或默认值决定。 */
    private String window;

    /**
     * 实际使用的时间粒度，{@code "hour"} 或 {@code "day"}。
     * 若请求未传 granularity，由服务端根据 window 自动推断后回填此字段。
     */
    private String granularity;

    /**
     * 时间序列列表。
     * 大多数指标只有一条序列；{@code quality} 指标返回两条（错误率 + 无知识率）。
     */
    private List<DashboardTrendSeriesVO> series;
}
