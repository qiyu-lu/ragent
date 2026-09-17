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

import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.controller.ResearchRunController;
import com.nageoffer.ai.ragent.research.model.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResearchEventStreamServiceTest {
    @AfterEach void clear() { UserContext.clear(); }
    @Test void reconnectReplaysAfterHighestCursorWithoutStartingOrCancellingExecution() throws Exception {
        var store = mock(ResearchRunStore.class);
        var service = mock(ResearchRunService.class);
        var run = new ResearchRun("run", "conv", "req", new ResearchBrief("goal", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb")),
                ResearchRun.Status.COMPLETED, 2, 1, Map.of(), Map.of("title", "artifact"), Map.of(), null, Instant.now(), Instant.now());
        when(store.get("run", "owner")).thenReturn(run);
        when(store.events("run", "owner", 12, 100)).thenReturn(List.of(new ResearchEvent(13, "main", "ARTIFACT", "ready", Map.of("title", "artifact"), Instant.now())));
        when(store.events("run", "owner", 13, 100)).thenReturn(List.of());
        UserContext.set(LoginUser.builder().userId("owner").build());
        try (var streams = new ResearchEventStreamService(store)) {
            var mvc = MockMvcBuilders.standaloneSetup(new ResearchRunController(service, streams)).build();
            var result = mvc.perform(get("/rag/research/runs/run/events").accept("text/event-stream").param("after", "10").header("Last-Event-ID", "12"))
                    .andExpect(request().asyncStarted()).andReturn();
            result.getAsyncResult(5000);
            var content = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(header().string("X-Accel-Buffering", "no")).andReturn().getResponse().getContentAsString();
            assertTrue(content.contains("id:13")); assertTrue(content.contains("event:artifact")); assertTrue(content.contains("event:snapshot"));
            verify(store).events("run", "owner", 12, 100); verifyNoInteractions(service);
        }
    }
    @Test void publicEventsExcludeToolHistoryAndInternalModelMetadata() {
        var source = new ResearchEvent(1, "main", "TOOL_STARTED", "查阅", Map.of("tool", "read_source", "arguments", Map.of("internal", "value"), "output", "raw history", "model", "fixture"), Instant.now());
        assertEquals(Map.of("tool", "read_source"), ResearchEventStreamService.publicEvent(source).payload());
        var invalid = new ResearchEvent(2, "main", "FINALIZATION_VALIDATION_FAILED", "校验失败",
                Map.of("reason", "EVIDENCE_IDS_REQUIRED", "rawOutput", "invalid private draft", "rawOutputTruncated", true), Instant.now());
        assertEquals(Map.of("reason", "EVIDENCE_IDS_REQUIRED"), ResearchEventStreamService.publicEvent(invalid).payload());
    }
    @Test void invalidCursorOrWrongOwnerCannotOpenStream() {
        var store = mock(ResearchRunStore.class);
        UserContext.set(LoginUser.builder().userId("foreign").build());
        when(store.get("run", "foreign")).thenThrow(new ClientException("无权访问"));
        try (var streams = new ResearchEventStreamService(store)) {
            assertThrows(ClientException.class, () -> streams.subscribe("run", -1));
            assertThrows(ClientException.class, () -> streams.subscribe("run", 0));
            verify(store, times(1)).get(anyString(), anyString());
        }
    }
}
