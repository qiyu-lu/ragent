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

package com.nageoffer.ai.ragent.rag.core.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EsKeywordRetrieverServiceTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void documentTermsAreSentBeforeSearchSizeIsApplied() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse response = new SearchResponse.Builder<>()
                .took(0).timedOut(false).shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(List.of())).build();
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);
        new EsKeywordRetrieverService(client, new KeywordProperties())
                .search("query", List.of("kb-a"), 3, List.of("doc-a", "doc-b"));
        ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captured.capture(), any(Class.class));
        var request = captured.getValue();
        assertEquals(3, request.size());
        assertEquals(List.of("collection_name", "doc_id"),
                request.query().bool().filter().stream().map(filter -> filter.terms().field()).toList());
        assertEquals(List.of("doc-a", "doc-b"), request.query().bool().filter().get(1)
                .terms().terms().value().stream().map(value -> value.stringValue()).toList());
    }

    @Test
    void emptyCollectionScopeNeverQueriesTheWholeIndex() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        assertTrue(new EsKeywordRetrieverService(client, new KeywordProperties()).search("query", List.of(), 3).isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void unsupportedKeywordBackendCannotSilentlyIgnoreDocumentScope() {
        KeywordRetrieverService backend = (query, collections, count) -> List.of();
        assertThrows(UnsupportedOperationException.class,
                () -> backend.search("query", List.of("kb-a"), 3, List.of("doc-a")));
    }
}
