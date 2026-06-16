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
 * 折线图的单个时间点数据。
 *
 * <p>{@code ts} 为该时间桶的起始时刻（毫秒级 Unix 时间戳），
 * 前端以此为 X 轴坐标，{@code value} 为对应的指标值（Y 轴）。
 * 缺失数据的时间桶由服务端补 0，保证横轴连续。</p>
 */
@Data
@Builder
public class DashboardTrendPointVO {

    /** 时间桶起始时刻，毫秒级 Unix 时间戳（如整点小时或当天零点）。 */
    private Long ts;

    /**
     * 该时间桶对应的指标值。
     * 计数类指标（会话数、消息数、活跃用户数）为整数，以 double 存储；
     * 比率/延迟类指标（平均延迟、错误率、无知识率）保留 1 位小数。
     */
    private Double value;
}
