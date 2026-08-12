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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskExecutionDO;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreTaskExecutionMapper;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreTaskTemplateMapper;
import com.nageoffer.ai.ragent.ironore.model.CandidateTaskTemplateView;
import com.nageoffer.ai.ragent.ironore.model.TaskEvidenceRef;
import com.nageoffer.ai.ragent.ironore.model.TaskExecutionView;
import com.nageoffer.ai.ragent.ironore.model.TaskExecutionView.TaskSimulationEvent;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplateStatus;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IronOreTaskTemplateService {

    private static final String PROMPT_PATH = "prompt/iron-ore-task-template.st";
    private static final int MAX_EVIDENCE_TEXT_CHARS = 4000;
    private static final int MAX_REPAIR_RAW_CHARS = 6000;

    private final IronOreTaskTemplateMapper taskTemplateMapper;
    private final IronOreTaskExecutionMapper taskExecutionMapper;
    private final ConversationMessageMapper messageMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final TaskTemplateValidator taskTemplateValidator;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CandidateTaskTemplateView createDraft(String sourceMessageId, String docId) {
        String userId = UserContext.requireUser().getUserId();
        IronOreTaskTemplateDO existing = findBySource(sourceMessageId, docId, userId);
        if (existing != null) {
            return toView(existing);
        }

        ConversationMessageDO assistant = requireAssistantMessage(sourceMessageId, userId);
        requireDocumentSource(assistant.getSources(), docId);
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (document == null) {
            throw new ClientException("来源文档不存在");
        }

        List<GroundingChunk> grounding = assistant.getRetrievedChunks() == null
                ? List.of()
                : assistant.getRetrievedChunks().stream()
                .filter(chunk -> chunk != null
                        && docId.equals(chunk.getDocId())
                        && StrUtil.isNotBlank(chunk.getChunkId())
                        && StrUtil.isNotBlank(chunk.getText()))
                .toList();
        if (grounding.isEmpty()) {
            throw new ClientException("该回答没有可用于生成任务的精确检索证据，请重新提问后再生成");
        }

        String question = resolveQuestion(assistant, userId);
        TaskTemplatePayload payload = generatePayload(question, document, grounding);
        List<TaskEvidenceRef> evidenceRefs = grounding.stream().map(this::toEvidenceRef).toList();

        String username = UserContext.getUsername();
        IronOreTaskTemplateDO row = IronOreTaskTemplateDO.builder()
                .conversationId(assistant.getConversationId())
                .sourceMessageId(sourceMessageId)
                .docId(docId)
                .ownerUserId(userId)
                .title(payload.title())
                .procedureName(payload.procedureName())
                .documentVersion(document.getDocumentVersion())
                .status(TaskTemplateStatus.DRAFT.name())
                .templateData(writeJson(payload))
                .evidenceRefs(writeJson(evidenceRefs))
                .createdBy(username)
                .updatedBy(username)
                .build();
        taskTemplateMapper.insert(row);
        return toView(row);
    }

    public List<CandidateTaskTemplateView> listByConversation(String conversationId) {
        String userId = UserContext.requireUser().getUserId();
        return taskTemplateMapper.selectList(new LambdaQueryWrapper<IronOreTaskTemplateDO>()
                        .eq(IronOreTaskTemplateDO::getConversationId, conversationId)
                        .eq(IronOreTaskTemplateDO::getOwnerUserId, userId)
                        .orderByAsc(IronOreTaskTemplateDO::getCreateTime))
                .stream()
                .map(this::toView)
                .toList();
    }

    public List<CandidateTaskTemplateView> listBySourceMessage(String sourceMessageId) {
        String userId = UserContext.requireUser().getUserId();
        requireAssistantMessage(sourceMessageId, userId);
        return taskTemplateMapper.selectList(new LambdaQueryWrapper<IronOreTaskTemplateDO>()
                        .eq(IronOreTaskTemplateDO::getSourceMessageId, sourceMessageId)
                        .eq(IronOreTaskTemplateDO::getOwnerUserId, userId)
                        .orderByAsc(IronOreTaskTemplateDO::getCreateTime))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public CandidateTaskTemplateView approve(String taskId) {
        IronOreTaskTemplateDO task = requireOwnedTask(taskId);
        if (TaskTemplateStatus.DRAFT.name().equals(task.getStatus())) {
            Date now = new Date();
            taskTemplateMapper.update(null, new LambdaUpdateWrapper<IronOreTaskTemplateDO>()
                    .set(IronOreTaskTemplateDO::getStatus, TaskTemplateStatus.APPROVED.name())
                    .set(IronOreTaskTemplateDO::getApprovedBy, UserContext.getUsername())
                    .set(IronOreTaskTemplateDO::getApprovedAt, now)
                    .set(IronOreTaskTemplateDO::getUpdatedBy, UserContext.getUsername())
                    .set(IronOreTaskTemplateDO::getUpdateTime, now)
                    .eq(IronOreTaskTemplateDO::getId, taskId)
                    .eq(IronOreTaskTemplateDO::getOwnerUserId, task.getOwnerUserId())
                    .eq(IronOreTaskTemplateDO::getStatus, TaskTemplateStatus.DRAFT.name()));
        }
        return toView(requireOwnedTask(taskId));
    }

    @Transactional
    public TaskExecutionView simulate(String taskId) {
        IronOreTaskTemplateDO task = requireOwnedTask(taskId);
        IronOreTaskExecutionDO existing = findExecution(taskId);
        if (existing != null) {
            return toExecutionView(existing);
        }
        if (!TaskTemplateStatus.APPROVED.name().equals(task.getStatus())) {
            throw new ClientException("只有已批准的候选任务可以模拟执行");
        }

        TaskTemplatePayload payload = readJson(task.getTemplateData(), TaskTemplatePayload.class);
        Date now = new Date();
        List<TaskSimulationEvent> events = buildSimulationEvents(payload);
        IronOreTaskExecutionDO execution = IronOreTaskExecutionDO.builder()
                .taskTemplateId(taskId)
                .status("SIMULATED_SUCCESS")
                .events(writeJson(events))
                .startTime(now)
                .endTime(now)
                .createdBy(UserContext.getUsername())
                .build();
        taskExecutionMapper.insert(execution);
        taskTemplateMapper.update(null, new LambdaUpdateWrapper<IronOreTaskTemplateDO>()
                .set(IronOreTaskTemplateDO::getStatus, TaskTemplateStatus.SIMULATED.name())
                .set(IronOreTaskTemplateDO::getUpdatedBy, UserContext.getUsername())
                .set(IronOreTaskTemplateDO::getUpdateTime, now)
                .eq(IronOreTaskTemplateDO::getId, taskId)
                .eq(IronOreTaskTemplateDO::getStatus, TaskTemplateStatus.APPROVED.name()));
        return toExecutionView(execution);
    }

    private TaskTemplatePayload generatePayload(String question,
                                                KnowledgeDocumentDO document,
                                                List<GroundingChunk> grounding) {
        Set<String> allowedIds = grounding.stream()
                .map(GroundingChunk::getChunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String systemPrompt = promptTemplateLoader.load(PROMPT_PATH);
        String userPrompt = buildUserPrompt(question, document, grounding);
        String raw = chat(systemPrompt, userPrompt);
        try {
            return parseAndValidate(raw, document.getDocumentVersion(), allowedIds);
        } catch (Exception first) {
            String repairPrompt = userPrompt
                    + "\n\n上一份输出未通过协议校验：" + first.getMessage()
                    + "\n请仅修复 JSON，不得添加新事实。上一份输出：\n"
                    + StrUtil.subPre(StrUtil.nullToEmpty(raw), MAX_REPAIR_RAW_CHARS);
            try {
                return parseAndValidate(chat(systemPrompt, repairPrompt), document.getDocumentVersion(), allowedIds);
            } catch (Exception second) {
                throw new ClientException("候选任务生成结果未通过结构与证据校验：" + second.getMessage());
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
                                   KnowledgeDocumentDO document,
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
                + "<document version=\"" + StrUtil.nullToEmpty(document.getDocumentVersion()) + "\">\n"
                + StrUtil.nullToEmpty(document.getDocName()) + "\n</document>\n"
                + "<evidence>\n" + evidence + "</evidence>";
    }

    private TaskTemplatePayload parseAndValidate(String raw,
                                                 String documentVersion,
                                                 Set<String> allowedIds) throws Exception {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        TaskTemplatePayload payload = objectMapper.readValue(cleaned, TaskTemplatePayload.class);
        return taskTemplateValidator.validate(payload, documentVersion, allowedIds);
    }

    private List<TaskSimulationEvent> buildSimulationEvents(TaskTemplatePayload payload) {
        List<TaskSimulationEvent> events = new ArrayList<>();
        events.add(new TaskSimulationEvent(0, "SIMULATION_STARTED", "开始模拟；不会连接或控制真实设备。", List.of()));
        for (TaskTemplatePayload.TaskStep step : payload.steps()) {
            events.add(new TaskSimulationEvent(step.order(), "STEP_COMPLETED",
                    "步骤 " + step.order() + "：" + step.action(), step.evidenceChunkIds()));
        }
        events.add(new TaskSimulationEvent(payload.steps().size() + 1, "SIMULATION_COMPLETED",
                "候选任务模拟完成；结果仅用于流程演示。", List.of()));
        return List.copyOf(events);
    }

    private ConversationMessageDO requireAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = messageMapper.selectById(messageId);
        if (message == null || !userId.equals(message.getUserId()) || !"assistant".equalsIgnoreCase(message.getRole())) {
            throw new ClientException("来源回答不存在或无权访问");
        }
        return message;
    }

    private void requireDocumentSource(List<SourceRef> sources, String docId) {
        boolean matched = sources != null && sources.stream()
                .anyMatch(source -> source != null && docId.equals(source.getDocId()));
        if (!matched) {
            throw new ClientException("指定文档不是该回答的检索来源");
        }
    }

    private String resolveQuestion(ConversationMessageDO assistant, String userId) {
        if (StrUtil.isBlank(assistant.getReplyToMessageId())) {
            return "根据当前检索证据生成候选任务模板";
        }
        ConversationMessageDO userMessage = messageMapper.selectById(assistant.getReplyToMessageId());
        return userMessage != null && userId.equals(userMessage.getUserId())
                ? StrUtil.nullToEmpty(userMessage.getContent())
                : "根据当前检索证据生成候选任务模板";
    }

    private IronOreTaskTemplateDO findBySource(String messageId, String docId, String userId) {
        return taskTemplateMapper.selectOne(new LambdaQueryWrapper<IronOreTaskTemplateDO>()
                .eq(IronOreTaskTemplateDO::getSourceMessageId, messageId)
                .eq(IronOreTaskTemplateDO::getDocId, docId)
                .eq(IronOreTaskTemplateDO::getOwnerUserId, userId)
                .last("LIMIT 1"));
    }

    private IronOreTaskTemplateDO requireOwnedTask(String taskId) {
        IronOreTaskTemplateDO task = taskTemplateMapper.selectById(taskId);
        String userId = UserContext.requireUser().getUserId();
        if (task == null || !userId.equals(task.getOwnerUserId())) {
            throw new ClientException("候选任务不存在或无权访问");
        }
        return task;
    }

    private IronOreTaskExecutionDO findExecution(String taskId) {
        return taskExecutionMapper.selectOne(new LambdaQueryWrapper<IronOreTaskExecutionDO>()
                .eq(IronOreTaskExecutionDO::getTaskTemplateId, taskId)
                .last("LIMIT 1"));
    }

    private TaskEvidenceRef toEvidenceRef(GroundingChunk chunk) {
        return new TaskEvidenceRef(
                chunk.getChunkId(),
                chunk.getDocId(),
                chunk.getDocName(),
                chunk.getDocumentVersion(),
                chunk.getSheetName(),
                chunk.getCellRange(),
                StrUtil.maxLength(StrUtil.trim(chunk.getText()), 180));
    }

    private CandidateTaskTemplateView toView(IronOreTaskTemplateDO row) {
        List<TaskEvidenceRef> evidence = readJson(row.getEvidenceRefs(), new TypeReference<>() {
        });
        IronOreTaskExecutionDO execution = findExecution(row.getId());
        return new CandidateTaskTemplateView(
                row.getId(),
                row.getConversationId(),
                row.getSourceMessageId(),
                row.getDocId(),
                row.getStatus(),
                readJson(row.getTemplateData(), TaskTemplatePayload.class),
                evidence,
                execution == null ? null : toExecutionView(execution),
                row.getApprovedBy(),
                row.getApprovedAt(),
                row.getCreateTime());
    }

    private TaskExecutionView toExecutionView(IronOreTaskExecutionDO row) {
        List<TaskSimulationEvent> events = readJson(row.getEvents(), new TypeReference<>() {
        });
        return new TaskExecutionView(
                row.getId(), row.getTaskTemplateId(), row.getStatus(), events, row.getStartTime(), row.getEndTime());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("铁矿 Demo 数据序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("铁矿 Demo 数据解析失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("铁矿 Demo 数据解析失败", e);
        }
    }
}
