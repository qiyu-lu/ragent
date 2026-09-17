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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord.SourceExtent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 只回查数据库块，不访问文件系统、URL、MCP 或摘录缓存。
 */
@Component
@RequiredArgsConstructor
public class ResearchSourceCatalog {
    private static final Set<String> LOCATION_KEYS = Set.of(
            "section_path", "sectionPath", "section_name", "outline_path",
            "source_paragraph_id", "sourceParagraphId", "paper_id", "page_number",
            "start_page", "end_page", "sheet_name", "cell_range", "source_file",
            "block_type", "dataset", "split", "source_extent", "section_index", "paragraph_index", "source_field", "source_content_hash", "source_title");

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ObjectMapper objectMapper;

    public record SourceChunk(KnowledgeChunkDO chunk, KnowledgeDocumentDO document,
                              String collectionName, String contentHash,
                              Map<String, Object> location, String metadataHash,
                              SourceExtent extent) {
    }

    public record Neighborhood(List<SourceChunk> sources, String note) { }

    private record Boundary(List<String> section, Object sectionIndex, Object sourceField, String sheet, String paragraph) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SourceChunk> loadAll(List<String> chunkIds, Set<String> kbIds, Set<String> docIds) {
        return chunkIds.stream().map(id -> load(id, kbIds, docIds)).toList();
    }

