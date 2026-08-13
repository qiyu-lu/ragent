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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver.ChunkMeta;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元数据富化后置处理器
 * <p>
 * 处于融合之后、Rerank 之前，对候选池按 chunkId 回表补齐文档归属和结构化检索文本。
 * Rerank 使用「文档名 + embedding_text」判断相关性，最终上下文仍使用原始正文；这样 PDF 的标准名、
 * Excel 的 sheet/键值信号能参与精排，又不会污染引用和前端预览。
 * <p>
 * 只富化、不重排：保持进入时的融合顺序不变。候选池由 candidateLimit 限制，回表规模有上界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataEnrichmentPostProcessor implements SearchResultPostProcessor {

    private final ChunkMetadataResolver chunkMetadataResolver;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "MetadataEnrichment";
    }

    @Override
    public int getOrder() {
        return 8;  // Fusion(5) 之后、Rerank(10) 之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getContextEnrichEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        List<String> chunkIds = chunks.stream().map(RetrievedChunk::getId).toList();
        Map<String, ChunkMeta> metaById = chunkMetadataResolver.resolve(chunkIds);

        // 1）按 chunkId 富化：向量 / 关键词证据的 chunk.id 即向量库主键，回表补齐 docId / 序号 / 标题
        // 原地富化，保持相关性顺序不变
        for (RetrievedChunk chunk : chunks) {
            ChunkMeta meta = metaById.get(chunk.getId());
            if (meta == null) {
                continue;
            }
            chunk.setDocId(meta.docId());
            chunk.setChunkIndex(meta.chunkIndex());
            chunk.setDocName(meta.docName());
            chunk.setDocumentVersion(meta.documentVersion());
            chunk.setSheetName(meta.sheetName());
            chunk.setCellRange(meta.cellRange());
            chunk.setBlockType(meta.blockType());
            chunk.setRankingText(composeRankingText(meta.docName(), meta.embeddingText(), chunk.getText()));
        }

        // 2）按 docId 补标题：图谱证据的 chunk.id 非向量库主键、上一步未命中，但已带归属 docId，
        // 据此补真实文档标题，使其与同源向量证据在上下文里聚合进同一文档块
        fillDocNamesByDocId(chunks);
        return chunks;
    }

    /**
     * 对上一步按 chunkId 未补到标题、但已带 docId 的证据（典型为图谱证据）按 docId 回表补真实文档标题
     */
    private void fillDocNamesByDocId(List<RetrievedChunk> chunks) {
        List<String> pendingDocIds = chunks.stream()
                .filter(c -> StrUtil.isBlank(c.getDocName()) && StrUtil.isNotBlank(c.getDocId()))
                .map(RetrievedChunk::getDocId)
                .toList();
        if (pendingDocIds.isEmpty()) {
            return;
        }
        Map<String, String> docNameById = chunkMetadataResolver.resolveDocNames(pendingDocIds);
        if (docNameById.isEmpty()) {
            return;
        }
        for (RetrievedChunk chunk : chunks) {
            if (StrUtil.isBlank(chunk.getDocName()) && StrUtil.isNotBlank(chunk.getDocId())) {
                String docName = docNameById.get(chunk.getDocId());
                if (StrUtil.isNotBlank(docName)) {
                    chunk.setDocName(docName);
                    if (StrUtil.isBlank(chunk.getRankingText())) {
                        chunk.setRankingText(composeRankingText(docName, null, chunk.getText()));
                    }
                }
            }
        }
    }

    private static String composeRankingText(String docName, String embeddingText, String displayText) {
        String body = StrUtil.isNotBlank(embeddingText) ? embeddingText.trim() : StrUtil.trim(displayText);
        String documentIdentity = stripExtension(StrUtil.trim(docName));
        if (StrUtil.isBlank(documentIdentity)) {
            return body;
        }
        if (StrUtil.isBlank(body)) {
            return documentIdentity;
        }
        return body.contains(documentIdentity) ? body : documentIdentity + "\n" + body;
    }

    private static String stripExtension(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(0, dot) : name;
    }
}
