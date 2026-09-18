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

package com.nageoffer.ai.ragent.core.ingest.embed;

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 嵌入缓存表的存储语义：向量按位原样往返、键含模型与维度、先写者保留、按最近使用淘汰；
 * 以及接上真实表后，重复入库不再调用上游
 */
@EnabledIfEnvironmentVariable(named = "RESEARCH_P3_TEST_URL", matches = ".+")
class EmbeddingCachePostgresIT {

    private JdbcTemplate jdbc;
    private EmbeddingCacheProperties properties;
    private PgEmbeddingCache cache;

    @BeforeEach
    void setup() {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only a random local research_p3_ database is allowed");
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD")));
        // 随机临时库，本类独占这张表
        jdbc.update("DELETE FROM t_embedding_cache");
        properties = new EmbeddingCacheProperties();
        cache = new PgEmbeddingCache(jdbc, properties);
    }

    @Test
    void vectorsRoundTripBitForBitAndKeysIncludeModelAndDimension() {
        float[] tricky = {0.1f, -0.30000001f, 1e-8f, Float.MIN_VALUE, -0f, 3.4028235e38f};
        cache.store("sf:model-a", 6, Map.of("h1", tricky));

        float[] back = cache.lookup("sf:model-a", 6, List.of("h1", "h-missing")).get("h1");
        assertEquals(tricky.length, back.length);
        for (int i = 0; i < tricky.length; i++) {
            assertEquals(Float.floatToRawIntBits(tricky[i]), Float.floatToRawIntBits(back[i]), "index " + i);
        }
        assertEquals(Set.of("h1"), cache.lookup("sf:model-a", 6, List.of("h1", "h-missing")).keySet());
        assertEquals(Map.of(), cache.lookup("sf:model-b", 6, List.of("h1")));
        assertEquals(Map.of(), cache.lookup("sf:model-a", 5, List.of("h1")));

        // 同键再写保留先写入的向量
        cache.store("sf:model-a", 6, Map.of("h1", new float[]{9, 9, 9, 9, 9, 9}));
        assertArrayEquals(tricky, cache.lookup("sf:model-a", 6, List.of("h1")).get("h1"));

        // 维度与向量长度不符的行进不了表
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO t_embedding_cache (model_id, dimension, text_sha256, embedding) VALUES ('m', 3, repeat('a', 64), ARRAY[1,2]::real[])"));
    }

    @Test
    void overflowEvictsTheLeastRecentlyUsed() throws InterruptedException {
        properties.setMaxEntries(3);
        for (String hash : List.of("a", "b", "c")) {
            cache.store("sf:m", 2, Map.of(hash, new float[]{1, 2}));
            Thread.sleep(5);
        }
        cache.lookup("sf:m", 2, List.of("a"));  // a 变为最近使用，b 最旧
        Thread.sleep(5);
        cache.store("sf:m", 2, Map.of("d", new float[]{3, 4}));

        List<String> left = jdbc.queryForList("SELECT text_sha256 FROM t_embedding_cache ORDER BY text_sha256", String.class);
        assertEquals(List.of("a", "c", "d"), left);
    }

    @Test
    void disabledCacheNeitherReadsNorWrites() {
        properties.setEnabled(false);
        cache.store("sf:m", 2, Map.of("a", new float[]{1, 2}));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM t_embedding_cache", Integer.class));
        assertEquals(Map.of(), cache.lookup("sf:m", 2, List.of("a")));
    }

    @Test
    void reingestionAgainstTheRealTableCallsTheUpstreamOnlyForNewText() {
        List<List<String>> upstreamCalls = new ArrayList<>();
        EmbeddingService upstream = new EmbeddingService() {
            @Override public List<Float> embed(String text) { throw new UnsupportedOperationException(); }
            @Override public List<Float> embed(String text, String modelId) { throw new UnsupportedOperationException(); }
            @Override public List<List<Float>> embedBatch(List<String> texts) { throw new UnsupportedOperationException(); }
            @Override public List<List<Float>> embedBatch(List<String> texts, String modelId) {
                upstreamCalls.add(List.copyOf(texts));
                return texts.stream().map(t -> List.of((float) t.length(), 1f, 0.5f)).toList();
            }
        };
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("emb");
        candidate.setProvider("siliconflow");
        candidate.setModel("Qwen/Qwen3-Embedding-8B");
        var models = new AIModelProperties();
        models.getEmbedding().setCandidates(List.of(candidate));
        var service = new ChunkEmbeddingService(upstream, cache, models);
        var target = new VectorTarget("kb_it", "emb", 3);

        service.embed(chunks("第一段", "第二段", "第三段"), target);
        ChunkEmbeddings repeat = service.embedWithStats(chunks("第一段", "第二段", "第三段"), target);
        ChunkEmbeddings edited = service.embedWithStats(chunks("第一段", "第二段（修订）", "第三段"), target);

        assertEquals(List.of(List.of("第一段", "第二段", "第三段"), List.of("第二段（修订）")), upstreamCalls);
        assertEquals(new ChunkEmbeddings.EmbeddingStats(3, 3, 0), repeat.stats());
        assertEquals(new ChunkEmbeddings.EmbeddingStats(3, 2, 1), edited.stats());
        assertEquals(List.of("siliconflow:Qwen/Qwen3-Embedding-8B"),
                jdbc.queryForList("SELECT DISTINCT model_id FROM t_embedding_cache", String.class));
    }

    private static List<Chunk> chunks(String... texts) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            chunks.add(ChunkAssembler.restore("c" + i, i, texts[i], texts[i]));
        }
        return chunks;
    }
}
