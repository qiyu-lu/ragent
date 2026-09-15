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

package com.nageoffer.ai.ragent.ironore.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagTaskKnowledgeTest {
    private final VectorRetrieverService vectors = mock(VectorRetrieverService.class);
    private final RerankService rerank = mock(RerankService.class);
    private JdbcTemplate jdbc;
    private TaskAgentTestDatabase database;
    private RagTaskKnowledge knowledge;
    private Document document;

    @BeforeEach
    void setup() {
        database = new TaskAgentTestDatabase();
        jdbc = new JdbcTemplate(database.source);
        jdbc.execute("CREATE TABLE t_knowledge_base(id VARCHAR PRIMARY KEY,collection_name VARCHAR,deleted INT)");
        jdbc.execute("CREATE TABLE t_knowledge_document(id VARCHAR PRIMARY KEY,kb_id VARCHAR,doc_name VARCHAR,document_version VARCHAR,enabled INT,deleted INT,status VARCHAR)");
        jdbc.execute("CREATE TABLE t_knowledge_chunk(id VARCHAR PRIMARY KEY,doc_id VARCHAR,content TEXT,embedding_text TEXT,metadata TEXT,enabled INT,deleted INT)");
        jdbc.update("INSERT INTO t_knowledge_base VALUES ('kb','procedures',0)");
        jdbc.update("INSERT INTO t_knowledge_document VALUES ('doc','kb','送检规程','V1.0',1,0,'success'),('other','kb','其他规程','V2.0',1,0,'success'),('pending','kb','处理中规程',NULL,1,0,'running')");
        jdbc.update("INSERT INTO t_knowledge_chunk VALUES ('e1','doc','当前内容：核对标签','标签核对要求','{\"sheet_name\":\"送检\",\"cell_range\":\"B2:C3\"}',1,0),('foreign','other','其他规程内容','其他要求',NULL,1,0),('disabled','doc','已停用内容',NULL,NULL,0,0)");
        knowledge = new RagTaskKnowledge(jdbc, vectors, rerank, new ObjectMapper());
        document = knowledge.document("doc");
    }

    @AfterEach
    void closeDatabase() { database.close(); }

    @Test
    void onlyListsAvailableDocumentsFromExistingKnowledgeBase() {
        assertEquals(2, knowledge.documents().size());
        assertThrows(ClientException.class, () -> knowledge.document("pending"));
        jdbc.update("UPDATE t_knowledge_base SET deleted=1 WHERE id='kb'");
        assertTrue(knowledge.documents().isEmpty());
        assertThrows(ClientException.class, () -> knowledge.document("doc"));
    }

    @Test
    void filtersForeignAndDisabledHitsAndReranksCurrentSourceContent() {
        when(vectors.retrieve(any())).thenReturn(List.of(hit("foreign"), hit("disabled"), hit("e1")));
        when(rerank.rerank(eq("标签"), anyList(), eq(5))).thenAnswer(call -> call.getArgument(1));
        List<Evidence> evidence = knowledge.search(document, "标签");
        ArgumentCaptor<RetrieveRequest> request = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(vectors).retrieve(request.capture());
        assertEquals("doc", request.getValue().getMetadataFilters().get("doc_id"));
        assertEquals("procedures", request.getValue().getCollectionName());
        assertEquals(20, request.getValue().getTopK());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RetrievedChunk>> candidates = ArgumentCaptor.forClass(List.class);
        verify(rerank).rerank(eq("标签"), candidates.capture(), eq(5));
        assertEquals(1, candidates.getValue().size());
        assertEquals("送检规程\n标签核对要求", candidates.getValue().get(0).getRankingText());
        assertEquals("当前内容：核对标签", evidence.get(0).text());
        assertEquals("B2:C3", evidence.get(0).cellRange());
        assertDoesNotThrow(() -> knowledge.validate(document, evidence));
    }

    @Test
    void changedContentOrCellLocationInvalidatesApprovalEvidence() {
        List<Evidence> original = search();
        jdbc.update("UPDATE t_knowledge_chunk SET content='内容修订' WHERE id='e1'");
        assertThrows(ClientException.class, () -> knowledge.validate(document, original));
        List<Evidence> revised = search();
        jdbc.update("UPDATE t_knowledge_chunk SET metadata='{\"sheet_name\":\"送检\",\"cell_range\":\"D4:E5\"}' WHERE id='e1'");
        assertThrows(ClientException.class, () -> knowledge.validate(document, revised));
    }

    @Test
    void changedVersionOrDisabledSourceCannotBeReused() {
        List<Evidence> evidence = search();
        jdbc.update("UPDATE t_knowledge_document SET document_version='V2.0' WHERE id='doc'");
        assertThrows(ClientException.class, () -> knowledge.search(document, "送检"));
        assertThrows(ClientException.class, () -> knowledge.validate(document, evidence));
        jdbc.update("UPDATE t_knowledge_document SET document_version='V1.0',enabled=0 WHERE id='doc'");
        assertThrows(ClientException.class, () -> knowledge.validate(document, evidence));
    }

    @Test
    void removedChunkAndForgedCrossDocumentEvidenceAreRejected() {
        Evidence evidence = search().get(0);
        Evidence forged = new Evidence(evidence.id(), "other", "V2.0", evidence.sheetName(), evidence.cellRange(), evidence.text(), evidence.contentHash());
        assertThrows(ClientException.class, () -> knowledge.validate(document, List.of(forged)));
        jdbc.update("UPDATE t_knowledge_chunk SET deleted=1 WHERE id='e1'");
        assertThrows(ClientException.class, () -> knowledge.validate(document, List.of(evidence)));
    }

    @Test
    void doesNotCallRerankerWhenNoCurrentEvidenceSurvives() {
        when(vectors.retrieve(any())).thenReturn(List.of(hit("foreign"), hit("disabled")));
        assertTrue(knowledge.search(document, "送检").isEmpty());
        verifyNoInteractions(rerank);
    }

    private List<Evidence> search() {
        when(vectors.retrieve(any())).thenReturn(List.of(hit("e1")));
        when(rerank.rerank(anyString(), anyList(), eq(5))).thenAnswer(call -> call.getArgument(1));
        return knowledge.search(document, "送检");
    }

    private RetrievedChunk hit(String id) { return RetrievedChunk.builder().id(id).text("过期的向量索引内容").score(0.9F).build(); }
}
