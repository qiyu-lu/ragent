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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FullTextIndexerTest {

    private static FullTextIndexer indexer(FullTextStore store, FullTextTokenizer tokenizer, boolean enabled) {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getFullText().setEnabled(enabled);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new FullTextIndexer(store, tokenizer, properties, transactions);
    }

    @Test
    void indexTextIsTheDocumentTitleWithoutExtensionPlusTheEmbeddingText() {
        assertEquals("铁矿石+硅含量的测定+重量法\n## 7.4.1.1 碱熔法\n正文",
                FullTextIndexer.indexText("铁矿石+硅含量的测定+重量法.pdf", "## 7.4.1.1 碱熔法\n正文"));
        assertEquals("正文", FullTextIndexer.indexText(null, "正文"));
    }

    @Test
    void writesOneTsvectorLiteralPerChunk() {
        FullTextStore store = mock(FullTextStore.class);
        when(store.sourcesOfDocument("doc-1")).thenReturn(List.of(new FullTextStore.IndexSource("c1", "全铁.pdf", "TFe 含量")));
        FullTextTokenizer tokenizer = mock(FullTextTokenizer.class);
        when(tokenizer.tokenize("全铁\nTFe 含量")).thenReturn(List.of(
                new FullTextTokenizer.Token("全铁", 1), new FullTextTokenizer.Token("tfe", 2),
                new FullTextTokenizer.Token("全铁", 2), new FullTextTokenizer.Token("含量", 3)));

        assertEquals(1, indexer(store, tokenizer, true).indexDocument("doc-1"));

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(store).writeTsvectors(captor.capture());
        assertEquals(Map.of("c1", "'全铁':1,2 'tfe':2 '含量':3"), captor.getValue());
    }

    @Test
    void refreshesStatisticsImmediatelyOutsideATransaction() {
        FullTextStore store = mock(FullTextStore.class);
        indexer(store, mock(FullTextTokenizer.class), true).refreshStatsAfterCommit("kb-1");
        verify(store).refreshStats("kb-1");
    }

    @Test
    void rebuildWalksEveryDocumentThenRefreshesTheKnowledgeBase() {
        FullTextStore store = mock(FullTextStore.class);
        when(store.liveKbIds()).thenReturn(List.of("kb-1"));
        when(store.documentIdsOfKb("kb-1")).thenReturn(List.of("doc-1", "doc-2"));
        when(store.sourcesOfDocument(any())).thenReturn(List.of(new FullTextStore.IndexSource("c", "d.pdf", "x")));
        FullTextTokenizer tokenizer = mock(FullTextTokenizer.class);
        when(tokenizer.tokenize(any())).thenReturn(List.of(new FullTextTokenizer.Token("x", 1)));

        FullTextIndexer.RebuildSummary summary = indexer(store, tokenizer, false).rebuild(null);

        assertEquals(1, summary.knowledgeBases());
        assertEquals(2, summary.documents());
        assertEquals(2, summary.chunks());
        verify(store).refreshStats("kb-1");
    }

    @Test
    void sinkDoesNothingWhileTheChannelIsOff() {
        FullTextStore store = mock(FullTextStore.class);
        FullTextChunkSink sink = new FullTextChunkSink(indexer(store, mock(FullTextTokenizer.class), false));
        VectorTarget target = new VectorTarget("kq", "model", 8);
        sink.replaceDocument(target, new DocumentRef("doc-1", "kb-1", "a.pdf"), List.of());
        sink.deleteDocument(target, new DocumentRef("doc-1", "kb-1", "a.pdf"));
        verifyNoInteractions(store);
    }

    @Test
    void sinkIndexesTheDocumentAndRefreshesItsKnowledgeBase() {
        FullTextStore store = mock(FullTextStore.class);
        when(store.sourcesOfDocument("doc-1")).thenReturn(List.of());
        FullTextChunkSink sink = new FullTextChunkSink(indexer(store, mock(FullTextTokenizer.class), true));

        sink.replaceDocument(new VectorTarget("kq", "model", 8), new DocumentRef("doc-1", "kb-1", "a.pdf"), List.of());

        verify(store).sourcesOfDocument("doc-1");
        verify(store).refreshStats("kb-1");
        verify(store, never()).refreshStats("kb-2");
    }
}
