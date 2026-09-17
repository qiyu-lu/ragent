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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorRetrieverServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void multipleDocumentsAreBoundBeforeOrderAndLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        new PgVectorRetrieverService(jdbc, mock(EmbeddingService.class)).retrieveByVector(new float[]{1, 0},
                RetrieveRequest.builder().collectionNames(List.of("kb-a", "kb-b"))
                        .documentIds(List.of("doc-'a", "doc-b")).topK(3).build());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("metadata->>'doc_id' IN (?, ?)"));
        assertTrue(sql.getValue().indexOf("metadata->>'doc_id' IN") < sql.getValue().indexOf("ORDER BY"));
        assertFalse(sql.getValue().contains("doc-'a"));
        assertEquals("doc-'a", args.getValue()[3]);
        assertEquals("doc-b", args.getValue()[4]);
        assertEquals(3, args.getValue()[6]);
    }

    @Test
    @DisplayName("限定规程在召回LIMIT之前过滤且文档ID仅通过参数传入")
    @SuppressWarnings("unchecked")
    void bindsSelectedDocumentFilterBeforeLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed("送检要求")).thenReturn(List.of(3.0F, 4.0F));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        String documentId = "doc-'quoted'";

        new PgVectorRetrieverService(jdbc, embeddings).retrieve(RetrieveRequest.builder()
                .query("送检要求").collectionName("procedures").topK(20)
                .metadataFilters(Map.of("doc_id", documentId)).build());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("metadata->>'doc_id' = ?"));
        assertTrue(sql.getValue().indexOf("metadata->>'doc_id'") < sql.getValue().indexOf("ORDER BY"));
        assertFalse(sql.getValue().contains(documentId));
        assertEquals("procedures", args.getValue()[1]);
        assertEquals(documentId, args.getValue()[2]);
        assertEquals(20, args.getValue()[4]);
    }

    @Test
    @DisplayName("多Collection使用单条IN查询并只携带一个总LIMIT")
    @SuppressWarnings("unchecked")
    void queryMultipleCollectionsWithOneSharedLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("报销流程")).thenReturn(List.of(3.0F, 4.0F));
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenReturn(List.<RetrievedChunk>of());

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        service.retrieve(RetrieveRequest.builder()
                .query("报销流程")
                .collectionNames(List.of("kb-finance", "kb-policy"))
                .topK(7)
                .build());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                argsCaptor.capture()
        );

        assertTrue(sqlCaptor.getValue().contains("collection_name IN (?, ?)"));
        Object[] args = argsCaptor.getValue();
        assertEquals("kb-finance", args[1]);
        assertEquals("kb-policy", args[2]);
        assertEquals(7, args[4], "SQL 只能有一个跨 Collection 共享的 LIMIT");
        verify(embeddingService, times(1)).embed("报销流程");
    }
}
