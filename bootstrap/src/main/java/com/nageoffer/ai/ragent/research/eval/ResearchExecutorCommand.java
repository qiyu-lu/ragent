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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.embedding.SiliconFlowEmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.VectorSearchChannel;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.runtime.ResearchAgentFactory;
import com.nageoffer.ai.ragent.research.runtime.ResearchModelFactory;
import com.nageoffer.ai.ragent.research.service.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验 X2 / X6：把研究执行器作为独立进程运行，多个进程共享一个隔离运行库，用于 kill -9、SIGSTOP、SIGTERM 等进程级故障
 * 与多实例排空积压的容量基准。走与在线服务相同的 {@link ResearchRunService}（租约、续租、轮询接管、续跑、优雅停机），
 * 模型与 embedding 指向模拟上游；运行库与在线服务一样走连接池。
 *
 *   ResearchExecutorCommand submit <cases.json>      # 在运行库里建 QUEUED 任务，每行打印 {"caseId","runId"}
 *   ResearchExecutorCommand serve [--lease-seconds N] [--heartbeat-seconds N] [--poll-seconds N]
 *                                 [--max-concurrent-runs N] [--shutdown-grace-seconds N] [--submit-when <cases.json>]
 *                                  # 作为一个实例领取并执行任务，直到收到 SIGTERM（优雅停机）或被杀。
 *                                  # --submit-when：该文件出现后经本实例的 create()（本地快速路径）提交其中的任务，
 *                                  # 每行打印 submitted {"caseId","runId"} 或 submitted {"caseId","error"}。
 * serve 每 5 秒打印一行累计的轮询领取统计：claim-stats {"at","calls","claimed","totalMicros","maxMicros"}（应用侧计时，含取连接）。
 */
