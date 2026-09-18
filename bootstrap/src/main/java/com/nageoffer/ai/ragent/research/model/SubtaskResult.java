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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 子任务的压缩返回契约，不包含隐藏推理或完整工具对话。
 */
public record SubtaskResult(String taskId, List<Finding> findings, List<String> gaps,
                            List<String> conflicts, Status status, List<String> executionIssues) {
    public SubtaskResult(String taskId, List<Finding> findings, List<String> gaps, List<String> conflicts, Status status) {
        this(taskId, findings, gaps, conflicts, status, List.of());
    }
    public enum Status { COMPLETED, PARTIAL, FAILED, CANCELLED }

    public record Finding(@JsonProperty(required = true) String statement,
                          @JsonProperty(required = true) List<String> evidenceIds) {
        public Finding {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    public SubtaskResult {
        if (taskId == null || taskId.isBlank() || status == null) {
            throw new IllegalArgumentException("子任务标识和状态不能为空");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        executionIssues = executionIssues == null ? List.of() : List.copyOf(executionIssues);
    }
}
