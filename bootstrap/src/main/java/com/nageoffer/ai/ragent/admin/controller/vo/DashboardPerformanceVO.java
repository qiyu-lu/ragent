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
 * {@code GET /admin/dashboard/performance} 接口的响应体。
 *
 * <p>所有比率字段均以百分比形式返回（如 {@code 95.3} 表示 95.3%），保留 1 位小数。
 * 延迟字段单位为毫秒，仅统计 {@code status=SUCCESS} 的 RAG 调用记录。</p>
 */
@Data
@Builder
public class DashboardPerformanceVO {

    /** 本次统计的时间窗口标签，如 {@code "24h"}、{@code "7d"}。 */
    private String window;

    /**
     * 窗口内成功 RAG 调用的平均端到端延迟（毫秒）。
     * 计算方式：{@code SUM(duration_ms) / COUNT}，四舍五入取整。
     */
    private Long avgLatencyMs;

    /**
     * 窗口内成功 RAG 调用的 P95 延迟（毫秒）。
     * 计算方式：对 {@code duration_ms} 升序排列后取第 95% 位置元素。
     */
    private Long p95LatencyMs;

    /**
     * RAG 调用成功率（%），保留 1 位小数。
     * 计算方式：{@code SUCCESS 次数 / (SUCCESS + ERROR) 次数 × 100}。
     */
    private Double successRate;

    /**
     * RAG 调用错误率（%），保留 1 位小数。
     * 计算方式：{@code ERROR 次数 / (SUCCESS + ERROR) 次数 × 100}。
     */
    private Double errorRate;

    /**
     * 无知识文档回复率（%），保留 1 位小数，反映知识库命中率的倒数。
     * 计算方式：{@code 内容="未检索到..."的 ASSISTANT 消息数 / ASSISTANT 消息总数 × 100}。
     */
    private Double noDocRate;

    /**
     * 慢请求率（%），保留 1 位小数。
     * 计算方式：{@code duration_ms > 20000ms 的成功记录数 / 成功记录总数 × 100}。
     */
    private Double slowRate;
}