public final class ResearchExecutorCommand {
    public record Case(String id, String goal) { }
    public static final String OWNER = "x2-owner";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private ResearchExecutorCommand() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !Set.of("submit", "serve").contains(args[0])) throw new IllegalArgumentException("submit <cases.json> | serve [options]");
        String corpusUrl = required("RAGENT_POSTGRES_URL");
        String runUrl = required("RESEARCH_P3_TEST_URL");
        if (!corpusUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_corpus_[a-zA-Z0-9_]+")
                || !runUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Dedicated local corpus and random P3 run databases required");
        }
        var corpus = new DriverManagerDataSource(corpusUrl, required("RESEARCH_TEST_PG_USER"), required("RESEARCH_TEST_PG_PASSWORD"));
        var pool = new HikariConfig();
        pool.setJdbcUrl(runUrl);
        pool.setUsername(required("RESEARCH_TEST_PG_USER"));
        pool.setPassword(required("RESEARCH_TEST_PG_PASSWORD"));
        pool.setMaximumPoolSize(10);
        pool.setMinimumIdle(1);
        var runData = new HikariDataSource(pool);
        var corpusJdbc = new JdbcTemplate(corpus);
        var runJdbc = new JdbcTemplate(runData);
        var claims = new ClaimStats();
        var store = new ResearchRunStore(runJdbc, JSON, new DataSourceTransactionManager(runData)) {
            @Override public List<Claim> claimAvailable(String executorId, Duration lease, int limit, int maxTakeovers) {
                long started = System.nanoTime();
                List<Claim> result = super.claimAvailable(executorId, lease, limit, maxTakeovers);
                claims.record((System.nanoTime() - started) / 1000, result.size());
                return result;
            }
        };
        String kb = corpusJdbc.queryForObject("SELECT id FROM t_knowledge_base WHERE collection_name = ? AND deleted = 0", String.class, "rs_stub_v1");
        if (args[0].equals("submit")) {
            for (Case example : JSON.readValue(Path.of(args[1]).toFile(), Case[].class)) {
                var run = store.create(OWNER, null, example.id(), new ResearchBrief(example.goal(), ResearchBrief.OutputType.REPORT, List.of(), List.of(kb)));
                System.out.println(JSON.writeValueAsString(Map.of("caseId", example.id(), "runId", run.id())));
            }
            return;
        }
        var environment = ResearchRunCommand.environment(System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "stub"));
        var models = Binder.get(environment).bind("ai", Bindable.of(AIModelProperties.class)).orElseThrow(IllegalStateException::new);
        var properties = Binder.get(environment).bind("research", Bindable.of(ResearchProperties.class)).orElseThrow(IllegalStateException::new);
        Path submitWhen = null;
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (args[i].equals("--submit-when")) { submitWhen = Path.of(args[i + 1]); continue; }
            int value = Integer.parseInt(args[i + 1]);
            switch (args[i]) {
                case "--lease-seconds" -> properties.setLeaseSeconds(value);
                case "--heartbeat-seconds" -> properties.setHeartbeatSeconds(value);
                case "--poll-seconds" -> properties.setPollSeconds(value);
                case "--max-concurrent-runs" -> properties.setMaxConcurrentRuns(value);
                case "--shutdown-grace-seconds" -> properties.setShutdownGraceSeconds(value);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        var sourceManager = new DataSourceTransactionManager(corpus);
        sourceManager.setEnforceReadOnly(true);
        var evidence = new ResearchEvidenceStore(runJdbc, JSON);
        ExecutorService retrieval = Executors.newFixedThreadPool(4);
        var context = new AnnotationConfigApplicationContext();
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("read-only-corpus", new SpringManagedTransactionFactory(), corpus));
        config.addMapper(KnowledgeBaseMapper.class);
        config.addMapper(KnowledgeDocumentMapper.class);
        config.addMapper(KnowledgeChunkMapper.class);
        var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var bases = sql.getMapper(KnowledgeBaseMapper.class);
        var docs = sql.getMapper(KnowledgeDocumentMapper.class);
        context.register(ResearchRunCommand.Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> sourceManager);
        context.registerBean(ResearchSourceCatalog.class, () -> new ResearchSourceCatalog(sql.getMapper(KnowledgeChunkMapper.class), docs, bases, JSON));
        context.refresh();
        var catalog = context.getBean(ResearchSourceCatalog.class);
        var candidate = models.getEmbedding().getCandidates().stream().filter(c -> "qwen-emb-8b".equals(c.getId())).findFirst().orElseThrow();
        var target = new ModelTarget(candidate.getId(), candidate, models.getProviders().get(candidate.getProvider()), null);
        var client = new SiliconFlowEmbeddingClient(new okhttp3.OkHttpClient.Builder()
                .readTimeout(properties.getToolTimeoutSeconds(), TimeUnit.SECONDS)
                .callTimeout(properties.getToolTimeoutSeconds(), TimeUnit.SECONDS).build());
        var embedding = new EmbeddingService() {
            public List<Float> embed(String text) { return embedBatch(List.of(text)).get(0); }
            public List<Float> embed(String text, String model) { return embed(text); }
            public List<List<Float>> embedBatch(List<String> texts) { return embedBatch(texts, target.id()); }
            public List<List<Float>> embedBatch(List<String> texts, String model) { return client.embedBatch(texts, target); }
        };
        var searchProperties = new SearchChannelProperties();
        searchProperties.getChannels().setTimeoutMs(properties.getToolTimeoutSeconds() * 1000L);
        var vector = new VectorSearchChannel(new PgVectorRetrieverService(corpusJdbc, embedding), searchProperties, retrieval);
        // 评测进程没有用户表：任务所有者按普通用户对待，仍受语料库可见性约束（导入的公开语料为 PUBLIC）
        var access = new com.nageoffer.ai.ragent.knowledge.service.KnowledgeAccessService(corpusJdbc) {
            @Override public Subject ofUser(String userId) { return new Subject(userId, "user"); }
        };
        var engine = new MultiChannelRetrievalEngine(List.of(vector), List.of(),
                new RetrievalScopeResolver(new KbCollectionProvider(bases), access), retrieval, searchProperties);
        var search = new KnowledgeSearchService(engine, bases, docs, catalog, evidence, JSON);
        var reader = new SourceReader(evidence, catalog, new EvidenceSnapshotFactory(JSON));
        var modelFactory = new ResearchModelFactory(models, properties);
        var completion = new ResearchCompletionService(store, new ResearchArtifactGenerator(modelFactory, properties, evidence,
                new PlanDraftValidator(), JSON, new HeuristicTokenCounterService(), ""), JSON);
        var agents = new ResearchAgentFactory(modelFactory, properties, search, reader, JSON, new HeuristicTokenCounterService(), false);
        var service = new ResearchRunService(store, agents, properties, runJdbc, JSON, completion, evidence, access);
        var stopped = new CountDownLatch(1);
        // SIGTERM 触发 JVM 关闭钩子：与 Spring 的 @PreDestroy 走同一个 close()，即步边界交还。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("executor " + service.executorId() + " shutting down");
            service.close();
            agents.close();
            retrieval.shutdownNow();
            stopped.countDown();
        }, "research-executor-shutdown"));
        service.startPolling();
        System.out.println("executor " + service.executorId() + " polling; lease " + properties.getLeaseSeconds() + " s");
        var stats = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread worker = new Thread(action, "claim-stats");
            worker.setDaemon(true);
            return worker;
        });
        stats.scheduleAtFixedRate(() -> System.out.println("claim-stats " + claims.json()), 5, 5, TimeUnit.SECONDS);
        if (submitWhen != null) submit(service, runJdbc, kb, submitWhen);
        stopped.await();
    }

    /** 与在线接口相同的创建路径：本地线程池满时任务留在库里，由任一实例轮询领取。 */
    private static void submit(ResearchRunService service, JdbcTemplate runJdbc, String kb, Path cases) throws Exception {
        while (!Files.exists(cases)) Thread.sleep(100);
        // 在线服务的知识库与任务同库；评测进程的语料在另一个库，创建时的存在性检查需要运行库里有同 ID 的库记录。
        runJdbc.update("""
                INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, visibility)
                VALUES (?, 'x6 stub corpus', 'stub', 'rs_stub_v1', 'x6', 'PUBLIC') ON CONFLICT (id) DO NOTHING
                """, kb);
        UserContext.set(LoginUser.builder().userId(OWNER).username(OWNER).role("user").build());
        try {
            for (Case example : JSON.readValue(cases.toFile(), Case[].class)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("caseId", example.id());
                try {
                    row.put("runId", service.create(new ResearchRunService.CreateRequest(null, example.id(), example.goal(),
                            ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of())).id());
                } catch (RuntimeException e) {
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.println("submitted " + JSON.writeValueAsString(row));
            }
        } finally {
            UserContext.clear();
        }
    }

    /** 轮询领取语句的调用次数与耗时（应用侧，含从连接池取连接与事务）。 */
    private static final class ClaimStats {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong claimed = new AtomicLong();
        private final AtomicLong totalMicros = new AtomicLong();
        private final AtomicLong maxMicros = new AtomicLong();

        void record(long micros, int rows) {
            calls.incrementAndGet();
            claimed.addAndGet(rows);
            totalMicros.addAndGet(micros);
            maxMicros.accumulateAndGet(micros, Math::max);
        }

        String json() {
            return "{\"at\":" + System.currentTimeMillis() + ",\"calls\":" + calls.get() + ",\"claimed\":" + claimed.get()
                    + ",\"totalMicros\":" + totalMicros.get() + ",\"maxMicros\":" + maxMicros.get() + "}";
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
