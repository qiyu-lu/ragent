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

package com.nageoffer.ai.ragent.research.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 模型只能提出目标与缩小文档范围；身份、预算和角色不属于参数。 */
public record ResearchTask(@JsonProperty(required = true) String goal,
                           @JsonProperty(required = true) List<String> dimensions,
                           @JsonProperty(required = true) String expectedOutput, List<String> documentIds) {
    public ResearchTask {
        if (goal == null || goal.isBlank() || goal.length() > 4000
                || expectedOutput == null || expectedOutput.isBlank() || expectedOutput.length() > 2000
                || dimensions == null || dimensions.isEmpty() || dimensions.size() > 8
                || dimensions.stream().anyMatch(d -> d == null || d.isBlank() || d.length() > 500)) {
            throw new IllegalArgumentException("子目标、1—8 个研究维度及返回要求必须明确且长度受限");
        }
        dimensions = List.copyOf(dimensions);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        if (documentIds.size() > 200 || documentIds.stream().anyMatch(d -> d == null || d.isBlank())) {
            throw new IllegalArgumentException("子任务文档范围无效");
        }
        documentIds = documentIds.stream().distinct().toList();
    }
}
