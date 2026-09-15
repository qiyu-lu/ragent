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

package com.nageoffer.ai.ragent.rag.eval;

import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PooledEvalControllerTest {
    @Test
    void refusesBusinessDatabaseBeforeRetrievalOrIngestion() {
        var jdbc = mock(JdbcTemplate.class);
        var documents = mock(KnowledgeDocumentService.class);
        var retrieval = mock(RetrievalEngine.class);
        var controller = new PooledEvalController(jdbc, mock(QueryRewriteService.class), retrieval, documents);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("ragent");
        assertThrows(IllegalStateException.class, controller::checkIsolation);
        verifyNoInteractions(documents, retrieval);
    }

    @Test
    void refusesMixedKnowledgeBases() {
        var jdbc = mock(JdbcTemplate.class);
        var retrieval = mock(RetrievalEngine.class);
        var controller = new PooledEvalController(jdbc, mock(QueryRewriteService.class), retrieval,
                mock(KnowledgeDocumentService.class));
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("ragent_eval_pool_v1");
        when(jdbc.queryForList("SELECT collection_name FROM t_knowledge_base WHERE deleted=0", String.class))
                .thenReturn(List.of("cs_pool_v1", "business"));
        assertThrows(IllegalStateException.class, controller::checkIsolation);
        verifyNoInteractions(retrieval);
    }

    @Test
    void refusesVectorsOutsideThePublicCollection() {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new PooledEvalController(jdbc, mock(QueryRewriteService.class), mock(RetrievalEngine.class),
                mock(KnowledgeDocumentService.class));
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("ragent_eval_pool_v1");
        when(jdbc.queryForList("SELECT collection_name FROM t_knowledge_base WHERE deleted=0", String.class))
                .thenReturn(List.of("cs_pool_v1"));
        when(jdbc.queryForObject("SELECT count(*) FROM t_knowledge_vector WHERE collection_name <> ?",
                Integer.class, "cs_pool_v1")).thenReturn(1);
        assertThrows(IllegalStateException.class, controller::checkIsolation);
    }

    @Test
    void authenticationPrecedesDatabaseAccess() {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new PooledEvalController(jdbc, mock(QueryRewriteService.class), mock(RetrievalEngine.class),
                mock(KnowledgeDocumentService.class));
        // No authenticated web context: real Sa-Token must reject before any SQL/model operation.
        assertThrows(RuntimeException.class, () -> controller.ingest("1"));
        verifyNoInteractions(jdbc);
    }
}
