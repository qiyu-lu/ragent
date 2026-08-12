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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionDispatchRequest;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload.TaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotMissionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RobotMissionCompiler compiler = new RobotMissionCompiler(objectMapper);

    @Test
    void compilesApprovedTaskIntoDeterministicDryRunWhitelist() throws Exception {
        IronOreTaskTemplateDO task = task();
        RobotMissionDispatchRequest request = new RobotMissionDispatchRequest(
                "SAMPLE_TRANSPORT", "robot-demo-01", "bucket-01", "sampling", "laboratory",
                "home", true, true);

        var first = compiler.compile("mission-1", task, request);
        var second = compiler.compile("mission-1", task, request);

        assertEquals(first.planHash(), second.planHash());
        assertEquals(64, first.planHash().length());
        assertTrue(first.payload().dryRun());
        assertEquals("SAMPLE_TRANSPORT", first.payload().missionType());
        assertEquals(List.of("NAVIGATE_TO_STATION", "TRANSPORT_CONTAINER", "NAVIGATE_TO_STATION"),
                first.payload().steps().stream().map(each -> each.skillId()).toList());
        assertEquals(first.planHash(), first.payload().planHash());
    }

    @Test
    void rejectsRealExecutionAndUnknownMissionType() throws Exception {
        RobotMissionDispatchRequest real = new RobotMissionDispatchRequest(
                "SAMPLE_TRANSPORT", null, null, null, null, null, null, false);
        RobotMissionDispatchRequest unknown = new RobotMissionDispatchRequest(
                "FREE_TEXT_COMMAND", null, null, null, null, null, null, true);

        assertThrows(ClientException.class, () -> compiler.compile("mission-1", task(), real));
        assertThrows(ClientException.class, () -> compiler.compile("mission-1", task(), unknown));
    }

    private IronOreTaskTemplateDO task() throws Exception {
        TaskTemplatePayload payload = new TaskTemplatePayload(
                "浓度检测任务", "浓度检测", "V1.2", List.of(),
                List.of(new TaskStep(1, "接收样品", List.of(), List.of(), List.of("chunk-1"))),
                List.of(), List.of(), List.of());
        return IronOreTaskTemplateDO.builder()
                .id("task-1")
                .documentVersion("V1.2")
                .templateData(objectMapper.writeValueAsString(payload))
                .build();
    }
}
