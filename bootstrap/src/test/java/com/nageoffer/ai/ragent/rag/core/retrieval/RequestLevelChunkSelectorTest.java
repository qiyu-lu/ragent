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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestLevelChunkSelectorTest {

    @Test
    void fillsInitialQuotasInFairRounds() {
        List<List<RetrievedChunk>> candidates = List.of(
                chunks("a0", "a1", "a2", "a3", "a4"),
                chunks("b0", "b1", "b2", "b3"),
                chunks("c0", "c1", "c2", "c3"));

        RequestLevelChunkSelector.SelectionResult result = RequestLevelChunkSelector.select(
                candidates, List.of(4, 3, 3), 10);

        assertEquals(List.of(4, 3, 3), result.selectedByQuestion().stream().map(List::size).toList());
        assertEquals(List.of("a0", "b0", "c0", "a1", "b1", "c1", "a2", "b2", "c2", "a3"),
                ids(result.orderedChunks()));
        assertEquals(13, result.candidateCount());
        assertEquals(13, result.candidateUniqueCount());
        assertEquals(10, result.uniqueBeforeRefill());
        assertEquals(0, result.refillAdded());
        assertEquals(10, result.finalUniqueCount());
        assertEquals(0, result.unfilledSlots());
    }

    @Test
    void skipsCrossQuestionDuplicatesAndScansDeeperWithinQuota() {
        List<List<RetrievedChunk>> candidates = List.of(
                chunks("shared", "a1", "a2"),
                chunks("shared", "b1", "b2"),
                chunks("c0", "c1", "c2"));

        RequestLevelChunkSelector.SelectionResult result = RequestLevelChunkSelector.select(
                candidates, List.of(2, 2, 2), 6);

        assertEquals(List.of("shared", "a1"), ids(result.selectedByQuestion().get(0)));
        assertEquals(List.of("b1", "b2"), ids(result.selectedByQuestion().get(1)));
        assertEquals(List.of("c0", "c1"), ids(result.selectedByQuestion().get(2)));
        assertEquals(5, result.uniqueBeforeRefill());
        assertEquals(1, result.refillAdded());
        assertEquals(6, result.finalUniqueCount());
        assertEquals(0, result.unfilledSlots());
    }

    @Test
    void refillsExhaustedQuestionSlotsRoundRobin() {
        List<List<RetrievedChunk>> candidates = List.of(
                chunks("a0"),
                chunks("b0", "b1", "b2", "b3", "b4"),
                chunks("c0", "c1", "c2", "c3"));

        RequestLevelChunkSelector.SelectionResult result = RequestLevelChunkSelector.select(
                candidates, List.of(3, 3, 2), 8);

        assertEquals(List.of(1, 4, 3), result.selectedByQuestion().stream().map(List::size).toList());
        assertEquals(6, result.uniqueBeforeRefill());
        assertEquals(2, result.refillAdded());
        assertEquals(8, result.finalUniqueCount());
        assertEquals(0, result.unfilledSlots());
        assertEquals(List.of("a0", "b0", "c0", "b1", "c1", "b2", "b3", "c2"),
                ids(result.orderedChunks()));
    }

    @Test
    void reportsUnfilledSlotsWhenUniqueCandidatesAreInsufficient() {
        List<List<RetrievedChunk>> candidates = List.of(
                chunks("shared"),
                chunks("shared"),
                List.of());

        RequestLevelChunkSelector.SelectionResult result = RequestLevelChunkSelector.select(
                candidates, List.of(2, 2, 1), 5);

        assertEquals(2, result.candidateCount());
        assertEquals(1, result.candidateUniqueCount());
        assertEquals(1, result.uniqueBeforeRefill());
        assertEquals(0, result.refillAdded());
        assertEquals(1, result.finalUniqueCount());
        assertEquals(4, result.unfilledSlots());
    }

    @Test
    void usesCanonicalTextHashWhenChunkIdIsMissing() {
        RetrievedChunk first = RetrievedChunk.builder().text("同一正文").build();
        RetrievedChunk duplicate = RetrievedChunk.builder().text("同一正文").build();
        List<List<RetrievedChunk>> candidates = List.of(List.of(first), List.of(duplicate));

        RequestLevelChunkSelector.SelectionResult result = RequestLevelChunkSelector.select(
                candidates, List.of(1, 1), 2);

        assertEquals(2, result.candidateCount());
        assertEquals(1, result.candidateUniqueCount());
        assertEquals(List.of(first), result.orderedChunks());
        assertEquals(List.of(first), candidates.get(0), "选择过程不得修改输入列表");
        assertEquals(List.of(duplicate), candidates.get(1), "选择过程不得修改输入列表");
    }

    @Test
    void rejectsMismatchedOrNegativeQuotas() {
        List<List<RetrievedChunk>> candidates = List.of(chunks("a"));

        assertThrows(IllegalArgumentException.class,
                () -> RequestLevelChunkSelector.select(candidates, List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> RequestLevelChunkSelector.select(candidates, List.of(-1), 1));
    }

    private List<RetrievedChunk> chunks(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> RetrievedChunk.builder().id(id).text(id).build())
                .toList();
    }

    private List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}
