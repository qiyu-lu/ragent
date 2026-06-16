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
 * 概览面板的 6 项 KPI 分组，对应前端统计卡片的排列顺序。
 *
 * <p>"全量"类字段（{@code totalUsers}、{@code totalSessions}、{@code totalMessages}）
 * 的 {@code delta} 为当前窗口内的新增量，{@code deltaPct} 为 {@code null}。
 * "窗口"类字段携带与上一个等长窗口的环比数据。</p>
 */
@Data
@Builder
public class DashboardOverviewGroupVO {

    /** 注册用户总数（全量），delta 为当前窗口内新增用户数。 */
    private DashboardOverviewKpiVO totalUsers;

    /** 当前窗口内活跃用户数（发过消息的去重用户），携带环比变化。 */
    private DashboardOverviewKpiVO activeUsers;

    /** 对话总数（全量），delta 为当前窗口内新增会话数。 */
    private DashboardOverviewKpiVO totalSessions;

    /** 当前窗口内新增会话数，携带环比变化。 */
    private DashboardOverviewKpiVO sessions24h;

    /** 消息总数（全量），delta 为当前窗口内新增消息数。 */
    private DashboardOverviewKpiVO totalMessages;

    /** 当前窗口内新增消息数，携带环比变化。 */
    private DashboardOverviewKpiVO messages24h;
}
