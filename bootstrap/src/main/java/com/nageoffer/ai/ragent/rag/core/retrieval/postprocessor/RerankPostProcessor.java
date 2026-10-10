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
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rerank 后置处理器
 * <p>
 * 使用 Rerank 模型对候选结果进行重排序。
 * <p>
 * 模型返回的 Top-K 作为候选池头部，未进入头部的融合候选按原顺序追加为回填尾部。请求级最终 Top-K
 * 由上层在多子问题全局去重后统一选择，避免各子问题提前截断后无法利用剩余额度。
 * <p>
 * 分数融合打开时让模型给整个候选池打分（模型本来就逐条打分，topN 只截返回）。模型实际打过分的块记进
 * 检索上下文的 {@link #SCORED_KEYS}：回退成 noop 时头部原样带着融合分，下游的阈值与分数融合据此跳过
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    /**
     * {@link SearchContext#getMetadata()} 里的键：Rerank 模型实际打过分的块（{@link RetrievedChunkKey}）
     */
    public static final String SCORED_KEYS = "rerankScoredKeys";

    private final RerankService rerankService;
    private final RAGConfigProperties ragConfigProperties;
    private final ScoreBlendProperties scoreBlendProperties;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 元数据/精排文本富化之后、候选池规模守卫之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getRerankEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        int topN = scoreBlendProperties.isEnabled() ? chunks.size() : context.getBudget().contextTopK();
        List<RetrievedChunk> rerankedHead = rerankService.rerank(
                context.getMainQuestion(),
                chunks,
                topN
        );
        List<RetrievedChunk> safeRerankedHead = rerankedHead == null ? List.of() : rerankedHead;
        context.getMetadata().put(SCORED_KEYS, scoredKeys(safeRerankedHead, chunks));

        List<RetrievedChunk> candidatePool = appendFusionTail(safeRerankedHead, chunks);
        logAttribution(chunks, safeRerankedHead, results);
        log.info("Rerank 候选池完成 - 模型头部: {}, 融合尾部回填后: {}",
                safeRerankedHead.size(), candidatePool.size());
        return candidatePool;
    }

    /**
     * 本子问题里 Rerank 模型实际打过分的块；没有 Rerank 阶段或模型没打分时为空集
     */
    @SuppressWarnings("unchecked")
    public static Set<String> scoredKeys(SearchContext context) {
        Object keys = context.getMetadata() == null ? null : context.getMetadata().get(SCORED_KEYS);
        return keys instanceof Set<?> set ? (Set<String>) set : Set.of();
    }

    /**
     * 模型打过分的块是客户端拷贝出的新对象（只覆盖分数）；noop 与补位的块原样返回输入对象
     */
    private static Set<String> scoredKeys(List<RetrievedChunk> head, List<RetrievedChunk> input) {
        Set<RetrievedChunk> inputs = Collections.newSetFromMap(new IdentityHashMap<>());
        inputs.addAll(input);
        Set<String> keys = new LinkedHashSet<>();
        for (RetrievedChunk chunk : head) {
            if (chunk != null && chunk.getScore() != null && !inputs.contains(chunk)) {
                keys.add(RetrievedChunkKey.of(chunk));
            }
        }
        return Set.copyOf(keys);
    }

    /**
     * 先保留 Rerank 返回对象（含新的相关性分数），再按融合顺序追加未命中的候选。
     * 身份规则与通道去重、融合保持一致；同一 key 以 Rerank 头部对象为准。
     */
    private List<RetrievedChunk> appendFusionTail(List<RetrievedChunk> rerankedHead,
                                                  List<RetrievedChunk> fusionCandidates) {
        Map<String, RetrievedChunk> ordered = new LinkedHashMap<>();
        if (rerankedHead != null) {
            for (RetrievedChunk chunk : rerankedHead) {
                if (chunk != null) {
                    ordered.putIfAbsent(RetrievedChunkKey.of(chunk), chunk);
                }
            }
        }
        for (RetrievedChunk chunk : fusionCandidates) {
            if (chunk != null) {
                ordered.putIfAbsent(RetrievedChunkKey.of(chunk), chunk);
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /**
     * 归因日志：对比 Rerank 前后各通道的候选数
     */
    private void logAttribution(List<RetrievedChunk> before,
                                List<RetrievedChunk> after,
                                List<SearchChannelResult> results) {
        if (results == null || results.size() <= 1) {
            return;
        }
        Map<String, Set<SearchChannelType>> index = ChannelAttribution.index(results);
        log.info("检索归因 - Rerank 输入按通道: {}, 输出 top{} 按通道: {}",
                ChannelAttribution.format(ChannelAttribution.countByChannel(before, index)),
                after.size(),
                ChannelAttribution.format(ChannelAttribution.countByChannel(after, index)));
    }
}
