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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@Component
@RequiredArgsConstructor
public class LlmTaskAgentPlanner implements TaskAgentPlanner {
    private final LLMService llm;
    private final ObjectMapper json;
    private final PromptTemplateLoader prompts;

    @Override
    public Decision next(State state, List<ToolSpec> tools) {
        try {
            String input = json.writeValueAsString(Map.of("task", state, "tools", tools));
            String raw = llm.chat(ChatRequest.builder().messages(List.of(
                            ChatMessage.system(prompts.load("prompt/iron-ore-task-agent.st")), ChatMessage.user(input)))
                    .temperature(0D).thinking(false).maxTokens(1800).build(), Tier.STANDARD);
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("模型返回了空决策");
            String value = raw.trim();
            if (value.startsWith("```") && value.endsWith("```")) {
                value = value.substring(value.indexOf('\n') + 1, value.length() - 3).trim();
            }
            Decision decision = json.readValue(value, Decision.class);
            if (decision == null || decision.tool() == null || decision.arguments() == null || !decision.arguments().isObject()) {
                throw new IllegalArgumentException("决策需要 tool 和 arguments 对象");
            }
            return decision;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("模型输出未符合工具决策 JSON 协议", invalid);
        }
    }
}
