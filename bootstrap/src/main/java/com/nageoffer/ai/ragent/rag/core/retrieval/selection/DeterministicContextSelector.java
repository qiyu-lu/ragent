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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic budgeted selector shared by replay and production wiring.
 * Scores are converted to within-query rank weights; they are not treated as
 * calibrated probabilities and raw values from different aspects are never mixed.
 */
public final class DeterministicContextSelector implements ContextSelector {

    private static final double EPSILON = 1e-12;
    private static final Pattern NUMBER_OR_VERSION = Pattern.compile(
            "(?i)(?:v(?:ersion)?\\s*)?\\d+(?:[._-]\\d+)*(?:%|[a-z]+)?");

    @Override
    public ContextSelectionResult select(ContextSelectionInput input) {
        validate(input);
        Map<String, Double> relevance = rankWeights(input.candidates(), true, -1);
        List<Map<String, Double>> aspectWeights = new ArrayList<>(input.predictedAspects().size());
        for (int aspect = 0; aspect < input.predictedAspects().size(); aspect++) {
            aspectWeights.add(rankWeights(input.candidates(), false, aspect));
        }
        int missingOriginal = (int) input.candidates().stream()
                .filter(candidate -> candidate.originalQuestionScore() == null
                        || !candidate.originalQuestionScore().usable())
                .count();
        int missingAspect = 0;
        for (ContextSelectionCandidate candidate : input.candidates()) {
            for (int aspect = 0; aspect < input.predictedAspects().size(); aspect++) {
                final int expectedAspect = aspect;
                boolean present = candidate.aspectScores().stream()
                        .anyMatch(score -> score.aspectIndex() == expectedAspect && score.usable());
                if (!present) {
                    missingAspect++;
                }
            }
        }

        SelectionState state = switch (input.strategy()) {
            case PREFIX -> selectOrdered(input, sourceOrder(input.candidates()), relevance, aspectWeights, "prefix");
            case RERANK -> selectOrdered(input, relevanceOrder(input.candidates(), relevance), relevance, aspectWeights,
                    "original-question-rerank");
            case MMR, COVERAGE, COVERAGE_NO_COVER, COVERAGE_NO_DIVERSITY ->
                    selectGreedy(input, relevance, aspectWeights);
        };
        List<ContextSelectionCandidate> rendered = state.selected.stream()
                .sorted(Comparator.comparingDouble((ContextSelectionCandidate candidate) ->
                                relevance.getOrDefault(candidate.id(), 0.0)).reversed()
                        .thenComparing(ContextSelectionCandidate::id))
                .toList();
        Map<String, Integer> renderIndexes = new HashMap<>();
        for (int index = 0; index < rendered.size(); index++) {
            renderIndexes.put(rendered.get(index).id(), index);
        }
        List<ContextSelectionDecision> decisions = new ArrayList<>(state.decisions.size());
        for (PendingDecision decision : state.decisions) {
            decisions.add(new ContextSelectionDecision(
                    decision.candidate.id(), decision.selectionIndex, renderIndexes.get(decision.candidate.id()),
                    decision.candidate.renderTokenCount(), decision.incrementalUtility,
                    decision.attributedAspectIndices, decision.reason));
        }
        return new ContextSelectionResult(
                input.exampleId(), input.strategy().label(), rendered.stream().map(ContextSelectionCandidate::id).toList(),
                List.copyOf(decisions), state.tokens, input.tokenBudget(), input.maxChunks(), missingOriginal,
                missingAspect, state.stoppedForNonPositiveGain);
    }

    private SelectionState selectOrdered(
            ContextSelectionInput input,
            List<ContextSelectionCandidate> ordered,
            Map<String, Double> relevance,
            List<Map<String, Double>> aspectWeights,
            String reason) {
        SelectionState state = new SelectionState();
        for (ContextSelectionCandidate candidate : ordered) {
            if (state.selected.size() >= input.maxChunks()) {
                break;
            }
            if (state.tokens + candidate.renderTokenCount() > input.tokenBudget()) {
                continue;
            }
            double utility = relevance.getOrDefault(candidate.id(), 0.0);
            List<Integer> attribution = strongestAspects(candidate.id(), aspectWeights);
            state.add(candidate, utility, attribution, reason);
        }
        return state;
    }

