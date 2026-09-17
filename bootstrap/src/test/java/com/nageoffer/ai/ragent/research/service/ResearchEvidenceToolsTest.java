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

package com.nageoffer.ai.ragent.research.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.retrieval.KnowledgeRetrievalResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.EvidenceSnapshot;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.SourceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchEvidenceToolsTest {
    private final KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final ResearchEvidenceStore store = mock(ResearchEvidenceStore.class);
    private final MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
    private final Map<String, KnowledgeChunkDO> chunkRows = new HashMap<>();
    private final Map<String, KnowledgeDocumentDO> documentRows = new HashMap<>();
    private final Map<String, EvidenceSnapshot> snapshots = new HashMap<>();
    private KnowledgeSearchService search;
    private SourceReader reader;

    @BeforeEach
    void setUp() {
        var kb = KnowledgeBaseDO.builder().id("kb-a").collectionName("collection-a").deleted(0).build();
        when(bases.selectById("kb-a")).thenReturn(kb);
        when(bases.selectList(any())).thenReturn(List.of(kb));
        when(chunks.selectById(anyString())).thenAnswer(call -> chunkRows.get(call.getArgument(0)));
        when(documents.selectById(anyString())).thenAnswer(call -> documentRows.get(call.getArgument(0)));
        when(store.requireBrief(anyString(), eq("owner"))).thenReturn(
                new ResearchBrief("compare", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb-a")));
        when(store.save(eq("owner"), any())).thenAnswer(call -> {
            EvidenceSnapshot snapshot = call.getArgument(1);
            snapshots.putIfAbsent(snapshot.evidence().evidenceId(), snapshot);
            return snapshots.get(snapshot.evidence().evidenceId());
        });
        when(store.find(anyString(), eq("owner"), anyString())).thenAnswer(call -> {
            EvidenceSnapshot snapshot = snapshots.get(call.getArgument(2));
            if (snapshot == null || !snapshot.evidence().runId().equals(call.getArgument(0))) {
                throw new ClientException("证据不存在或不属于本次研究任务");
            }
            return snapshot;
        });
        when(store.markRead(eq("owner"), any())).thenAnswer(call -> {
            EvidenceSnapshot snapshot = call.getArgument(1);
            String text = EvidenceText.preview(snapshot.sourceText(), EvidenceText.READ_MAX_CHARS);
            var read = snapshot.evidence().withReadText(text, text.length() < snapshot.sourceText().length());
            snapshots.put(read.evidenceId(), new EvidenceSnapshot(read, snapshot.sourceText(),
                    snapshot.sourceMetadataHash()));
            return read;
        });
        var catalog = new ResearchSourceCatalog(chunks, documents, bases, new ObjectMapper());
        search = new KnowledgeSearchService(engine, bases, catalog, store, new ObjectMapper());
        reader = new SourceReader(store, catalog);
        addSource("chunk-a", "doc-a", "V1", "old body with 5 mg", "{}");
        returnCandidates("chunk-a");
    }

    @Test
    void serverScopeIsPassedBeforeRecallAndMultipleDocumentsUseStoredBody() {
        addSource("chunk-b", "doc-b", "V2", "second document", "{}");
        returnCandidates("chunk-a", "chunk-b");
        var hits = search.search("run-a", "owner", "main", "compare", null, 10);
        assertEquals(List.of("doc-a", "doc-b"), hits.stream().map(hit -> hit.docId()).toList());
        assertEquals("old body with 5 mg", hits.get(0).text(), "不能使用索引内的伪摘录");
        verify(engine).retrieveScopedKnowledgeChannels(eq("compare"), any(RetrievalBudget.class),
                eq(List.of("collection-a")));
        assertFalse(snapshots.get(hits.get(0).evidenceId()).evidence().read());
    }

    @Test
    void modelCannotWidenScope() {
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", List.of("kb-other"), 10));
        verifyNoInteractions(engine);
        verify(bases, never()).selectList(any());
    }

    @Test
    void removedScopeDoesNotFallBackToGlobalRecall() {
        when(bases.selectList(any())).thenReturn(List.of());
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, 10));
        verifyNoInteractions(engine);
    }

    @Test
    void outOfScopeIndexResultCannotBecomeEvidence() {
        when(engine.retrieveScopedKnowledgeChannels(anyString(), any(), anyList()))
                .thenReturn(new KnowledgeRetrievalResult(List.of(
                        RetrievedChunk.builder().id("chunk-a").collectionName("other").build()), Map.of(), java.util.Set.of()));
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, 10));
        verify(store, never()).save(anyString(), any());
    }

    @Test
    void evidenceIdentityIsSharedByWorkersAndSeparatesRunsVersionsAndContent() {
        String first = search("run-a", "worker-a");
        assertEquals(first, search("run-a", "worker-b"));
        assertEquals("worker-a", snapshots.get(first).evidence().retrievedByTaskId());
        assertNotEquals(first, search("run-b", "worker-a"));
        documentRows.get("doc-a").setDocumentVersion("V2");
        String secondVersion = search("run-a", "worker-a");
        assertNotEquals(first, secondVersion);
        setContent("chunk-a", "new body with 6 mg");
        assertNotEquals(secondVersion, search("run-a", "worker-a"));
    }

    @Test
    void identicalTextAtDifferentSourceBlocksDoesNotMerge() {
        addSource("chunk-b", "doc-a", "V1", "old body with 5 mg", "{}");
        returnCandidates("chunk-a", "chunk-b");
        var hits = search.search("run-a", "owner", "main", "query", null, 10);
        assertEquals(2, hits.size());
        assertNotEquals(hits.get(0).evidenceId(), hits.get(1).evidenceId());
    }

    @Test
    void readExpandsPinnedSnapshotAndMarksActualRead() {
        setContent("chunk-a", "x".repeat(2000));
        String id = search("run-a", "main");
        assertEquals(1024, snapshots.get(id).evidence().text().length());
        assertTrue(snapshots.get(id).evidence().truncated());
        var result = reader.read("run-a", "owner", id);
        assertEquals(SourceState.CURRENT, result.sourceState());
        assertEquals(2000, result.evidence().text().length());
        assertTrue(result.evidence().read());
        assertFalse(result.evidence().truncated());
        assertEquals(result, reader.read("run-a", "owner", id));
    }

    @Test
    void sourceChangeIsReportedWithoutMixingNewTextOrVersion() {
        String id = search("run-a", "main");
        documentRows.get("doc-a").setDocumentVersion("V2");
        setContent("chunk-a", "new body with 6 mg");
        var result = reader.read("run-a", "owner", id);
        assertEquals(SourceState.CHANGED, result.sourceState());
        assertEquals("old body with 5 mg", result.evidence().text());
        assertEquals("V1", result.evidence().documentVersion());
    }

    @Test
    void disabledDocumentCannotBeReadFromSnapshot() {
        String id = search("run-a", "main");
        documentRows.get("doc-a").setEnabled(0);
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", id));
        verify(store, never()).markRead(anyString(), any());
    }

    @Test
    void missingAndForeignRunEvidenceAreRejected() {
        String id = search("run-a", "main");
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", "ev-fictional"));
        assertThrows(ClientException.class, () -> reader.read("run-b", "owner", id));
        verify(store, never()).markRead(anyString(), any());
    }

    @Test
    void staleIndexAndCorruptSourceHashAreExplicitErrors() {
        chunkRows.remove("chunk-a");
        assertThrows(ClientException.class, () -> search("run-a", "main"));
        addSource("chunk-a", "doc-a", "V1", "body", "{}");
        chunkRows.get("chunk-a").setContentHash("fictional-hash");
        assertThrows(ClientException.class, () -> search("run-a", "main"));
    }

    @Test
    void corruptSnapshotIsNotPublished() {
        String id = search("run-a", "main");
        var saved = snapshots.get(id);
        snapshots.put(id, new EvidenceSnapshot(saved.evidence(), "corrupt body", saved.sourceMetadataHash()));
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", id));
        verify(store, never()).markRead(anyString(), any());
    }

    @Test
    void longBlockRemainsExplicitlyTruncatedAfterRead() {
        setContent("chunk-a", "x".repeat(15999) + "😀" + "tail");
        var result = reader.read("run-a", "owner", search("run-a", "main"));
        assertEquals(15999, result.evidence().text().length(), "不能截断 UTF-16 代理对");
        assertTrue(result.evidence().truncated());
    }

    @Test
    void excerptAndRealLocationArePreservedWithoutInventedSections() {
        chunkRows.get("chunk-a").setMetadata(
                "{\"dataset\":\"musique\",\"source_paragraph_id\":\"p-7\",\"sheet_name\":\"Sheet A\",\"cell_range\":\"B2:C3\"}");
        var result = reader.read("run-a", "owner", search("run-a", "main"));
        assertEquals(EvidenceRecord.SourceExtent.AVAILABLE_EXCERPT, result.evidence().sourceExtent());
        assertEquals("p-7", result.evidence().sourceLocation().get("source_paragraph_id"));
        assertEquals("B2:C3", result.evidence().sourceLocation().get("cell_range"));
        assertFalse(result.evidence().sourceLocation().containsKey("section_path"));
        verify(chunks, times(2)).selectById("chunk-a");
        verify(chunks, never()).selectList(any());
    }

    @Test
    void invalidMetadataAndInvalidToolArgumentsFailClearly() {
        chunkRows.get("chunk-a").setMetadata("null");
        assertThrows(ClientException.class, () -> search("run-a", "main"));
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, 0));
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, 21));
    }

    private String search(String runId, String taskId) {
        return search.search(runId, "owner", taskId, "query", null, 10).get(0).evidenceId();
    }

    private void addSource(String chunkId, String docId, String version, String content, String metadata) {
        documentRows.put(docId, KnowledgeDocumentDO.builder().id(docId).kbId("kb-a")
                .docName(docId + ".md").documentVersion(version).enabled(1).deleted(0).build());
        chunkRows.put(chunkId, KnowledgeChunkDO.builder().id(chunkId).kbId("kb-a").docId(docId)
                .chunkIndex(chunkRows.size()).content(content).contentHash(SecureUtil.sha256(content))
                .metadata(metadata).enabled(1).deleted(0).build());
    }

    private void setContent(String chunkId, String text) {
        chunkRows.get(chunkId).setContent(text);
        chunkRows.get(chunkId).setContentHash(SecureUtil.sha256(text));
    }

    private void returnCandidates(String... ids) {
        var results = java.util.Arrays.stream(ids).map(id -> RetrievedChunk.builder().id(id)
                .text("fictional index excerpt").collectionName("collection-a").build()).toList();
        when(engine.retrieveScopedKnowledgeChannels(anyString(), any(), anyList()))
                .thenReturn(new KnowledgeRetrievalResult(results, Map.of(), java.util.Set.of()));
    }
}
