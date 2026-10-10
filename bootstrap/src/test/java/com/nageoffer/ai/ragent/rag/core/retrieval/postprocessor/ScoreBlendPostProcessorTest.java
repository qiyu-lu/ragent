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
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.PgFullTextSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreBlendPostProcessorTest {

    private static RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).score(score).build();
    }

    private static SearchContext context(Set<String> scored) {
        SearchContext context = SearchContext.builder().rewrittenQuestion("问题").budget(new RetrievalBudget(20, 40, 10)).build();
        context.getMetadata().put(RerankPostProcessor.SCORED_KEYS, scored);
        return context;
    }

    private static SearchChannelResult fullText(Map<String, Float> bm25) {
        // 块对象的分数被融合改写过（这里故意填 RRF 量级），融合必须读元数据里的原始 BM25
        SearchChannelResult result = SearchChannelResult.builder().channelType(SearchChannelType.FULL_TEXT)
                .channelName("FullTextSearch")
                .chunks(bm25.keySet().stream().map(id -> chunk(id, 0.03F)).toList()).build();
        result.getMetadata().put(PgFullTextSearchChannel.BM25_SCORES, bm25);
        return result;
    }

    private static ScoreBlendPostProcessor processor(double alpha) {
        ScoreBlendProperties properties = new ScoreBlendProperties();
        properties.setEnabled(true);
        properties.setAlpha(alpha);
        RAGConfigProperties rag = mock(RAGConfigProperties.class);
        when(rag.getRerankEnabled()).thenReturn(true);
        return new ScoreBlendPostProcessor(properties, rag);
    }

    @Test
    void blendsNormalizedBm25WithRerankScoresAndReorders() {
        // a：重排 0.9、全文没召回；b：重排 0.6、BM25 是本题最高（归一化 1.0）；c：重排 0.5、BM25 一半
        List<RetrievedChunk> pool = List.of(chunk("a", 0.9F), chunk("b", 0.6F), chunk("c", 0.5F));
        List<SearchChannelResult> results = List.of(fullText(Map.of("b", 12F, "c", 6F)));

        List<RetrievedChunk> blended = processor(0.5).process(pool, results, context(Set.of("a", "b", "c")));

        assertEquals(List.of("b", "c", "a"), blended.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.5F * 1.0F + 0.5F * 0.6F, blended.get(0).getScore(), 1e-6);
        assertEquals(0.5F * 0.5F + 0.5F * 0.5F, blended.get(1).getScore(), 1e-6);
        assertEquals(0.5F * 0.9F, blended.get(2).getScore(), 1e-6);
    }

    @Test
    void smallAlphaKeepsTheRerankOrder() {
        List<RetrievedChunk> pool = List.of(chunk("a", 0.9F), chunk("b", 0.6F));
        List<RetrievedChunk> blended = processor(0.1).process(pool, List.of(fullText(Map.of("b", 12F))), context(Set.of("a", "b")));
        assertEquals(List.of("a", "b"), blended.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    void unscoredChunksStayBehindAndNoopRerankIsLeftAlone() {
        List<RetrievedChunk> pool = List.of(chunk("tail", 0.04F), chunk("a", 0.9F));
        List<RetrievedChunk> blended = processor(0.5).process(pool, List.of(fullText(Map.of("tail", 9F))), context(Set.of("a")));
        assertEquals(List.of("a", "tail"), blended.stream().map(RetrievedChunk::getId).toList());

        assertEquals(pool, processor(0.5).process(pool, List.of(), context(Set.of())));
    }
}
