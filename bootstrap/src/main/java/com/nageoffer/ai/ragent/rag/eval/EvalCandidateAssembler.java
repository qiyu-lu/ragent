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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把一次检索留下的 {@link RetrievalCapture} 整理成评测出参：每个子问题的候选池、通道分、重排分与最终入选
 * <p>
 * 候选池取该子问题最后一个后置处理阶段的输出，Rerank 之后即“模型头部 + 融合尾部”。通道分取自通道阶段的原始分：
 * 单通道时 RRF 不改写分数，它就是向量余弦。重排分只对头部有意义：BaiLian 客户端把 relevance_score 写回头部对象，
 * 尾部对象仍带通道分、与头部分数不可比，故按 Rerank 请求的 topN 截取头部，尾部的重排分记为 null
 */
final class EvalCandidateAssembler {

    static final String CHANNEL_PREFIX = "channel-";
    static final String POST_PREFIX = "post-";
    static final String RERANK_STAGE = "post-Rerank";

    private EvalCandidateAssembler() {
    }

    static EvalResponse assemble(String question,
                                 String mode,
                                 String rewrittenQuestion,
                                 List<String> subQuestions,
                                 RetrievalContext context,
                                 RetrievalCapture capture,
                                 List<Integer> rerankTopN,
                                 long latencyMs) {
        List<RetrievalCapture.Stage> stages = capture.stages();
        Map<String, Integer> finalRanks = finalRanks(context);
        List<EvalResponse.SubQuestionResult> results = new ArrayList<>(subQuestions.size());
        for (int i = 0; i < subQuestions.size(); i++) {
            String subQuestion = subQuestions.get(i);
            List<RetrievalCapture.Stage> own = stages.stream()
                    .filter(stage -> subQuestion.equals(stage.question()))
                    .toList();
            results.add(assembleOne(subQuestion, own, rerankTopN.get(i), finalRanks));
        }
        List<EvalResponse.StageSummary> summaries = stages.stream()
                .map(stage -> new EvalResponse.StageSummary(stage.question(), stage.stage(),
                        stage.chunks().size(), stage.elapsedMs(), stage.failure()))
                .toList();
        return new EvalResponse(question, mode, rewrittenQuestion, List.copyOf(subQuestions), results,
                new ArrayList<>(finalRanks.keySet()), context == null ? null : context.getRetrievalDiagnostics(),
                summaries, latencyMs);
    }

    /**
     * 请求级最终上下文：以 {@link RetrievalContext#effectiveKbChunks()} 为准，它就是 Prompt 与来源共用的 canonical 列表
     */
    private static Map<String, Integer> finalRanks(RetrievalContext context) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        if (context == null) {
            return ranks;
        }
        for (RetrievedChunk chunk : context.effectiveKbChunks()) {
            if (chunk != null && chunk.getId() != null) {
                ranks.putIfAbsent(chunk.getId(), ranks.size());
            }
        }
        return ranks;
    }

    private static EvalResponse.SubQuestionResult assembleOne(String subQuestion,
                                                             List<RetrievalCapture.Stage> own,
                                                             int rerankTopN,
                                                             Map<String, Integer> finalRanks) {
        Map<String, Float> channelScores = new LinkedHashMap<>();
        List<RetrievalCapture.Chunk> channelChunks = new ArrayList<>();
        own.stream()
                .filter(stage -> stage.stage().startsWith(CHANNEL_PREFIX))
                .forEach(stage -> stage.chunks().forEach(chunk -> {
                    channelChunks.add(chunk);
                    channelScores.putIfAbsent(chunk.id(), chunk.score());
                }));

        List<RetrievalCapture.Stage> postStages = own.stream()
                .filter(stage -> stage.stage().startsWith(POST_PREFIX))
                .toList();
        List<RetrievalCapture.Chunk> pool = postStages.isEmpty()
                ? channelChunks
                : postStages.get(postStages.size() - 1).chunks();

        Map<String, Float> rerankScores = new LinkedHashMap<>();
        Optional<RetrievalCapture.Stage> rerank = own.stream()
                .filter(stage -> RERANK_STAGE.equals(stage.stage()) && stage.failure() == null)
                .findFirst();
        rerank.ifPresent(stage -> {
            int head = Math.max(0, Math.min(rerankTopN, stage.chunks().size()));
            stage.chunks().subList(0, head).forEach(chunk -> rerankScores.putIfAbsent(chunk.id(), chunk.score()));
        });

        List<EvalResponse.Candidate> candidates = new ArrayList<>(pool.size());
        for (int rank = 0; rank < pool.size(); rank++) {
            RetrievalCapture.Chunk chunk = pool.get(rank);
            Integer finalRank = finalRanks.get(chunk.id());
            candidates.add(new EvalResponse.Candidate(rank, chunk.id(), chunk.docId(), chunk.docName(),
                    chunk.collectionName(), channelScores.get(chunk.id()), rerankScores.get(chunk.id()),
                    rerankScores.containsKey(chunk.id()), finalRank != null, finalRank, chunk.text()));
        }
        return new EvalResponse.SubQuestionResult(subQuestion, rerankScores.size(), candidates);
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static List<String> trimmed(List<String> values) {
        return values.stream().filter(Objects::nonNull).map(String::trim).toList();
    }
}
