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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingUsageCapture;
import com.nageoffer.ai.ragent.infra.embedding.SiliconFlowEmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.runtime.*;
import com.nageoffer.ai.ragent.research.service.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.BufferedWriter;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 小规模真实研究 联调：语料连接只读，运行/证据写入随机隔离库，禁止用作批量评分器。 */
public class ResearchRunCommand {
    public record Case(String id, String collection, List<String> sourceDocumentIds, String goal,
                        ResearchBrief.OutputType outputType, long cancelAfterMillis, String reply, Boolean cancelWhenWorkersRunning) { }
    public record Job(String runDir, List<Case> cases) { }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        Job job = JSON.readValue(Path.of(args[0]).toFile(), Job.class);
        if (job.cases().isEmpty() || job.cases().size() > 6) throw new IllegalArgumentException("P3 smoke supports 1—6 cases only");
        String corpusUrl = required("RAGENT_POSTGRES_URL");
        String runUrl = required("RESEARCH_P3_TEST_URL");
        if (!corpusUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_corpus_[a-zA-Z0-9_]+")
                || !runUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Dedicated local corpus and random P3 run databases required");
        }
        var corpus = new DriverManagerDataSource(corpusUrl, required("RESEARCH_TEST_PG_USER"), required("RESEARCH_TEST_PG_PASSWORD"));
        var runData = new DriverManagerDataSource(runUrl, required("RESEARCH_TEST_PG_USER"), required("RESEARCH_TEST_PG_PASSWORD"));
        var corpusJdbc = new JdbcTemplate(corpus);
        var runJdbc = new JdbcTemplate(runData);
        Path directory = Path.of(job.runDir());
        Files.createDirectories(directory);
        var environment = new StandardEnvironment();
        for (var propertySource : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(propertySource);
        }
        var models = Binder.get(environment).bind("ai", Bindable.of(AIModelProperties.class)).orElseThrow(IllegalStateException::new);
        var properties = Binder.get(environment).bind("research", Bindable.of(ResearchProperties.class)).orElseThrow(IllegalStateException::new);
        var sourceManager = new DataSourceTransactionManager(corpus);
        sourceManager.setEnforceReadOnly(true);
        var store = new ResearchRunStore(runJdbc, JSON, new DataSourceTransactionManager(runData));
        var evidence = new ResearchEvidenceStore(runJdbc, JSON);
        ExecutorService retrieval = Executors.newFixedThreadPool(4);
        ScheduledExecutorService cancels = Executors.newSingleThreadScheduledExecutor();
        try (var context = new AnnotationConfigApplicationContext();
             var embeddingUsage = Files.newBufferedWriter(directory.resolve("embedding-usage.jsonl"));
             var predictions = Files.newBufferedWriter(directory.resolve("predictions.jsonl"));
             var traces = Files.newBufferedWriter(directory.resolve("traces.jsonl"));
             var usage = Files.newBufferedWriter(directory.resolve("usage.jsonl"))) {
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("read-only-corpus", new SpringManagedTransactionFactory(), corpus));
            config.addMapper(KnowledgeBaseMapper.class);
            config.addMapper(KnowledgeDocumentMapper.class);
            config.addMapper(KnowledgeChunkMapper.class);
            var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var bases = sql.getMapper(KnowledgeBaseMapper.class);
            var docs = sql.getMapper(KnowledgeDocumentMapper.class);
            context.register(Transactions.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> sourceManager);
            context.registerBean(ResearchSourceCatalog.class, () -> new ResearchSourceCatalog(sql.getMapper(KnowledgeChunkMapper.class), docs, bases, JSON));
            context.refresh();
            var catalog = context.getBean(ResearchSourceCatalog.class);
            var targetCandidate = models.getEmbedding().getCandidates().stream().filter(c -> "qwen-emb-8b".equals(c.getId())).findFirst().orElseThrow();
            var target = new ModelTarget(targetCandidate.getId(), targetCandidate, models.getProviders().get(targetCandidate.getProvider()), null);
            var client = new SiliconFlowEmbeddingClient(new okhttp3.OkHttpClient.Builder()
                    .callTimeout(properties.getToolTimeoutSeconds(), TimeUnit.SECONDS).build());
            var embedding = new EmbeddingService() {
                public List<Float> embed(String text) { return embedBatch(List.of(text)).get(0); }
                public List<Float> embed(String text, String model) { return embed(text); }
                public List<List<Float>> embedBatch(List<String> texts) { return embedBatch(texts, target.id()); }
                public List<List<Float>> embedBatch(List<String> texts, String model) {
                    if (!target.id().equals(model)) throw new IllegalArgumentException("Unexpected embedding model");
                    try (var capture = new EmbeddingUsageCapture(event -> append(embeddingUsage, event))) {
                        return client.embedBatch(texts, target);
                    }
                }
            };
            var searchProperties = new SearchChannelProperties();
            searchProperties.getChannels().setTimeoutMs(properties.getToolTimeoutSeconds() * 1000L);
            var vector = new VectorSearchChannel(new PgVectorRetrieverService(corpusJdbc, embedding), searchProperties, retrieval);
            var engine = new MultiChannelRetrievalEngine(List.of(vector), List.of(),
                    new RetrievalScopeResolver(searchProperties, new KbCollectionProvider(bases)), retrieval, searchProperties);
            var search = new KnowledgeSearchService(engine, bases, docs, catalog, evidence, JSON);
            var reader = new SourceReader(evidence, catalog, new EvidenceSnapshotFactory(JSON));
            try (var runner = new ResearchAgentFactory(new ResearchModelFactory(models, properties), properties, search, reader, JSON, new HeuristicTokenCounterService())) {
            for (Case example : job.cases()) {
                String kb = corpusJdbc.queryForObject("SELECT id FROM t_knowledge_base WHERE collection_name = ? AND deleted = 0", String.class, example.collection());
                List<String> documents = new ArrayList<>();
                for (String sourceId : example.sourceDocumentIds()) documents.add(corpusJdbc.queryForObject(
                        "SELECT doc_id FROM t_research_corpus_document WHERE kb_id = ? AND source_document_id = ?", String.class, kb, sourceId));
                String goal = example.goal();
                for (int i = 0; i < documents.size(); i++) goal = goal.replace("[[DOC_" + i + "]]", documents.get(i));
                var brief = new ResearchBrief(goal, example.outputType(), List.of(), List.of(kb), documents);
                var run = store.create("p3-real-smoke", "p3-smoke", example.id(), brief);
                execute(store, runner, run, properties, example.cancelAfterMillis(), Boolean.TRUE.equals(example.cancelWhenWorkersRunning()), cancels);
                run = store.get(run.id(), "p3-real-smoke");
                if (run.status() == ResearchRun.Status.WAITING_INPUT && example.reply() != null) {
                    run = store.input(run.id(), "p3-real-smoke", run.revision(), example.reply());
                    execute(store, runner, run, properties, 0, false, cancels);
                    run = store.get(run.id(), "p3-real-smoke");
                }
                append(predictions, run);
                for (var event : store.events(run.id(), "p3-real-smoke", 0, 500)) append(traces,
                        Map.of("runId", run.id(), "caseId", example.id(), "event", event));
                if (run.usage().get("calls") instanceof List<?> calls) for (Object call : calls) append(usage,
                        Map.of("runId", run.id(), "caseId", example.id(), "call", call));
                System.out.println(example.id() + " " + run.status() + " calls=" + run.usage().get("modelCalls"));
            }
            }
        } finally { retrieval.shutdownNow(); cancels.shutdownNow(); }
    }

    private static void execute(ResearchRunStore store, ResearchRunner runner, ResearchRun run,
                                 ResearchProperties properties, long cancelAfterMillis, boolean cancelWhenWorkersRunning, ScheduledExecutorService cancels) {
        var claim = store.claim(run.id(), "p3-real-smoke", Duration.ofSeconds(properties.getMaxDurationSeconds() + 30L)).orElseThrow();
        var session = new ResearchSession(store, claim, new ResearchBudget(properties, run.usage()), new ResearchControl());
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> workersCancellation = cancelWhenWorkersRunning ? cancels.scheduleWithFixedDelay(() -> {
            var live = store.get(run.id(), claim.owner());
            if (live.usage().get("calls") instanceof List<?> calls && calls.stream().filter(c -> c instanceof Map<?, ?> value
                    && "worker".equals(value.get("role")) && "STARTED".equals(value.get("status"))).count() >= 2
                    && cancelled.compareAndSet(false, true)) {
                store.cancel(run.id(), claim.owner());
                session.control.cancel();
            }
        }, 50, 50, TimeUnit.MILLISECONDS) : null;
        ScheduledFuture<?> cancellation = cancelAfterMillis > 0 ? cancels.schedule(() -> {
            store.cancel(run.id(), claim.owner());
            session.control.cancel();
        }, cancelAfterMillis, TimeUnit.MILLISECONDS) : null;
        try {
            var outcome = runner.run(session);
            Map<String, Object> state = new HashMap<>();
            state.put("readEvidenceIds", session.delivered().keySet());
            state.put("acceptedWorkerEvidenceIds", session.acceptedEvidenceIds());
            if (outcome.question() != null) state.put("question", outcome.question());
            else state.put("researchResult", JSON.convertValue(outcome.result(), Map.class));
            store.finish(claim, outcome.question() != null ? ResearchRun.Status.WAITING_INPUT
                            : outcome.result().status() == com.nageoffer.ai.ragent.research.model.SubtaskResult.Status.PARTIAL
                            ? ResearchRun.Status.PARTIAL : ResearchRun.Status.COMPLETED,
                    state, session.budget.snapshot(), null);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && !(root instanceof ResearchBudget.Exhausted)) root = root.getCause();
            String reason = root instanceof ResearchBudget.Exhausted ? root.getMessage()
                    : "NATIVE_FINISH_REQUIRED".equals(root.getMessage()) ? "NATIVE_FINISH_REQUIRED" : root.getClass().getSimpleName();
            boolean bounded = root instanceof ResearchBudget.Exhausted || root instanceof TimeoutException;
            var status = (bounded && !session.citableIds().isEmpty() || !session.acceptedEvidenceIds().isEmpty()) ? ResearchRun.Status.PARTIAL : ResearchRun.Status.FAILED;
            store.finish(claim, status, Map.of("failureType", reason, "researchResult", JSON.convertValue(session.partial(reason), Map.class),
                    "readEvidenceIds", session.delivered().keySet(), "acceptedWorkerEvidenceIds", session.acceptedEvidenceIds()), session.budget.snapshot(), "SMOKE_EXECUTION_FAILED");
            System.err.println("Smoke execution failure: " + reason);
        } finally {
            if (cancellation != null) cancellation.cancel(false);
            if (workersCancellation != null) workersCancellation.cancel(false);
            store.cancelledLocally(claim, session.budget.snapshot());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
    private static void append(BufferedWriter writer, Object value) {
        synchronized (writer) {
            try { writer.write(JSON.writeValueAsString(value)); writer.newLine(); writer.flush(); }
            catch (Exception e) { throw new IllegalStateException("Smoke output write failed", e); }
        }
    }
}
