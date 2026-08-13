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

class FinalTopKPostProcessorTest {

    @Test
    void capsResultsEvenWhenRerankIsDisabled() {
        List<RetrievedChunk> candidates = IntStream.range(0, 12)
                .mapToObj(index -> RetrievedChunk.builder().id(String.valueOf(index)).build())
                .toList();
        SearchContext context = SearchContext.builder()
                .budget(new RetrievalBudget(20, 40, 4))
                .build();

        List<RetrievedChunk> result = new FinalTopKPostProcessor()
                .process(candidates, List.of(), context);

        assertEquals(List.of("0", "1", "2", "3"),
                result.stream().map(RetrievedChunk::getId).toList());
    }
}
