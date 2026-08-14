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
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;
    private final RAGConfigProperties ragConfigProperties;

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

        List<RetrievedChunk> rerankedHead = rerankService.rerank(
                context.getMainQuestion(),
                chunks,
                context.getBudget().contextTopK()
        );
        List<RetrievedChunk> safeRerankedHead = rerankedHead == null ? List.of() : rerankedHead;

        List<RetrievedChunk> candidatePool = appendFusionTail(safeRerankedHead, chunks);
        logAttribution(chunks, safeRerankedHead, results);
        log.info("Rerank 候选池完成 - 模型头部: {}, 融合尾部回填后: {}",
                safeRerankedHead.size(), candidatePool.size());
        return candidatePool;
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
     * 归因日志：对比 Rerank 前后各通道的候选数，重点是「图谱证据存活率」
     * <p>
     * 若图谱大量进入 Rerank 却几乎不存活，说明其当前是纯成本（塞候选、占名额、被淘汰），
     * 应下调图谱权重（{@code fusion.channel-weights.graph}）或先优化其长证据的可排性，再决定去留
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

        // 按图谱通道在场判断而非 graphIn > 0：0/0 恰是最需要看见的形态——图谱召回了却在融合截断处全军覆没，
        // 按输入量守门会让这行日志在事故发生时恒沉默
        boolean graphChannelPresent = results.stream()
                .anyMatch(result -> result.getChannelType() == SearchChannelType.GRAPH);
        if (graphChannelPresent) {
            long graphIn = ChannelAttribution.countOfChannel(before, index, SearchChannelType.GRAPH);
            long graphOut = ChannelAttribution.countOfChannel(after, index, SearchChannelType.GRAPH);
            log.info("检索归因 - 图谱证据存活: {}/{}", graphOut, graphIn);
        }
    }
}
