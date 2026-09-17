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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MilvusVectorRetrieverServiceTest {
    @Test
    void documentsAreFilteredInsideSearchRequestWithEscapedValues() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        SearchResp response = mock(SearchResp.class);
        when(response.getSearchResults()).thenReturn(List.of());
        when(client.search(any(SearchReq.class))).thenReturn(response);
        new MilvusVectorRetrieverService(mock(EmbeddingService.class), client, properties())
                .retrieveByVector(new float[]{1, 0}, RetrieveRequest.builder().collectionName("kb-a")
                        .documentIds(List.of("doc-\"quoted\"\\path", "doc-b")).topK(2).build());
        ArgumentCaptor<SearchReq> request = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(request.capture());
        assertTrue(request.getValue().getFilter().contains("collection_name == \"kb-a\""));
        assertTrue(request.getValue().getFilter().contains("metadata[\"doc_id\"] in ["));
        assertTrue(request.getValue().getFilter().contains("doc-\\\"quoted\\\"\\\\path"));
        assertEquals(2, request.getValue().getTopK());
    }

    @Test
    void legacyDocumentFilterAndBothSdkMetadataRepresentationsAreSupported() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        SearchResp response = mock(SearchResp.class);
        SearchResp.SearchResult mapHit = mock(SearchResp.SearchResult.class);
        SearchResp.SearchResult jsonHit = mock(SearchResp.SearchResult.class);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("doc_id", "doc-a");
        when(mapHit.getEntity()).thenReturn(Map.of("id", "map", "content", "body",
                "collection_name", "kb-a", "metadata", Map.of("doc_id", "doc-a")));
        when(jsonHit.getEntity()).thenReturn(Map.of("id", "json", "content", "body",
                "collection_name", "kb-a", "metadata", metadata));
        when(response.getSearchResults()).thenReturn(List.of(List.of(mapHit, jsonHit)));
        when(client.search(any(SearchReq.class))).thenReturn(response);
        var hits = new MilvusVectorRetrieverService(mock(EmbeddingService.class), client, properties())
                .retrieveByVector(new float[]{1, 0}, RetrieveRequest.builder().collectionName("kb-a")
                        .metadataFilters(Map.of("doc_id", "doc-a")).topK(2).build());
        assertEquals(List.of("doc-a", "doc-a"), hits.stream().map(hit -> hit.getDocId()).toList());
        ArgumentCaptor<SearchReq> request = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(request.capture());
        assertTrue(request.getValue().getFilter().contains("metadata[\"doc_id\"] in [\"doc-a\"]"));
    }

    private RAGDefaultProperties properties() {
        var properties = new RAGDefaultProperties();
        properties.setCollectionName("shared");
        properties.setMetricType("COSINE");
        return properties;
    }
}
