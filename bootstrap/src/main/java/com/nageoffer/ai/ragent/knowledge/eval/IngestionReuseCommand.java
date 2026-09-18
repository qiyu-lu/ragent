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

package com.nageoffer.ai.ragent.knowledge.eval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.CodeChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HtmlTableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ListChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.ingest.DefaultIngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.IngestionOutcome;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.ingest.embed.EmbeddingCacheProperties;
import com.nageoffer.ai.ragent.core.ingest.embed.PgEmbeddingCache;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.CsvDocumentParser;
import com.nageoffer.ai.ragent.core.parser.MarkdownDocumentParser;
import com.nageoffer.ai.ragent.core.parser.TikaDocumentParser;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelDocumentParser;
import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingUsageCapture;
import com.nageoffer.ai.ragent.infra.embedding.SiliconFlowEmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import okhttp3.OkHttpClient;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 实验 X5 的离线命令：不起 Web、消息与生成模型，按步骤把文件走一遍真实的摄取内核（解析 → 分块 → 向量化 →
 * 同一事务写块表与 pgvector），每步记录缓存命中、上游调用次数与供应商返回的 token 用量。
 * <p>
 * 只接受专用的 {@code ragent_x5_*} 库，缓存从空表开始。上游地址用 {@code AI_PROVIDERS_SILICONFLOW_URL}
 * 覆盖即可切到模拟上游
 */
public class IngestionReuseCommand {

    public record Step(String name, String file, String docId, boolean cache) {
    }

    public record Job(String runDir, int dimension, List<Step> steps) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String KB_ID = "x5-kb";
    private static final String PARTITION = "x5_reuse";

