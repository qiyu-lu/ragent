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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.ResearchModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResearchModelSelectionTest {
    private final ResearchProperties properties = new ResearchProperties();
    private final AIModelProperties config = new AIModelProperties();
    private final ResearchModelFactory factory;

    ResearchModelSelectionTest() {
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://localhost:12345"); provider.setApiKey("fixture-key");
        provider.setEndpoints(Map.of("chat", "/v1/chat/completions"));
        config.getProviders().put("fixture", provider);
        config.getChat().setCandidates(List.of(candidate("research-flash", "fixture-flash"), candidate("research-max", "fixture-max")));
        factory = new ResearchModelFactory(config, properties);
    }

    private static AIModelProperties.ModelCandidate candidate(String id, String model) {
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id); candidate.setModel(model); candidate.setProvider("fixture"); candidate.setSupportsToolCalling(true);
        return candidate;
    }

    @Test void absentAndBlankRoleSettingsKeepTheLegacySingleModel() {
        properties.setWorkerModelId(" ");
        for (var role : ResearchModelRole.values()) assertEquals("fixture-flash", factory.create(role).getModelName());
        assertEquals(Map.of("main", "fixture-flash", "worker", "fixture-flash", "finalization", "fixture-flash"), factory.modelsByRole());
    }

    @Test void partialRoleOverridesKeepLegacyCallsAndOtherRolesUnchanged() {
        properties.setMainModelId("research-max"); properties.setFinalizationModelId("research-max");
        assertEquals("fixture-max", factory.create(ResearchModelRole.MAIN).getModelName());
        assertEquals("fixture-max", factory.create(ResearchModelRole.FINALIZATION).getModelName());
        assertEquals("fixture-flash", factory.create(ResearchModelRole.WORKER).getModelName());
        assertEquals("fixture-flash", factory.create().getModelName());
    }

    @Test void invalidOrDisabledRoleModelCannotSilentlyUseTheLegacyModel() {
        properties.setMainModelId("unregistered");
        assertThrows(IllegalStateException.class, () -> factory.create(ResearchModelRole.MAIN));
        properties.setMainModelId("research-max");
        config.getChat().getCandidates().get(1).setEnabled(false);
        assertThrows(IllegalStateException.class, () -> factory.create(ResearchModelRole.MAIN));
        config.getChat().getCandidates().get(1).setEnabled(true);
        config.getChat().getCandidates().get(1).setSupportsToolCalling(false);
        assertThrows(IllegalStateException.class, () -> factory.create(ResearchModelRole.MAIN));
    }
}