    /**
     * 每侧至多一个块；章节/工作表/片段边界不明时只返回原块。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Neighborhood neighbors(String chunkId, Set<String> kbIds, Set<String> docIds) {
        SourceChunk seed = load(chunkId, kbIds, docIds);
        Boundary boundary = boundary(seed);
        if (boundary == null) {
            return new Neighborhood(List.of(seed), "缺少可靠来源分组，只返回原块");
        }
        int index = seed.chunk().getChunkIndex();
        List<Integer> adjacent = new ArrayList<>();
        if (index > 0) {
            adjacent.add(index - 1);
        }
        if (index < Integer.MAX_VALUE) {
            adjacent.add(index + 1);
        }
        List<KnowledgeChunkDO> rows = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class).eq(KnowledgeChunkDO::getDocId, seed.document().getId())
                        .in(KnowledgeChunkDO::getChunkIndex, adjacent)
                        .eq(KnowledgeChunkDO::getEnabled, 1).eq(KnowledgeChunkDO::getDeleted, 0)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex).last("LIMIT 3"));
        List<SourceChunk> sources = new ArrayList<>(List.of(seed));
        for (int neighborIndex : adjacent) {
            List<KnowledgeChunkDO> atIndex = rows.stream()
                    .filter(row -> Integer.valueOf(neighborIndex).equals(row.getChunkIndex())).toList();
            if (atIndex.size() > 1) {
                return new Neighborhood(List.of(seed), "邻块序号不唯一，只返回原块");
            }
            if (atIndex.isEmpty()) {
                continue;
            }
            SourceChunk neighbor = load(atIndex.get(0).getId(), kbIds, docIds);
            if (Objects.equals(seed.document().getId(), neighbor.document().getId())
                    && Objects.equals(seed.document().getDocumentVersion(), neighbor.document().getDocumentVersion())
                    && seed.extent() == neighbor.extent() && boundary.equals(boundary(neighbor))) {
                sources.add(neighbor);
            }
        }
        sources.sort(Comparator.comparing(source -> source.chunk().getChunkIndex()));
        return new Neighborhood(List.copyOf(sources), sources.size() == 1 ? "没有符合来源边界的邻块" : null);
    }

    private Boundary boundary(SourceChunk source) {
        Map<String, Object> location = source.location();
        Object path = location.get("section_path");
        if (path == null) path = location.get("sectionPath");
        if (path == null) path = location.get("outline_path");
        List<String> section = List.of();
        if (path instanceof List<?> values && !values.isEmpty()
                && values.stream().allMatch(value -> value instanceof String text && !text.isBlank())) {
            section = values.stream().map(Object::toString).toList();
        }
        String sheet = stringLocation(location.get("sheet_name"));
        String paragraph = stringLocation(location.get("source_paragraph_id"));
        if (paragraph == null) paragraph = stringLocation(location.get("sourceParagraphId"));
        // 可用段落不跨原始段落；普通章节允许读该章节内相邻的不同段落。
        if (source.extent() == SourceExtent.AVAILABLE_EXCERPT) {
            return paragraph == null ? null : new Boundary(section, location.get("section_index"), location.get("source_field"), sheet, paragraph);
        }
        if (!section.isEmpty() || sheet != null) {
            return new Boundary(section, location.get("section_index"), location.get("source_field"), sheet, null);
        }
        if (location.get("section_index") instanceof Number && "full_text".equals(location.get("source_field"))) {
            return new Boundary(section, location.get("section_index"), location.get("source_field"), null, null);
        }
        return paragraph == null ? null : new Boundary(List.of(), null, null, null, paragraph);
    }

    private String stringLocation(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceChunk load(String chunkId, Set<String> allowedKbIds) {
        return load(chunkId, allowedKbIds, Set.of());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceChunk load(String chunkId, Set<String> allowedKbIds, Set<String> allowedDocIds) {
        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || Integer.valueOf(1).equals(chunk.getDeleted())
                || !Integer.valueOf(1).equals(chunk.getEnabled())) {
            throw new ClientException("来源块不存在、已删除或已停用");
        }
        if (!allowedKbIds.contains(chunk.getKbId())) {
            throw new ClientException("来源超出研究任务的知识库范围");
        }
        if (!allowedDocIds.isEmpty() && !allowedDocIds.contains(chunk.getDocId())) {
            throw new ClientException("来源超出研究任务的文档范围");
        }
        KnowledgeDocumentDO document = documentMapper.selectById(chunk.getDocId());
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(chunk.getKbId());
        if (document == null || kb == null || Integer.valueOf(1).equals(document.getDeleted())
                || Integer.valueOf(1).equals(kb.getDeleted())
                || !Integer.valueOf(1).equals(document.getEnabled())
                || !Objects.equals(document.getKbId(), chunk.getKbId())) {
            throw new ClientException("来源文档或知识库不存在、已删除或已停用");
        }
        if (chunk.getContent() == null) {
            throw new ClientException("来源块正文缺失");
        }
        if (chunk.getChunkIndex() == null || chunk.getChunkIndex() < 0
                || document.getDocName() == null || document.getDocName().isBlank()) {
            throw new ClientException("来源块序号或文档名称缺失");
        }
        String hash = SecureUtil.sha256(chunk.getContent());
        if (chunk.getContentHash() != null && !chunk.getContentHash().isBlank()
                && !hash.equals(chunk.getContentHash())) {
            throw new ClientException("来源块正文与存储 hash 不一致");
        }
        Map<String, Object> metadata = parseMetadata(chunk.getMetadata());
        Object metadataVersion = metadata.get("document_version");
        if (metadataVersion != null && !Objects.equals(metadataVersion.toString(), document.getDocumentVersion())) {
            throw new ClientException("来源块版本与当前文档不一致");
        }
        Map<String, Object> location = new TreeMap<>();
        metadata.forEach((key, value) -> {
            if (LOCATION_KEYS.contains(key) && value != null) {
                location.put(key, value);
            }
        });
        // chunk_index 是块身份；它不证明两个相邻块属于同一章节。
        location.put("chunk_index", chunk.getChunkIndex());
        if (document.getSourceLocation() != null) {
            location.put("source_location", document.getSourceLocation());
        }
        SourceExtent extent = "available_excerpt".equals(metadata.get("source_extent"))
                || "musique".equalsIgnoreCase(Objects.toString(metadata.get("dataset"), ""))
                ? SourceExtent.AVAILABLE_EXCERPT : SourceExtent.CHUNK;
        try {
            return new SourceChunk(chunk, document, kb.getCollectionName(), hash,
                    Map.copyOf(location), SecureUtil.sha256(objectMapper.writeValueAsString(location)), extent);
        } catch (Exception e) {
            throw new IllegalStateException("来源位置序列化失败", e);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() { });
            if (metadata == null) {
                throw new IllegalArgumentException("元数据必须是 JSON 对象");
            }
            return metadata;
        } catch (Exception e) {
            throw new ClientException("来源元数据格式无效");
        }
    }
}
