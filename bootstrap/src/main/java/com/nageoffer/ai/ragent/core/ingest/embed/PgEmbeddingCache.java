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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入缓存的 PostgreSQL 实现：向量存 {@code REAL[]}，按二进制原样往返，不依赖 pgvector 的维度约束，
 * 所以不同维度的模型共用一张表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgEmbeddingCache implements EmbeddingCache {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingCacheProperties properties;

    @Override
    public Map<String, float[]> lookup(String modelId, int dimension, Collection<String> textHashes) {
        if (!properties.isEnabled() || textHashes.isEmpty()) {
            return Map.of();
        }
        String[] hashes = textHashes.toArray(String[]::new);
        Map<String, float[]> hits = new HashMap<>();
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE t_embedding_cache SET last_used = CURRENT_TIMESTAMP"
                            + " WHERE model_id = ? AND dimension = ? AND text_sha256 = ANY(?)"
                            + " RETURNING text_sha256, embedding");
            ps.setString(1, modelId);
            ps.setInt(2, dimension);
            ps.setArray(3, con.createArrayOf("varchar", hashes));
            return ps;
        }, rs -> {
            Array array = rs.getArray(2);
            Float[] boxed = (Float[]) array.getArray();
            float[] vector = new float[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                vector[i] = boxed[i];
            }
            hits.put(rs.getString(1), vector);
        });
        return hits;
    }

    @Override
    public void store(String modelId, int dimension, Map<String, float[]> vectorsByHash) {
        if (!properties.isEnabled() || vectorsByHash.isEmpty()) {
            return;
        }
        List<Map.Entry<String, float[]>> entries = new ArrayList<>(vectorsByHash.entrySet());
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_embedding_cache (model_id, dimension, text_sha256, embedding) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (model_id, dimension, text_sha256) DO UPDATE SET last_used = CURRENT_TIMESTAMP",
                entries, entries.size(), (ps, entry) -> {
                    float[] vector = entry.getValue();
                    Float[] boxed = new Float[vector.length];
                    for (int i = 0; i < vector.length; i++) {
                        boxed[i] = vector[i];
                    }
                    ps.setString(1, modelId);
                    ps.setInt(2, dimension);
                    ps.setString(3, entry.getKey());
                    ps.setArray(4, ps.getConnection().createArrayOf("float4", boxed));
                });
        evictOverflow();
    }

    /**
     * 超出容量时删掉最久未用的条目；只在写入后检查，读路径不做计数
     */
    private void evictOverflow() {
        long max = properties.getMaxEntries();
        if (max <= 0) {
            return;
        }
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int evicted = jdbcTemplate.update("""
                DELETE FROM t_embedding_cache c
                USING (SELECT model_id, dimension, text_sha256 FROM t_embedding_cache
                       ORDER BY last_used, create_time
                       LIMIT GREATEST((SELECT count(*) FROM t_embedding_cache) - ?, 0)) old
                WHERE c.model_id = old.model_id AND c.dimension = old.dimension AND c.text_sha256 = old.text_sha256
                """, max);
        if (evicted > 0) {
            log.info("嵌入缓存超出容量，按最近使用淘汰 {} 条（上限 {}）", evicted, max);
        }
    }
}
