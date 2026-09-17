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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.research.model.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "RESEARCH_TEST_PG_URL", matches = ".+")
class ResearchEvidencePostgresIT {
    private static JdbcTemplate jdbc;
    private static ResearchEvidenceStore store;
    private static ResearchSourceCatalog catalog;
    private static KnowledgeBaseMapper bases;
    private static KnowledgeDocumentMapper documents;
    private static KnowledgeChunkMapper chunkRows;
    private static AnnotationConfigApplicationContext context;
    private static final List<Boolean> sourceTransactions = new CopyOnWriteArrayList<>();
    private static final ObjectMapper json = new ObjectMapper();
    private String prefix, kbId, docId, otherDocId, centerId, beforeId, afterId, runId, owner;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class ObserveSourceTransactions implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Connection connection = (Connection) invocation.getArgs()[0];
            sourceTransactions.add(connection.isReadOnly()
                    && connection.getTransactionIsolation() == Connection.TRANSACTION_REPEATABLE_READ);
            return invocation.proceed();
        }
    }

    @BeforeAll
    static void infrastructure() {
        String url = System.getenv("RESEARCH_TEST_PG_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p2_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Integration tests require a local random research_p2_ database");
        }
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        store = new ResearchEvidenceStore(jdbc, json);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("isolated-pg", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler()));
        config.addInterceptor(new ObserveSourceTransactions());
        config.addMapper(KnowledgeBaseMapper.class);
        config.addMapper(KnowledgeChunkMapper.class);
        config.addMapper(KnowledgeDocumentMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(config);
        var session = new SqlSessionTemplate(factory);
        bases = session.getMapper(KnowledgeBaseMapper.class);
        documents = session.getMapper(KnowledgeDocumentMapper.class);
        var chunks = session.getMapper(KnowledgeChunkMapper.class);
        chunkRows = chunks;
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ResearchSourceCatalog.class, () -> new ResearchSourceCatalog(chunks, documents, bases, json));
        context.refresh();
        catalog = context.getBean(ResearchSourceCatalog.class);
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void fixtures() throws Exception {
        sourceTransactions.clear();
        prefix = "pg" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        kbId = prefix + "-kb";
        docId = prefix + "-da";
        otherDocId = prefix + "-db";
        centerId = prefix + "-c1";
        beforeId = prefix + "-c0";
        afterId = prefix + "-c2";
        runId = prefix + "-run";
        owner = prefix + "-owner";
        jdbc.update("""
                INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by)
                VALUES (?, ?, 'fixture', ?, 'p2-tester')
                """, kbId, kbId, prefix);
        for (String document : List.of(docId, otherDocId)) {
            jdbc.update("""
                    INSERT INTO t_knowledge_document
                        (id, kb_id, doc_name, document_key, document_version, file_url, file_type, status, created_by)
                    VALUES (?, ?, ?, ?, 'V1', 'fixture.md', 'markdown', 'success', 'p2-tester')
                    """, document, kbId, document + ".md", document);
        }
        source(beforeId, docId, 0, "before", "{\"section_path\":[\"Methods\"]}");
        source(centerId, docId, 1, "center", "{\"section_path\":[\"Methods\"]}");
        source(afterId, docId, 2, "after", "{\"section_path\":[\"Methods\"]}");
        source(prefix + "-cb", otherDocId, 0, "distractor", "{}");
        newRun(runId, owner);
        vector(centerId, docId, 1F);
        vector(prefix + "-cb", otherDocId, 0F);
    }

    @Test
    void javaInsertIsIdempotentAndCannotOverwriteFirstSnapshot() {
        var first = snapshot(runId, "ev-" + prefix, "first", List.of(centerId));
        var saved = store.save(owner, first);
        var second = snapshot(runId, first.evidence().evidenceId(), "replacement", List.of(centerId));
        assertEquals(saved, store.save(owner, second));
        assertEquals("first", saved.sourceText());
        assertTrue(store.markRead(owner, saved).read());
    }

    @Test
    void corpusImportPreservesOrderSameNamedSectionBoundariesAndReusesChunks() {
        var embedding = corpusEmbeddings();
        var importer = corpusImporter(embedding, new com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService(jdbc, json));
        var source = new ResearchCorpusImporter.Document("qasper:" + prefix,
                List.of(corpusUnit("later", "later body", 1, 0), corpusUnit("second", "second body", 0, 1),
                        corpusUnit("first", "first body", 0, 0)));
        var kb = bases.selectById(kbId);
        var first = importer.importBatch(kb, List.of(source), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults());
        assertEquals(List.of(prefix + "-first", prefix + "-second", prefix + "-later"), first.mappings().stream().map(ResearchCorpusImporter.Mapping::sourceId).toList());
        var middle = first.mappings().get(1);
        var neighborhood = catalog.neighbors(middle.chunkId(), Set.of(kbId), Set.of(middle.docId()));
        assertEquals(List.of("first body", "second body"), neighborhood.sources().stream().map(s -> s.chunk().getContent()).toList());
        var reused = importer.importBatch(kb, List.of(source), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults());
        assertEquals(first.mappings(), reused.mappings());
        assertEquals(1, reused.reusedDocuments());
        assertEquals(0, reused.embeddedChunks());
        verify(embedding, times(1)).embedBatch(anyList(), eq("fixture"));
        assertThrows(IllegalArgumentException.class, () -> importer.importBatch(kb, List.of(source), new com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget(512,64,50)));
        com.nageoffer.ai.ragent.framework.context.UserContext.clear();
    }

    @Test
    void failedCorpusBatchRollsBackDocumentsMappingsChunksAndVectorsAndCanRetry() {
        var embedding = corpusEmbeddings();
        var vectors = new com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService(jdbc, json);
        var failing = spy(vectors);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("injected index failure"); })
                .when(failing).indexDocumentChunks(anyString(), anyString(), anyList());
        var source = new ResearchCorpusImporter.Document("qasper:" + prefix, List.of(corpusUnit("p", "source body", 0, 0)));
        var kb = bases.selectById(kbId);
        int before = jdbc.queryForObject("SELECT count(*) FROM t_knowledge_document WHERE kb_id=?", Integer.class, kbId);
        assertThrows(IllegalStateException.class, () -> corpusImporter(embedding, failing).importBatch(kb, List.of(source), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults()));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM t_knowledge_document WHERE kb_id=?", Integer.class, kbId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM t_research_corpus_document WHERE kb_id=?", Integer.class, kbId));
        var imported = corpusImporter(embedding, vectors).importBatch(kb, List.of(source), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults());
        assertEquals(1, imported.importedDocuments());
        assertEquals("source body", catalog.load(imported.mappings().get(0).chunkId(), Set.of(kbId)).chunk().getContent());
        com.nageoffer.ai.ragent.framework.context.UserContext.clear();
    }

    @Test
    void corpusAnnotationsAndProviderFailuresCannotPublishDocuments() {
        var embedding = corpusEmbeddings();
        var importer = corpusImporter(embedding, new com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService(jdbc, json));
        var unit = corpusUnit("p", "source body", 0, 0);
        Map<String,Object> leaked = new HashMap<>(unit.metadata()); leaked.put("answer", "gold secret");
        var invalid = new ResearchCorpusImporter.Unit(unit.schema_version(),unit.id(),unit.dataset(),unit.split(),unit.document_id(),unit.title(),unit.text(),unit.content_hash(),unit.source_extent(),leaked);
        var kb = bases.selectById(kbId);
        assertThrows(IllegalArgumentException.class, () -> importer.importBatch(kb, List.of(new ResearchCorpusImporter.Document(unit.document_id(),List.of(invalid))), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults()));
        verifyNoInteractions(embedding);
        when(embedding.embedBatch(anyList(), anyString())).thenThrow(new IllegalStateException("provider failure"));
        assertThrows(IllegalStateException.class, () -> importer.importBatch(kb, List.of(new ResearchCorpusImporter.Document(unit.document_id(),List.of(unit))), com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget.defaults()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM t_research_corpus_document WHERE kb_id=?", Integer.class, kbId));
        com.nageoffer.ai.ragent.framework.context.UserContext.clear();
    }

    @Test
    void musiqueImportsIndependentAvailableExcerptsWithTheSameTitle() {
        var embedding = corpusEmbeddings();
        var importer = corpusImporter(embedding,new com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService(jdbc,json));
        List<ResearchCorpusImporter.Document> sources = new ArrayList<>();
        for (String suffix : List.of("a","b")) {
            String id = prefix + "-" + suffix, body = "Independent source " + suffix;
            var unit = new ResearchCorpusImporter.Unit("research-corpus-v1",id,"musique","dev",id,"Same article",body,
                    SecureUtil.sha256(body),"AVAILABLE_EXCERPT",Map.of("dataset","musique","split","dev",
                    "source_paragraph_id",id,"source_extent","available_excerpt","document_version","musique-v1.0",
                    "block_type","paragraph","source_field","paragraph_text"));
            sources.add(new ResearchCorpusImporter.Document(id,List.of(unit)));
        }
        var budget = new com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget(768,96,50,2);
        var result = importer.importBatch(bases.selectById(kbId),sources,budget);
        assertEquals(2,result.importedDocuments());
        assertEquals(2,result.mappings().stream().map(ResearchCorpusImporter.Mapping::docId).distinct().count());
        for (var mapping : result.mappings()) {
            var spec = new com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec(json).read(
                    jdbc.queryForObject("SELECT ingestion_spec::text FROM t_knowledge_document WHERE id=?",String.class,mapping.docId()));
            assertEquals(budget,spec.budget());
            var source = catalog.load(mapping.chunkId(),Set.of(kbId));
            assertEquals(EvidenceRecord.SourceExtent.AVAILABLE_EXCERPT,source.extent());
            assertEquals(1,catalog.neighbors(mapping.chunkId(),Set.of(kbId),Set.of(mapping.docId())).sources().size());
        }
        com.nageoffer.ai.ragent.framework.context.UserContext.clear();
    }

    private ResearchCorpusImporter.Unit corpusUnit(String suffix, String body, int section, int paragraph) {
        String id = prefix + "-" + suffix;
        return new ResearchCorpusImporter.Unit("research-corpus-v1",id,"qasper","train","qasper:"+prefix,"paper",body,
                SecureUtil.sha256(body),"CHUNK",Map.of("dataset","qasper","split","train","source_paragraph_id",id,
                "source_extent","chunk","document_version","V1","section_path",List.of("Same name"),"section_index",section,
                "paragraph_index",paragraph,"source_field","full_text"));
    }
    private EmbeddingService corpusEmbeddings() {
        com.nageoffer.ai.ragent.framework.context.UserContext.set(com.nageoffer.ai.ragent.framework.context.LoginUser.builder().userId("p2-tester").username("p2-tester").build());
        var embedding = mock(EmbeddingService.class);
        when(embedding.embedBatch(anyList(), eq("fixture"))).thenAnswer(invocation ->
                ((List<?>)invocation.getArgument(0)).stream().map(t -> {
                    List<Float> v = new ArrayList<>(Collections.nCopies(1536,0F)); v.set(0,1F); return v;
                }).toList());
        return embedding;
    }
    private ResearchCorpusImporter corpusImporter(EmbeddingService embedding, com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService vectors) {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var defaults = new com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties(); defaults.setDimension(1536);
        var writer = new com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter(List.of(
                new com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink(chunkRows,new com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService(),json),
                new com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink(vectors)),transactions);
        return new ResearchCorpusImporter(jdbc,json,new com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker(),
                new com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService(embedding),writer,
                new com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver(defaults),transactions);
    }

    @Test
    void actualStoreQueriesEnforceOwnerAndRunBoundaries() throws Exception {
        var saved = store.save(owner, snapshot(runId, "ev-" + prefix, "body", List.of(centerId)));
        String foreignRun = prefix + "-foreign";
        newRun(foreignRun, "other-owner");
        assertThrows(ClientException.class, () -> store.requireBrief(runId, "other-owner"));
        assertThrows(ClientException.class, () -> store.find(runId, "other-owner", saved.evidence().evidenceId()));
        assertThrows(ClientException.class, () -> store.find(foreignRun, "other-owner", saved.evidence().evidenceId()));
        assertThrows(ClientException.class, () -> store.save("other-owner", saved));
        assertThrows(ClientException.class, () -> store.markRead("other-owner", saved));
        assertFalse(store.find(runId, owner, saved.evidence().evidenceId()).evidence().read());
    }

    @Test
    void concurrentExpansionWritersPublishOneFirstSnapshot() throws Exception {
        var parent = store.save(owner, snapshot(runId, "ev-" + prefix, "parent", List.of(centerId)));
        var left = snapshot(runId, "ev-left-" + prefix, "left", List.of(beforeId, centerId));
        var right = snapshot(runId, "ev-right-" + prefix, "right", List.of(centerId, afterId));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Future<EvidenceSnapshot> a = workers.submit(() -> {
                gate.await();
                return store.saveExpansion(owner, parent.evidence().evidenceId(), left);
            });
            Future<EvidenceSnapshot> b = workers.submit(() -> {
                gate.await();
                return store.saveExpansion(owner, parent.evidence().evidenceId(), right);
            });
            gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM t_research_evidence WHERE origin_evidence_id = ?",
                    Integer.class, parent.evidence().evidenceId()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expansionCannotAttachToParentInAnotherRunEvenForSameOwner() throws Exception {
        var parent = store.save(owner, snapshot(runId, "ev-" + prefix, "parent", List.of(centerId)));
        String secondRun = prefix + "-second";
        newRun(secondRun, owner);
        var expanded = snapshot(secondRun, "ev-child-" + prefix, "body", List.of(centerId, afterId));
        assertThrows(ClientException.class, () ->
                store.saveExpansion(owner, parent.evidence().evidenceId(), expanded));
        assertTrue(store.findExpansion(secondRun, owner, parent.evidence().evidenceId()).isEmpty());
    }

    @Test
    void realVectorRecallFiltersDocumentsBeforeTopOneAndMapsDocumentId() {
        var retriever = new PgVectorRetrieverService(jdbc, mock(EmbeddingService.class));
        var hits = retriever.retrieveByVector(queryVector(), RetrieveRequest.builder()
                .collectionName(prefix).documentIds(List.of(docId)).topK(1).build());
        assertEquals(List.of(centerId), hits.stream().map(hit -> hit.getId()).toList());
        assertEquals(docId, hits.get(0).getDocId());
        var unfiltered = retriever.retrieveByVector(queryVector(), RetrieveRequest.builder()
                .collectionName(prefix).topK(1).build());
        assertEquals(otherDocId, unfiltered.get(0).getDocId(), "夹具中其他文档相似度更高，不能先 TopK 再过滤");
    }

    @Test
    void realSearchAndNeighborReadPersistSnapshotsAndUseReadOnlyRepeatableTransactions() {
        var search = searchService();
        var hit = search.search(runId, owner, "main", "query", null, List.of(), 1).get(0);
        // 作用域列表查询没有来源事务；只观测下方 neighbors/loadAll 的一致性读取。
        sourceTransactions.clear();
        var reader = new SourceReader(store, catalog, new EvidenceSnapshotFactory(json));
        var first = reader.read(runId, owner, hit.evidenceId(), SourceReadResult.ReadMode.NEIGHBORS);
        assertEquals(List.of(beforeId, centerId, afterId), first.evidence().chunkIds());
        assertEquals("before\n\ncenter\n\nafter", first.evidence().text());
        assertTrue(first.evidence().read());
        assertFalse(sourceTransactions.isEmpty());
        assertTrue(sourceTransactions.stream().allMatch(Boolean::booleanValue));
        jdbc.update("UPDATE t_knowledge_chunk SET content = ?, content_hash = ? WHERE id = ?",
                "new after", SecureUtil.sha256("new after"), afterId);
        var repeated = reader.read(runId, owner, hit.evidenceId(), SourceReadResult.ReadMode.NEIGHBORS);
        assertEquals(first.evidence(), repeated.evidence());
        assertEquals(SourceReadResult.SourceState.CHANGED, repeated.sourceState());
        assertThrows(ClientException.class, () ->
                search.search(runId, owner, "main", "query", null, List.of(otherDocId), 1));
    }

    @Test
    void disabledNeighborIsRejectedByActualMapperEvenWithSavedExpansion() {
        var hit = searchService().search(runId, owner, "main", "query", null, 1).get(0);
        var reader = new SourceReader(store, catalog, new EvidenceSnapshotFactory(json));
        reader.read(runId, owner, hit.evidenceId(), SourceReadResult.ReadMode.NEIGHBORS);
        jdbc.update("UPDATE t_knowledge_chunk SET enabled = 0 WHERE id = ?", afterId);
        assertThrows(ClientException.class, () ->
                reader.read(runId, owner, hit.evidenceId(), SourceReadResult.ReadMode.NEIGHBORS));
    }

    private KnowledgeSearchService searchService() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        List<Float> vector = new ArrayList<>();
        for (float value : queryVector()) vector.add(value);
        when(embeddings.embed("query")).thenReturn(vector);
        var properties = new SearchChannelProperties();
        var channel = new VectorSearchChannel(new PgVectorRetrieverService(jdbc, embeddings), properties, Runnable::run);
        var engine = new MultiChannelRetrievalEngine(List.of(channel), List.of(),
                mock(RetrievalScopeResolver.class), Runnable::run, properties);
        return new KnowledgeSearchService(engine, bases, documents, catalog, store, json);
    }

    private void source(String id, String document, int index, String body, String metadata) {
        jdbc.update("""
                INSERT INTO t_knowledge_chunk (id, kb_id, doc_id, chunk_index, content, content_hash, metadata, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'p2-tester')
                """, id, kbId, document, index, body, SecureUtil.sha256(body), metadata);
    }

    private void newRun(String id, String user) throws Exception {
        jdbc.update("""
                INSERT INTO t_research_run (id, owner_user_id, conversation_id, client_request_id, output_type, brief)
                VALUES (?, ?, 'fixture', ?, 'REPORT', ?::jsonb)
                """, id, user, id, json.writeValueAsString(
                new ResearchBrief("compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kbId), List.of(docId))));
    }

    private void vector(String id, String document, float secondCoordinate) {
        float[] vector = queryVector();
        vector[1] = secondCoordinate;
        String literal = "[" + java.util.stream.IntStream.range(0, vector.length)
                .mapToObj(index -> Float.toString(vector[index])).collect(Collectors.joining(",")) + "]";
        jdbc.update("INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding) VALUES (?, ?, ?, ?::jsonb, ?::vector)",
                id, prefix, "index excerpt", "{\"doc_id\":\"" + document + "\"}", literal);
    }

    private float[] queryVector() {
        float[] vector = new float[1536];
        vector[0] = 1;
        return vector;
    }

    private EvidenceSnapshot snapshot(String run, String id, String body, List<String> chunks) {
        var evidence = new EvidenceRecord(run, id, kbId, docId, "fixture.md", "V1", chunks,
                SecureUtil.sha256(body), body, Map.of(), "worker", false, false, EvidenceRecord.SourceExtent.CHUNK);
        return new EvidenceSnapshot(evidence, body, SecureUtil.sha256("{}"));
    }
}
