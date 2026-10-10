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
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.fulltext.FullTextStore;
import com.nageoffer.ai.ragent.rag.core.fulltext.FullTextTokenizer;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgFullTextSearchChannelTest {

    private static FullTextStore.Match match(String id, int length, Map<String, Integer> tf) {
        return new FullTextStore.Match(id, length, tf);
    }

    @Test
    void ranksByBm25SoARareTermBeatsRepeatedCommonOnes() {
        // common 在 90/100 块里，rare 只在 1 块里：只命中 rare 一次的块应排在命中 common 五次的块前面
        FullTextStore.CorpusStats stats = new FullTextStore.CorpusStats(100, 10_000, Map.of("common", 90L, "rare", 1L));
        List<PgFullTextSearchChannel.Scored> ranked = PgFullTextSearchChannel.rank(List.of(
                match("common-x5", 100, Map.of("common", 5)),
                match("rare-x1", 100, Map.of("rare", 1))), stats, 1.2, 0.75);
        assertEquals(List.of("rare-x1", "common-x5"), ranked.stream().map(PgFullTextSearchChannel.Scored::chunkId).toList());
    }

    @Test
    void fallsBackToMatchOrderWithoutStatistics() {
        List<PgFullTextSearchChannel.Scored> ranked = PgFullTextSearchChannel.rank(List.of(
                match("first", 10, Map.of("a", 1)), match("second", 10, Map.of("a", 9))),
                new FullTextStore.CorpusStats(0, 0, Map.of()), 1.2, 0.75);
        assertEquals(List.of("first", "second"), ranked.stream().map(PgFullTextSearchChannel.Scored::chunkId).toList());
    }

    @Test
    void searchReturnsTopBudgetWithTextAndPublishesRawBm25() {
        FullTextTokenizer tokenizer = mock(FullTextTokenizer.class);
        when(tokenizer.queryTerms("焦硫酸钾用量")).thenReturn(List.of("焦硫酸钾", "用量"));
        FullTextStore store = mock(FullTextStore.class);
        when(store.kbIdsOf(List.of("kq"))).thenReturn(List.of("kb-1"));
        when(store.matches(List.of("kb-1"), List.of("焦硫酸钾", "用量"), "'焦硫酸钾' | '用量'", List.of(), 2000))
                .thenReturn(List.of(match("c1", 50, Map.of("用量", 1)), match("c2", 50, Map.of("焦硫酸钾", 2)),
                        match("c3", 50, Map.of("用量", 1))));
        when(store.stats(List.of("kb-1"), List.of("焦硫酸钾", "用量")))
                .thenReturn(new FullTextStore.CorpusStats(200, 10_000, Map.of("焦硫酸钾", 2L, "用量", 60L)));
        when(store.texts(List.of("c2", "c1"))).thenReturn(List.of(
                new FullTextStore.ChunkText("c2", "doc-2", "kq", "加 8 g～10 g 焦硫酸钾"),
                new FullTextStore.ChunkText("c1", "doc-1", "kq", "用量见表 1")));

        SearchChannelResult result = channel(tokenizer, store).search(context("焦硫酸钾用量", List.of("kq"), 2));

        assertEquals(SearchChannelType.FULL_TEXT, result.getChannelType());
        List<RetrievedChunk> chunks = result.getChunks();
        assertEquals(List.of("c2", "c1"), chunks.stream().map(RetrievedChunk::getId).toList());
        assertEquals("doc-2", chunks.get(0).getDocId());
        assertEquals("kq", chunks.get(0).getCollectionName());
        assertTrue(chunks.get(0).getScore() > chunks.get(1).getScore());
        Map<?, ?> bm25 = (Map<?, ?>) result.getMetadata().get(PgFullTextSearchChannel.BM25_SCORES);
        assertEquals(chunks.get(0).getScore(), bm25.get("c2"));
    }

    @Test
    void emptyScopeOrNoUsableTermsSendNoSql() {
        FullTextTokenizer tokenizer = mock(FullTextTokenizer.class);
        when(tokenizer.queryTerms(anyString())).thenReturn(List.of());
        FullTextStore store = mock(FullTextStore.class);

        assertTrue(channel(tokenizer, store).search(context("什么？", List.of("kq"), 20)).getChunks().isEmpty());
        assertTrue(channel(tokenizer, store).search(context("焦硫酸钾", List.of(), 20)).getChunks().isEmpty());
        verify(store, never()).matches(anyList(), anyList(), anyString(), any(), anyInt());
    }

    private static PgFullTextSearchChannel channel(FullTextTokenizer tokenizer, FullTextStore store) {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getFullText().setEnabled(true);
        return new PgFullTextSearchChannel(properties, tokenizer, store);
    }

    private static SearchContext context(String question, List<String> collections, int recall) {
        return SearchContext.builder().originalQuestion(question).rewrittenQuestion(question)
                .budget(new RetrievalBudget(recall, 40, 10)).retrievalScope(RetrievalScope.of(collections)).build();
    }
}
