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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CandidatePoolLimitPostProcessorTest {

    @Test
    void capsCandidatePoolWithoutApplyingContextTopK() {
        List<RetrievedChunk> candidates = chunks(12);
        SearchContext context = SearchContext.builder()
                .budget(new RetrievalBudget(20, 4, 2))
                .build();

        List<RetrievedChunk> result = new CandidatePoolLimitPostProcessor()
                .process(candidates, List.of(), context);

        assertEquals(List.of("0", "1", "2", "3"), ids(result));
    }

    @Test
    void nonPositiveCandidateLimitKeepsWholePool() {
        List<RetrievedChunk> candidates = chunks(5);
        SearchContext context = SearchContext.builder()
                .budget(new RetrievalBudget(20, 0, 2))
                .build();

        List<RetrievedChunk> result = new CandidatePoolLimitPostProcessor()
                .process(candidates, List.of(), context);

        assertSame(candidates, result);
    }

    private List<RetrievedChunk> chunks(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> RetrievedChunk.builder().id(String.valueOf(index)).build())
                .toList();
    }

    private List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}
