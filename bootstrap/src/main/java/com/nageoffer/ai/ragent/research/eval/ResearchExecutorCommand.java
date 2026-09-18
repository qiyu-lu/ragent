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
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 实验 X2：把研究执行器作为独立进程运行，多个进程共享一个隔离运行库，用于 kill -9、SIGSTOP、SIGTERM 等进程级故障。
 * 走与在线服务相同的 {@link ResearchRunService}（租约、续租、轮询接管、续跑、优雅停机），模型与 embedding 指向模拟上游。
 *
 *   ResearchExecutorCommand submit <cases.json>      # 在运行库里建 QUEUED 任务，每行打印 {"caseId","runId"}
 *   ResearchExecutorCommand serve [--lease-seconds N] [--heartbeat-seconds N] [--poll-seconds N]
 *                                 [--max-concurrent-runs N] [--shutdown-grace-seconds N]
 *                                  # 作为一个实例领取并执行任务，直到收到 SIGTERM（优雅停机）或被杀
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
        var runData = new DriverManagerDataSource(runUrl, required("RESEARCH_TEST_PG_USER"), required("RESEARCH_TEST_PG_PASSWORD"));
        var corpusJdbc = new JdbcTemplate(corpus);
        var runJdbc = new JdbcTemplate(runData);
        var store = new ResearchRunStore(runJdbc, JSON, new DataSourceTransactionManager(runData));
        if (args[0].equals("submit")) {
            String kb = corpusJdbc.queryForObject("SELECT id FROM t_knowledge_base WHERE collection_name = ? AND deleted = 0", String.class, "rs_stub_v1");
            for (Case example : JSON.readValue(Path.of(args[1]).toFile(), Case[].class)) {
                var run = store.create(OWNER, null, example.id(), new ResearchBrief(example.goal(), ResearchBrief.OutputType.REPORT, List.of(), List.of(kb)));
                System.out.println(JSON.writeValueAsString(Map.of("caseId", example.id(), "runId", run.id())));
            }
            return;
        }
        var environment = ResearchRunCommand.environment(System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "stub"));
        var models = Binder.get(environment).bind("ai", Bindable.of(AIModelProperties.class)).orElseThrow(IllegalStateException::new);
        var properties = Binder.get(environment).bind("research", Bindable.of(ResearchProperties.class)).orElseThrow(IllegalStateException::new);
        for (int i = 1; i + 1 < args.length; i += 2) {
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
                new RetrievalScopeResolver(searchProperties, new KbCollectionProvider(bases), access), retrieval, searchProperties);
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
        stopped.await();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
