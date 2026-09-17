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
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreTaskTemplateMapper;
import com.nageoffer.ai.ragent.ironore.model.CandidateTaskTemplateView;
import com.nageoffer.ai.ragent.ironore.model.TaskEvidenceRef;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplateStatus;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IronOreTaskTemplateService {

    private final IronOreTaskTemplateMapper taskTemplateMapper;
    private final ConversationMessageMapper messageMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final TaskTemplateGenerator taskTemplateGenerator;
    private final ObjectMapper objectMapper;

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
            throw new ClientException("该回答没有可用于生成计划草稿的精确检索证据，请重新提问后再生成");
        }

        String question = resolveQuestion(assistant, userId);
        TaskTemplatePayload payload = taskTemplateGenerator.generate(
                question, document.getDocName(), document.getDocumentVersion(), grounding);
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
        // One insert is atomic; model calls above do not hold a database transaction.
        try {
            taskTemplateMapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            IronOreTaskTemplateDO concurrent = findBySource(sourceMessageId, docId, userId);
            if (concurrent == null) {
                throw duplicate;
            }
            return toView(concurrent);
        }
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
            return "根据当前检索证据生成计划草稿";
        }
        ConversationMessageDO userMessage = messageMapper.selectById(assistant.getReplyToMessageId());
        return userMessage != null && userId.equals(userMessage.getUserId())
                ? StrUtil.nullToEmpty(userMessage.getContent())
                : "根据当前检索证据生成计划草稿";
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
        return new CandidateTaskTemplateView(
                row.getId(),
                row.getConversationId(),
                row.getSourceMessageId(),
                row.getDocId(),
                draftStatus(row.getStatus()),
                readJson(row.getTemplateData(), TaskTemplatePayload.class),
                evidence,
                row.getApprovedBy(),
                row.getApprovedAt(),
                row.getCreateTime());
    }

    private String draftStatus(String storedStatus) {
        // Older databases may retain simulated rows; only their draft is exposed now.
        return "SIMULATED".equals(storedStatus) ? TaskTemplateStatus.APPROVED.name() : storedStatus;
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
