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

package com.nageoffer.ai.ragent.core.ingest.sink;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重新入库的可见性：块表与向量表的“先删后插”在 {@link ChunkIndexWriter} 的同一个事务里，
 * 并发读者在提交前只看得到旧版本，写到一半失败则两张表一起回滚。块表是逻辑删除，读者按 deleted = 0 读
 */
@EnabledIfEnvironmentVariable(named = "RESEARCH_P3_TEST_URL", matches = ".+")
class ChunkReplaceAtomicityPostgresIT {

    private static final int DIMENSION = 1536;

    private JdbcTemplate reader;
    private List<ChunkSink> realSinks;
    private TransactionTemplate transactions;
    private String docId;
    private VectorTarget target;

    @BeforeEach
    void setup() {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only a random local research_p3_ database is allowed");
        }
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD"));
        reader = new JdbcTemplate(dataSource);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("w5-it", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MyMetaObjectHandler()));
        config.addMapper(KnowledgeChunkMapper.class);
        var chunks = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config))
                .getMapper(KnowledgeChunkMapper.class);
        var json = new ObjectMapper();
        // 与应用相同：两个落点共用一个数据源，事务由写入器开启
        realSinks = List.of(new RelationalChunkSink(chunks, new HeuristicTokenCounterService(), json),
                new VectorChunkSink(new PgVectorStoreService(new JdbcTemplate(dataSource), json)));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        docId = "d" + s;
        target = new VectorTarget("w5_" + s, "emb", DIMENSION);
        UserContext.set(LoginUser.builder().userId("w5").username("w5").role("admin").build());
        new ChunkIndexWriter(realSinks, transactions).replaceDocument(target, doc(), chunks("v1", 3));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void readersSeeTheOldVersionUntilCommitThenTheNewOne() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var writer = new ChunkIndexWriter(withLast(new PausingSink(written, release, false)), transactions);

        CompletableFuture<Void> replace = CompletableFuture.runAsync(() -> {
            UserContext.set(LoginUser.builder().userId("w5").username("w5").role("admin").build());
            try {
                writer.replaceDocument(target, doc(), chunks("v2", 2));
            } finally {
                UserContext.clear();
            }
        });
        assertTrue(written.await(10, TimeUnit.SECONDS));
        // 两个落点都已删旧插新、尚未提交：另一个连接只看得到旧版本
        assertEquals(List.of("v1", "v1", "v1"), relationalVersions());
        assertEquals(List.of("v1", "v1", "v1"), vectorVersions());

        release.countDown();
        replace.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("v2", "v2"), relationalVersions());
        assertEquals(List.of("v2", "v2"), vectorVersions());
    }

    @Test
    void aFailureAfterBothSinksWroteRollsBothBack() {
        var writer = new ChunkIndexWriter(withLast(new PausingSink(null, null, true)), transactions);
        assertThrows(IllegalStateException.class, () -> writer.replaceDocument(target, doc(), chunks("v2", 2)));
        assertEquals(List.of("v1", "v1", "v1"), relationalVersions());
        assertEquals(List.of("v1", "v1", "v1"), vectorVersions());
    }

    private List<ChunkSink> withLast(ChunkSink sink) {
        List<ChunkSink> sinks = new ArrayList<>(realSinks);
        sinks.add(sink);
        return sinks;
    }

    private List<String> relationalVersions() {
        return reader.queryForList("SELECT split_part(content, ':', 1) FROM t_knowledge_chunk"
                + " WHERE doc_id = ? AND deleted = 0 ORDER BY chunk_index", String.class, docId);
    }

    private List<String> vectorVersions() {
        return reader.queryForList("SELECT split_part(content, ':', 1) FROM t_knowledge_vector"
                + " WHERE collection_name = ? AND metadata->>'doc_id' = ? ORDER BY content",
                String.class, target.partition(), docId);
    }

    private DocumentRef doc() {
        return new DocumentRef(docId, "kb-w5", "fixture.xlsx");
    }

    private List<EmbeddedChunk> chunks(String version, int n) {
        List<EmbeddedChunk> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float[] v = new float[DIMENSION];
            v[i] = 1f;
            String id = version + docId.substring(1) + i;
            list.add(new EmbeddedChunk(ChunkAssembler.restore(id, i, version + ":" + i, version + ":" + i), v));
        }
        return list;
    }

    /**
     * 排在真实落点之后：此时两张表都已删旧插新，由它决定何时提交或让事务失败
     */
    private record PausingSink(CountDownLatch written, CountDownLatch release, boolean fail) implements ChunkSink {

        @Override
        public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
            if (fail) {
                throw new IllegalStateException("third sink failed");
            }
            written.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void deleteDocument(VectorTarget target, DocumentRef doc) {
        }
    }
}
