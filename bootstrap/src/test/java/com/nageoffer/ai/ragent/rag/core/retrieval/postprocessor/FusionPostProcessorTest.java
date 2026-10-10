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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 全文通道的 RRF 权重什么时候起作用：两通道并集不超过候选池上限时，权重只改池内顺序，送进 Rerank 的集合不变
 */
class FusionPostProcessorTest {

    private static List<RetrievedChunk> chunks(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> RetrievedChunk.builder().id(prefix + i).text(prefix + i)
                .score(1F - i * 0.01F).build()).toList();
    }

    private static Set<String> pool(double fullTextWeight, int candidateLimit, List<RetrievedChunk> vector, List<RetrievedChunk> fullText) {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getFusion().getChannelWeights().setFullText(fullTextWeight);
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder().channelType(SearchChannelType.VECTOR).channelName("v").chunks(vector).build(),
                SearchChannelResult.builder().channelType(SearchChannelType.FULL_TEXT).channelName("f").chunks(fullText).build());
        List<RetrievedChunk> deduplicated = new DeduplicationPostProcessor().process(new ArrayList<>(), results, null);
        SearchContext context = SearchContext.builder().budget(new RetrievalBudget(20, candidateLimit, 10)).build();
        return new FusionPostProcessor(properties).process(deduplicated, results, context).stream()
                .map(RetrievedChunk::getId).collect(Collectors.toSet());
    }

    @Test
    void weightCannotChangeTheRerankPoolWhenTheUnionFitsTheLimit() {
        // 召回 20 + 20、候选池上限 40：并集最多 40 条，全部送进 Rerank
        assertEquals(pool(0.5, 40, chunks("v", 20), chunks("f", 20)), pool(1.0, 40, chunks("v", 20), chunks("f", 20)));
        assertEquals(40, pool(0.5, 40, chunks("v", 20), chunks("f", 20)).size());
    }

    @Test
    void weightDecidesMembershipOnceThePoolIsTruncated() {
        assertNotEquals(pool(0.2, 20, chunks("v", 20), chunks("f", 20)), pool(5.0, 20, chunks("v", 20), chunks("f", 20)));
    }
}
