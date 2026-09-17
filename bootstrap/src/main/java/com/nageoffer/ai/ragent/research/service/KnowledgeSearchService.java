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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.EvidenceSnapshot;
import com.nageoffer.ai.ragent.research.model.KnowledgeSearchHit;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * search_knowledge 的纯知识服务。run/owner/task 标识由执行器提供，不能来自模型工具参数。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {
    private final MultiChannelRetrievalEngine retrievalEngine;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ResearchSourceCatalog sourceCatalog;
    private final ResearchEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;

    public List<KnowledgeSearchHit> search(String runId, String ownerUserId, String taskId,
                                          String query, List<String> narrowedKbIds, int limit) {
        return search(runId, ownerUserId, taskId, query, narrowedKbIds, List.of(), limit);
    }

    public List<KnowledgeSearchHit> search(String runId, String ownerUserId, String taskId,
                                          String query, List<String> narrowedKbIds,
                                          List<String> narrowedDocIds, int limit) {
        ResearchBrief brief = evidenceStore.requireBrief(runId, ownerUserId);
        if (query == null || query.isBlank() || query.length() > 10000
                || taskId == null || taskId.isBlank() || limit < 1 || limit > 20) {
            throw new ClientException("检索问题、任务标识或条数无效（条数应为 1—20）");
        }
        Set<String> allowed = Set.copyOf(brief.allowedKbIds());
        List<String> selected = narrowedKbIds == null || narrowedKbIds.isEmpty()
                ? brief.allowedKbIds() : narrowedKbIds;
        if (selected.stream().anyMatch(id -> id == null || !allowed.contains(id))) {
            throw new ClientException("检索范围不能超出研究任务允许的知识库");
        }
        List<KnowledgeBaseDO> active = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, selected)
                        .eq(KnowledgeBaseDO::getDeleted, 0));
        Set<String> selectedSet = Set.copyOf(selected);
        // 知识库当前为全局共享；createdBy 是审计字段，不是租户读取授权。
        List<String> collections = active.stream()
                .filter(kb -> selectedSet.contains(kb.getId()) && !Integer.valueOf(1).equals(kb.getDeleted()))
                .map(KnowledgeBaseDO::getCollectionName)
                .filter(name -> name != null && !name.isBlank()).distinct().toList();
        if (collections.isEmpty()) {
            throw new ClientException("研究范围内没有可用的知识库");
        }
        Set<String> activeKbIds = active.stream()
                .filter(kb -> selectedSet.contains(kb.getId()) && !Integer.valueOf(1).equals(kb.getDeleted())
                        && kb.getCollectionName() != null && !kb.getCollectionName().isBlank())
                .map(KnowledgeBaseDO::getId).collect(java.util.stream.Collectors.toSet());
        List<String> documents = resolveDocuments(brief, narrowedDocIds, activeKbIds);
        Set<String> documentSet = Set.copyOf(documents);
        RetrievalBudget budget = new RetrievalBudget(Math.max(20, limit), 40, limit);
        var result = documents.isEmpty()
                ? retrievalEngine.retrieveScopedKnowledgeChannels(query, budget, collections)
                : retrievalEngine.retrieveScopedKnowledgeChannels(query, budget, collections, documents);
        List<KnowledgeSearchHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RetrievedChunk candidate : result.chunks()) {
            if (hits.size() >= limit) {
                break;
            }
            if (candidate.getId() == null || candidate.getId().isBlank()
                    || !collections.contains(candidate.getCollectionName())) {
                throw new ClientException("检索结果缺少可信来源或超出允许范围");
            }
            var source = sourceCatalog.load(candidate.getId(), activeKbIds, documentSet);
            if (!Objects.equals(source.collectionName(), candidate.getCollectionName())) {
                throw new ClientException("检索索引与数据库来源不一致");
            }
            // 使用数据库正文而非向量/重排摘录；身份包含任务、文档、版本、块及正文/位置 hash。
            String evidenceId = stableId(runId, source);
            if (!seen.add(evidenceId)) {
                continue;
            }
            String text = EvidenceText.preview(source.chunk().getContent(), EvidenceText.SEARCH_MAX_CHARS);
            EvidenceRecord evidence = new EvidenceRecord(runId, evidenceId, source.chunk().getKbId(),
                    source.document().getId(), source.document().getDocName(),
                    source.document().getDocumentVersion(), List.of(source.chunk().getId()),
                    source.contentHash(), text, source.location(), taskId,
                    text.length() < source.chunk().getContent().length(), false, source.extent());
            EvidenceSnapshot stored = evidenceStore.save(ownerUserId,
                    new EvidenceSnapshot(evidence, source.chunk().getContent(), source.metadataHash()));
            EvidenceRecord saved = stored.evidence();
            String summary = EvidenceText.preview(stored.sourceText(), EvidenceText.SEARCH_MAX_CHARS);
            hits.add(new KnowledgeSearchHit(saved.evidenceId(), saved.kbId(), saved.docId(),
                    saved.documentName(), saved.documentVersion(), summary,
                    summary.length() < stored.sourceText().length(), saved.sourceLocation(), saved.sourceExtent()));
        }
        return List.copyOf(hits);
    }

    public List<String> validateDocumentScope(String runId, String owner, List<String> requested) {
        ResearchBrief brief = evidenceStore.requireBrief(runId, owner);
        return resolveDocuments(brief, requested, Set.copyOf(brief.allowedKbIds()));
    }

    private List<String> resolveDocuments(ResearchBrief brief, List<String> requested, Set<String> kbIds) {
        boolean explicit = requested != null && !requested.isEmpty();
        List<String> selected = explicit ? requested : brief.allowedDocIds();
        if (selected.isEmpty()) {
            return List.of();
        }
        if (selected.stream().anyMatch(id -> id == null || id.isBlank()
                || (!brief.allowedDocIds().isEmpty() && !brief.allowedDocIds().contains(id)))) {
            throw new ClientException("检索文档不能超出研究任务允许的范围");
        }
        List<KnowledgeDocumentDO> rows = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class).in(KnowledgeDocumentDO::getId, selected)
                        .in(KnowledgeDocumentDO::getKbId, kbIds)
                        .eq(KnowledgeDocumentDO::getEnabled, 1).eq(KnowledgeDocumentDO::getDeleted, 0));
        Set<String> current = rows.stream().filter(doc -> kbIds.contains(doc.getKbId())
                        && Integer.valueOf(1).equals(doc.getEnabled()) && !Integer.valueOf(1).equals(doc.getDeleted()))
                .map(KnowledgeDocumentDO::getId).collect(java.util.stream.Collectors.toSet());
        if (explicit && !current.containsAll(selected)) {
            throw new ClientException("选定文档不存在、已停用或不属于选定知识库");
        }
        List<String> resolved = selected.stream().filter(current::contains).distinct().toList();
        if (resolved.isEmpty()) {
            throw new ClientException("研究文档范围内没有可用来源");
        }
        return resolved;
    }

    private String stableId(String runId, ResearchSourceCatalog.SourceChunk source) {
        try {
            return "ev-" + SecureUtil.sha256(objectMapper.writeValueAsString(List.of(
                    runId, source.chunk().getKbId(), source.document().getId(),
                    Objects.toString(source.document().getDocumentVersion(), ""),
                    source.chunk().getId(), source.contentHash(), source.metadataHash())));
        } catch (Exception e) {
            throw new IllegalStateException("证据身份序列化失败", e);
        }
    }
}
