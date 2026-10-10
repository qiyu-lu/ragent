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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.operation.RequestOperation;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.fulltext.Bm25;
import com.nageoffer.ai.ragent.rag.core.fulltext.FullTextStore;
import com.nageoffer.ai.ragent.rag.core.fulltext.FullTextTokenizer;
import com.nageoffer.ai.ragent.rag.core.fulltext.TsvectorLiteral;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文全文通道（knowledge-quality 计划 §6）：与向量通道共用检索作用域，在 {@code t_knowledge_chunk.content_tsv} 上召回
 * <ol>
 *   <li>查询用与索引相同的分词函数切成词项，组成 OR 查询</li>
 *   <li>作用域的 collection 经 {@code t_knowledge_base} 映射成 kb_id；{@code @@} 走 GIN 索引取全部命中块的轻量行</li>
 *   <li>应用侧按库存的文档频率与平均长度算 BM25，取前 recallBudget 条，再回表取正文</li>
 * </ol>
 * 统计缺失（库还没重建过）时退回命中顺序（ts_rank，没有 IDF）并告警。
 * 本通道的 BM25 分放进结果的 {@link #BM25_SCORES} 元数据：融合会就地改写块的分数，分数融合要读原始 BM25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgFullTextSearchChannel implements SearchChannel {

    /**
     * {@link SearchChannelResult#getMetadata()} 里的键：chunk id → BM25 分
     */
    public static final String BM25_SCORES = "bm25Scores";

    private final SearchChannelProperties properties;
    private final FullTextTokenizer tokenizer;
    private final FullTextStore store;

    @Override
    public String getName() {
        return "FullTextSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getFullText().isEnabled();
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.FULL_TEXT;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<RetrievedChunk> chunks = retrieve(context);
            Map<String, Float> bm25 = new LinkedHashMap<>();
            chunks.forEach(chunk -> bm25.put(chunk.getId(), chunk.getScore()));
            SearchChannelResult result = SearchChannelResult.builder()
                    .channelType(SearchChannelType.FULL_TEXT)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
            result.getMetadata().put(BM25_SCORES, Map.copyOf(bm25));
            return result;
        } catch (Exception e) {
            if (RequestOperation.current() != null) {
                throw RequestOperation.failure("full-text", e);
            }
            log.error("全文检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    private List<RetrievedChunk> retrieve(SearchContext context) {
        RetrievalScope scope = context.getRetrievalScope();
        if (scope == null || scope.targetCollections().isEmpty()) {
            return List.of();
        }
        int budget = context.getBudget().recallBudget();
        List<String> terms = tokenizer.queryTerms(context.getMainQuestion());
        String tsquery = TsvectorLiteral.orQuery(terms);
        if (budget <= 0 || tsquery == null) {
            log.info("全文检索跳过：查询没有可用词项");
            return List.of();
        }
        List<String> kbIds = store.kbIdsOf(scope.targetCollections());
        if (kbIds.isEmpty()) {
            return List.of();
        }
        SearchChannelProperties.FullText config = properties.getChannels().getFullText();
        List<FullTextStore.Match> matches = store.matches(kbIds, terms, tsquery, context.getDocumentIds(), config.getMaxMatches());
        if (matches.size() >= config.getMaxMatches()) {
            log.warn("全文命中达到上限 {}，超出部分按 ts_rank（无 IDF）截掉了", config.getMaxMatches());
        }
        FullTextStore.CorpusStats stats = store.stats(kbIds, terms);
        List<Scored> ranked = rank(matches, stats, config.getK1(), config.getB());
        List<Scored> top = ranked.size() > budget ? ranked.subList(0, budget) : ranked;

        Map<String, Float> scoreById = new HashMap<>();
        top.forEach(scored -> scoreById.put(scored.chunkId(), (float) scored.score()));
        List<RetrievedChunk> chunks = new ArrayList<>(top.size());
        for (FullTextStore.ChunkText text : store.texts(top.stream().map(Scored::chunkId).toList())) {
            chunks.add(RetrievedChunk.builder()
                    .id(text.chunkId())
                    .text(text.content())
                    .collectionName(text.collectionName())
                    .docId(text.docId())
                    .score(scoreById.get(text.chunkId()))
                    .build());
        }
        log.info("全文检索完成，{} 库 词项 {} 命中 {} 块，返回 {} 条（最高 BM25 {}）",
                kbIds.size(), terms.size(), matches.size(), chunks.size(), ChunkRanking.topScoreOf(chunks));
        return chunks;
    }

    record Scored(String chunkId, double score) {
    }

    /**
     * 按 BM25 降序（同分按命中顺序）；作用域内没有统计时退回命中顺序，分数为名次倒数
     */
    static List<Scored> rank(List<FullTextStore.Match> matches, FullTextStore.CorpusStats stats, double k1, double b) {
        List<Scored> scored = new ArrayList<>(matches.size());
        if (stats.chunkCount() <= 0) {
            if (!matches.isEmpty()) {
                log.warn("全文索引缺少词项统计，按 ts_rank（无 IDF）排序；请调 POST /admin/full-text/rebuild");
            }
            for (int i = 0; i < matches.size(); i++) {
                scored.add(new Scored(matches.get(i).chunkId(), 1D / (i + 1)));
            }
            return scored;
        }
        for (FullTextStore.Match match : matches) {
            scored.add(new Scored(match.chunkId(), Bm25.score(match.termFreqs(), match.length(), stats.docFreqs(),
                    stats.chunkCount(), stats.avgLength(), k1, b)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored;
    }
}
