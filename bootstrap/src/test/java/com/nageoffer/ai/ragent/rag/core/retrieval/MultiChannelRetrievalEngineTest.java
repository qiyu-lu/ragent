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
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.CandidatePoolLimitPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

class MultiChannelRetrievalEngineTest {

    @Test
    void documentScopeReachesChannelAndRejectsOtherDocumentsBeforeRerank() {
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", RetrievedChunk.builder()
                        .id("1").collectionName("allowed").docId("other-doc").build()));
        SearchResultPostProcessor rerank = mock(SearchResultPostProcessor.class);
        assertThrows(IllegalStateException.class, () ->
                engine(List.of(vector), List.of(rerank), RetrievalScope.of(List.of()))
                        .retrieveScopedKnowledgeChannels("query", RetrievalBudget.uniform(10),
                                List.of("allowed"), List.of("doc-a")));
        ArgumentCaptor<SearchContext> context = ArgumentCaptor.forClass(SearchContext.class);
        verify(vector).search(context.capture());
        assertEquals(List.of("doc-a"), context.getValue().getDocumentIds());
        verifyNoInteractions(rerank);
    }

    @Test
    void researchScopeIsUsedBeforeRecallWithoutWebSearch() {
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", chunk("1", "body", "allowed", 0.9F)));
        SearchChannel web = channel("web", SearchChannelType.WEB_SEARCH,
                channelResult(SearchChannelType.WEB_SEARCH, "web", chunk("web", "web body", 1F)));
        RetrievalScopeResolver resolver = mock(RetrievalScopeResolver.class);
        var engine = new MultiChannelRetrievalEngine(List.of(vector, web), List.of(),
                resolver, Runnable::run, new SearchChannelProperties());

        var result = engine.retrieveScopedKnowledgeChannels("query", RetrievalBudget.uniform(10),
                List.of("allowed"));
        ArgumentCaptor<SearchContext> context = ArgumentCaptor.forClass(SearchContext.class);
        verify(vector).search(context.capture());
        assertEquals(List.of("allowed"), context.getValue().getRetrievalScope().targetCollections());
        assertEquals(1, result.chunks().size());
        verifyNoInteractions(resolver);
        verify(web, never()).search(any());
    }

    @Test
    void emptyResearchScopeNeverQueriesGlobalCollections() {
        SearchChannel vector = mock(SearchChannel.class);
        assertTrue(engine(List.of(vector), List.of(), RetrievalScope.of(List.of("other")))
                .retrieveScopedKnowledgeChannels("query", RetrievalBudget.uniform(10), List.of()).chunks().isEmpty());
        verifyNoInteractions(vector);
    }

    @Test
    void scopeViolationsAreRejectedBeforeAnyRerank() {
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", chunk("1", "body", "other", 0.9F)));
        SearchResultPostProcessor rerank = mock(SearchResultPostProcessor.class);
        assertThrows(IllegalStateException.class, () ->
                engine(List.of(vector), List.of(rerank), RetrievalScope.of(List.of("other")))
                        .retrieveScopedKnowledgeChannels("query", RetrievalBudget.uniform(10), List.of("allowed")));
        verifyNoInteractions(rerank);
    }

    @Test
    void returnsChunksInFinalPostProcessorOrder() {
        RetrievedChunk vectorChunk = chunk("v1", "A资料", "kb-a", 0.9F);
        RetrievedChunk discarded = chunk("v2", "被淘汰的A资料", "kb-a", 0.8F);
        RetrievedChunk webChunk = chunk("k1", "B资料", "kb-b", 0.7F);

        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                channelResult(SearchChannelType.VECTOR, "vector", vectorChunk, discarded));
        SearchChannel web = channel("web", SearchChannelType.WEB_SEARCH,
                channelResult(SearchChannelType.WEB_SEARCH, "web", webChunk));

        SearchResultPostProcessor finalSelection = mock(SearchResultPostProcessor.class);
        when(finalSelection.getOrder()).thenReturn(1);
        when(finalSelection.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(finalSelection.process(anyList(), anyList(), any(SearchContext.class)))
                .thenReturn(List.of(webChunk, vectorChunk));

        KnowledgeRetrievalResult result = engine(List.of(vector, web), List.of(finalSelection),
                RetrievalScope.of(List.of("kb-a", "kb-b")))
                .retrieveKnowledgeChannels("问题", RetrievalBudget.uniform(10));

        assertEquals(List.of(webChunk, vectorChunk), result.chunks(), "不得改变最终后处理顺序");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void candidateGuardRunsBeforeRerankWhenFusionIsDisabled() {
        List<RetrievedChunk> candidates = List.of(
                chunk("1", "一", 0.9F),
                chunk("2", "二", 0.8F),
                chunk("3", "三", 0.7F),
                chunk("4", "四", 0.6F),
                chunk("5", "五", 0.5F));
        SearchChannel vector = channel("vector", SearchChannelType.VECTOR,
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR)
                        .channelName("vector")
                        .chunks(candidates)
                        .build());
        SearchResultPostProcessor rerank = mock(SearchResultPostProcessor.class);
        when(rerank.getOrder()).thenReturn(10);
        when(rerank.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(rerank.process(anyList(), anyList(), any(SearchContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        engine(List.of(vector), List.of(rerank, new CandidatePoolLimitPostProcessor()),
                RetrievalScope.of(List.of("kb-a")))
                .retrieveKnowledgeChannels("问题", new RetrievalBudget(20, 3, 2));

        ArgumentCaptor<List<RetrievedChunk>> input = ArgumentCaptor.forClass((Class) List.class);
        verify(rerank).process(input.capture(), anyList(), any(SearchContext.class));
        assertEquals(List.of("1", "2", "3"),
                input.getValue().stream().map(RetrievedChunk::getId).toList(),
                "融合关闭时也必须先限制候选池，再调用 Rerank");
    }

    @Test
    void slowChannelTimesOutWithoutBlockingOtherChannels() {
        // 慢通道模拟卡死的后端：超时只丢它自己的结果，不钳制同一子问题里其余通道
        RetrievedChunk fastChunk = chunk("fast", "补充库资料", "kb-supplement", 0.9F);
        SearchChannel fast = channel(
                "vector",
                SearchChannelType.VECTOR,
                SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR)
                        .channelName("vector")
                        .chunks(List.of(fastChunk))
                        .build()
        );
        SearchChannel slow = mock(SearchChannel.class);
        when(slow.getName()).thenReturn("web");
        when(slow.getType()).thenReturn(SearchChannelType.WEB_SEARCH);
        when(slow.isEnabled(any(SearchContext.class))).thenReturn(true);
        // Mockito 对接口 default 方法默认桩为 null，会被引擎的 nonNull 过滤悄悄吞掉——
        // 那样本测试只证明快通道无恙，降级出口本身反而没被测到，必须真调 default 实现
        when(slow.emptyResult(anyLong())).thenCallRealMethod();
        when(slow.search(any(SearchContext.class))).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.WEB_SEARCH)
                    .channelName("web")
                    .chunks(List.of(chunk("slow", "慢通道资料", 0.8F)))
                    .build();
        });

        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().setTimeoutMs(200);
        RetrievalScopeResolver resolver = mock(RetrievalScopeResolver.class);
        when(resolver.resolve()).thenReturn(RetrievalScope.of(List.of("kb-a", "kb-supplement")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            KnowledgeRetrievalResult result = new MultiChannelRetrievalEngine(
                    List.of(fast, slow),
                    List.of(),
                    resolver,
                    pool,
                    properties)
                    .retrieveKnowledgeChannels("问题", RetrievalBudget.uniform(10));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(List.of("fast"), result.chunks().stream().map(RetrievedChunk::getId).toList(),
                    "慢通道超时按空结果降级，快通道证据保留");
            assertTrue(elapsedMs < 800, "慢通道不得钳制整次检索，实际耗时 " + elapsedMs + "ms");
            verify(slow).emptyResult(0L);
        } finally {
            pool.shutdownNow();
        }
    }

    private MultiChannelRetrievalEngine engine(List<SearchChannel> channels,
                                               List<SearchResultPostProcessor> processors,
                                               RetrievalScope scope) {
        RetrievalScopeResolver resolver = mock(RetrievalScopeResolver.class);
        when(resolver.resolve()).thenReturn(scope);
        return new MultiChannelRetrievalEngine(
                channels, processors, resolver, Runnable::run, new SearchChannelProperties());
    }

    private static SearchChannelResult channelResult(SearchChannelType type, String name, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type)
                .channelName(name)
                .chunks(List.of(chunks))
                .build();
    }

    private SearchChannel channel(String name, SearchChannelType type, SearchChannelResult result) {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn(name);
        when(channel.getType()).thenReturn(type);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenReturn(result);
        return channel;
    }

    private RetrievedChunk chunk(String id, String text, float score) {
        return RetrievedChunk.builder().id(id).text(text).score(score).build();
    }

    private RetrievedChunk chunk(String id, String text, String collectionName, float score) {
        return RetrievedChunk.builder().id(id).text(text).collectionName(collectionName).score(score).build();
    }
}
