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
 * 折线图的单条时间序列。
 *
 * <p>大多数指标只返回一条序列；{@code quality} 指标同时返回两条（错误率 + 无知识率），
 * 前端按 {@code name} 区分图例。</p>
 */
@Data
@Builder
public class DashboardTrendSeriesVO {

    /**
     * 序列显示名称，用于前端图例，如
     * {@code "会话数"}、{@code "消息数"}、{@code "活跃用户"}、
     * {@code "平均响应时间"}、{@code "错误率"}、{@code "无知识率"}。
     */
    private String name;

    /** 该序列的时间点列表，按时间升序排列，缺失的桶已补 0，横轴连续。 */
    private List<DashboardTrendPointVO> data;
}
