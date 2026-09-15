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

package com.nageoffer.ai.ragent.rag.core.retrieval.selection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicContextSelectorTest {

    private final ContextSelector selector = new DeterministicContextSelector();

    @Test
    void coverageKeepsComplementaryEvidenceWhenDuplicateWouldOccupyBudget() {
        List<ContextSelectionCandidate> candidates = List.of(
                candidate("a", 3.0, List.of(3.0, 0.1), List.of(1.0, 0.0), 10, "fact a"),
                candidate("a-copy", 2.9, List.of(2.9, 0.1), List.of(1.0, 0.0), 10, "fact a"),
                candidate("b", 2.0, List.of(0.1, 3.0), List.of(0.0, 1.0), 10, "fact b"));

        ContextSelectionResult result = selector.select(input(candidates, ContextSelectionStrategy.COVERAGE, 20, 2, 2.0, 0.5));

        assertEquals(List.of("a", "b"), result.selectedIds());
    }

    @Test
    void oneAspectAndMissingScoresAreSafe() {
        ContextSelectionCandidate missing = new ContextSelectionCandidate(
                "missing", "doc", "v1", Map.of(), "text", 0,
                new ContextSelectionScore(null, "partial", false), List.of(), List.of(), "none", 5);
        ContextSelectionResult result = selector.select(input(
                List.of(missing, candidate("valid", 1.0, List.of(1.0), List.of(1.0), 5, "valid")),
                ContextSelectionStrategy.COVERAGE, 10, 2, 1.0, 0.2));

        assertEquals(List.of("valid"), result.selectedIds());
        assertEquals(1, result.missingOriginalScores());
        assertEquals(1, result.missingAspectScores());
    }

    @Test
    void emptyCandidatesReturnEmptySelection() {
        ContextSelectionResult result = selector.select(input(
                List.of(), ContextSelectionStrategy.COVERAGE, 10, 2, 1.0, 0.2));
        assertTrue(result.selectedIds().isEmpty());
    }

    @Test
    void oversizedCandidateIsSkippedAndLaterCandidateCanFit() {
        List<ContextSelectionCandidate> candidates = List.of(
                candidate("large", 2.0, List.of(2.0), List.of(1.0), 20, "large"),
                candidate("small", 1.0, List.of(1.0), List.of(0.0), 5, "small"));
        ContextSelectionResult result = selector.select(input(
                candidates, ContextSelectionStrategy.RERANK, 10, 2, 1.0, 0.2));
        assertEquals(List.of("small"), result.selectedIds());
    }

    @Test
    void multiAspectCandidateRecordsAttributionAndRenderOrderUsesOriginalRank() {
        List<ContextSelectionCandidate> candidates = List.of(
                candidate("first", 5.0, List.of(1.0, 1.0), List.of(1.0, 0.0), 9, "first"),
                candidate("multi", 1.0, List.of(5.0, 5.0), List.of(0.0, 1.0), 1, "multi"));
        ContextSelectionResult result = selector.select(input(
                candidates, ContextSelectionStrategy.COVERAGE, 10, 2, 3.0, 0.0));
        assertEquals(List.of("first", "multi"), result.selectedIds());
        ContextSelectionDecision multi = result.decisions().stream()
                .filter(decision -> decision.candidateId().equals("multi")).findFirst().orElseThrow();
        assertEquals(List.of(0, 1), multi.attributedAspectIndices());
        assertEquals(1, multi.renderIndex());
    }

    @Test
    void tiesUseStableCandidateId() {
        List<ContextSelectionCandidate> candidates = List.of(
                candidate("z", 1.0, List.of(1.0), List.of(1.0, 0.0), 5, "z"),
                candidate("a", 1.0, List.of(1.0), List.of(0.0, 1.0), 5, "a"));
        ContextSelectionResult result = selector.select(input(
                candidates, ContextSelectionStrategy.COVERAGE, 5, 1, 0.0, 0.0));
        assertEquals(List.of("a"), result.selectedIds());
    }

    @Test
    void distinctVersionsAndNumbersCanBothSurviveDiversityPenalty() {
        ContextSelectionCandidate oldVersion = new ContextSelectionCandidate(
                "old", "doc", "V1.2", Map.of(), "limit 10 percent", 0,
                score(2.0), aspects(2.0), List.of(1.0, 0.0), "test", 5);
        ContextSelectionCandidate newVersion = new ContextSelectionCandidate(
                "new", "doc", "V1.3", Map.of(), "limit 20 percent", 1,
                score(1.0), aspects(1.0), List.of(1.0, 0.0), "test", 5);
        ContextSelectionResult result = selector.select(input(
                List.of(oldVersion, newVersion), ContextSelectionStrategy.MMR, 10, 2, 0.0, 0.5));
        assertEquals(2, result.selectedIds().size());
    }

    @Test
    void duplicateIdsAreRejected() {
        ContextSelectionCandidate duplicate = candidate("same", 1.0, List.of(1.0), List.of(1.0), 5, "x");
        assertThrows(IllegalArgumentException.class, () -> selector.select(input(
                List.of(duplicate, duplicate), ContextSelectionStrategy.COVERAGE, 10, 2, 1.0, 0.2)));
    }

    @Test
    void inputContractCannotCarryGoldAnswerOrEvidenceLabels() {
        List<String> componentNames = Arrays.stream(ContextSelectionInput.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertFalse(componentNames.contains("answer"));
        assertFalse(componentNames.contains("expectedFacts"));
        assertFalse(componentNames.contains("evidenceRequirements"));
    }

    private ContextSelectionInput input(
            List<ContextSelectionCandidate> candidates,
            ContextSelectionStrategy strategy,
            int tokenBudget,
            int maxChunks,
            double lambda,
            double mu) {
        int aspects = candidates.stream().mapToInt(candidate -> candidate.aspectScores().size()).max().orElse(1);
        return new ContextSelectionInput(
                "example", "question", java.util.stream.IntStream.range(0, aspects)
                        .mapToObj(index -> "aspect-" + index).toList(), candidates,
                strategy, tokenBudget, maxChunks, lambda, mu);
    }

    private ContextSelectionCandidate candidate(
            String id,
            double relevance,
            List<Double> aspectValues,
            List<Double> embedding,
            int tokens,
            String text) {
        return new ContextSelectionCandidate(
                id, "doc-" + id, "V1", Map.of(), text, 0, score(relevance),
                java.util.stream.IntStream.range(0, aspectValues.size())
                        .mapToObj(index -> new ContextSelectionAspectScore(
                                index, aspectValues.get(index), "test", true)).toList(),
                embedding, "test", tokens);
    }

    private ContextSelectionScore score(double value) {
        return new ContextSelectionScore(value, "test", true);
    }

    private List<ContextSelectionAspectScore> aspects(double value) {
        return List.of(new ContextSelectionAspectScore(0, value, "test", true));
    }
}
