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

package com.nageoffer.ai.ragent.research.config;

import com.nageoffer.ai.ragent.research.model.ResearchModelRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "research")
public class ResearchProperties {
    private String modelId = "research-flash";
    private String mainModelId;
    private String workerModelId;
    private String finalizationModelId;
    private int maxConcurrentRuns = 2;
    private int queueCapacity = 32;
    private int maxConcurrentModelCalls = 2;
    private int maxConcurrentWorkers = 2;
    private int maxTotalWorkers = 4;
    private int maxWorkerModelCalls = 6;
    private int workerTimeoutSeconds = 180;
    private int maxModelCalls = 16;
    private int maxToolCalls = 24;
    private int reservedFinalizationModelCalls = 2;
    private int maxDurationSeconds = 300;
    private int modelCallTimeoutSeconds = 60;
    private int toolTimeoutSeconds = 30;
    private int maxInputTokens = 28000;
    private int maxOutputTokens = 4096;

    /** 空角色配置沿用旧单模型设置；注册项错误由工厂明确拒绝。 */
    public String modelId(ResearchModelRole role) {
        String selected = switch (role) {
            case MAIN -> mainModelId;
            case WORKER -> workerModelId;
            case FINALIZATION -> finalizationModelId;
        };
        return selected == null || selected.isBlank() ? modelId : selected;
    }

    public void validate() {
        if (modelId == null || modelId.isBlank() || maxConcurrentRuns < 1 || queueCapacity < 1
                || maxConcurrentModelCalls < 1 || maxConcurrentWorkers < 1 || maxConcurrentWorkers > 2
                || maxTotalWorkers < 1 || maxTotalWorkers > 4 || maxWorkerModelCalls < 1
                || workerTimeoutSeconds < 1 || maxModelCalls < 1 || maxToolCalls < 1
                || reservedFinalizationModelCalls < 0 || reservedFinalizationModelCalls >= maxModelCalls
                || maxDurationSeconds < 1 || modelCallTimeoutSeconds < 1 || toolTimeoutSeconds < 1
                || maxInputTokens < 1024 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("研究模型、并发、预算或超时配置无效");
        }
    }
}
