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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreTaskTemplateMapper;
import com.nageoffer.ai.ragent.ironore.model.CandidateTaskTemplateView;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IronOreTaskTemplateServiceTest {

    private final IronOreTaskTemplateMapper drafts = mock(IronOreTaskTemplateMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final TaskTemplateGenerator generator = mock(TaskTemplateGenerator.class);
    private final ObjectMapper json = new ObjectMapper();
    private final IronOreTaskTemplateService service = new IronOreTaskTemplateService(
            drafts, messages, documents, generator, json);
    private final GroundingChunk evidence = GroundingChunk.builder()
            .chunkId("chunk-1").docId("doc-1").docName("准备规程")
            .documentVersion("V1.2").text("准备采样桶").sheetName("准备").cellRange("B2:C3").build();
    private final TaskTemplatePayload payload = new TaskTemplatePayload(
            "准备草稿", "准备规程", "V1.2", List.of(),
            List.of(new TaskTemplatePayload.TaskStep(1, "准备采样桶", List.of(), List.of(), List.of("chunk-1"))),
            List.of(), List.of(), List.of());

    @BeforeEach
    void setup() {
        UserContext.set(LoginUser.builder().userId("alice").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void savesDraftAndEvidenceFromOnlyTheRequestedDocument() throws Exception {
        prepareSources(List.of(evidence, GroundingChunk.builder()
                .chunkId("other").docId("doc-2").text("其他文档").build()));

        CandidateTaskTemplateView view = service.createDraft("answer-1", "doc-1");

        verify(generator).generate("整理准备步骤", "准备规程", "V1.2", List.of(evidence));
        ArgumentCaptor<IronOreTaskTemplateDO> saved = ArgumentCaptor.forClass(IronOreTaskTemplateDO.class);
        verify(drafts).insert(saved.capture());
        assertEquals("alice", saved.getValue().getOwnerUserId());
        assertEquals("DRAFT", view.status());
        assertEquals("chunk-1", view.evidenceRefs().get(0).chunkId());
        assertEquals("B2:C3", view.evidenceRefs().get(0).cellRange());
        assertFalse(json.readTree(json.writeValueAsString(view)).has("execution"));
    }

    @Test
    void reusesHistoricalSimulatedDraftWithoutReadingExecutionOrCallingModel() throws Exception {
        when(drafts.selectOne(any())).thenReturn(storedDraft("SIMULATED"));

        CandidateTaskTemplateView view = service.createDraft("answer-1", "doc-1");

        assertEquals("APPROVED", view.status());
        assertEquals("draft-1", view.id());
        verifyNoInteractions(messages, documents, generator);
        verify(drafts, never()).insert(any(IronOreTaskTemplateDO.class));
        assertFalse(json.readTree(json.writeValueAsString(view)).has("execution"));
    }

    @Test
    void rejectsAnotherUsersSourceAnswerBeforeGenerating() {
        when(messages.selectById("answer-1")).thenReturn(ConversationMessageDO.builder()
                .userId("bob").role("assistant").build());

        assertThrows(ClientException.class, () -> service.createDraft("answer-1", "doc-1"));

        verifyNoInteractions(documents, generator);
        verify(drafts, never()).insert(any(IronOreTaskTemplateDO.class));
    }

    @Test
    void rejectsSourcesWithoutExactGroundingFromTheRequestedDocument() {
        prepareSources(List.of(GroundingChunk.builder()
                .chunkId("other").docId("doc-2").text("其他文档").build()));

        assertThrows(ClientException.class, () -> service.createDraft("answer-1", "doc-1"));

        verifyNoInteractions(generator);
        verify(drafts, never()).insert(any(IronOreTaskTemplateDO.class));
    }

    @Test
    void failedGenerationDoesNotPersistADraft() {
        prepareSources(List.of(evidence));
        when(generator.generate(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new ClientException("未知证据"));

        assertThrows(ClientException.class, () -> service.createDraft("answer-1", "doc-1"));

        verify(drafts, never()).insert(any(IronOreTaskTemplateDO.class));
    }

    @Test
    void duplicateInsertReturnsTheConcurrentlyPersistedDraft() throws Exception {
        prepareSources(List.of(evidence));
        when(drafts.selectOne(any())).thenReturn(null, storedDraft("DRAFT"));
        when(drafts.insert(any(IronOreTaskTemplateDO.class))).thenThrow(new DuplicateKeyException("source"));

        CandidateTaskTemplateView view = service.createDraft("answer-1", "doc-1");

        assertEquals("draft-1", view.id());
        assertEquals(payload, view.template());
        verify(drafts).insert(any(IronOreTaskTemplateDO.class));
    }

    @Test
    void approvalRejectsAnotherUsersDraft() throws Exception {
        IronOreTaskTemplateDO row = storedDraft("DRAFT");
        row.setOwnerUserId("bob");
        when(drafts.selectById("draft-1")).thenReturn(row);

        assertThrows(ClientException.class, () -> service.approve("draft-1"));

        verifyNoInteractions(generator);
    }

    private void prepareSources(List<GroundingChunk> grounding) {
        when(messages.selectById("answer-1")).thenReturn(ConversationMessageDO.builder()
                .id("answer-1").conversationId("conversation-1").userId("alice").role("assistant")
                .replyToMessageId("question-1").sources(List.of(SourceRef.builder().docId("doc-1").build()))
                .retrievedChunks(grounding).build());
        when(messages.selectById("question-1")).thenReturn(ConversationMessageDO.builder()
                .userId("alice").role("user").content("整理准备步骤").build());
        when(documents.selectById("doc-1")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc-1").docName("准备规程").documentVersion("V1.2").build());
        when(generator.generate(anyString(), anyString(), anyString(), anyList())).thenReturn(payload);
    }

    private IronOreTaskTemplateDO storedDraft(String status) throws Exception {
        return IronOreTaskTemplateDO.builder()
                .id("draft-1").conversationId("conversation-1").sourceMessageId("answer-1")
                .docId("doc-1").ownerUserId("alice").status(status)
                .templateData(json.writeValueAsString(payload)).evidenceRefs("[]").build();
    }
}
