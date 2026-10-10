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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全文通道的 PostgreSQL 读写：{@code t_knowledge_chunk.content_tsv}、{@code t_kq_term_stats}、{@code t_kq_kb_stats}
 * <p>
 * 只认未删除、已启用的块；检索还要求所属文档未删除且已启用，与向量库只保留启用块的口径一致
 */
@Component
@RequiredArgsConstructor
public class FullTextStore {

    private static final String LIVE_CHUNK = "c.deleted = 0 AND c.enabled = 1 AND c.content_tsv IS NOT NULL";

    private static final String INDEX_SOURCE = """
            SELECT c.id, d.doc_name, COALESCE(NULLIF(c.embedding_text, ''), c.content) AS body
            FROM t_knowledge_chunk c LEFT JOIN t_knowledge_document d ON d.id = c.doc_id
            WHERE c.deleted = 0 AND\s""";

    private final JdbcTemplate jdbc;

    /**
     * 索引一块要用的文本：文档名 + 向量文本（章节路径 + 正文）
     */
    public record IndexSource(String chunkId, String docName, String body) {
    }

    /**
     * 命中块的轻量行：只带 BM25 要用的长度与查询词项的词频
     */
    public record Match(String chunkId, int length, Map<String, Integer> termFreqs) {
    }

    /**
     * 回表取到的块正文与归属
     */
    public record ChunkText(String chunkId, String docId, String collectionName, String content) {
    }

    /**
     * 作用域内的语料统计：块数、词项位置总数、查询词项的文档频率
     */
    public record CorpusStats(long chunkCount, long totalTerms, Map<String, Long> docFreqs) {

        public double avgLength() {
            return chunkCount <= 0 ? 0D : (double) totalTerms / chunkCount;
        }
    }

    // ------------------------------------------------------------------ 作用域

    /**
     * 检索作用域给的是 collection，统计与索引按 kb_id 存：经 {@code t_knowledge_base.collection_name} 映射
     */
    public List<String> kbIdsOf(Collection<String> collections) {
        if (collections.isEmpty()) {
            return List.of();
        }
        return query("SELECT id FROM t_knowledge_base WHERE deleted = 0 AND collection_name = ANY (?) ORDER BY id",
                (con, ps) -> ps.setArray(1, textArray(con, collections)),
                rs -> rs.getString(1));
    }

    public List<String> liveKbIds() {
        return jdbc.queryForList("SELECT id FROM t_knowledge_base WHERE deleted = 0 ORDER BY id", String.class);
    }

    // ------------------------------------------------------------------ 索引

    public List<IndexSource> sourcesOfDocument(String docId) {
        return jdbc.query(INDEX_SOURCE + "c.doc_id = ? ORDER BY c.chunk_index, c.id",
                (rs, i) -> new IndexSource(rs.getString(1), rs.getString(2), rs.getString(3)), docId);
    }