    private SelectionState selectGreedy(
            ContextSelectionInput input,
            Map<String, Double> relevance,
            List<Map<String, Double>> aspectWeights) {
        double lambda = switch (input.strategy()) {
            case MMR, COVERAGE_NO_COVER -> 0.0;
            default -> input.coverageWeight();
        };
        double mu = input.strategy() == ContextSelectionStrategy.COVERAGE_NO_DIVERSITY
                ? 0.0 : input.diversityWeight();
        SelectionState state = new SelectionState();
        double[] currentAspectMax = new double[aspectWeights.size()];
        Set<String> selectedIds = new HashSet<>();
        while (state.selected.size() < input.maxChunks()) {
            ContextSelectionCandidate best = null;
            double bestGain = Double.NEGATIVE_INFINITY;
            double bestRatio = Double.NEGATIVE_INFINITY;
            List<Integer> bestAttribution = List.of();
            for (ContextSelectionCandidate candidate : input.candidates()) {
                if (selectedIds.contains(candidate.id())
                        || state.tokens + candidate.renderTokenCount() > input.tokenBudget()) {
                    continue;
                }
                double coverageGain = 0.0;
                List<Integer> attribution = new ArrayList<>();
                for (int aspect = 0; aspect < aspectWeights.size(); aspect++) {
                    double candidateWeight = aspectWeights.get(aspect).getOrDefault(candidate.id(), 0.0);
                    if (candidateWeight > currentAspectMax[aspect] + EPSILON) {
                        coverageGain += candidateWeight - currentAspectMax[aspect];
                        attribution.add(aspect);
                    }
                }
                double redundancy = 0.0;
                for (ContextSelectionCandidate selected : state.selected) {
                    redundancy += redundancy(candidate, selected);
                }
                double gain = relevance.getOrDefault(candidate.id(), 0.0) + lambda * coverageGain - mu * redundancy;
                double ratio = gain / candidate.renderTokenCount();
                if (ratio > bestRatio + EPSILON
                        || (Math.abs(ratio - bestRatio) <= EPSILON
                        && (best == null || candidate.id().compareTo(best.id()) < 0))) {
                    best = candidate;
                    bestGain = gain;
                    bestRatio = ratio;
                    bestAttribution = List.copyOf(attribution);
                }
            }
            if (best == null || bestGain <= EPSILON) {
                state.stoppedForNonPositiveGain = best != null;
                break;
            }
            state.add(best, bestGain, bestAttribution, input.strategy().label() + " greedy-gain-per-token");
            selectedIds.add(best.id());
            for (int aspect = 0; aspect < aspectWeights.size(); aspect++) {
                currentAspectMax[aspect] = Math.max(
                        currentAspectMax[aspect], aspectWeights.get(aspect).getOrDefault(best.id(), 0.0));
            }
        }
        return state;
    }

    private Map<String, Double> rankWeights(
            List<ContextSelectionCandidate> candidates, boolean original, int aspectIndex) {
        List<ContextSelectionCandidate> usable = candidates.stream()
                .filter(candidate -> original
                        ? candidate.originalQuestionScore() != null && candidate.originalQuestionScore().usable()
                        : candidate.aspectScores().stream().anyMatch(score ->
                                score.aspectIndex() == aspectIndex && score.usable()))
                .sorted(Comparator.comparingDouble((ContextSelectionCandidate candidate) ->
                                rawScore(candidate, original, aspectIndex)).reversed()
                        .thenComparing(ContextSelectionCandidate::id))
                .toList();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (int index = 0; index < usable.size(); index++) {
            weights.put(usable.get(index).id(), 1.0 / (Math.log(index + 2.0) / Math.log(2.0)));
        }
        return weights;
    }

    private double rawScore(ContextSelectionCandidate candidate, boolean original, int aspectIndex) {
        if (original) {
            return candidate.originalQuestionScore().value();
        }
        return candidate.aspectScores().stream()
                .filter(score -> score.aspectIndex() == aspectIndex && score.usable())
                .mapToDouble(ContextSelectionAspectScore::value)
                .findFirst()
                .orElse(Double.NEGATIVE_INFINITY);
    }

