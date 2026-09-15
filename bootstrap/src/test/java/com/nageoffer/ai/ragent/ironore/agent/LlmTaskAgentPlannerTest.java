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

package com.nageoffer.ai.ragent.ironore.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmTaskAgentPlannerTest {
    @Test
    void usesExistingModelServiceAndParsesStructuredToolArguments() {
        LLMService llm = mock(LLMService.class);
        PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
        when(prompts.load(anyString())).thenReturn("system instructions");
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn("```json\n{\"tool\":\"search_procedure\",\"arguments\":{\"query\":\"送检\"},\"message\":\"查询资料\"}\n```");
        State state = new State(); state.setGoal("办理送检");
        var planner = new LlmTaskAgentPlanner(llm, new ObjectMapper(), prompts);
        Decision decision = planner.next(state, List.of(new ToolSpec("search_procedure", "查询规程", "{query:string}")));
        assertEquals("search_procedure", decision.tool());
        assertEquals("送检", decision.arguments().get("query").asText());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(request.capture(), eq(Tier.STANDARD));
        assertEquals(2, request.getValue().getMessages().size());
        assertEquals(1800, request.getValue().getMaxTokens());
    }

    @Test
    void malformedModelOutputCannotBecomeToolExecution() {
        LLMService llm = mock(LLMService.class);
        PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
        when(prompts.load(anyString())).thenReturn("system instructions");
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn("已经帮你预约好了");
        var planner = new LlmTaskAgentPlanner(llm, new ObjectMapper(), prompts);
        assertThrows(IllegalArgumentException.class, () -> planner.next(new State(), List.of()));
    }
}