    public static void main(String[] args) throws Exception {
        Job job = JSON.readValue(Path.of(args[0]).toFile(), Job.class);
        String url = System.getenv("RAGENT_POSTGRES_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/ragent_x5_[a-z0-9_]+")) {
            throw new IllegalArgumentException("command requires a local dedicated ragent_x5_ database");
        }
        String key = System.getenv("SILICONFLOW_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("SILICONFLOW_API_KEY required");
        }
        Path run = Path.of(job.runDir());
        Files.createDirectories(run);
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(System.getenv("RAGENT_POSTGRES_USER"));
        pool.setPassword(System.getenv("RAGENT_POSTGRES_PASSWORD"));
        pool.setMaximumPoolSize(4);
        UserContext.set(LoginUser.builder().userId("x5").username("x5").role("admin").build());
        try (HikariDataSource dataSource = new HikariDataSource(pool)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (jdbc.queryForObject("SELECT count(*) FROM t_embedding_cache", Integer.class) != 0) {
                throw new IllegalStateException("X5 needs an empty embedding cache; create a fresh ragent_x5_ database");
            }
            var candidate = new AIModelProperties.ModelCandidate();
            candidate.setId("qwen-emb-8b");
            candidate.setProvider("siliconflow");
            candidate.setModel("Qwen/Qwen3-Embedding-8B");
            candidate.setDimension(job.dimension());
            var models = new AIModelProperties();
            models.getEmbedding().setCandidates(List.of(candidate));
            EmbeddingService upstream = upstream(candidate, key);

            var cacheProperties = new EmbeddingCacheProperties();
            cacheProperties.setMaxEntries(0);
            var cached = new ChunkEmbeddingService(upstream, new PgEmbeddingCache(jdbc, cacheProperties), models);
            var uncached = new ChunkEmbeddingService(upstream);
            var writer = new ChunkIndexWriter(List.of(
                    new RelationalChunkSink(chunkMapper(dataSource), new HeuristicTokenCounterService(), JSON),
                    new VectorChunkSink(new PgVectorStoreService(jdbc, JSON))),
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            var target = new VectorTarget(PARTITION, candidate.getId(), job.dimension());

            List<Map<String, Object>> results = new ArrayList<>();
            for (Step step : job.steps()) {
                var kernel = new DefaultIngestionKernel(parsers(), chunking(), step.cache() ? cached : uncached, writer);
                Map<String, Object> result = runStep(kernel, step, target, jdbc);
                results.add(result);
                Files.writeString(run.resolve("x5-steps.jsonl"), JSON.writeValueAsString(result) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.printf("X5 %-14s chunks=%s hits=%s upstreamTexts=%s calls=%s tokens=%s embedMs=%s totalMs=%s%n",
                        step.name(), result.get("chunks"), result.get("cacheHits"), result.get("upstreamTexts"),
                        result.get("upstreamCalls"), result.get("upstreamTokens"), result.get("embedMillis"), result.get("totalMillis"));
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("experiment", "X5");
            summary.put("finishedAt", Instant.now().toString());
            summary.put("upstreamUrl", System.getenv().getOrDefault("AI_PROVIDERS_SILICONFLOW_URL", "https://api.siliconflow.cn"));
            summary.put("model", candidate.getProvider() + ":" + candidate.getModel());
            summary.put("dimension", job.dimension());
            summary.put("steps", results);
            summary.put("cacheRows", jdbc.queryForObject("SELECT count(*) FROM t_embedding_cache", Integer.class));
            PRETTY.writeValue(run.resolve("x5-summary.json").toFile(), summary);
        } finally {
            UserContext.clear();
        }
    }

    private static Map<String, Object> runStep(DefaultIngestionKernel kernel, Step step, VectorTarget target, JdbcTemplate jdbc)
            throws Exception {
        Path file = Path.of(step.file());
        byte[] bytes = Files.readAllBytes(file);
        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("step", step.name());
        result.put("file", file.getFileName().toString());
        result.put("docId", step.docId());
        result.put("cache", step.cache());
        long started = System.nanoTime();
        IngestionOutcome outcome;
        try (var capture = new EmbeddingUsageCapture(calls::add)) {
            outcome = kernel.run(new DocumentRef(step.docId(), KB_ID, file.getFileName().toString()), bytes,
                    IngestionSpec.defaults(), target);
        }
        long totalMillis = (System.nanoTime() - started) / 1_000_000;
        List<Map<String, Object>> completed = calls.stream().filter(c -> "COMPLETED".equals(c.get("request_state"))).toList();
        boolean tokensKnown = completed.stream().allMatch(c -> c.get("usage") instanceof Map<?, ?>);
        long tokens = completed.stream()
                .map(c -> c.get("usage") instanceof Map<?, ?> usage ? usage.get("total_tokens") : null)
                .mapToLong(v -> v instanceof Number n ? n.longValue() : 0L).sum();
        result.put("chunks", outcome.embedding().chunks());
        result.put("cacheHits", outcome.embedding().cacheHits());
        result.put("cacheMisses", outcome.embedding().cacheMisses());
        result.put("upstreamTexts", outcome.embedding().upstreamTexts());
        result.put("upstreamCalls", calls.stream().filter(c -> "STARTED".equals(c.get("request_state"))).count());
        result.put("upstreamFailures", completed.stream().filter(c -> !Boolean.TRUE.equals(c.get("success"))).count());
        result.put("upstreamTokens", tokensKnown ? tokens : null);
        result.put("parseMillis", outcome.timings().parseMillis());
        result.put("chunkMillis", outcome.timings().chunkMillis());
        result.put("embedMillis", outcome.timings().embedMillis());
        result.put("indexMillis", outcome.timings().indexMillis());
        result.put("totalMillis", totalMillis);
        // 落库核对：块表与向量表对这份文档的行数应都等于块数
        result.put("chunkRows", jdbc.queryForObject(
                "SELECT count(*) FROM t_knowledge_chunk WHERE doc_id = ? AND deleted = 0", Integer.class, step.docId()));
        result.put("vectorRows", jdbc.queryForObject(
                "SELECT count(*) FROM t_knowledge_vector WHERE collection_name = ? AND metadata->>'doc_id' = ?",
                Integer.class, target.partition(), step.docId()));
        return result;
    }

    private static EmbeddingService upstream(AIModelProperties.ModelCandidate candidate, String key) {
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(System.getenv().getOrDefault("AI_PROVIDERS_SILICONFLOW_URL", "https://api.siliconflow.cn"));
        provider.setApiKey(key);
        provider.setEndpoints(Map.of("embedding", "/v1/embeddings"));
        var target = new ModelTarget(candidate.getId(), candidate, provider, null);
        var http = new OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS);
        String proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            var proxy = java.net.URI.create(proxyUrl);
            http.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        var client = new SiliconFlowEmbeddingClient(http.build());
        return new EmbeddingService() {
            public List<Float> embed(String text) { return embedBatch(List.of(text)).get(0); }
            public List<Float> embed(String text, String model) { return embedBatch(List.of(text), model).get(0); }
            public List<List<Float>> embedBatch(List<String> texts) { return embedBatch(texts, candidate.getId()); }
            public List<List<Float>> embedBatch(List<String> texts, String model) {
                if (!candidate.getId().equals(model)) throw new IllegalArgumentException("unexpected embedding model " + model);
                return client.embedBatch(texts, target);
            }
        };
    }

    /**
     * 与应用相同的离线可构造解析器；Excel 内嵌图片保持默认关闭（图片描述来自视觉模型，不在本实验范围）
     */
    private static ParserRegistry parsers() {
        return new ParserRegistry(List.of(new MarkdownDocumentParser(), new CsvDocumentParser(), new TikaDocumentParser(),
                new ExcelDocumentParser(null, new ImageParseProperties())));
    }

    private static ChunkingService chunking() {
        return new ChunkingService(new BlockAwareChunkerDispatcher(new HeadingHandler(), new ChunkPacker(), List.of(
                new HeadingChunker(), new ParagraphChunker(), new TableChunker(), new HtmlTableChunker(),
                new ImageChunker(), new CodeChunker(), new ListChunker())));
    }

    private static KnowledgeChunkMapper chunkMapper(javax.sql.DataSource dataSource) {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("x5", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MyMetaObjectHandler()));
        config.addMapper(KnowledgeChunkMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(KnowledgeChunkMapper.class);
    }
}
