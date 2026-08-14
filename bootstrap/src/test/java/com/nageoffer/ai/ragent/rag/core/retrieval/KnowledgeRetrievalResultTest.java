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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrievalResultTest {

    @Test
    void retainChunksDropsUnselectedAttributionAndEligibility() {
        RetrievedChunk selected = chunk("selected");
        RetrievedChunk tail = chunk("tail");
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                List.of(selected, tail),
                Map.of(
                        RetrievedChunkKey.of(selected), Set.of("A"),
                        RetrievedChunkKey.of(tail), Set.of("B")),
                Set.of("A", "B"));

        KnowledgeRetrievalResult retained = result.retainChunks(List.of(selected));

        assertEquals(List.of(selected), retained.chunks());
        assertEquals(Map.of(RetrievedChunkKey.of(selected), Set.of("A")), retained.intentIdsByChunkKey());
        assertEquals(Set.of("A"), retained.eligibleIntentIds(List.of(intent("A"), intent("B"))));
    }

    @Test
    void emptyRetainKeepsDirectedMissIneligible() {
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of("A"));

        assertTrue(result.retainChunks(List.of()).eligibleIntentIds(List.of(intent("A"))).isEmpty());
    }

    private RetrievedChunk chunk(String id) {
        return RetrievedChunk.builder().id(id).text(id).build();
    }

    private NodeScore intent(String id) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).build())
                .score(0.9)
                .build();
    }
}