    private List<ContextSelectionCandidate> relevanceOrder(
            List<ContextSelectionCandidate> candidates, Map<String, Double> relevance) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble((ContextSelectionCandidate candidate) ->
                                relevance.getOrDefault(candidate.id(), 0.0)).reversed()
                        .thenComparing(ContextSelectionCandidate::id))
                .toList();
    }

    private List<ContextSelectionCandidate> sourceOrder(List<ContextSelectionCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(ContextSelectionCandidate::sourceOrder)
                        .thenComparing(ContextSelectionCandidate::id))
                .toList();
    }

    private List<Integer> strongestAspects(String candidateId, List<Map<String, Double>> aspectWeights) {
        double strongest = aspectWeights.stream()
                .mapToDouble(weights -> weights.getOrDefault(candidateId, 0.0))
                .max().orElse(0.0);
        if (strongest <= 0.0) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (int aspect = 0; aspect < aspectWeights.size(); aspect++) {
            if (Math.abs(aspectWeights.get(aspect).getOrDefault(candidateId, 0.0) - strongest) <= EPSILON) {
                result.add(aspect);
            }
        }
        return List.copyOf(result);
    }

    private double redundancy(ContextSelectionCandidate left, ContextSelectionCandidate right) {
        if (left.embedding().isEmpty() || left.embedding().size() != right.embedding().size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.embedding().size(); index++) {
            double leftValue = left.embedding().get(index);
            double rightValue = right.embedding().get(index);
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
                return 0.0;
            }
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) {
            return 0.0;
        }
        double similarity = Math.max(0.0, dot / Math.sqrt(leftNorm * rightNorm));
        Set<String> leftNumbers = numericTokens(left.text());
        Set<String> rightNumbers = numericTokens(right.text());
        if ((!leftNumbers.isEmpty() || !rightNumbers.isEmpty()) && !leftNumbers.equals(rightNumbers)) {
            similarity = Math.min(similarity, 0.85);
        }
        if (left.version() != null && right.version() != null
                && !left.version().equalsIgnoreCase(right.version())) {
            similarity = Math.min(similarity, 0.85);
        }
        return similarity;
    }

    private Set<String> numericTokens(String text) {
        Set<String> values = new HashSet<>();
        Matcher matcher = NUMBER_OR_VERSION.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private void validate(ContextSelectionInput input) {
        if (input == null || input.strategy() == null) {
            throw new IllegalArgumentException("selection input and strategy are required");
        }
        if (input.tokenBudget() <= 0 || input.maxChunks() <= 0) {
            throw new IllegalArgumentException("tokenBudget and maxChunks must be positive");
        }
        if (!Double.isFinite(input.coverageWeight()) || input.coverageWeight() < 0
                || !Double.isFinite(input.diversityWeight()) || input.diversityWeight() < 0) {
            throw new IllegalArgumentException("coverage and diversity weights must be finite and non-negative");
        }
        Set<String> ids = new HashSet<>();
        for (ContextSelectionCandidate candidate : input.candidates()) {
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()) {
                throw new IllegalArgumentException("candidate id is required");
            }
            if (!ids.add(candidate.id())) {
                throw new IllegalArgumentException("duplicate candidate id: " + candidate.id());
            }
            if (candidate.renderTokenCount() <= 0) {
                throw new IllegalArgumentException("candidate token count must be positive: " + candidate.id());
            }
        }
    }

    private static final class SelectionState {
        private final List<ContextSelectionCandidate> selected = new ArrayList<>();
        private final List<PendingDecision> decisions = new ArrayList<>();
        private int tokens;
        private boolean stoppedForNonPositiveGain;

        private void add(
                ContextSelectionCandidate candidate,
                double utility,
                List<Integer> attribution,
                String reason) {
            decisions.add(new PendingDecision(
                    candidate, selected.size(), utility, List.copyOf(attribution), reason));
            selected.add(candidate);
            tokens += candidate.renderTokenCount();
        }
    }

    private record PendingDecision(
            ContextSelectionCandidate candidate,
            int selectionIndex,
            double incrementalUtility,
            List<Integer> attributedAspectIndices,
            String reason) {
    }
}
