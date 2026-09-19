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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSearchChannelTest {

    @Test
    void documentScopeIsPassedToVectorBackendBeforeRecall() {
        VectorRetrieverService retriever = mock(VectorRetrieverService.class);
        when(retriever.supportsGlobalRetrieval()).thenReturn(true);
        when(retriever.embedAndNormalize("query")).thenReturn(new float[]{1, 0});
        when(retriever.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(List.of(chunk("one", 0.9F)));
        var context = SearchContext.builder().originalQuestion("query").budget(RetrievalBudget.uniform(2))
                .retrievalScope(RetrievalScope.of(List.of("kb-a")))
                .documentIds(List.of("doc-a")).build();
        new VectorSearchChannel(retriever, new SearchChannelProperties(), Runnable::run).search(context);
        ArgumentCaptor<RetrieveRequest> request = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retriever).retrieveByVector(any(float[].class), request.capture());
        assertEquals(List.of("doc-a"), request.getValue().getEffectiveDocumentIds());
        assertEquals(List.of("kb-a"), request.getValue().getEffectiveCollectionNames());
    }

    private static final String QUESTION = "报销发票贴哪张表？";
    private static final float[] QUERY_VECTOR = {0.6F, 0.8F};
    /** 生产配置：每通道召回 20、Rerank 候选池 40、最终 10 条 */
    private static final RetrievalBudget PRODUCTION_BUDGET = new RetrievalBudget(20, 40, 10);

    private VectorRetrieverService retrieverService;
    private SearchChannelProperties properties;

    @BeforeEach
    void setUp() {
        retrieverService = mock(VectorRetrieverService.class);
        when(retrieverService.embedAndNormalize(QUESTION)).thenReturn(QUERY_VECTOR);
        when(retrieverService.supportsGlobalRetrieval()).thenReturn(true);
        // 遵守 topK 的桩：真实后端返回条数受请求深度约束
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> {
                    RetrieveRequest request = invocation.getArgument(1);
                    return roundRobin(request.getEffectiveCollectionNames(), request.getTopK());
                });
        properties = new SearchChannelProperties();
    }

    @Test
    @DisplayName("单次跨作用域内全部库检索，取数深度与其他通道同源")
    void queriesAllScopedCollectionsOnce() {
        // 取数只受 recallBudget 管：候选池上限是 RRF 之后的闸门而非取数目标，
        // 拿它当取数条数会让「调 Rerank 池」顺带改写向量库查询深度，一份配置两个职责
        RetrievalScope scope = RetrievalScope.of(List.of("kb-finance", "kb-hr", "kb-tech"));

        search(scope, PRODUCTION_BUDGET);

        List<RetrieveRequest> requests = captureRequests();
        assertEquals(1, requests.size());
        assertEquals(List.of("kb-finance", "kb-hr", "kb-tech"), requests.get(0).getEffectiveCollectionNames());
        assertEquals(20, requests.get(0).getTopK());
    }

    @Test
    @DisplayName("后端不支持跨库单查时召回额度仍是总量")
    void fanOutFallbackCapsTotalRecallBudget() {
        // 回归：fan-out 兜底下预算是每库上限，不截断则 3 个库把 20 条撑成 60 条；
        // 两个现有后端都支持跨库单查，故这条今天不触发，但接口默认值是 false，新接入后端不覆写就会踩中
        when(retrieverService.supportsGlobalRetrieval()).thenReturn(false);

        List<RetrievedChunk> chunks = search(
                RetrievalScope.of(List.of("kb-finance", "kb-hr", "kb-tech")), PRODUCTION_BUDGET).getChunks();

        assertEquals(20, chunks.size(), "召回额度是总量，不随库数放大");
    }

    @Test
    @DisplayName("作用域为空时不发起任何向量查询")
    void emptyScopeSkipsQuery() {
        assertTrue(search(RetrievalScope.of(List.of()), PRODUCTION_BUDGET).getChunks().isEmpty());
        verify(retrieverService, never()).retrieveByVector(any(float[].class), any(RetrieveRequest.class));
    }

    @Test
    @DisplayName("后端返回乱序时通道出口仍按相关性降序")
    void backendOutOfOrderIsResortedAtChannelExit() {
        // 回归：PG 开了 hnsw.iterative_scan=relaxed_order，pgvector 在该模式下允许轻微乱序且规划器不补 Sort，
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(List.of(chunk("a", 0.5F), chunk("b", 0.9F), chunk("c", 0.7F)));

        List<RetrievedChunk> chunks = search(
                RetrievalScope.of(List.of("kb-finance")), PRODUCTION_BUDGET).getChunks();

        assertEquals(List.of("b", "c", "a"), chunks.stream().map(RetrievedChunk::getId).toList(),
                "下游 RRF 按列表位次取分，出口乱序等于名次基准失真");
    }

    private SearchChannelResult search(RetrievalScope scope, RetrievalBudget budget) {
        SearchContext context = SearchContext.builder()
                .originalQuestion(QUESTION)
                .budget(budget)
                .retrievalScope(scope)
                .build();
        return new VectorSearchChannel(retrieverService, properties, Runnable::run).search(context);
    }

    private List<RetrieveRequest> captureRequests() {
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, atLeastOnce()).retrieveByVector(any(float[].class), captor.capture());
        return captor.getAllValues();
    }

    private static RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }

    /**
     * 按库轮转填满 topK
     * <p>
     * chunk 的 id 只由所属库决定：逐库 fan-out 与单次跨库查询返回同一批 chunk，
     * 「fan-out 下名额被放大」这类缺陷才能在用例里看见
     */
    private static List<RetrievedChunk> roundRobin(List<String> collections, int topK) {
        return IntStream.range(0, topK)
                .mapToObj(index -> {
                    String collection = collections.get(index % collections.size());
                    String id = collection + "-" + index / collections.size();
                    return RetrievedChunk.builder()
                            .id(id)
                            .text(id)
                            .score(0.9F - index * 0.001F)
                            .build();
                })
                .toList();
    }
}
