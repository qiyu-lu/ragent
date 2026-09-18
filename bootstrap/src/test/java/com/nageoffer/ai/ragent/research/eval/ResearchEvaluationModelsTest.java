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

package com.nageoffer.ai.ragent.research.eval;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.runtime.ResearchModelFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResearchEvaluationModelsTest {
    private final ResearchProperties properties = new ResearchProperties();
    private final AIModelProperties config = new AIModelProperties();

    ResearchEvaluationModelsTest() {
        config.getChat().setCandidates(List.of(candidate("research-flash", "flash-snapshot"), candidate("research-max", "max-snapshot")));
        properties.setMainModelId("research-max"); properties.setWorkerModelId("research-flash"); properties.setFinalizationModelId("research-max");
    }

    private static AIModelProperties.ModelCandidate candidate(String id, String model) {
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id); candidate.setModel(model); candidate.setSupportsToolCalling(true);
        return candidate;
    }

    private static ResearchRunCommand.Job job(Map<String, String> roles) {
        return new ResearchRunCommand.Job("fixture", List.of(), true, "MIXED", 2, null, "",
                "flash-snapshot", Map.of(), false, false, roles);
    }

    @Test void legacyEvaluationCannotInheritNewOnlineRoleDefaults() {
        ResearchRunCommand.configureEvaluationModels(job(null), config, properties);
        assertEquals(Map.of("main", "flash-snapshot", "worker", "flash-snapshot", "finalization", "flash-snapshot"),
                new ResearchModelFactory(config, properties).modelsByRole());
    }

    @Test void evaluationBindsExactProviderModelsAndFillsUnspecifiedRolesWithLegacyModel() {
        ResearchRunCommand.configureEvaluationModels(job(Map.of("main", "max-snapshot", "finalization", "max-snapshot")), config, properties);
        assertEquals(Map.of("main", "max-snapshot", "worker", "flash-snapshot", "finalization", "max-snapshot"),
                new ResearchModelFactory(config, properties).modelsByRole());
    }

    @Test void unknownRolesUnavailableSnapshotsAndDisabledModelsAreRejectedBeforeMutation() {
        for (var requested : List.of(Map.of("judge", "max-snapshot"), Map.of("main", "research-max"), Map.of("main", ""))) {
            assertThrows(IllegalArgumentException.class, () -> ResearchRunCommand.configureEvaluationModels(job(requested), config, properties));
        }
        config.getChat().getCandidates().get(1).setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> ResearchRunCommand.configureEvaluationModels(job(Map.of("main", "max-snapshot")), config, properties));
        assertEquals("research-max", properties.getMainModelId());
    }
}
