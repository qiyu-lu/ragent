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
import com.nageoffer.ai.ragent.rag.config.RerankThresholdProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 重排分阈值（knowledge-quality 计划 §6 的 S3-thr 臂，借鉴 RAGFlow"最终分低于 0.2 丢弃"），排在所有改分的处理器之后
 * <p>
 * 只留 Rerank 模型打过分、且分数不低于阈值的块；没打分的融合尾部一并丢掉，否则请求级选择会拿尾部补满 contextTopK，
 * 阈值就只换了块、没减少块。Rerank 回退成 noop 时没有模型分，不过滤。全部低于阈值时返回空，这个子问题不带知识上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankThresholdPostProcessor implements SearchResultPostProcessor {

    private final RerankThresholdProperties properties;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "RerankThreshold";
    }

    @Override
    public int getOrder() {
        return 13;  // Rerank(10)、MetadataBoost(11)、ScoreBlend(12) 之后
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
                log.warn("Rerank 没有给出模型分（回退或失败），本子问题不按重排分阈值过滤");
            }
            return chunks;
        }
        double minScore = properties.getMinScore();
        List<RetrievedChunk> kept = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            if (scored.contains(RetrievedChunkKey.of(chunk)) && chunk.getScore() != null && chunk.getScore() >= minScore) {
                kept.add(chunk);
            }
        }
        log.info("重排分阈值 {}：{} 块留下 {} 块", minScore, chunks.size(), kept.size());
        return kept;
    }
}
