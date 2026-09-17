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

package com.nageoffer.ai.ragent.research.model;

import java.util.List;

/**
 * 服务端确认后的目标与知识库范围。allowedKbIds 不是模型可修改的工具参数。
 */
public record ResearchBrief(String goal, OutputType outputType,
                            List<String> constraints, List<String> allowedKbIds) {
    public enum OutputType { REPORT, PLAN }

    public ResearchBrief {
        if (goal == null || goal.isBlank() || outputType == null) {
            throw new IllegalArgumentException("研究目标和产物类型不能为空");
        }
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        if (allowedKbIds == null || allowedKbIds.isEmpty()
                || allowedKbIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("必须保存明确的知识库范围");
        }
        allowedKbIds = allowedKbIds.stream().distinct().toList();
    }
}
