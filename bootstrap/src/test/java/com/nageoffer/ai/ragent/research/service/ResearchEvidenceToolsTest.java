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
import java.util.Optional;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.ReadMode;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.ExpansionState;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;

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
    private final Map<String, String> expansions = new HashMap<>();
    private KnowledgeSearchService search;
    private SourceReader reader;

    @BeforeEach
    void setUp() {
        var kb = KnowledgeBaseDO.builder().id("kb-a").collectionName("collection-a").deleted(0).build();
        when(bases.selectById("kb-a")).thenReturn(kb);
        when(bases.selectList(any())).thenReturn(List.of(kb));
        when(chunks.selectById(anyString())).thenAnswer(call -> chunkRows.get(call.getArgument(0)));
        when(documents.selectById(anyString())).thenAnswer(call -> documentRows.get(call.getArgument(0)));
        when(documents.selectList(any())).thenAnswer(call -> List.copyOf(documentRows.values()));
        when(chunks.selectList(any())).thenAnswer(call -> chunkRows.values().stream()
                .filter(row -> row.getId().startsWith("neighbor-")).toList());
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
        when(store.findExpansion(anyString(), eq("owner"), anyString())).thenAnswer(call ->
                Optional.ofNullable(expansions.get(call.getArgument(2))).map(snapshots::get));
        when(store.saveExpansion(eq("owner"), anyString(), any())).thenAnswer(call -> {
            EvidenceSnapshot snapshot = call.getArgument(2);
            String origin = call.getArgument(1);
            expansions.putIfAbsent(origin, snapshot.evidence().evidenceId());
            snapshots.putIfAbsent(snapshot.evidence().evidenceId(), snapshot);
            return snapshots.get(expansions.get(origin));
        });
        var catalog = new ResearchSourceCatalog(chunks, documents, bases, new ObjectMapper());
        search = new KnowledgeSearchService(engine, bases, documents, catalog, store, new ObjectMapper());
        reader = new SourceReader(store, catalog, new EvidenceSnapshotFactory(new ObjectMapper()));
        addSource("chunk-a", "doc-a", "V1", "old body with 5 mg", "{}");
        returnCandidates("chunk-a");
    }

    @Test
    void workerScopeIsValidatedBeforeAdmissionAndCannotMarkAnotherDocumentRead() {
        assertEquals(List.of("doc-a"), search.validateDocumentScope("run-a", "owner", List.of("doc-a")));
        assertThrows(ClientException.class, () -> search.validateDocumentScope("run-a", "owner", List.of("missing")));
        String id = search("run-a", "worker-other");
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", id, ReadMode.CHUNK, List.of("doc-b")));
        assertFalse(snapshots.get(id).evidence().read());
        verify(store, never()).markRead(anyString(), any());
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

    @Test
    void emptyToolDocumentParameterPreservesServerDocumentRestriction() {
        allowDocuments("doc-a");
        returnDocumentCandidates("chunk-a");
        var hits = search.search("run-a", "owner", "main", "query", null, List.of(), 10);
        assertEquals("doc-a", hits.get(0).docId());
        verify(engine).retrieveScopedKnowledgeChannels(eq("query"), any(), eq(List.of("collection-a")),
                eq(List.of("doc-a")));
        verify(engine, never()).retrieveScopedKnowledgeChannels(anyString(), any(), anyList());
    }

    @Test
    void documentSelectionCannotWidenSavedScope() {
        allowDocuments("doc-a");
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, List.of("doc-other"), 10));
        verifyNoInteractions(engine);
    }

    @Test
    void selectedDocumentMustExistAndBelongToSelectedKnowledgeBase() {
        addSource("chunk-b", "doc-b", "V1", "body", "{}");
        documentRows.get("doc-b").setKbId("kb-other");
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, List.of("doc-b"), 10));
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, List.of("missing"), 10));
        verifyNoInteractions(engine);
    }

    @Test
    void staleIndexCannotReturnDocumentOutsideResolvedScope() {
        addSource("chunk-b", "doc-b", "V1", "other body", "{}");
        allowDocuments("doc-a");
        returnDocumentCandidates("chunk-b");
        assertThrows(ClientException.class, () ->
                search.search("run-a", "owner", "main", "query", null, List.of(), 10));
        verify(store, never()).save(anyString(), any());
    }

    @Test
    void previouslySavedEvidenceCannotBypassCurrentDocumentScope() {
        String id = search("run-a", "main");
        allowDocuments("doc-other");
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", id));
        verify(store, never()).markRead(anyString(), any());
    }

    @Test
    void neighborsUseStoredChapterAndPreservePerBlockLocations() throws Exception {
        // authoritative parser outline overrides conflicting extras before storage.
        String metadata = new ObjectMapper().writeValueAsString(new ChunkMetadata(List.of("Methods"),
                List.of(), null, "text", Map.of("section_path", List.of("Fake"))).toMap());
        neighboringSources(metadata, metadata, metadata);
        String id = search("run-a", "main");
        var result = reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        assertEquals(ExpansionState.NEIGHBORS, result.expansionState());
        assertEquals(List.of("neighbor-before", "chunk-a", "neighbor-after"), result.evidence().chunkIds());
        assertEquals("before\n\nold body with 5 mg\n\nafter", result.evidence().text());
        assertNotEquals(id, result.evidence().evidenceId());
        assertEquals(id, result.requestedEvidenceId());
        assertTrue(result.evidence().read());
        var locations = (List<?>) result.evidence().sourceLocation().get("chunks");
        assertEquals(List.of("Methods"), ((Map<?, ?>) locations.get(0)).get("section_path"));
        assertEquals(3, locations.size());
    }

    @Test
    void repeatedNeighborReadReusesFirstSnapshotAndMarksChangedNeighbor() {
        String metadata = "{\"section_path\":[\"Methods\"]}";
        neighboringSources(metadata, metadata, metadata);
        String id = search("run-a", "main");
        var first = reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        setContent("neighbor-after", "new neighbor content");
        var second = reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        assertEquals(first.evidence(), second.evidence());
        assertEquals(SourceState.CHANGED, second.sourceState());
        verify(store, times(1)).saveExpansion(anyString(), anyString(), any());
    }

    @Test
    void missingChapterMetadataFallsBackToPinnedSingleBlock() {
        String id = search("run-a", "main");
        var result = reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        assertEquals(ExpansionState.BLOCK_ONLY, result.expansionState());
        assertEquals(id, result.evidence().evidenceId());
        assertNotNull(result.note());
        verify(chunks, never()).selectList(any());
    }

    @Test
    void neighborsNeverCrossSectionsOrSheets() {
        neighboringSources("{\"section_path\":[\"Methods\"],\"sheet_name\":\"A\"}",
                "{\"section_path\":[\"Intro\"],\"sheet_name\":\"A\"}",
                "{\"section_path\":[\"Methods\"],\"sheet_name\":\"B\"}");
        var result = reader.read("run-a", "owner", search("run-a", "main"), ReadMode.NEIGHBORS);
        assertEquals(ExpansionState.BLOCK_ONLY, result.expansionState());
        assertEquals(List.of("chunk-a"), result.evidence().chunkIds());
    }

    @Test
    void availableExcerptCanExpandWithinButNotAcrossOriginalParagraphs() {
        String seed = "{\"dataset\":\"musique\",\"source_paragraph_id\":\"p-7\"}";
        neighboringSources(seed, seed, "{\"dataset\":\"musique\",\"source_paragraph_id\":\"p-8\"}");
        var result = reader.read("run-a", "owner", search("run-a", "main"), ReadMode.NEIGHBORS);
        assertEquals(List.of("neighbor-before", "chunk-a"), result.evidence().chunkIds());
        assertEquals(EvidenceRecord.SourceExtent.AVAILABLE_EXCERPT, result.evidence().sourceExtent());
    }

    @Test
    void changedSeedCannotBeCombinedWithCurrentNeighbors() {
        String metadata = "{\"section_path\":[\"Methods\"]}";
        neighboringSources(metadata, metadata, metadata);
        String id = search("run-a", "main");
        documentRows.get("doc-a").setDocumentVersion("V2");
        var result = reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        assertEquals(SourceState.CHANGED, result.sourceState());
        assertEquals(ExpansionState.BLOCK_ONLY, result.expansionState());
        assertEquals("V1", result.evidence().documentVersion());
        verify(store, never()).saveExpansion(anyString(), anyString(), any());
    }

    @Test
    void disabledPinnedNeighborRevokesExpandedRead() {
        String metadata = "{\"section_path\":[\"Methods\"]}";
        neighboringSources(metadata, metadata, metadata);
        String id = search("run-a", "main");
        reader.read("run-a", "owner", id, ReadMode.NEIGHBORS);
        chunkRows.get("neighbor-before").setEnabled(0);
        clearInvocations(store);
        assertThrows(ClientException.class, () -> reader.read("run-a", "owner", id, ReadMode.NEIGHBORS));
        verify(store, never()).markRead(anyString(), any());
    }

    @Test
    void mismatchedChunkVersionCannotEnterNeighborSnapshot() {
        String metadata = "{\"section_path\":[\"Methods\"]}";
        neighboringSources(metadata, metadata, "{\"section_path\":[\"Methods\"],\"document_version\":\"V0\"}");
        assertThrows(ClientException.class, () ->
                reader.read("run-a", "owner", search("run-a", "main"), ReadMode.NEIGHBORS));
        verify(store, never()).saveExpansion(anyString(), anyString(), any());
    }

    private void allowDocuments(String... ids) {
        when(store.requireBrief(anyString(), eq("owner"))).thenReturn(
                new ResearchBrief("compare", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb-a"), List.of(ids)));
    }

    private void returnDocumentCandidates(String... ids) {
        var results = java.util.Arrays.stream(ids).map(id -> RetrievedChunk.builder().id(id)
                .collectionName("collection-a").docId(chunkRows.get(id).getDocId()).build()).toList();
        when(engine.retrieveScopedKnowledgeChannels(anyString(), any(), anyList(), anyList()))
                .thenReturn(new KnowledgeRetrievalResult(results, Map.of(), java.util.Set.of()));
    }

    private void neighboringSources(String seed, String before, String after) {
        chunkRows.get("chunk-a").setChunkIndex(1);
        chunkRows.get("chunk-a").setMetadata(seed);
        addSource("neighbor-before", "doc-a", "V1", "before", before);
        chunkRows.get("neighbor-before").setChunkIndex(0);
        addSource("neighbor-after", "doc-a", "V1", "after", after);
        chunkRows.get("neighbor-after").setChunkIndex(2);
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
