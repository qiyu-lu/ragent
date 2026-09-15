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

import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.MetadataEnrichmentPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridEvalControllerTest {
    @Test
    void reusesCapturedCandidatesAndPreservesEarlierScores() {
        var pooled = mock(PooledEvalController.class);
        var enrichment = mock(MetadataEnrichmentPostProcessor.class);
        var rerank = mock(RerankService.class);
        var request = new PooledEvalController.Request("q", false);
        var chunk = new RetrievalCapture.Chunk("c", "text", .2f, "cs_pool_v1", null, null, "text");
        var stages = List.of(new RetrievalCapture.Stage("q", "channel-VectorSearch", List.of(chunk), 1, null),
                new RetrievalCapture.Stage("q", "channel-KeywordSearch", List.of(chunk), 1, null));
        when(pooled.evaluate(request)).thenReturn(Results.success(new PooledEvalController.Response(
                "q", List.of("q"), false, "", stages, false, 0, 1)));
        when(rerank.rerank(eq("q"), anyList(), eq(10))).thenAnswer(invocation -> {
            List<com.nageoffer.ai.ragent.framework.convention.RetrievedChunk> chunks = invocation.getArgument(1);
            chunks.get(0).setScore(.9f);
            return chunks;
        });
        var result = new HybridEvalController(pooled, enrichment, rerank).compare(request).getData();
        assertEquals(2, result.baselines().size());
        assertEquals(.9f, result.baselines().get(0).chunks().get(0).score());
        assertEquals(.2f, result.hybrid().stages().get(0).chunks().get(0).score());
        verify(pooled).evaluate(request);
        verify(rerank, times(2)).rerank(eq("q"), anyList(), eq(10));
    }

    @Test
    void refusesRewritingForPairedComparison() {
        var pooled = mock(PooledEvalController.class);
        var controller = new HybridEvalController(pooled, mock(MetadataEnrichmentPostProcessor.class), mock(RerankService.class));
        assertThrows(IllegalArgumentException.class, () -> controller.compare(new PooledEvalController.Request("q", true)));
        verifyNoInteractions(pooled);
    }
}
