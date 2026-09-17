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

package com.nageoffer.ai.ragent.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.service.ResearchRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResearchRunControllerTest {
    private MockMvc mvc;
    private ResearchRunService service;
    private com.nageoffer.ai.ragent.research.service.ResearchEventStreamService streams;
    private final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() {
        service = mock(ResearchRunService.class);
        streams = mock(com.nageoffer.ai.ragent.research.service.ResearchEventStreamService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ResearchRunController(service, streams)).build();
    }

    @Test void createValidatesGoalAndScopeBeforeScheduling() throws Exception {
        mvc.perform(post("/rag/research/runs").contentType("application/json").content(json.writeValueAsString(Map.of(
                "conversationId", "conversation", "clientRequestId", "request", "goal", "compare",
                "outputType", "REPORT", "allowedKbIds", List.of("kb")))))
                .andExpect(status().isOk());
        verify(service).create(new ResearchRunService.CreateRequest("conversation", "request", "compare", ResearchBrief.OutputType.REPORT, null, List.of("kb"), null));
        reset(service);
        mvc.perform(post("/rag/research/runs").contentType("application/json").content("{\"goal\":\"\",\"allowedKbIds\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void readingStateAndEventsDoesNotCreateOrResumeResearch() throws Exception {
        when(service.events("run", 12, 2)).thenReturn(List.of());
        mvc.perform(get("/rag/research/runs/run")).andExpect(status().isOk());
        mvc.perform(get("/rag/research/runs/run/events").param("after", "12").param("limit", "2"))
                .andExpect(status().isOk());
        verify(service).get("run");
        verify(service).events("run", 12, 2);
        verifyNoMoreInteractions(service);
    }

    @Test void inputRequiresRevisionAndNonblankConditionAndCancelHasOwnEndpoint() throws Exception {
        mvc.perform(post("/rag/research/runs/run/input").contentType("application/json").content("{\"revision\":3,\"answer\":\"25 C\"}"))
                .andExpect(status().isOk());
        verify(service).input("run", 3, "25 C");
        reset(service);
        mvc.perform(post("/rag/research/runs/run/input").contentType("application/json").content("{\"revision\":-1,\"answer\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post("/rag/research/runs/run/cancel")).andExpect(status().isOk());
        verify(service).cancel("run");
    }

    @Test void conversationRecoveryAndReadSourcesOnlyReadExistingRunAndRegenerationValidatesRequestId() throws Exception {
        when(service.list("conversation")).thenReturn(List.of());
        when(service.sources("run")).thenReturn(List.of());
        mvc.perform(get("/rag/research/runs").param("conversationId", "conversation")).andExpect(status().isOk());
        mvc.perform(get("/rag/research/runs/run/sources")).andExpect(status().isOk());
        verify(service).list("conversation"); verify(service).sources("run"); verifyNoMoreInteractions(service);
        mvc.perform(post("/rag/research/runs/run/regenerate").contentType("application/json").content("{\"clientRequestId\":\"new-request\"}")).andExpect(status().isOk());
        verify(service).regenerate("run", "new-request");
        reset(service);
        mvc.perform(post("/rag/research/runs/run/regenerate").contentType("application/json").content("{\"clientRequestId\":\"\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void disconnectedEventStreamDoesNotAttemptJsonErrorResponseOrCancelResearch() throws Exception {
        for (Exception failure : List.of(new java.io.IOException("disconnected"),
                new org.springframework.web.context.request.async.AsyncRequestNotUsableException("disconnected"))) {
            doAnswer(invocation -> { throw failure; }).when(streams).subscribe("run", 0);
            mvc.perform(get("/rag/research/runs/run/events").accept("text/event-stream"))
                    .andExpect(status().isOk()).andExpect(content().string(""));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedCreateJsonIsNotMistakenForDisconnectedProgressStream() throws Exception {
        mvc.perform(post("/rag/research/runs").contentType("application/json").content("{invalid"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode.SERVICE_ERROR.code()));
        verifyNoInteractions(service);
    }
}
