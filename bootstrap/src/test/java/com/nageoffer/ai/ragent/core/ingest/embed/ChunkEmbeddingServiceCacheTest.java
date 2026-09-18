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
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkEmbeddingServiceCacheTest {

    private static final int DIMENSION = 4;
    private static final VectorTarget TARGET = new VectorTarget("kb_a", "emb-a", DIMENSION);

    private final FakeUpstream upstream = new FakeUpstream();
    private final MemoryCache cache = new MemoryCache();
    private final ChunkEmbeddingService service = new ChunkEmbeddingService(upstream, cache,
            properties(candidate("emb-a", "siliconflow", "Qwen/Qwen3-Embedding-8B"),
                    candidate("emb-b", "siliconflow", "Qwen/Qwen3-Embedding-4B"),
                    candidate("emb-a-alias", "siliconflow", "Qwen/Qwen3-Embedding-8B")));

    @Test
    void repeatedIngestionSendsNothingUpstream() {
        List<Chunk> chunks = chunks("alpha", "beta", "gamma");
        ChunkEmbeddings cold = service.embedWithStats(chunks, TARGET);
        assertEquals(new ChunkEmbeddings.EmbeddingStats(3, 0, 3), cold.stats());
        assertEquals(1, upstream.calls.size());

        ChunkEmbeddings warm = service.embedWithStats(chunks("alpha", "beta", "gamma"), TARGET);
        assertEquals(new ChunkEmbeddings.EmbeddingStats(3, 3, 0), warm.stats());
        assertEquals(1, upstream.calls.size(), "a full hit must not call the upstream at all");
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(cold.chunks().get(i).embedding(), warm.chunks().get(i).embedding());
        }
    }

    @Test
    void onlyChangedTextsAreReembeddedAndOrderIsKept() {
        service.embed(chunks("alpha", "beta", "gamma"), TARGET);
        ChunkEmbeddings next = service.embedWithStats(chunks("alpha", "beta v2", "gamma", "delta"), TARGET);

        assertEquals(List.of("beta v2", "delta"), upstream.calls.get(1));
        assertEquals(new ChunkEmbeddings.EmbeddingStats(4, 2, 2), next.stats());
        assertEquals(2, next.stats().cacheMisses());
        List<String> texts = next.chunks().stream().map(EmbeddedChunk::embeddingText).toList();
        assertEquals(List.of("alpha", "beta v2", "gamma", "delta"), texts);
        for (EmbeddedChunk chunk : next.chunks()) {
            assertArrayEquals(FakeUpstream.vectorOf(chunk.embeddingText()), chunk.embedding());
        }
    }

    @Test
    void duplicateTextsInOneBatchAreSentOnce() {
        ChunkEmbeddings result = service.embedWithStats(chunks("header", "row 1", "header", "row 2", "header"), TARGET);
        assertEquals(List.of("header", "row 1", "row 2"), upstream.calls.get(0));
        assertEquals(new ChunkEmbeddings.EmbeddingStats(5, 0, 3), result.stats());
        assertArrayEquals(result.chunks().get(0).embedding(), result.chunks().get(4).embedding());
    }

    @Test
    void vectorsAreNotSharedAcrossModelsOrDimensionsButAreAcrossAliases() {
        service.embed(chunks("alpha"), TARGET);
        service.embed(chunks("alpha"), new VectorTarget("kb_a", "emb-b", DIMENSION));
        assertEquals(2, upstream.calls.size(), "another model must miss");

        upstream.dimension = 8;
        service.embed(chunks("alpha"), new VectorTarget("kb_a", "emb-a", 8));
        assertEquals(3, upstream.calls.size(), "another dimension must miss");

        // 别名指向同一个供应商模型：键取解析后的模型，命中；知识库（分区）不在键里，同样命中
        ChunkEmbeddings alias = service.embedWithStats(chunks("alpha"), new VectorTarget("kb_other", "emb-a-alias", DIMENSION));
        assertEquals(1, alias.stats().cacheHits());
        assertEquals(3, upstream.calls.size());
        assertTrue(cache.entries.keySet().stream().allMatch(key -> key.startsWith("siliconflow:Qwen/")));
    }

    @Test
    void cacheFailuresFallBackToTheUpstream() {
        cache.failing = true;
        ChunkEmbeddings result = service.embedWithStats(chunks("alpha", "beta"), TARGET);
        assertEquals(new ChunkEmbeddings.EmbeddingStats(2, 0, 2), result.stats());
        assertEquals(2, result.chunks().size());
    }

    @Test
    void wrongDimensionFromUpstreamIsRejectedAndNotCached() {
        upstream.dimension = 3;
        assertThrows(ServiceException.class, () -> service.embed(chunks("alpha"), TARGET));
        assertTrue(cache.entries.isEmpty());
    }

    @Test
    void offlineConstructorNeverTouchesACache() {
        var plain = new ChunkEmbeddingService(upstream);
        plain.embed(chunks("alpha"), TARGET);
        ChunkEmbeddings again = plain.embedWithStats(chunks("alpha"), TARGET);
        assertEquals(0, again.stats().cacheHits());
        assertEquals(2, upstream.calls.size());
    }

    private static List<Chunk> chunks(String... texts) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            chunks.add(ChunkAssembler.restore("c" + i, i, texts[i], texts[i]));
        }
        return chunks;
    }

    private static AIModelProperties.ModelCandidate candidate(String id, String provider, String model) {
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(model);
        return candidate;
    }

    private static AIModelProperties properties(AIModelProperties.ModelCandidate... candidates) {
        var properties = new AIModelProperties();
        properties.getEmbedding().setCandidates(List.of(candidates));
        return properties;
    }

    /** 向量由文本确定，便于核对命中项取回的是同一向量 */
    private static final class FakeUpstream implements EmbeddingService {
        final List<List<String>> calls = new ArrayList<>();
        int dimension = DIMENSION;

        static float[] vectorOf(String text) {
            float[] v = new float[DIMENSION];
            for (int i = 0; i < DIMENSION; i++) v[i] = (text.hashCode() >>> (i * 4) & 0xF) / 16f;
            return v;
        }

        @Override public List<Float> embed(String text) { throw new UnsupportedOperationException(); }
        @Override public List<Float> embed(String text, String modelId) { throw new UnsupportedOperationException(); }
        @Override public List<List<Float>> embedBatch(List<String> texts) { throw new UnsupportedOperationException(); }

        @Override
        public List<List<Float>> embedBatch(List<String> texts, String modelId) {
            calls.add(List.copyOf(texts));
            return texts.stream().map(text -> {
                float[] v = vectorOf(text);
                List<Float> row = new ArrayList<>();
                for (int i = 0; i < dimension; i++) row.add(i < v.length ? v[i] : 0f);
                return row;
            }).toList();
        }
    }

    private static final class MemoryCache implements EmbeddingCache {
        final Map<String, float[]> entries = new HashMap<>();
        boolean failing;

        @Override
        public Map<String, float[]> lookup(String modelId, int dimension, Collection<String> textHashes) {
            if (failing) throw new IllegalStateException("cache down");
            Map<String, float[]> hits = new HashMap<>();
            for (String hash : textHashes) {
                float[] v = entries.get(modelId + "|" + dimension + "|" + hash);
                if (v != null) hits.put(hash, v.clone());
            }
            return hits;
        }

        @Override
        public void store(String modelId, int dimension, Map<String, float[]> vectorsByHash) {
            if (failing) throw new IllegalStateException("cache down");
            vectorsByHash.forEach((hash, v) -> entries.putIfAbsent(modelId + "|" + dimension + "|" + hash, v.clone()));
        }
    }
}
