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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineTest {

    @Test
    void singleQuestionFormatsItsSelectedChunks() {
        RetrievedChunk chunkA = chunk("a", "A资料");
        RetrievedChunk chunkB = chunk("b", "B资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(anyString(), any(RetrievalBudget.class)))
                .thenReturn(new KnowledgeRetrievalResult(List.of(chunkA, chunkB)));
        when(contextFormatter.formatKbContext(anyList(), anyInt())).thenReturn("上下文");

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of("问题"));

        assertEquals(List.of(chunkA, chunkB), result.getKbChunks());
        assertEquals("上下文", result.getKbContext());
        verify(contextFormatter).formatKbContext(eq(List.of(chunkA, chunkB)), anyInt());
    }

    @Test
    void failedSubQuestionDegradesToEmptyContext() {
        RetrievedChunk chunk = chunk("b", "B资料");
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(anyString(), any(RetrievalBudget.class)))
                .thenThrow(new IllegalStateException("retrieval unavailable"))
                .thenReturn(new KnowledgeRetrievalResult(List.of(chunk)));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of("问题一", "问题二"));

        assertEquals(List.of(chunk), result.getKbChunks());
    }

    @Test
    void multiQuestionSharesOneRequestLevelContextBudget() {
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        AtomicInteger callIndex = new AtomicInteger();
        when(multiChannel.retrieveKnowledgeChannels(
                anyString(), any(RetrievalBudget.class)))
                .thenAnswer(invocation -> {
                    RetrievalBudget budget = invocation.getArgument(1);
                    int prefix = callIndex.getAndIncrement();
                    List<RetrievedChunk> chunks = new ArrayList<>(budget.contextTopK());
                    for (int i = 0; i < budget.contextTopK(); i++) {
                        chunks.add(chunk(prefix + "-" + i, "资料" + prefix + "-" + i));
                    }
                    return new KnowledgeRetrievalResult(chunks);
                });

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of("问题一", "问题二", "问题三"));

        ArgumentCaptor<RetrievalBudget> budgets = ArgumentCaptor.forClass(RetrievalBudget.class);
        verify(multiChannel, times(3)).retrieveKnowledgeChannels(
                anyString(), budgets.capture());
        assertEquals(List.of(4, 3, 3),
                budgets.getAllValues().stream().map(RetrievalBudget::contextTopK).toList());
        assertEquals(10, result.getKbChunks().size(),
                "拆成三个子问题后，最终证据总数仍不得超过请求级 TopK");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fairRefillKeepsCandidatePoolsAndFillsDuplicateGaps() {
        RetrievedChunk shared = chunk("shared", "共享资料");
        List<RetrievedChunk> first = List.of(
                shared,
                chunk("a1", "A1"),
                chunk("a2", "A2"),
                chunk("a3", "A3"),
                chunk("a4", "A4"),
                chunk("a5", "A5"),
                chunk("a6", "A6"),
                chunk("a7", "A7"));
        List<RetrievedChunk> second = List.of(
                shared,
                chunk("b1", "B1"),
                chunk("b2", "B2"));
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                anyString(), any(RetrievalBudget.class)))
                .thenReturn(
                        new KnowledgeRetrievalResult(first),
                        new KnowledgeRetrievalResult(second));
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.setRequestLevelRefillEnabled(true);

        RetrievalContext result = engine(properties, multiChannel, contextFormatter).retrieve(List.of("问题一", "问题二"));

        ArgumentCaptor<RetrievalBudget> budgets = ArgumentCaptor.forClass(RetrievalBudget.class);
        verify(multiChannel, times(2)).retrieveKnowledgeChannels(
                anyString(), budgets.capture());
        assertEquals(List.of(10, 10),
                budgets.getAllValues().stream().map(RetrievalBudget::contextTopK).toList());
        assertEquals(10, result.getKbChunks().size());
        assertEquals(10, new LinkedHashSet<>(result.getKbChunks().stream().map(RetrievedChunk::getId).toList()).size());
        assertEquals(7, result.getRetrievalDiagnostics().uniqueBeforeRefill());
        assertEquals(3, result.getRetrievalDiagnostics().refillAdded());
        assertEquals(0, result.getRetrievalDiagnostics().unfilledSlots());
        assertEquals(List.of("shared", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "b1", "b2"),
                result.getKbChunks().stream().map(RetrievedChunk::getId).toList(),
                "canonical 顺序应与按子问题分组渲染的 Prompt 顺序一致");

        ArgumentCaptor<List<RetrievedChunk>> formatted = ArgumentCaptor.forClass((Class) List.class);
        verify(contextFormatter, times(2)).formatKbContext(formatted.capture(), anyInt());
        Set<String> formattedIds = formatted.getAllValues().stream()
                .flatMap(List::stream)
                .map(RetrievedChunk::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(result.getKbChunks().stream().map(RetrievedChunk::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)), formattedIds);
    }

    @Test
    void disabledRefillKeepsLegacyPrefixesAndDuplicateGap() {
        RetrievedChunk shared = chunk("shared", "共享资料");
        List<RetrievedChunk> first = List.of(
                shared,
                chunk("a1", "A1"),
                chunk("a2", "A2"),
                chunk("a3", "A3"),
                chunk("a4", "A4"),
                chunk("a5", "A5"));
        List<RetrievedChunk> second = List.of(
                shared,
                chunk("b1", "B1"),
                chunk("b2", "B2"),
                chunk("b3", "B3"),
                chunk("b4", "B4"),
                chunk("b5", "B5"));
        MultiChannelRetrievalEngine multiChannel = mock(MultiChannelRetrievalEngine.class);
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(multiChannel.retrieveKnowledgeChannels(
                anyString(), any(RetrievalBudget.class)))
                .thenReturn(
                        new KnowledgeRetrievalResult(first),
                        new KnowledgeRetrievalResult(second));

        RetrievalContext result = engine(multiChannel, contextFormatter).retrieve(List.of("问题一", "问题二"));

        assertEquals(List.of("shared", "a1", "a2", "a3", "a4", "b1", "b2", "b3", "b4"),
                result.getKbChunks().stream().map(RetrievedChunk::getId).toList());
        assertEquals(9, result.getRetrievalDiagnostics().uniqueBeforeRefill());
        assertEquals(0, result.getRetrievalDiagnostics().refillAdded());
        assertEquals(9, result.getRetrievalDiagnostics().finalUniqueCount());
        assertEquals(1, result.getRetrievalDiagnostics().unfilledSlots());
    }

    private RetrievalEngine engine(MultiChannelRetrievalEngine multiChannel, ContextFormatter contextFormatter) {
        return engine(new SearchChannelProperties(), multiChannel, contextFormatter);
    }

    private RetrievalEngine engine(SearchChannelProperties properties,
                                   MultiChannelRetrievalEngine multiChannel,
                                   ContextFormatter contextFormatter) {
        return new RetrievalEngine(
                properties,
                contextFormatter,
                mock(PromptTemplateLoader.class),
                multiChannel,
                Runnable::run
        );
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).build();
    }
}
