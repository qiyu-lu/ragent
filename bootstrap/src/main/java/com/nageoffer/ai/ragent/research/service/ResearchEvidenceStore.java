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

package com.nageoffer.ai.ragent.research.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.EvidenceSnapshot;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 证据归属检查和持久化。运行器、租约及终态竞争由 P3 接续实现。
 */
@Repository
@RequiredArgsConstructor
public class ResearchEvidenceStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchBrief requireBrief(String runId, String ownerUserId) {
        requireIdentity(runId, ownerUserId);
        List<String> rows = jdbcTemplate.query(
                "SELECT brief::text FROM t_research_run WHERE id = ? AND owner_user_id = ?",
                (rs, row) -> rs.getString(1), runId, ownerUserId);
        if (rows.isEmpty()) {
            throw new ClientException("研究任务不存在或无权访问");
        }
        return parse(rows.get(0), ResearchBrief.class);
    }

    public EvidenceSnapshot find(String runId, String ownerUserId, String evidenceId) {
        requireIdentity(runId, ownerUserId);
        List<EvidenceSnapshot> rows = jdbcTemplate.query("""
                SELECT e.* FROM t_research_evidence e
                JOIN t_research_run r ON r.id = e.run_id
                WHERE e.run_id = ? AND r.owner_user_id = ? AND e.evidence_id = ?
                """, (rs, row) -> readSnapshot(rs), runId, ownerUserId, evidenceId);
        if (rows.isEmpty()) {
            throw new ClientException("证据不存在或不属于本次研究任务");
        }
        return rows.get(0);
    }

    public List<EvidenceRecord> readSources(String runId, String ownerUserId) {
        requireBrief(runId, ownerUserId);
        return jdbcTemplate.query("""
                SELECT e.* FROM t_research_evidence e JOIN t_research_run r ON r.id = e.run_id
                WHERE e.run_id = ? AND r.owner_user_id = ? AND e.read = TRUE
                ORDER BY e.create_time, e.evidence_id
                """, (rs, row) -> readSnapshot(rs).evidence(), runId, ownerUserId);
    }

    public EvidenceSnapshot save(String ownerUserId, EvidenceSnapshot snapshot) {
        EvidenceRecord e = snapshot.evidence();
        insert(ownerUserId, snapshot, null);
        return find(e.runId(), ownerUserId, e.evidenceId());
    }

    public Optional<EvidenceSnapshot> findExpansion(String runId, String ownerUserId, String originEvidenceId) {
        requireIdentity(runId, ownerUserId);
        return jdbcTemplate.query("""
                SELECT e.* FROM t_research_evidence e JOIN t_research_run r ON r.id = e.run_id
                WHERE e.run_id = ? AND r.owner_user_id = ? AND e.origin_evidence_id = ?
                """, (rs, row) -> readSnapshot(rs), runId, ownerUserId, originEvidenceId).stream().findFirst();
    }

    public EvidenceSnapshot saveExpansion(String ownerUserId, String originEvidenceId, EvidenceSnapshot snapshot) {
        if (originEvidenceId == null || originEvidenceId.equals(snapshot.evidence().evidenceId())) {
            throw new ClientException("邻接证据必须关联不同的原候选");
        }
        insert(ownerUserId, snapshot, originEvidenceId);
        return findExpansion(snapshot.evidence().runId(), ownerUserId, originEvidenceId)
                .orElseThrow(() -> new ClientException("原候选不存在或不属于本次研究"));
    }

    private void insert(String ownerUserId, EvidenceSnapshot snapshot, String originEvidenceId) {
        EvidenceRecord e = snapshot.evidence();
        requireIdentity(e.runId(), ownerUserId);
        // 并发子任务保存同一证据时保留第一次快照和首次来源任务。
        jdbcTemplate.update("""
                INSERT INTO t_research_evidence
                    (evidence_id, run_id, kb_id, doc_id, document_name, document_version,
                     chunk_ids, content_hash, source_text, text, source_location,
                     source_metadata_hash, retrieved_by_task_id, truncated, read, source_extent, origin_evidence_id)
                SELECT ?, r.id, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?
                FROM t_research_run r WHERE r.id = ? AND r.owner_user_id = ?
                  AND (?::text IS NULL OR EXISTS (SELECT 1 FROM t_research_evidence p
                          WHERE p.evidence_id = ? AND p.run_id = r.id))
                ON CONFLICT DO NOTHING
                """, e.evidenceId(), e.kbId(), e.docId(), e.documentName(), e.documentVersion(),
                json(e.chunkIds()), e.contentHash(), snapshot.sourceText(), e.text(),
                json(e.sourceLocation()), snapshot.sourceMetadataHash(), e.retrievedByTaskId(),
                e.truncated(), e.read(), e.sourceExtent().name(), originEvidenceId,
                e.runId(), ownerUserId, originEvidenceId, originEvidenceId);
    }

    public EvidenceRecord markRead(String ownerUserId, EvidenceSnapshot snapshot) {
        EvidenceRecord e = snapshot.evidence();
        requireIdentity(e.runId(), ownerUserId);
        String text = EvidenceText.preview(snapshot.sourceText(), EvidenceText.READ_MAX_CHARS);
        jdbcTemplate.update("""
                UPDATE t_research_evidence e
                SET text = ?, truncated = ?, read = TRUE, update_time = CURRENT_TIMESTAMP
                WHERE e.evidence_id = ? AND e.run_id = ?
                  AND EXISTS (SELECT 1 FROM t_research_run r
                              WHERE r.id = e.run_id AND r.owner_user_id = ?)
                """, text, text.length() < snapshot.sourceText().length(),
                e.evidenceId(), e.runId(), ownerUserId);
        return find(e.runId(), ownerUserId, e.evidenceId()).evidence();
    }

    private EvidenceSnapshot readSnapshot(ResultSet rs) throws SQLException {
        EvidenceRecord e = new EvidenceRecord(
                rs.getString("run_id"), rs.getString("evidence_id"), rs.getString("kb_id"),
                rs.getString("doc_id"), rs.getString("document_name"), rs.getString("document_version"),
                parse(rs.getString("chunk_ids"), new TypeReference<List<String>>() { }),
                rs.getString("content_hash"), rs.getString("text"),
                parse(rs.getString("source_location"), new TypeReference<Map<String, Object>>() { }),
                rs.getString("retrieved_by_task_id"), rs.getBoolean("truncated"), rs.getBoolean("read"),
                EvidenceRecord.SourceExtent.valueOf(rs.getString("source_extent")));
        return new EvidenceSnapshot(e, rs.getString("source_text"), rs.getString("source_metadata_hash"));
    }

    private void requireIdentity(String runId, String ownerUserId) {
        if (runId == null || runId.isBlank() || ownerUserId == null || ownerUserId.isBlank()) {
            throw new ClientException("研究任务和用户标识不能为空");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("研究数据序列化失败", e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("研究数据反序列化失败", e);
        }
    }

    private <T> T parse(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("证据数据反序列化失败", e);
        }
    }
}
