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

package com.nageoffer.ai.ragent.ironore.model;

import java.util.List;

/**
 * 有来源约束的候选任务模板。所有事实条目必须引用本次检索真实存在的 evidence chunk。
 */
public record TaskTemplatePayload(
        String title,
        String procedureName,
        String documentVersion,
        List<EvidenceItem> prerequisites,
        List<TaskStep> steps,
        List<EvidenceItem> qualityCriteria,
        List<EvidenceItem> exceptionHandling,
        List<EvidenceItem> safetyConstraints
) {

    public TaskTemplatePayload {
        prerequisites = immutable(prerequisites);
        steps = immutable(steps);
        qualityCriteria = immutable(qualityCriteria);
        exceptionHandling = immutable(exceptionHandling);
        safetyConstraints = immutable(safetyConstraints);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record EvidenceItem(String text, List<String> evidenceChunkIds) {
        public EvidenceItem {
            evidenceChunkIds = immutable(evidenceChunkIds);
        }
    }

    public record TaskStep(
            Integer order,
            String action,
            List<String> tools,
            List<TaskParameter> parameters,
            List<String> evidenceChunkIds
    ) {
        public TaskStep {
            tools = immutable(tools);
            parameters = immutable(parameters);
            evidenceChunkIds = immutable(evidenceChunkIds);
        }
    }

    public record TaskParameter(String name, String value, String unit) {
    }
}