    public List<IndexSource> sourcesOfChunks(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return query(INDEX_SOURCE + "c.id = ANY (?) ORDER BY c.id",
                (con, ps) -> ps.setArray(1, textArray(con, chunkIds)),
                rs -> new IndexSource(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /**
     * 库里有未删除块的文档，重建时逐个文档写，内存只装一个文档的块
     */
    public List<String> documentIdsOfKb(String kbId) {
        return jdbc.queryForList("SELECT DISTINCT doc_id FROM t_knowledge_chunk WHERE kb_id = ? AND deleted = 0 ORDER BY doc_id",
                String.class, kbId);
    }

    /**
     * 写入 tsvector 字面量（应用侧分词的结果，不经 PostgreSQL 解析器再切一次）
     */
    public void writeTsvectors(Map<String, String> literalByChunkId) {
        if (literalByChunkId.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(literalByChunkId.size());
        literalByChunkId.forEach((id, literal) -> args.add(new Object[]{literal, id}));
        jdbc.batchUpdate("UPDATE t_knowledge_chunk SET content_tsv = CAST(? AS tsvector) WHERE id = ?", args);
    }

    /**
     * 按库重算词项的文档频率与块数、位置总数；同一个库的重算用事务级咨询锁串行
     * <p>
     * 必须在事务里调用（锁随事务释放）
     */
    public void refreshStats(String kbId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "t_kq_term_stats:" + kbId);
        jdbc.update("DELETE FROM t_kq_term_stats WHERE kb_id = ?", kbId);
        jdbc.update("""
                INSERT INTO t_kq_term_stats (kb_id, term, doc_freq)
                SELECT ?, u.lexeme, count(*)
                FROM t_knowledge_chunk c CROSS JOIN LATERAL unnest(c.content_tsv) u
                WHERE c.kb_id = ? AND %s
                GROUP BY u.lexeme""".formatted(LIVE_CHUNK), kbId, kbId);
        jdbc.update("""
                INSERT INTO t_kq_kb_stats (kb_id, chunk_count, total_terms, update_time)
                SELECT ?, count(*), COALESCE(sum(t.len), 0), CURRENT_TIMESTAMP
                FROM (SELECT (SELECT COALESCE(sum(COALESCE(cardinality(u.positions), 1)), 0)
                              FROM unnest(c.content_tsv) u) AS len
                      FROM t_knowledge_chunk c WHERE c.kb_id = ? AND %s) t
                ON CONFLICT (kb_id) DO UPDATE SET chunk_count = EXCLUDED.chunk_count,
                    total_terms = EXCLUDED.total_terms, update_time = EXCLUDED.update_time""".formatted(LIVE_CHUNK),
                kbId, kbId);
    }

    // ------------------------------------------------------------------ 检索

    /**
     * 作用域内的统计：多个库合在一起算（N 与位置总数相加，文档频率相加），等于把作用域当成一个语料
     */
    public CorpusStats stats(List<String> kbIds, List<String> terms) {
        long[] totals = {0L, 0L};
        query("SELECT COALESCE(sum(chunk_count), 0), COALESCE(sum(total_terms), 0) FROM t_kq_kb_stats WHERE kb_id = ANY (?)",
                (con, ps) -> ps.setArray(1, textArray(con, kbIds)),
                rs -> {
                    totals[0] = rs.getLong(1);
                    totals[1] = rs.getLong(2);
                    return null;
                });
        Map<String, Long> docFreqs = new HashMap<>();
        if (!terms.isEmpty()) {
            query("SELECT term, sum(doc_freq) FROM t_kq_term_stats WHERE kb_id = ANY (?) AND term = ANY (?) GROUP BY term",
                    (con, ps) -> {
                        ps.setArray(1, textArray(con, kbIds));
                        ps.setArray(2, textArray(con, terms));
                    },
                    rs -> docFreqs.put(rs.getString(1), rs.getLong(2)));
        }
        return new CorpusStats(totals[0], totals[1], docFreqs);
    }

    /**
     * 命中任一查询词项的块（{@code @@} 走 GIN 索引），只取轻量行；超过上限时按 ts_rank 截断
     */
    public List<Match> matches(List<String> kbIds, List<String> terms, String tsquery,
                               List<String> documentIds, int limit) {
        boolean byDocument = documentIds != null && !documentIds.isEmpty();
        String sql = """
                SELECT c.id,
                       (SELECT COALESCE(sum(COALESCE(cardinality(u.positions), 1)), 0) FROM unnest(c.content_tsv) u) AS len,
                       ARRAY(SELECT u.lexeme FROM unnest(c.content_tsv) u WHERE u.lexeme = ANY (?) ORDER BY u.lexeme) AS terms,
                       ARRAY(SELECT COALESCE(cardinality(u.positions), 1) FROM unnest(c.content_tsv) u
                             WHERE u.lexeme = ANY (?) ORDER BY u.lexeme) AS freqs
                FROM t_knowledge_chunk c
                JOIN t_knowledge_document d ON d.id = c.doc_id AND d.deleted = 0 AND d.enabled = 1
                WHERE c.kb_id = ANY (?) AND %s AND c.content_tsv @@ CAST(? AS tsquery)%s
                ORDER BY ts_rank(c.content_tsv, CAST(? AS tsquery)) DESC, c.id
                LIMIT ?""".formatted(LIVE_CHUNK, byDocument ? " AND c.doc_id = ANY (?)" : "");
        return query(sql, (con, ps) -> {
            int i = 1;
            ps.setArray(i++, textArray(con, terms));
            ps.setArray(i++, textArray(con, terms));
            ps.setArray(i++, textArray(con, kbIds));
            ps.setString(i++, tsquery);
            if (byDocument) {
                ps.setArray(i++, textArray(con, documentIds));
            }
            ps.setString(i++, tsquery);
            ps.setInt(i, limit);
        }, rs -> {
            String[] lexemes = (String[]) rs.getArray("terms").getArray();
            Object[] freqs = (Object[]) rs.getArray("freqs").getArray();
            Map<String, Integer> termFreqs = new LinkedHashMap<>();
            for (int k = 0; k < lexemes.length; k++) {
                termFreqs.put(lexemes[k], ((Number) freqs[k]).intValue());
            }
            return new Match(rs.getString("id"), rs.getInt("len"), termFreqs);
        });
    }

    /**
     * 回表取正文与归属，按传入顺序返回
     */
    public List<ChunkText> texts(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        Map<String, ChunkText> byId = new HashMap<>();
        query("""
                SELECT c.id, c.doc_id, kb.collection_name, c.content
                FROM t_knowledge_chunk c JOIN t_knowledge_base kb ON kb.id = c.kb_id
                WHERE c.id = ANY (?)""",
                (con, ps) -> ps.setArray(1, textArray(con, chunkIds)),
                rs -> byId.put(rs.getString(1),
                        new ChunkText(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))));
        return chunkIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    // ------------------------------------------------------------------ JDBC

    @FunctionalInterface
    private interface Binder {
        void bind(Connection con, PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, Row<T> row) {
        return jdbc.execute((ConnectionCallback<List<T>>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                binder.bind(con, ps);
                List<T> out = new ArrayList<>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(row.map(rs));
                    }
                }
                return out;
            }
        });
    }

    private static Array textArray(Connection con, Collection<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray());
    }
}
