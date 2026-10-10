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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.PgFullTextSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词项分与重排分融合（knowledge-quality 计划 §6 的 S3-blend 臂）：最终分 = α × BM25 / 本子问题 BM25 最大值 + (1 − α) × 重排分
 * <p>
 * 打开时 Rerank 已给整个候选池打分，这里对全部打过分的块重算并排序，请求级选择取前 contextTopK；
 * 全文通道没召回的块词项分为 0。Rerank 回退成 noop 时没有模型分，不融合
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreBlendPostProcessor implements SearchResultPostProcessor {

    private final ScoreBlendProperties properties;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "ScoreBlend";
    }

    @Override
    public int getOrder() {
        return 12;  // Rerank(10)、MetadataBoost(11) 之后，阈值(13) 之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.isEnabled() && Boolean.TRUE.equals(ragConfigProperties.getRerankEnabled());
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Set<String> scored = RerankPostProcessor.scoredKeys(context);
        if (scored.isEmpty()) {
            if (!chunks.isEmpty()) {
                log.warn("Rerank 没有给出模型分（回退或失败），本子问题不做分数融合");
            }
            return chunks;
        }
        Map<String, Float> bm25 = bm25Scores(results);
        double max = bm25.values().stream().mapToDouble(Float::doubleValue).max().orElse(0D);
        double alpha = properties.getAlpha();
        List<RetrievedChunk> blended = new ArrayList<>(chunks.size());
        List<RetrievedChunk> unscored = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            String key = RetrievedChunkKey.of(chunk);
            if (!scored.contains(key) || chunk.getScore() == null) {
                unscored.add(chunk);
                continue;
            }
            double term = max > 0 ? bm25.getOrDefault(key, 0F) / max : 0D;
            blended.add(chunk.toBuilder().score((float) (alpha * term + (1 - alpha) * chunk.getScore())).build());
        }
        blended.sort(RetrievedChunk.BY_SCORE_DESC);
        log.info("分数融合 α={}：{} 块按 BM25 与重排分重排，全文通道召回 {} 块", alpha, blended.size(), bm25.size());
        blended.addAll(unscored);
        return blended;
    }

    /**
     * 全文通道写在结果元数据里的原始 BM25（块对象的分数会被融合就地改写，不能用）
     */
    @SuppressWarnings("unchecked")
    static Map<String, Float> bm25Scores(List<SearchChannelResult> results) {
        Map<String, Float> scores = new HashMap<>();
        if (results == null) {
            return scores;
        }
        for (SearchChannelResult result : results) {
            if (result != null && result.getChannelType() == SearchChannelType.FULL_TEXT
                    && result.getMetadata().get(PgFullTextSearchChannel.BM25_SCORES) instanceof Map<?, ?> map) {
                scores.putAll((Map<String, Float>) map);
            }
        }
        return scores;
    }
}
