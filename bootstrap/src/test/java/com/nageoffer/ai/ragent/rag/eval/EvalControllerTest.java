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

package com.nageoffer.ai.ragent.rag.eval;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalControllerTest {

    @Test
    void replayUsesFixedSubQuestionsWithoutCallingRewriteModel() {
        Fixture fixture = fixture();
        List<SubQuestionIntent> resolved = List.of(
                new SubQuestionIntent("固定问题一", List.of()),
                new SubQuestionIntent("固定问题二", List.of()));
        when(fixture.intentResolver().resolve(any(RewriteResult.class))).thenReturn(resolved);
        when(fixture.retrievalEngine().retrieve(resolved)).thenReturn(emptyContext());

        EvalResponse response = fixture.controller().replay(new EvalReplayRequest(
                " 原始问题 ", List.of(" 固定问题一 ", "固定问题二"))).getData();

        ArgumentCaptor<RewriteResult> captor = ArgumentCaptor.forClass(RewriteResult.class);
        verify(fixture.intentResolver()).resolve(captor.capture());
        assertEquals("原始问题", captor.getValue().rewrittenQuestion());
        assertEquals(List.of("固定问题一", "固定问题二"), captor.getValue().subQuestions());
        assertEquals(List.of("固定问题一", "固定问题二"), response.getSubIntents());
        verify(fixture.queryRewriteService(), never()).rewriteWithSplit(any(), any());
    }

    @Test
    void replayRejectsBlankDuplicateAndOversizedFixtures() {
        Fixture fixture = fixture();

        assertThrows(ClientException.class,
                () -> fixture.controller().replay(new EvalReplayRequest("", List.of("问题"))));
        assertThrows(ClientException.class,
                () -> fixture.controller().replay(new EvalReplayRequest("问题", List.of(" "))));
        assertThrows(ClientException.class,
                () -> fixture.controller().replay(new EvalReplayRequest("问题", List.of("重复", " 重复 "))));
        assertThrows(ClientException.class,
                () -> fixture.controller().replay(new EvalReplayRequest("问题", java.util.Collections.nCopies(11, "问题"))));
    }

    @Test
    void onlineEndpointStillUsesRewriteService() {
        Fixture fixture = fixture();
        RewriteResult rewritten = new RewriteResult("改写问题", List.of("改写问题"));
        List<SubQuestionIntent> resolved = List.of(new SubQuestionIntent("改写问题", List.of()));
        when(fixture.queryRewriteService().rewriteWithSplit("原始问题", List.of())).thenReturn(rewritten);
        when(fixture.intentResolver().resolve(rewritten)).thenReturn(resolved);
        when(fixture.retrievalEngine().retrieve(resolved)).thenReturn(emptyContext());

        fixture.controller().chat("原始问题");

        verify(fixture.queryRewriteService()).rewriteWithSplit("原始问题", List.of());
    }

    private Fixture fixture() {
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        IntentResolver resolver = mock(IntentResolver.class);
        RetrievalEngine retrieval = mock(RetrievalEngine.class);
        EvalController controller = new EvalController(
                rewrite,
                resolver,
                retrieval,
                mock(KnowledgeChunkMapper.class),
                mock(KnowledgeDocumentMapper.class));
        return new Fixture(controller, rewrite, resolver, retrieval);
    }

    private RetrievalContext emptyContext() {
        return RetrievalContext.builder()
                .kbChunks(List.of())
                .intentChunks(Map.of())
                .build();
    }

    private record Fixture(EvalController controller,
                           QueryRewriteService queryRewriteService,
                           IntentResolver intentResolver,
                           RetrievalEngine retrievalEngine) {
    }
}
