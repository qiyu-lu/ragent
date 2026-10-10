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

import com.nageoffer.ai.ragent.rag.dto.RetrievalSelectionDiagnostics;

import java.util.List;
import java.util.Map;

/**
 * 评测检索出参：只有检索证据，没有生成
 *
 * @param question             原问题
 * @param mode                 {@code rewrite}（本次在线改写）或 {@code replay}（使用请求给定的子问题）
 * @param rewrittenQuestion    改写后的问题，回放时为 null
 * @param subQuestions         实际参与检索的子问题
 * @param results              每个子问题的候选池，顺序与 subQuestions 一致
 * @param finalChunkIds        请求级最终入选的 chunk id，按进入上下文的顺序
 * @param retrievalDiagnostics 请求级选择诊断
 * @param stages               检索过程各阶段的摘要，供排障
 * @param latencyMs            接口总耗时
 */
public record EvalResponse(String question,
                           String mode,
                           String rewrittenQuestion,
                           List<String> subQuestions,
                           List<SubQuestionResult> results,
                           List<String> finalChunkIds,
                           RetrievalSelectionDiagnostics retrievalDiagnostics,
                           List<StageSummary> stages,
                           long latencyMs) {

    /**
     * @param subQuestion    子问题
     * @param rerankHeadSize Rerank 模型实际打分的头部条数（等于该子问题的 Rerank topN 与候选池大小的较小者；
     *                       Rerank 关闭或失败时为 0）
     * @param rerankScored   头部里分数确实被 Rerank 改过的条数；为 0 而头部不止一条时 Rerank 回退成了 noop
     * @param candidates     候选池，按最后一个后置处理阶段的输出顺序（Rerank 之后即模型头部 + 融合尾部）
     */
    public record SubQuestionResult(String subQuestion, int rerankHeadSize, int rerankScored, List<Candidate> candidates) {
    }

    /**
     * @param rank           在候选池中的名次，从 0 开始
     * @param id             chunk id（向量库主键）
     * @param docId          所属文档 id，元数据富化后才有
     * @param docName        所属文档名
     * @param collectionName 所属知识库 collection
     * @param channelScore   通道原始分（向量通道为余弦相似度，只被全文通道召回的为 BM25）；该 chunk 未出现在通道阶段时为 null
     * @param channelScores  每个召回它的通道各自的原始分，键为通道名（VectorSearch / FullTextSearch）
     * @param rerankScore    Rerank 模型写回的相关性分，只对 {@code rerankHead} 为 true 的候选有值
     * @param rerankHead     是否属于 Rerank 模型打分的头部
     * @param finalSelected  是否进入请求级最终上下文
     * @param finalRank      进入最终上下文的名次，从 0 开始；未入选为 null
     * @param text           chunk 正文
     */
    public record Candidate(int rank,
                            String id,
                            String docId,
                            String docName,
                            String collectionName,
                            Float channelScore,
                            Map<String, Float> channelScores,
                            Float rerankScore,
                            boolean rerankHead,
                            boolean finalSelected,
                            Integer finalRank,
                            String text) {
    }

    /**
     * @param subQuestion 阶段所属子问题，请求级阶段为空串
     * @param stage       阶段名，如 channel-VectorSearch、post-Rerank、request-final
     * @param chunkCount  该阶段输出的 chunk 数
     * @param elapsedMs   阶段耗时
     * @param failure     失败标记，正常为 null
     */
    public record StageSummary(String subQuestion, String stage, int chunkCount, long elapsedMs, String failure) {
    }
}
