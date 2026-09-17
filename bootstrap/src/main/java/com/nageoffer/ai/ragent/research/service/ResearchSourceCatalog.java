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
            "block_type", "dataset", "split", "source_extent");

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ObjectMapper objectMapper;

    public record SourceChunk(KnowledgeChunkDO chunk, KnowledgeDocumentDO document,
                              String collectionName, String contentHash,
                              Map<String, Object> location, String metadataHash,
                              SourceExtent extent) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceChunk load(String chunkId, Set<String> allowedKbIds) {
        KnowledgeChunkDO chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || Integer.valueOf(1).equals(chunk.getDeleted())
                || !Integer.valueOf(1).equals(chunk.getEnabled())) {
            throw new ClientException("来源块不存在、已删除或已停用");
        }
        if (!allowedKbIds.contains(chunk.getKbId())) {
            throw new ClientException("来源超出研究任务的知识库范围");
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
