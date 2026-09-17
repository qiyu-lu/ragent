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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskTemplateGeneratorTest {

    private final LLMService llm = mock(LLMService.class);
    private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
    private final TaskTemplateGenerator generator = new TaskTemplateGenerator(
            prompts, new TaskTemplateValidator(), llm, new ObjectMapper());
    private final List<GroundingChunk> evidence = List.of(GroundingChunk.builder()
            .chunkId("chunk-1").docId("doc-1").text("准备采样桶")
            .sheetName("准备").cellRange("B2:C3").build());

    @BeforeEach
    void setup() {
        when(prompts.load("prompt/iron-ore-task-template.st")).thenReturn("仅输出有证据的草稿");
    }

    @Test
    void producesValidatedDraftFromSuppliedEvidenceWithoutRepair() {
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn(output("chunk-1"));

        TaskTemplatePayload draft = generator.generate("整理准备步骤", "准备规程", "V1.2", evidence);

        assertEquals("V1.2", draft.documentVersion());
        assertEquals(List.of("chunk-1"), draft.steps().get(0).evidenceChunkIds());
        assertTrue(draft.qualityCriteria().isEmpty());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(request.capture(), eq(Tier.STANDARD));
        String input = request.getValue().getMessages().get(1).getContent();
        assertTrue(input.contains("整理准备步骤"));
        assertTrue(input.contains("B2:C3"));
        assertTrue(input.contains("准备采样桶"));
        assertFalse(request.getValue().getThinking());
    }

    @Test
    void repairsUnknownCitationUsingTheSameEvidenceWhitelist() {
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD)))
                .thenReturn(output("invented"), output("chunk-1"));

        TaskTemplatePayload draft = generator.generate("整理准备步骤", "准备规程", "V1.2", evidence);

        assertEquals(List.of("chunk-1"), draft.steps().get(0).evidenceChunkIds());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm, times(2)).chat(request.capture(), eq(Tier.STANDARD));
        String repair = request.getAllValues().get(1).getMessages().get(1).getContent();
        assertTrue(repair.contains("未知证据"));
        assertTrue(repair.contains("不得添加新事实"));
        assertTrue(repair.contains("<chunk id=\"chunk-1\""));
    }

    @Test
    void repeatedInvalidCitationsFailAfterOneRepair() {
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn(output("invented"));

        ClientException failure = assertThrows(ClientException.class,
                () -> generator.generate("整理准备步骤", "准备规程", "V1.2", evidence));

        assertTrue(failure.getMessage().contains("结构与证据校验"));
        verify(llm, times(2)).chat(any(ChatRequest.class), eq(Tier.STANDARD));
    }

    @Test
    void providerFailureDoesNotTriggerAnOutputRepairCall() {
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD)))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThrows(IllegalStateException.class,
                () -> generator.generate("整理准备步骤", "准备规程", "V1.2", evidence));

        verify(llm).chat(any(ChatRequest.class), eq(Tier.STANDARD));
    }

    private String output(String evidenceId) {
        return """
                {"title":"准备草稿","procedureName":"准备规程","documentVersion":"model-version",
                 "prerequisites":[],"steps":[{"order":1,"action":"准备采样桶","tools":[],
                 "parameters":[],"evidenceChunkIds":["%s"]}],"qualityCriteria":[],
                 "exceptionHandling":[],"safetyConstraints":[]}
                """.formatted(evidenceId);
    }
}
