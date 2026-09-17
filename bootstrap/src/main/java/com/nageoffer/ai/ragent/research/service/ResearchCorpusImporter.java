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
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkContext;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.model.*;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.*;

/** Import already parsed public paragraphs through the existing chunk/embed/index components. */
@Service
@RequiredArgsConstructor
public class ResearchCorpusImporter {
    private static final Set<String> METADATA_KEYS = Set.of("dataset", "split", "document_version",
            "source_extent", "paper_id", "section_path", "section_index", "paragraph_index",
            "source_paragraph_id", "block_type", "source_field");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ParagraphChunker paragraphChunker;
    private final ChunkEmbeddingService embedding;
    private final ChunkIndexWriter writer;
    private final VectorTargetResolver targets;
    private final TransactionOperations transactions;

    public record Unit(String schema_version, String id, String dataset, String split, String document_id,
                       String title, String text, String content_hash, String source_extent,
                       Map<String, Object> metadata) { }
    public record Document(String sourceDocumentId, List<Unit> units) { }
    public record Mapping(String sourceDocumentId, String docId, String sourceId, String chunkId,
                          int chunkIndex, String contentHash) { }
    public record Result(int importedDocuments, int reusedDocuments, int embeddedChunks, List<Mapping> mappings) { }
    private record Pending(Document source, String hash, String docId, List<Chunk> chunks) { }

    public Result importBatch(KnowledgeBaseDO kb, List<Document> documents, ChunkBudget budget) {
        VectorTarget target = targets.resolve(kb);
        List<Pending> pending = new ArrayList<>();
        List<Mapping> mappings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int reused = 0;
        for (Document input : documents) {
            Document source = ordered(input);
            if (!seen.add(source.sourceDocumentId())) throw new IllegalArgumentException("duplicate source document");
            String hash = hash(source, budget, target);
            var existing = jdbc.queryForList("""
                    SELECT doc_id, import_hash FROM t_research_corpus_document
                    WHERE kb_id=? AND source_document_id=?
                    """, kb.getId(), source.sourceDocumentId());
            if (!existing.isEmpty()) {
                requireHash(hash, existing.get(0).get("import_hash"));
                mappings.addAll(mappings(kb.getId(), source.sourceDocumentId()));
                reused++;
            } else {
                pending.add(new Pending(source, hash, IdUtil.getSnowflakeNextIdStr(), chunks(source, budget)));
            }
        }
        List<Chunk> allChunks = pending.stream().flatMap(item -> item.chunks().stream()).toList();
        // Provider calls deliberately precede the short DB transaction. A failed batch has no partial mapping.
        List<EmbeddedChunk> embedded = embedding.embed(allChunks, target);
        int[] imported = {0};
        int[] raced = {0};
        transactions.executeWithoutResult(status -> {
            int offset = 0;
            if (!pending.isEmpty()) jdbc.queryForObject("SELECT id FROM t_knowledge_base WHERE id=? AND deleted=0 FOR UPDATE",
                    String.class, kb.getId());
            for (Pending item : pending) {
                // Serialize commits per KB, including two importers racing to publish the same source.
                var existing = jdbc.queryForList("""
                        SELECT import_hash FROM t_research_corpus_document WHERE kb_id=? AND source_document_id=?
                        """, kb.getId(), item.source().sourceDocumentId());
                int end = offset + item.chunks().size();
                if (existing.isEmpty()) {
                    Unit first = item.source().units().get(0);
                    String location = "research-corpus:" + first.dataset() + "/" + first.split() + "/" + item.source().sourceDocumentId();
                    jdbc.update("""
                            INSERT INTO t_knowledge_document
                            (id,kb_id,doc_name,document_key,document_version,chunk_count,file_url,file_type,mime_type,
                             process_mode,status,source_type,source_location,created_by,ingestion_spec)
                            VALUES (?,?,?,?,?,?,?,?,?,'chunk','success','corpus',?,'research-import',?::jsonb)
                            """, item.docId(), kb.getId(), title(first.title()), item.source().sourceDocumentId(),
                            first.metadata().get("document_version"), item.chunks().size(), location, "jsonl",
                            "application/x-ndjson", location, new IngestionSpecCodec(json).write(IngestionSpec.of(ParseProfile.FAST, budget)));
                    writer.replaceDocument(target, new DocumentRef(item.docId(), kb.getId(), title(first.title())),
                            embedded.subList(offset, end));
                    jdbc.update("""
                            INSERT INTO t_research_corpus_document (kb_id,source_document_id,doc_id,import_hash)
                            VALUES (?,?,?,?)
                            """, kb.getId(), item.source().sourceDocumentId(), item.docId(), item.hash());
                    imported[0]++;
                } else {
                    requireHash(item.hash(), existing.get(0).get("import_hash"));
                    raced[0]++;
                }
                mappings.addAll(mappings(kb.getId(), item.source().sourceDocumentId()));
                offset = end;
            }
        });
        return new Result(imported[0], reused + raced[0], allChunks.size(), List.copyOf(mappings));
    }

