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

package com.nageoffer.ai.ragent.ironore.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ironore.config.RobotGatewayProperties;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload.RobotSkillStep;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotGatewayClientTest {

    @Test
    void dispatchesStructuredMissionAndReadsSnapshot() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"missionId":"mission-1","status":"DISPATCHED","currentStep":0,
                             "totalSteps":1,"currentSkillId":null,"message":"accepted","events":[]}
                            """));
            RobotGatewayProperties properties = properties(server.url("/").toString(), true);
            RobotGatewayClient client = new RobotGatewayClient(new OkHttpClient(), new ObjectMapper(), properties);
            RobotMissionPayload mission = new RobotMissionPayload(
                    "mission-1", "task-1", "V1.2", "SAMPLE_TRANSPORT", "robot-demo-01", true,
                    "dry-run", "0".repeat(64),
                    List.of(new RobotSkillStep(1, "NAVIGATE_TO_STATION",
                            Map.of("station_id", "sampling_area"), 30)));

            RobotGatewaySnapshot snapshot = client.dispatch(mission);

            assertEquals("DISPATCHED", snapshot.status());
            var request = server.takeRequest();
            assertEquals("/missions", request.getPath());
            assertEquals("POST", request.getMethod());
            assertTrue(request.getBody().readUtf8().contains("\"dryRun\":true"));
        }
    }

    @Test
    void refusesDispatchWhenGatewayIsDisabled() {
        RobotGatewayClient client = new RobotGatewayClient(
                new OkHttpClient(), new ObjectMapper(), properties("http://127.0.0.1:1", false));
        RobotMissionPayload mission = new RobotMissionPayload(
                "mission-1", "task-1", "V1.2", "SAMPLE_TRANSPORT", "robot-demo-01", true,
                "dry-run", "0".repeat(64), List.of());

        assertThrows(RobotGatewayException.class, () -> client.dispatch(mission));
    }

    private RobotGatewayProperties properties(String baseUrl, boolean enabled) {
        RobotGatewayProperties properties = new RobotGatewayProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl(baseUrl);
        properties.setTimeoutMs(1000);
        return properties;
    }
}
