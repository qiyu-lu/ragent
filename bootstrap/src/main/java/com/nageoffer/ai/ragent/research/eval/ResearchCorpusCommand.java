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

package com.nageoffer.ai.ragent.research.eval;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.*;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.*;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.config.*;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.*;
import com.nageoffer.ai.ragent.rag.core.vector.*;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.service.*;
import okhttp3.OkHttpClient;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit offline command: no web server, message consumer or generation model is started. */
public class ResearchCorpusCommand {
    public record Job(String documentsFile, String queriesFile, String collection, String runDir,
                      int batchDocuments, int maxRetries, int maxChars, int overlapChars, int dimension) { }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Job job = JSON.readValue(Path.of(args[0]).toFile(), Job.class);
        String url = System.getenv("RAGENT_POSTGRES_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/research_corpus_[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("command requires a local dedicated research_corpus_ database");
        String key = System.getenv("SILICONFLOW_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("SILICONFLOW_API_KEY required");
        if (!job.collection().matches("rs_[a-z0-9_]+")) throw new IllegalArgumentException("research collection required");
        Path run = Path.of(job.runDir());
        Files.createDirectories(run);
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url); pool.setUsername(System.getenv("RAGENT_POSTGRES_USER"));
        pool.setPassword(System.getenv("RAGENT_POSTGRES_PASSWORD")); pool.setMaximumPoolSize(4);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService embedWorkers = Executors.newFixedThreadPool(16);
        ExecutorService importWorkers = Executors.newFixedThreadPool(8);
        UserContext.set(LoginUser.builder().userId("research-import").username("research-import").role("admin").build());
        try (HikariDataSource dataSource = new HikariDataSource(pool);
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             BufferedWriter usage = Files.newBufferedWriter(run.resolve("usage.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             BufferedWriter trace = Files.newBufferedWriter(run.resolve("traces.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             BufferedWriter mapping = Files.newBufferedWriter(run.resolve("mapping.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("research-corpus", new SpringManagedTransactionFactory(), dataSource));
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler()));
            config.addMapper(KnowledgeBaseMapper.class); config.addMapper(KnowledgeChunkMapper.class); config.addMapper(KnowledgeDocumentMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var bases = session.getMapper(KnowledgeBaseMapper.class);
            var chunks = session.getMapper(KnowledgeChunkMapper.class);
            var docs = session.getMapper(KnowledgeDocumentMapper.class);
            var manager = new DataSourceTransactionManager(dataSource);
            var transactions = new TransactionTemplate(manager);
            context.register(Transactions.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
            context.registerBean(ResearchSourceCatalog.class, () -> new ResearchSourceCatalog(chunks, docs, bases, JSON));
            context.refresh();
            var defaults = new RAGDefaultProperties(); defaults.setDimension(job.dimension());
            var candidate = new AIModelProperties.ModelCandidate();
            candidate.setId("qwen-emb-8b"); candidate.setProvider("siliconflow"); candidate.setModel("Qwen/Qwen3-Embedding-8B"); candidate.setDimension(job.dimension());
            var provider = new AIModelProperties.ProviderConfig(); provider.setUrl("https://api.siliconflow.cn");
            provider.setApiKey(key); provider.setEndpoints(Map.of("embedding", "/v1/embeddings"));
            var target = new ModelTarget(candidate.getId(), candidate, provider, null);
            var http = new OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS);
            String proxyUrl = System.getenv("HTTPS_PROXY");
            if (proxyUrl != null && !proxyUrl.isBlank()) {
                var proxy = java.net.URI.create(proxyUrl);
                http.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(proxy.getHost(), proxy.getPort())));
            }
            var client = new SiliconFlowEmbeddingClient(http.build());
            ThreadLocal<String> phase = ThreadLocal.withInitial(() -> "source-verification");
            EmbeddingService embedding = new EmbeddingService() {
                public List<Float> embed(String text) { return embedBatch(List.of(text)).get(0); }
                public List<Float> embed(String text, String model) { return embedBatch(List.of(text), model).get(0); }
                public List<List<Float>> embedBatch(List<String> texts) { return embedBatch(texts, candidate.getId()); }
                public List<List<Float>> embedBatch(List<String> texts, String model) {
                    if (!candidate.getId().equals(model)) throw new IllegalArgumentException("unexpected embedding model");
                    String requestPhase = phase.get();
                    List<CompletableFuture<List<List<Float>>>> calls = new ArrayList<>();
                    for (int start = 0; start < texts.size(); start += 32) {
                        List<String> slice = texts.subList(start, Math.min(start + 32, texts.size()));
                        calls.add(CompletableFuture.supplyAsync(() -> {
                            try (var capture = new EmbeddingUsageCapture(event -> {
                                var values = new LinkedHashMap<>(event); values.put("phase", requestPhase);
                                values.put("collection", job.collection()); append(usage, values);
                            })) { return client.embedBatch(slice, target); }
                        }, embedWorkers));
                    }
                    CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
                    return calls.stream().flatMap(call -> call.join().stream()).toList();
                }
            };
            var index = new ChunkIndexWriter(List.of(new RelationalChunkSink(chunks, new HeuristicTokenCounterService(), JSON),
                    new VectorChunkSink(new PgVectorStoreService(jdbc, JSON))), transactions);
            var importer = new ResearchCorpusImporter(jdbc, JSON, new ParagraphChunker(), new ChunkEmbeddingService(embedding),
                    index, new VectorTargetResolver(defaults), transactions);
            jdbc.update("""
                    INSERT INTO t_knowledge_base (id,name,embedding_model,collection_name,created_by)
                    VALUES (?,?, 'qwen-emb-8b',?,'research-import') ON CONFLICT (collection_name) DO NOTHING
                    """, IdUtil.getSnowflakeNextIdStr(), job.collection(), job.collection());
            String kbId = jdbc.queryForObject("SELECT id FROM t_knowledge_base WHERE collection_name=? AND deleted=0", String.class, job.collection());
            var kb = bases.selectById(kbId);
            int imported = 0, reused = 0, embedded = 0, batches = 0, sourceUnits = 0;
            try (BufferedReader input = Files.newBufferedReader(Path.of(job.documentsFile()))) {
                Deque<Future<ResearchCorpusImporter.Result>> inFlight = new ArrayDeque<>();
                boolean end = false;
                int submitted = 0;
                while (!end || !inFlight.isEmpty()) {
                    while (!end && inFlight.size() < 8) {
                        List<ResearchCorpusImporter.Document> batch = new ArrayList<>();
                        for (int n = 0; n < job.batchDocuments(); n++) {
                            String line = input.readLine(); if (line == null) { end = true; break; }
                            batch.add(JSON.readValue(line, ResearchCorpusImporter.Document.class));
                        }
                        if (batch.isEmpty()) break;
                        sourceUnits += batch.stream().mapToInt(document -> document.units().size()).sum();
                        int batchNumber = ++submitted;
                        inFlight.add(importWorkers.submit(() -> {
                            phase.set("import-batch-" + batchNumber);
                            UserContext.set(LoginUser.builder().userId("research-import").username("research-import").role("admin").build());
                            try {
                                for (int attempt = 0; ; attempt++) {
                                    try { return importer.importBatch(kb, batch, new ChunkBudget(job.maxChars(), job.overlapChars(), 50)); }
                                    catch (RuntimeException failure) {
                                        append(trace, Map.of("batch", batchNumber, "attempt", attempt, "status", "failed", "error", failure.toString(),
                                                "source_document_ids", batch.stream().map(ResearchCorpusImporter.Document::sourceDocumentId).toList()));
                                        if (attempt == job.maxRetries() || failure instanceof IllegalArgumentException) throw failure;
                                        Thread.sleep(Math.min(5000, 500L << attempt));
                                    }
                                }
                            } finally { UserContext.clear(); phase.remove(); }
                        }));
                    }
                    if (inFlight.isEmpty()) break;
                    var result = inFlight.removeFirst().get();
                    batches++;
                    imported += result.importedDocuments(); reused += result.reusedDocuments(); embedded += result.embeddedChunks();
                    for (var entry : result.mappings()) append(mapping, entry);
                    append(trace, Map.of("batch", batches, "status", "success", "imported", result.importedDocuments(), "reused", result.reusedDocuments(), "embedded_chunks", result.embeddedChunks()));
                    if (batches % 20 == 0 || batches == 1) System.out.println(job.collection() + " documents=" + (imported + reused) + " embedded=" + embedded);
                    JSON.writeValue(run.resolve("progress.json").toFile(), Map.of("collection", job.collection(), "kb_id", kbId,
                            "imported_documents", imported, "reused_documents", reused, "embedded_chunks", embedded, "batches", batches, "updated_at", Instant.now().toString()));
                }
            }
            long storedDocuments = jdbc.queryForObject("SELECT count(*) FROM t_research_corpus_document WHERE kb_id=?", Long.class, kbId);
            var stored = jdbc.queryForMap("""
                    SELECT count(*) chunks, count(DISTINCT c.metadata->>'source_paragraph_id') source_units,
                           count(*) FILTER (WHERE v.id IS NULL OR v.content IS DISTINCT FROM c.content
                             OR (v.metadata->>'source_paragraph_id') IS DISTINCT FROM (c.metadata->>'source_paragraph_id')
                             OR jsonb_exists_any(c.metadata, ARRAY['answer','answerable','is_supporting','gold','decomposition'])
                             OR jsonb_exists_any(v.metadata, ARRAY['answer','answerable','is_supporting','gold','decomposition'])) invalid_chunks
                    FROM t_knowledge_chunk c LEFT JOIN t_knowledge_vector v ON v.id=c.id AND v.collection_name=?
                    WHERE c.kb_id=? AND c.deleted=0
                    """, job.collection(), kbId);
            if (storedDocuments != imported + reused || ((Number)stored.get("source_units")).intValue() != sourceUnits
                    || ((Number)stored.get("invalid_chunks")).longValue() != 0)
                throw new IllegalStateException("stored corpus counts, source/vector mapping or annotation isolation mismatch");
            JSON.writeValue(run.resolve("corpus-audit.json").toFile(), Map.of("documents", storedDocuments,
                    "source_units", sourceUnits, "chunks", stored.get("chunks"), "invalid_chunks", stored.get("invalid_chunks")));
            phase.set("source-verification");
            jdbc.execute("ANALYZE t_knowledge_vector, t_knowledge_document, t_knowledge_chunk, t_research_corpus_document");
            verify(job, jdbc, kbId, bases, docs, context.getBean(ResearchSourceCatalog.class), embedding, workers, run);
            JSON.writeValue(run.resolve("complete.json").toFile(), Map.of("collection", job.collection(), "kb_id", kbId,
                    "imported_documents", imported, "reused_documents", reused, "embedded_chunks", embedded, "batches", batches,
                    "finished_at", Instant.now().toString(), "dimension", job.dimension(), "model", candidate.getModel(), "source_units", sourceUnits));
        } finally { UserContext.clear(); workers.shutdownNow(); embedWorkers.shutdownNow(); importWorkers.shutdownNow(); }
    }

    private static void verify(Job job, JdbcTemplate jdbc, String kbId, KnowledgeBaseMapper bases,
                               KnowledgeDocumentMapper docs, ResearchSourceCatalog catalog,
                               EmbeddingService embedding, ExecutorService workers, Path run) throws Exception {
        var properties = new SearchChannelProperties(); properties.getChannels().setTimeoutMs(120000L);
        var channel = new VectorSearchChannel(new PgVectorRetrieverService(jdbc, embedding), properties, workers);
        var engine = new MultiChannelRetrievalEngine(List.of(channel), List.of(), new RetrievalScopeResolver(properties, new KbCollectionProvider(bases)), workers, properties);
        var store = new ResearchEvidenceStore(jdbc, JSON);
        var search = new KnowledgeSearchService(engine, bases, docs, catalog, store, JSON);
        var reader = new SourceReader(store, catalog, new EvidenceSnapshotFactory(JSON));
        String runId = "p2-probe-" + UUID.randomUUID();
        var brief = new ResearchBrief("verify public corpus", ResearchBrief.OutputType.REPORT, List.of(), List.of(kbId));
        jdbc.update("""
                INSERT INTO t_research_run (id,owner_user_id,conversation_id,client_request_id,output_type,brief)
                VALUES (?,'research-import','p2-verification',?,'REPORT',?::jsonb)
                """, runId, runId, JSON.writeValueAsString(brief));
        try (BufferedReader queries = Files.newBufferedReader(Path.of(job.queriesFile()));
             BufferedWriter output = Files.newBufferedWriter(run.resolve("source-probes.jsonl"))) {
            for (int i = 0; i < 3; i++) {
                String line = queries.readLine(); if (line == null) break;
                var query = JSON.readTree(line);
                List<String> docIds = new ArrayList<>();
                for (var id : query.get("document_ids")) docIds.add(jdbc.queryForObject("""
                        SELECT doc_id FROM t_research_corpus_document WHERE kb_id=? AND source_document_id=?
                        """, String.class, kbId, id.asText()));
                var hits = search.search(runId, "research-import", "probe", query.get("question").asText(), List.of(kbId), docIds, 3);
                if (hits.isEmpty()) throw new IllegalStateException("real search returned no source");
                List<Object> reads = new ArrayList<>();
                for (var hit : hits) {
                    var read = reader.read(runId, "research-import", hit.evidenceId(), com.nageoffer.ai.ragent.research.model.SourceReadResult.ReadMode.NEIGHBORS);
                    if (!read.evidence().read() || read.evidence().text().isBlank()
                            || !read.evidence().sourceLocation().containsKey("source_paragraph_id") && !read.evidence().sourceLocation().containsKey("chunks"))
                        throw new IllegalStateException("source read lacks body/location");
                    reads.add(read);
                }
                append(output, Map.of("question_id", query.get("id").asText(), "question", query.get("question").asText(), "hits", hits, "reads", reads));
            }
            try { reader.read(runId, "research-import", "ev-missing"); throw new IllegalStateException("missing source accepted"); }
            catch (com.nageoffer.ai.ragent.framework.exception.ClientException expected) { append(output, Map.of("missing_source_rejected", true)); }
            try { search.search(runId, "research-import", "probe", "query", List.of("outside-scope"), 1); throw new IllegalStateException("scope accepted"); }
            catch (com.nageoffer.ai.ragent.framework.exception.ClientException expected) { append(output, Map.of("outside_scope_rejected", true)); }
        }
    }
    private static synchronized void append(BufferedWriter writer, Object value) {
        try { writer.write(JSON.writeValueAsString(value)); writer.newLine(); writer.flush(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
