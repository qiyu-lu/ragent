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
 * {@code GET /admin/dashboard/overview} 接口的响应体。
 *
 * <p>包含当前统计窗口的元信息和 6 项 KPI 指标；
 * 前端根据 {@code window} 和 {@code compareWindow} 显示"与上一 N 小时/天相比"的对比文案。</p>
 */
@Data
@Builder
public class DashboardOverviewVO {

    /** 当前统计窗口标签，如 {@code "24h"}、{@code "7d"}，由请求参数决定。 */
    private String window;

    /** 上一个等长对比窗口标签，格式为 {@code "prev_24h"} 等，供前端展示对比说明。 */
    private String compareWindow;

    /** 数据更新时间戳（毫秒），即本次接口响应时刻的系统时间。 */
    private Long updatedAt;

    /** 6 项 KPI 指标分组（用户 / 会话 / 消息各两项）。 */
    private DashboardOverviewGroupVO kpis;
}
