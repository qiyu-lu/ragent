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

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 只验证 SELECT/UPDATE 的归属与结果映射；INSERT ON CONFLICT 与 DDL 由 PostgreSQL 验证。
 */
class ResearchEvidenceStoreTest {
    private JdbcTemplate jdbc;
    private ResearchEvidenceStore store;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        store = new ResearchEvidenceStore(jdbc, new ObjectMapper());
        jdbc.execute("CREATE TABLE t_research_run (id VARCHAR PRIMARY KEY, owner_user_id VARCHAR, brief TEXT)");
        jdbc.execute("""
                CREATE TABLE t_research_evidence (
                    evidence_id VARCHAR PRIMARY KEY, run_id VARCHAR, kb_id VARCHAR, doc_id VARCHAR,
                    document_name VARCHAR, document_version VARCHAR, chunk_ids TEXT, content_hash VARCHAR,
                    source_text TEXT, text TEXT, source_location TEXT, source_metadata_hash VARCHAR,
                    retrieved_by_task_id VARCHAR, truncated BOOLEAN, read BOOLEAN, source_extent VARCHAR,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.update("INSERT INTO t_research_run VALUES (?, ?, ?)", "run-a", "owner-a",
                "{\"goal\":\"compare\",\"outputType\":\"REPORT\",\"constraints\":[],\"allowedKbIds\":[\"kb-a\"]}");
        jdbc.update("INSERT INTO t_research_run VALUES (?, ?, ?)", "run-b", "owner-b",
                "{\"goal\":\"compare\",\"outputType\":\"REPORT\",\"constraints\":[],\"allowedKbIds\":[\"kb-a\"]}");
        jdbc.update("""
                INSERT INTO t_research_evidence
                    (evidence_id, run_id, kb_id, doc_id, document_name, document_version, chunk_ids,
                     content_hash, source_text, text, source_location, source_metadata_hash,
                     retrieved_by_task_id, truncated, read, source_extent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "ev-a", "run-a", "kb-a", "doc-a", "paper.md", "V1", "[\"chunk-a\"]",
                SecureUtil.sha256("full stored body"), "full stored body", "full", "{\"section_path\":[\"Methods\"]}",
                "metadata-hash", "worker-a", true, false, "CHUNK");
    }

    @Test
    void briefRequiresActualDatabaseOwnership() {
        assertEquals("compare", store.requireBrief("run-a", "owner-a").goal());
        assertThrows(ClientException.class, () -> store.requireBrief("run-a", "owner-b"));
        assertThrows(ClientException.class, () -> store.requireBrief("run-missing", "owner-a"));
        assertThrows(ClientException.class, () -> store.requireBrief("run-a", ""));
    }

    @Test
    void evidenceCannotBeReadByAnotherOwnerOrAnotherRun() {
        var saved = store.find("run-a", "owner-a", "ev-a");
        assertEquals("full stored body", saved.sourceText());
        assertEquals("V1", saved.evidence().documentVersion());
        assertThrows(ClientException.class, () -> store.find("run-a", "owner-b", "ev-a"));
        assertThrows(ClientException.class, () -> store.find("run-b", "owner-b", "ev-a"));
        assertThrows(ClientException.class, () -> store.find("run-a", "owner-a", "ev-invented"));
    }

    @Test
    void readUpdatesDeliveredTextAndIsIdempotent() {
        var saved = store.find("run-a", "owner-a", "ev-a");
        var read = store.markRead("owner-a", saved);
        assertEquals("full stored body", read.text());
        assertTrue(read.read());
        assertFalse(read.truncated());
        assertEquals(read, store.markRead("owner-a", saved));
        assertEquals("worker-a", read.retrievedByTaskId());
    }

    @Test
    void anotherOwnerCannotMarkEvidenceRead() {
        var saved = store.find("run-a", "owner-a", "ev-a");
        assertThrows(ClientException.class, () -> store.markRead("owner-b", saved));
        assertFalse(store.find("run-a", "owner-a", "ev-a").evidence().read());
    }

    @Test
    void oversizedSnapshotReadRetainsTruncationFlag() {
        jdbc.update("UPDATE t_research_evidence SET source_text = ? WHERE evidence_id = ?",
                "x".repeat(17000), "ev-a");
        var read = store.markRead("owner-a", store.find("run-a", "owner-a", "ev-a"));
        assertEquals(16000, read.text().length());
        assertTrue(read.truncated());
    }
}
