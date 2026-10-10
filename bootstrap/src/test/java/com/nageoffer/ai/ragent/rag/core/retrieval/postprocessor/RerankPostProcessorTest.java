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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankPostProcessorTest {

    @Test
    void keepsRerankedHeadObjectsAndAppendsFusionTail() {
        RetrievedChunk a = chunk("a", 0.4F);
        RetrievedChunk b = chunk("b", 0.3F);
        RetrievedChunk c = chunk("c", 0.2F);
        RetrievedChunk d = chunk("d", 0.1F);
        RetrievedChunk rerankedB = b.toBuilder().score(0.99F).build();
        RetrievedChunk rerankedA = a.toBuilder().score(0.88F).build();

        RerankService rerankService = mock(RerankService.class);
        when(rerankService.rerank("问题", List.of(a, b, c, d), 2))
                .thenReturn(List.of(rerankedB, rerankedA));

        List<RetrievedChunk> result = processor(rerankService)
                .process(List.of(a, b, c, d), List.of(), context(2));

        assertEquals(List.of("b", "a", "c", "d"), ids(result));
        assertSame(rerankedB, result.get(0), "头部必须保留 Rerank 返回的新分数对象");
        assertSame(rerankedA, result.get(1), "头部必须保留 Rerank 返回的新分数对象");
        assertEquals(0.99F, result.get(0).getScore());
        assertEquals(0.88F, result.get(1).getScore());
    }

    @Test
    void deduplicatesRerankedHeadAndTailByCanonicalChunkKey() {
        RetrievedChunk a = chunk("a", 0.4F);
        RetrievedChunk b = chunk("b", 0.3F);
        RetrievedChunk rerankedA = a.toBuilder().score(0.95F).build();

        RerankService rerankService = mock(RerankService.class);
        when(rerankService.rerank("问题", List.of(a, b), 2))
                .thenReturn(List.of(rerankedA, rerankedA));

        List<RetrievedChunk> result = processor(rerankService)
                .process(List.of(a, b), List.of(), context(2));

        assertEquals(List.of("a", "b"), ids(result));
        assertSame(rerankedA, result.get(0));
    }

    @Test
    void recordsOnlyTheChunksTheModelActuallyScored() {
        RetrievedChunk a = chunk("a", 0.4F);
        RetrievedChunk b = chunk("b", 0.3F);
        RetrievedChunk c = chunk("c", 0.2F);
        RerankService rerankService = mock(RerankService.class);
        // 模型只返回了一条，客户端用输入对象 c 补位：c 没有模型分
        when(rerankService.rerank("问题", List.of(a, b, c), 2)).thenReturn(List.of(b.toBuilder().score(0.9F).build(), c));
        SearchContext context = context(2);

        processor(rerankService).process(List.of(a, b, c), List.of(), context);

        assertEquals(Set.of("b"), RerankPostProcessor.scoredKeys(context));
    }

    @Test
    void noopRerankLeavesNoScoredKeys() {
        RetrievedChunk a = chunk("a", 0.04F);
        RetrievedChunk b = chunk("b", 0.03F);
        RerankService noop = mock(RerankService.class);
        when(noop.rerank("问题", List.of(a, b), 2)).thenReturn(List.of(a, b));
        SearchContext context = context(2);

        processor(noop).process(List.of(a, b), List.of(), context);

        assertTrue(RerankPostProcessor.scoredKeys(context).isEmpty());
    }

    @Test
    void scoresTheWholePoolWhenBlendingIsOn() {
        List<RetrievedChunk> pool = List.of(chunk("a", 0.4F), chunk("b", 0.3F), chunk("c", 0.2F));
        RerankService rerankService = mock(RerankService.class);
        when(rerankService.rerank("问题", pool, 3)).thenReturn(pool.stream().map(x -> x.toBuilder().score(0.5F).build()).toList());
        ScoreBlendProperties blend = new ScoreBlendProperties();
        blend.setEnabled(true);
        SearchContext context = context(2);

        new RerankPostProcessor(rerankService, mock(RAGConfigProperties.class), blend).process(pool, List.of(), context);

        verify(rerankService).rerank("问题", pool, 3);
        assertEquals(Set.of("a", "b", "c"), RerankPostProcessor.scoredKeys(context));
    }

    private RerankPostProcessor processor(RerankService rerankService) {
        return new RerankPostProcessor(rerankService, mock(RAGConfigProperties.class), new ScoreBlendProperties());
    }

    private SearchContext context(int contextTopK) {
        return SearchContext.builder()
                .rewrittenQuestion("问题")
                .budget(new RetrievalBudget(20, 40, contextTopK))
                .build();
    }

    private RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }

    private List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}
