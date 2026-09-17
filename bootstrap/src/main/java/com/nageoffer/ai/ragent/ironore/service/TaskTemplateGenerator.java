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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Transitional single-document draft generation, independent of storage and execution. */
@Service
@RequiredArgsConstructor
public class TaskTemplateGenerator {

    private static final String PROMPT_PATH = "prompt/iron-ore-task-template.st";
    private static final int MAX_EVIDENCE_TEXT_CHARS = 4000;
    private static final int MAX_REPAIR_RAW_CHARS = 6000;

    private final PromptTemplateLoader promptTemplateLoader;
    private final TaskTemplateValidator validator;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public TaskTemplatePayload generate(String question,
                                        String documentName,
                                        String documentVersion,
                                        List<GroundingChunk> grounding) {
        Set<String> allowedIds = grounding.stream()
                .map(GroundingChunk::getChunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String systemPrompt = promptTemplateLoader.load(PROMPT_PATH);
        String userPrompt = buildUserPrompt(question, documentName, documentVersion, grounding);
        String raw = chat(systemPrompt, userPrompt);
        try {
            return parseAndValidate(raw, documentVersion, allowedIds);
        } catch (Exception first) {
            String repairPrompt = userPrompt
                    + "\n\n上一份输出未通过协议校验：" + first.getMessage()
                    + "\n请仅修复 JSON，不得添加新事实。上一份输出：\n"
                    + StrUtil.subPre(StrUtil.nullToEmpty(raw), MAX_REPAIR_RAW_CHARS);
            try {
                return parseAndValidate(chat(systemPrompt, repairPrompt), documentVersion, allowedIds);
            } catch (Exception second) {
                throw new ClientException("计划草稿生成结果未通过结构与证据校验：" + second.getMessage());
            }
        }
    }

    private String chat(String systemPrompt, String userPrompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)))
                .temperature(0D)
                .topP(0.2D)
                .thinking(false)
                .build();
        return llmService.chat(request, Tier.STANDARD);
    }

    private String buildUserPrompt(String question,
                                   String documentName,
                                   String documentVersion,
                                   List<GroundingChunk> grounding) {
        StringBuilder evidence = new StringBuilder();
        for (GroundingChunk chunk : grounding) {
            evidence.append("<chunk id=\"").append(chunk.getChunkId()).append("\" sheet=\"")
                    .append(StrUtil.nullToEmpty(chunk.getSheetName())).append("\" cells=\"")
                    .append(StrUtil.nullToEmpty(chunk.getCellRange())).append("\">\n")
                    .append(StrUtil.subPre(chunk.getText(), MAX_EVIDENCE_TEXT_CHARS))
                    .append("\n</chunk>\n");
        }
        return "<question>\n" + StrUtil.nullToEmpty(question) + "\n</question>\n"
                + "<document version=\"" + StrUtil.nullToEmpty(documentVersion) + "\">\n"
                + StrUtil.nullToEmpty(documentName) + "\n</document>\n"
                + "<evidence>\n" + evidence + "</evidence>";
    }

    private TaskTemplatePayload parseAndValidate(String raw,
                                                 String documentVersion,
                                                 Set<String> allowedIds) throws Exception {
        TaskTemplatePayload payload = objectMapper.readValue(
                LLMResponseCleaner.stripMarkdownCodeFence(raw), TaskTemplatePayload.class);
        return validator.validate(payload, documentVersion, allowedIds);
    }
}
