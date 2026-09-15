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

package com.nageoffer.ai.ragent.ironore.agent;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@Service
@RequiredArgsConstructor
public class RagTaskKnowledge implements TaskKnowledge {
    private final JdbcTemplate jdbc;
    private final VectorRetrieverService vectors;
    private final RerankService rerank;
    private final ObjectMapper json;

    @Override
    public List<Document> documents() {
        return jdbc.query("SELECT d.id,d.doc_name,d.document_version FROM t_knowledge_document d JOIN t_knowledge_base b ON b.id=d.kb_id WHERE d.deleted=0 AND d.enabled=1 AND d.status='success' AND b.deleted=0 ORDER BY d.doc_name LIMIT 200",
                (rs, n) -> new Document(rs.getString("id"), rs.getString("doc_name"),
                        Objects.requireNonNullElse(rs.getString("document_version"), "未标注版本")));
    }

    @Override
    public Document document(String id) {
        return jdbc.query("SELECT d.id,d.doc_name,d.document_version FROM t_knowledge_document d JOIN t_knowledge_base b ON b.id=d.kb_id WHERE d.id=? AND d.deleted=0 AND d.enabled=1 AND d.status='success' AND b.deleted=0",
                (rs, n) -> new Document(rs.getString("id"), rs.getString("doc_name"),
                        Objects.requireNonNullElse(rs.getString("document_version"), "未标注版本")), id).stream()
                .findFirst().orElseThrow(() -> new ClientException("规程文档不可用，请选择已完成入库且启用的文档"));
    }

    @Override
    public List<Evidence> search(Document pinned, String query) {
        requireVersion(pinned);
        String collection = jdbc.queryForObject("SELECT b.collection_name FROM t_knowledge_base b JOIN t_knowledge_document d ON b.id=d.kb_id WHERE d.id=? AND b.deleted=0",
                String.class, pinned.id());
        List<RetrievedChunk> candidates = vectors.retrieve(RetrieveRequest.builder().query(query).topK(20)
                .collectionName(collection).metadataFilters(Map.of("doc_id", pinned.id())).build());
        List<RetrievedChunk> current = new ArrayList<>();
        for (RetrievedChunk candidate : candidates) {
            List<RetrievedChunk> rows = jdbc.query("SELECT id,content,embedding_text FROM t_knowledge_chunk WHERE id=? AND doc_id=? AND enabled=1 AND deleted=0",
                    (rs, n) -> RetrievedChunk.builder().id(rs.getString("id")).text(rs.getString("content"))
                            .rankingText(pinned.name() + "\n" + Objects.requireNonNullElse(rs.getString("embedding_text"), rs.getString("content")))
                            .score(candidate.getScore()).build(), candidate.getId(), pinned.id());
            current.addAll(rows);
        }
        if (current.isEmpty()) return List.of();
        return rerank.rerank(query, current, 5).stream().map(hit -> evidence(pinned, hit.getId())).toList();
    }

    @Override
    public void validate(Document pinned, List<Evidence> evidence) {
        requireVersion(pinned);
        for (Evidence item : evidence) {
            Evidence current = evidence(pinned, item.id());
            if (!pinned.id().equals(item.documentId()) || !pinned.version().equals(item.documentVersion())
                    || !current.contentHash().equals(item.contentHash())
                    || !Objects.equals(current.sheetName(), item.sheetName())
                    || !Objects.equals(current.cellRange(), item.cellRange())) {
                throw new ClientException("引用的规程内容已改变，请重新检索并确认草稿");
            }
        }
    }

    private void requireVersion(Document pinned) {
        if (!pinned.version().equals(document(pinned.id()).version())) {
            throw new ClientException("规程版本已改变，请按当前版本重新建立任务");
        }
    }

    private Evidence evidence(Document doc, String id) {
        return jdbc.query("SELECT content,metadata FROM t_knowledge_chunk WHERE id=? AND doc_id=? AND enabled=1 AND deleted=0",
                (rs, n) -> {
                    String content = rs.getString("content");
                    String metadata = rs.getString("metadata");
                    String sheet = null;
                    String cells = null;
                    if (metadata != null && !metadata.isBlank()) {
                        try {
                            var node = json.readTree(metadata);
                            sheet = node.path("sheet_name").asText(null);
                            cells = node.path("cell_range").asText(null);
                        } catch (Exception invalid) { throw new IllegalStateException("分块来源元数据无效", invalid); }
                    }
                    return new Evidence(id, doc.id(), doc.version(), sheet, cells,
                            content.substring(0, Math.min(content.length(), 1600)), DigestUtil.sha256Hex(content));
                }, id, doc.id()).stream().findFirst().orElseThrow(() -> new ClientException("引用的规程片段已停用或删除"));
    }
}
