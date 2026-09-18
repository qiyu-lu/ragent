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

package com.nageoffer.ai.ragent.rag.core.keyword;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 Elasticsearch 的关键词检索服务
 * <p>
 * 在共享索引上用 BM25 在 content 字段做全文匹配，
 * 并以 collection_name terms 过滤限定知识库范围；命中 _id 即向量库主键 chunkId，
 * 映射为与向量结果同构的 {@link RetrievedChunk}
 * <p>
 * 仅当开启 ES 关键词检索（rag.keyword.type=es）时装配
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsKeywordRetrieverService implements KeywordRetrieverService {

    private final ElasticsearchClient esClient;
    private final KeywordProperties keywordProperties;

    @Override
    public List<RetrievedChunk> search(String query, List<String> collectionNames, int topK) {
        return search(query, collectionNames, topK, List.of());
    }

    @Override
    public List<RetrievedChunk> search(String query, List<String> collectionNames, int topK, List<String> documentIds) {
        // 范围为空即无结果：空列表若当作「不限库」，上游作用域被权限裁空时会退化成全索引检索
        if (CollUtil.isEmpty(collectionNames)) {
            return List.of();
        }
        String index = keywordProperties.sharedIndex();
        List<FieldValue> collectionFilter = collectionNames.stream().map(FieldValue::of).toList();
        List<FieldValue> documentFilter = documentIds.stream().map(FieldValue::of).toList();

        try {
            SearchResponse<KeywordHitDocument> resp = esClient.search(s -> s
                            .index(index)
                            .size(topK)
                            .ignoreUnavailable(true)
                            .allowNoIndices(true)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.match(mt -> mt.field("content").query(query)));
                                b.filter(f -> f.terms(t -> t
                                        .field("collection_name")
                                        .terms(tv -> tv.value(collectionFilter))));
                                if (!documentFilter.isEmpty()) {
                                    b.filter(f -> f.terms(t -> t.field("doc_id")
                                            .terms(tv -> tv.value(documentFilter))));
                                }
                                return b;
                            })),
                    KeywordHitDocument.class);

            List<Hit<KeywordHitDocument>> hits = resp.hits().hits();
            if (CollUtil.isEmpty(hits)) {
                return List.of();
            }
            return hits.stream()
                    .map(this::toChunk)
                    .toList();
        } catch (Exception e) {
            if (com.nageoffer.ai.ragent.infra.operation.RequestOperation.current() != null)
                throw com.nageoffer.ai.ragent.infra.operation.RequestOperation.failure("keyword.http", e);
            log.error("ES 关键词检索失败, index={}, collections={}, query={}", index, collectionNames, query, e);
            return List.of();
        }
    }

    private RetrievedChunk toChunk(Hit<KeywordHitDocument> hit) {
        KeywordHitDocument source = hit.source();
        String content = source == null || source.getContent() == null ? "" : source.getContent();
        float score = hit.score() == null ? 0f : hit.score().floatValue();
        return RetrievedChunk.builder()
                .id(hit.id())
                .text(content)
                .collectionName(source == null ? null : source.getCollectionName())
                .docId(source == null ? null : source.getDocId())
                .score(score)
                .build();
    }

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KeywordHitDocument {

        private String content;

        @JsonProperty("collection_name")
        private String collectionName;

        @JsonProperty("doc_id")
        private String docId;
    }
}