    public List<Mapping> mappings(String kbId, String sourceDocumentId) {
        List<Mapping> result = jdbc.query("""
                SELECT m.doc_id,c.id,c.chunk_index,c.content_hash,c.metadata->>'source_paragraph_id' source_id
                FROM t_research_corpus_document m
                JOIN t_knowledge_document d ON d.id=m.doc_id AND d.deleted=0 AND d.enabled=1 AND d.status='success'
                JOIN t_knowledge_chunk c ON c.doc_id=d.id AND c.deleted=0 AND c.enabled=1
                WHERE m.kb_id=? AND m.source_document_id=? ORDER BY c.chunk_index
                """, (rs, row) -> new Mapping(sourceDocumentId, rs.getString("doc_id"), rs.getString("source_id"),
                rs.getString("id"), rs.getInt("chunk_index"), rs.getString("content_hash")), kbId, sourceDocumentId);
        if (result.isEmpty()) throw new IllegalStateException("imported source no longer has readable chunks");
        return result;
    }

    Document ordered(Document input) {
        if (input.sourceDocumentId() == null || input.sourceDocumentId().isBlank() || input.units().isEmpty())
            throw new IllegalArgumentException("source document and paragraphs required");
        Unit first = input.units().get(0);
        Set<String> ids = new HashSet<>();
        for (Unit unit : input.units()) {
            if (!"research-corpus-v1".equals(unit.schema_version())
                    || !Set.of("qasper", "musique").contains(unit.dataset())
                    || !input.sourceDocumentId().equals(unit.document_id())
                    || !first.dataset().equals(unit.dataset()) || !first.split().equals(unit.split())
                    || !Objects.equals(first.title(), unit.title()) || !ids.add(unit.id())
                    || unit.text() == null || unit.text().isBlank()
                    || !SecureUtil.sha256(unit.text()).equals(unit.content_hash())
                    || !METADATA_KEYS.containsAll(unit.metadata().keySet())
                    || !unit.id().equals(unit.metadata().get("source_paragraph_id"))
                    || !unit.dataset().equals(unit.metadata().get("dataset"))
                    || !unit.split().equals(unit.metadata().get("split"))
                    || !unit.source_extent().toLowerCase(Locale.ROOT).equals(unit.metadata().get("source_extent"))
                    || !Objects.equals(first.metadata().get("document_version"), unit.metadata().get("document_version")))
                throw new IllegalArgumentException("invalid corpus identity, body or metadata (annotations forbidden)");
        }
        if ("musique".equals(first.dataset()) && (input.units().size() != 1 || !"AVAILABLE_EXCERPT".equals(first.source_extent())))
            throw new IllegalArgumentException("MuSiQue sources must be independent available excerpts");
        List<Unit> units = input.units().stream().sorted(Comparator
                .comparingInt((Unit unit) -> "abstract".equals(unit.metadata().get("source_field")) ? 0 : 1)
                .thenComparingInt(unit -> number(unit.metadata(), "section_index", -1))
                .thenComparingInt(unit -> number(unit.metadata(), "paragraph_index", 0))).toList();
        return new Document(input.sourceDocumentId(), units);
    }

    List<Chunk> chunks(Document source, ChunkBudget budget) {
        List<Chunk> result = new ArrayList<>();
        for (Unit unit : source.units()) {
            List<String> path = unit.metadata().containsKey("section_path")
                    ? ((List<?>) unit.metadata().get("section_path")).stream().map(Object::toString).toList() : List.of();
            var block = new ParagraphBlock(Provenance.ofFile(source.sourceDocumentId()), unit.text());
            // Each original paragraph retains its own identity, even when a long paragraph needs multiple chunks.
            for (ChunkDraft draft : paragraphChunker.chunk(block, new ChunkContext(path, budget))) {
                Map<String, Object> extras = new TreeMap<>(unit.metadata());
                extras.put("source_content_hash", unit.content_hash());
                extras.put("source_title", unit.title());
                result.add(ChunkAssembler.assemble(result.size(), ChunkDraft.of(draft.content(), draft.metadata().withExtras(extras))));
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("empty paragraph chunks");
        return List.copyOf(result);
    }

    private int number(Map<String, Object> metadata, String key, int fallback) {
        return metadata.get(key) instanceof Number n ? n.intValue() : fallback;
    }
    private String title(String value) { return EvidenceText.preview(value, 256); }
    private String hash(Document source, ChunkBudget budget, VectorTarget target) {
        // Sort metadata keys so JSON object key order cannot invalidate an otherwise identical retry.
        List<Object> identity = new ArrayList<>(List.of(source.sourceDocumentId(), budget, target));
        for (Unit unit : source.units()) identity.add(List.of(unit.id(), unit.title(), unit.content_hash(), new TreeMap<>(unit.metadata())));
        return SecureUtil.sha256(encode(identity));
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("cannot serialize corpus", e); }
    }
    private void requireHash(String hash, Object existing) {
        if (!hash.equals(existing)) throw new IllegalArgumentException("source/configuration changed; use a new corpus knowledge base");
    }
}
