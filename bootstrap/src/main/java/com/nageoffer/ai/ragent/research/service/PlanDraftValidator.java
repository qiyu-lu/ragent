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

package com.nageoffer.ai.ragent.research.service;

import com.nageoffer.ai.ragent.research.model.PlanDraft;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PlanDraftValidator {
    public void validate(PlanDraft draft, Set<String> readIds) {
        if (draft == null) throw new IllegalArgumentException("PLAN_REQUIRED");
        requirements(draft.prerequisites(), readIds, "plan.prerequisites");
        requirements(draft.resources(), readIds, "plan.resources");
        requirements(draft.cautions(), readIds, "plan.cautions");
        bounded(draft.steps(), 40, "plan.steps");
        bounded(draft.pendingItems(), 40, "plan.pendingItems");
        strings(draft.pendingItems(), 40);
        for (int stepIndex = 0; stepIndex < draft.steps().size(); stepIndex++) {
            var step = draft.steps().get(stepIndex);
            String path = "plan.steps[" + stepIndex + "]";
            if (step == null || step.order() == null || step.order() != stepIndex + 1) {
                throw new IllegalArgumentException("STEP_ORDER_MUST_BE_CONTIGUOUS at " + path + ".order; expected " + (stepIndex + 1));
            }
            text(step.action());
            references(step.evidenceIds(), readIds, true, path + ".evidenceIds");
            bounded(step.parameters(), 20, path + ".parameters");
            for (int index = 0; index < step.parameters().size(); index++) {
                var parameter = step.parameters().get(index);
                if (parameter == null) throw new IllegalArgumentException("PARAMETER_REQUIRED");
                text(parameter.name());
                if (parameter.unit() != null && parameter.unit().length() > 100) throw new IllegalArgumentException("UNIT_TOO_LONG");
                boolean supplied = parameter.value() != null && !parameter.value().isBlank();
                if (supplied) text(parameter.value());
                references(parameter.evidenceIds(), readIds, supplied, path + ".parameters[" + index + "].evidenceIds");
                if (!supplied && (parameter.value() != null || !parameter.evidenceIds().isEmpty() || parameter.unit() != null)) {
                    throw new IllegalArgumentException("MISSING_PARAMETER_MUST_HAVE_NULL_VALUE_UNIT_AND_NO_CITATION at " + path + ".parameters[" + index + "]");
                }
            }
        }
    }

    private void requirements(List<PlanDraft.Requirement> items, Set<String> ids, String path) {
        bounded(items, 40, path);
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            if (item == null) throw new IllegalArgumentException("REQUIREMENT_REQUIRED");
            text(item.text());
            references(item.evidenceIds(), ids, true, path + "[" + index + "].evidenceIds");
        }
    }

    static void references(List<String> ids, Set<String> allowed, boolean required, String path) {
        bounded(ids, 12, path);
        if (required && ids.isEmpty()) {
            throw new IllegalArgumentException("EVIDENCE_IDS_REQUIRED at " + path);
        }
        List<String> unknown = ids.stream().filter(id -> !allowed.contains(id))
                .map(id -> EvidenceText.preview(id, 128)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("REFERENCE_MUST_BELONG_TO_THIS_RUN_AND_HAVE_READ_PROOF at " + path + "; unknown evidenceIds: " + unknown);
        }
        if (Set.copyOf(ids).size() != ids.size()) throw new IllegalArgumentException("DUPLICATE_REFERENCE");
    }
    static void text(String text) {
        if (text == null || text.isBlank() || text.length() > 12000) throw new IllegalArgumentException("TEXT_REQUIRED_OR_TOO_LONG");
        if (text.matches("(?s).*([\\[【][1-9]\\d*[\\]】]|#cite-\\d+).*")) {
            throw new IllegalArgumentException("CITATION_NUMBERS_ARE_ASSIGNED_BY_SERVER_USE_EVIDENCE_IDS");
        }
    }
    static void strings(List<String> values, int max) { bounded(values, max); values.forEach(PlanDraftValidator::text); }
    static void bounded(List<?> values, int max) { bounded(values, max, "array"); }
    static void bounded(List<?> values, int max, String path) {
        if (values == null || values.size() > max || values.stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException("ARRAY_REQUIRED_OR_TOO_LARGE at " + path);
        }
    }
}
