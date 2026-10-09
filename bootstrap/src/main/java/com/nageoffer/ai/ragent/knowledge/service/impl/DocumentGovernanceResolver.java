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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.support.DocumentMetadataCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档治理信息的读取：元数据、解析审计结论、时效性（是否已被同库里的新版本代替）
 * <p>
 * 时效性规则（计划 §5.3）：同一知识库里同一标准（去掉年份的标准号相同）有多个版本时，年份最新的为现行，
 * 其余标"已被代替"；另一份文档的"代替"清单里列出了本文档的标准号，也算被它代替。只在读时计算，
 * 新版本删掉后旧版本自动恢复为现行，不需要回写
 */
@Service
@RequiredArgsConstructor
public class DocumentGovernanceResolver {

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentMetadataCodec metadataCodec;

    /**
     * @param docId        文档 ID
     * @param metadata     元数据，没有时为 null
     * @param parseVerdict 解析审计结论，没有审计时为 null
     * @param supersededBy 代替本文档的新版本标准号；现行文档为 null
     */
    public record DocumentGovernance(String docId, DocumentMetadata metadata, String parseVerdict, String supersededBy) {

        public boolean superseded() {
            return supersededBy != null;
        }
    }

    public Map<String, DocumentGovernance> resolve(Collection<String> docIds) {
        if (CollUtil.isEmpty(docIds)) {
            return Map.of();
        }
        List<KnowledgeDocumentDO> docs = documentMapper.selectBatchIds(new LinkedHashSet<>(docIds));
        if (CollUtil.isEmpty(docs)) {
            return Map.of();
        }
        Map<String, DocumentMetadata> metadataById = new HashMap<>();
        Map<String, String> verdictById = new HashMap<>();
        Set<String> kbIds = new LinkedHashSet<>();
        for (KnowledgeDocumentDO doc : docs) {
            Map<String, Object> raw = metadataCodec.read(doc.getDocMetadata());
            if (!raw.isEmpty()) {
                DocumentMetadata metadata = DocumentMetadata.fromMap(raw);
                metadataById.put(doc.getId(), metadata);
                verdictById.put(doc.getId(), DocumentMetadataCodec.parseVerdict(raw));
                if (metadata.standardBase() != null) {
                    kbIds.add(doc.getKbId());
                }
            }
        }
        List<Sibling> siblings = kbIds.isEmpty() ? List.of() : siblings(kbIds);

        Map<String, DocumentGovernance> result = new HashMap<>();
        for (KnowledgeDocumentDO doc : docs) {
            DocumentMetadata metadata = metadataById.get(doc.getId());
            result.put(doc.getId(), new DocumentGovernance(doc.getId(), metadata, verdictById.get(doc.getId()),
                    supersededBy(doc.getId(), doc.getKbId(), metadata, siblings)));
        }
        return result;
    }

    record Sibling(String docId, String kbId, DocumentMetadata metadata) {
    }

    private List<Sibling> siblings(Set<String> kbIds) {
        List<KnowledgeDocumentDO> rows = documentMapper.selectList(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .select(KnowledgeDocumentDO::getId, KnowledgeDocumentDO::getKbId, KnowledgeDocumentDO::getDocMetadata)
                .in(KnowledgeDocumentDO::getKbId, kbIds)
                .eq(KnowledgeDocumentDO::getEnabled, 1)
                .isNotNull(KnowledgeDocumentDO::getDocMetadata));
        List<Sibling> siblings = new ArrayList<>(rows.size());
        for (KnowledgeDocumentDO row : rows) {
            DocumentMetadata metadata = metadataCodec.governance(row.getDocMetadata());
            if (metadata != null && metadata.standardNo() != null) {
                siblings.add(new Sibling(row.getId(), row.getKbId(), metadata));
            }
        }
        return siblings;
    }

    /**
     * 同库里代替本文档的最新版本的标准号；包内可见供测试
     */
    static String supersededBy(String docId, String kbId, DocumentMetadata metadata, List<Sibling> siblings) {
        if (metadata == null || metadata.standardNo() == null) {
            return null;
        }
        Sibling newest = null;
        for (Sibling sibling : siblings) {
            if (sibling.docId().equals(docId) || !Objects.equals(sibling.kbId(), kbId)) {
                continue;
            }
            DocumentMetadata other = sibling.metadata();
            boolean sameStandardNewer = Objects.equals(other.standardBase(), metadata.standardBase())
                    && other.publishYear() != null && metadata.publishYear() != null
                    && other.publishYear() > metadata.publishYear();
            boolean listsUsAsReplaced = other.replaces().contains(metadata.standardNo());
            if ((sameStandardNewer || listsUsAsReplaced) && (newest == null || newer(other, newest.metadata()))) {
                newest = sibling;
            }
        }
        return newest == null ? null : newest.metadata().standardNo();
    }

    private static boolean newer(DocumentMetadata a, DocumentMetadata b) {
        int ya = a.publishYear() == null ? 0 : a.publishYear();
        int yb = b.publishYear() == null ? 0 : b.publishYear();
        return ya > yb;
    }
}
