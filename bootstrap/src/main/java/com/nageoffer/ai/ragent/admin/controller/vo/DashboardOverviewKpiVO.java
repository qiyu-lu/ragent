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

/**
 * 单项 KPI 指标数据，包含绝对值、环比变化量和变化百分比。
 *
 * <p>前端使用此结构渲染带"↑/↓ X%"角标的数值卡片。
 * {@code deltaPct} 为 {@code null} 时表示无对比窗口（如"全量总数"类指标）。</p>
 */
@Data
@Builder
public class DashboardOverviewKpiVO {

    /** 当前窗口内的指标绝对值（或全量累计值）。 */
    private Long value;

    /** 与上一个对比窗口相比的变化量（current - prev），可为负数。 */
    private Long delta;

    /** 与上一个对比窗口相比的变化百分比，保留 1 位小数；无法计算时为 {@code null}（如 prev=0）。 */
    private Double deltaPct;
}
