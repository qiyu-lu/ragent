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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
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

    private RerankPostProcessor processor(RerankService rerankService) {
        return new RerankPostProcessor(rerankService, mock(RAGConfigProperties.class));
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
