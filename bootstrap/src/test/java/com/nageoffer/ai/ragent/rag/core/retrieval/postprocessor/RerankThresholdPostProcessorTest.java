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
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RerankThresholdProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankThresholdPostProcessorTest {

    private static RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }

    private static SearchContext context(Set<String> scored) {
        SearchContext context = SearchContext.builder().rewrittenQuestion("问题").budget(new RetrievalBudget(20, 40, 10)).build();
        context.getMetadata().put(RerankPostProcessor.SCORED_KEYS, scored);
        return context;
    }

    private static RerankThresholdPostProcessor processor(double minScore, boolean rerankEnabled) {
        RerankThresholdProperties properties = new RerankThresholdProperties();
        properties.setEnabled(true);
        properties.setMinScore(minScore);
        RAGConfigProperties rag = mock(RAGConfigProperties.class);
        when(rag.getRerankEnabled()).thenReturn(rerankEnabled);
        return new RerankThresholdPostProcessor(properties, rag);
    }

    @Test
    void dropsScoredChunksBelowTheThresholdAndTheUnscoredTail() {
        // 头部 a、b、c 有模型分，尾部 d 是融合分（数值大于阈值也不能留）
        List<RetrievedChunk> pool = List.of(chunk("a", 0.91F), chunk("b", 0.35F), chunk("c", 0.12F), chunk("d", 0.5F));

        List<RetrievedChunk> kept = processor(0.2, true).process(pool, List.of(), context(Set.of("a", "b", "c")));

        assertEquals(List.of("a", "b"), kept.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    void returnsNothingWhenEveryScoredChunkIsBelowTheThreshold() {
        List<RetrievedChunk> pool = List.of(chunk("a", 0.15F), chunk("b", 0.05F));
        assertTrue(processor(0.2, true).process(pool, List.of(), context(Set.of("a", "b"))).isEmpty());
    }

    @Test
    void leavesThePoolAloneWhenTheModelDidNotScoreIt() {
        // Rerank 回退成 noop：头部是 RRF 分（约 0.05），按 0.2 过滤会把上下文清空
        List<RetrievedChunk> pool = List.of(chunk("a", 0.05F), chunk("b", 0.04F));
        assertEquals(pool, processor(0.2, true).process(pool, List.of(), context(Set.of())));
    }

    @Test
    void isOffWithoutRerank() {
        assertFalse(processor(0.2, false).isEnabled(context(Set.of())));
        assertTrue(processor(0.2, true).isEnabled(context(Set.of())));
    }
}
