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

package com.nageoffer.ai.ragent.ironore.service;

import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload.EvidenceItem;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload.TaskStep;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选任务的确定性协议校验。模型可以组织文字，但不能引用本次检索之外的证据。
 */
@Component
public class TaskTemplateValidator {

    public TaskTemplatePayload validate(TaskTemplatePayload payload,
                                        String documentVersion,
                                        Set<String> allowedEvidenceIds) {
        if (payload == null) {
            throw new IllegalArgumentException("任务模板为空");
        }
        requireText(payload.title(), "title");
        requireText(payload.procedureName(), "procedureName");
        if (payload.steps().isEmpty()) {
            throw new IllegalArgumentException("steps 至少包含一项");
        }

        validateItems(payload.prerequisites(), "prerequisites", allowedEvidenceIds);
        validateItems(payload.qualityCriteria(), "qualityCriteria", allowedEvidenceIds);
        validateItems(payload.exceptionHandling(), "exceptionHandling", allowedEvidenceIds);
        validateItems(payload.safetyConstraints(), "safetyConstraints", allowedEvidenceIds);

        for (int i = 0; i < payload.steps().size(); i++) {
            TaskStep step = payload.steps().get(i);
            if (step == null || step.order() == null || step.order() != i + 1) {
                throw new IllegalArgumentException("steps.order 必须从 1 连续递增");
            }
            requireText(step.action(), "steps[" + i + "].action");
            validateEvidence(step.evidenceChunkIds(), "steps[" + i + "]", allowedEvidenceIds);
        }

        return new TaskTemplatePayload(
                payload.title().trim(),
                payload.procedureName().trim(),
                documentVersion,
                payload.prerequisites(),
                payload.steps(),
                payload.qualityCriteria(),
                payload.exceptionHandling(),
                payload.safetyConstraints());
    }

    private void validateItems(List<EvidenceItem> items, String field, Set<String> allowedEvidenceIds) {
        for (int i = 0; i < items.size(); i++) {
            EvidenceItem item = items.get(i);
            if (item == null) {
                throw new IllegalArgumentException(field + "[" + i + "] 为空");
            }
            requireText(item.text(), field + "[" + i + "].text");
            validateEvidence(item.evidenceChunkIds(), field + "[" + i + "]", allowedEvidenceIds);
        }
    }

    private void validateEvidence(List<String> evidenceIds, String field, Set<String> allowedEvidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(field + " 缺少 evidenceChunkIds");
        }
        Set<String> distinct = new LinkedHashSet<>(evidenceIds);
        if (distinct.size() != evidenceIds.size()) {
            throw new IllegalArgumentException(field + " 包含重复 evidenceChunkIds");
        }
        if (!allowedEvidenceIds.containsAll(distinct)) {
            distinct.removeAll(allowedEvidenceIds);
            throw new IllegalArgumentException(field + " 引用了未知证据：" + distinct);
        }
    }

    private static void requireText(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
