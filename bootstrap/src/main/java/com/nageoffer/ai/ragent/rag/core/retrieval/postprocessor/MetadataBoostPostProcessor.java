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

import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import com.nageoffer.ai.ragent.core.ingest.metadata.TermDictionary;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.DocumentGovernanceResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.DocumentGovernanceResolver.DocumentGovernance;
import com.nageoffer.ai.ragent.rag.config.MetadataBoostProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 查询侧文档元数据加权（knowledge-quality 计划 §5.3），在 Rerank 之后
 * <p>
 * 查询用术语表识别出检测对象与组分后，对 Rerank 头部每个块：最终分 = 重排分 + β × 匹配度。匹配度是查询提到的
 * 维度里该块所属文档对上的比例：问"钛铁矿精矿的全铁"时，YS/T 360.2（钛铁矿精矿、全铁）得 1，GB/T 6730.5
 * （铁矿石、全铁）得 0.5。只动重排头部：尾部是没被模型打分的融合候选，分数与头部不可比。
 * <p>
 * 时效性：同一标准的旧版本与新版本同时在头部时，旧版本的块不排在新版本最好的块之前
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataBoostPostProcessor implements SearchResultPostProcessor {

    private static final float VERSION_GAP = 1e-4F;

    private final MetadataBoostProperties properties;
    private final RAGConfigProperties ragConfigProperties;
    private final TermDictionary termDictionary;
    private final DocumentGovernanceResolver governanceResolver;

    @Override
    public String getName() {
        return "MetadataBoost";
    }

    @Override
    public int getOrder() {
        return 11;  // Rerank(10) 之后
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.isEnabled() && Boolean.TRUE.equals(ragConfigProperties.getRerankEnabled());
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int headSize = Math.min(context.getBudget() == null ? chunks.size() : context.getBudget().contextTopK(),
                chunks.size());
        List<RetrievedChunk> head = chunks.subList(0, headSize);
        if (head.isEmpty() || head.stream().anyMatch(chunk -> chunk.getScore() == null)) {
            return chunks;
        }
        String question = context.getMainQuestion();
        List<String> objects = termDictionary.canonicals(question, TermDictionary.OBJECT);
        List<String> components = termDictionary.canonicals(question, TermDictionary.COMPONENT);
        Map<String, DocumentGovernance> governance = governanceResolver.resolve(
                head.stream().map(RetrievedChunk::getDocId).filter(Objects::nonNull).distinct().toList());

        List<RetrievedChunk> boosted = new ArrayList<>(head.size());
        for (RetrievedChunk chunk : head) {
            DocumentGovernance doc = governance.get(chunk.getDocId());
            double match = match(objects, components, doc == null ? null : doc.metadata());
            boosted.add(chunk.toBuilder().score((float) (chunk.getScore() + properties.getBeta() * match)).build());
        }
        keepNewVersionsFirst(boosted, governance);
        boosted.sort(Comparator.comparing(RetrievedChunk::getScore, Comparator.reverseOrder()));

        List<RetrievedChunk> output = new ArrayList<>(chunks.size());
        output.addAll(boosted);
        output.addAll(chunks.subList(headSize, chunks.size()));
        log.info("元数据加权 β={} 查询检测对象={} 组分={}", properties.getBeta(), objects, components);
        return output;
    }

    /**
     * 查询提到的维度里文档对上的比例；查询没提到检测对象和组分时为 0
     */
    static double match(List<String> objects, List<String> components, DocumentMetadata metadata) {
        int mentioned = (objects.isEmpty() ? 0 : 1) + (components.isEmpty() ? 0 : 1);
        if (mentioned == 0 || metadata == null) {
            return 0D;
        }
        int matched = 0;
        if (!objects.isEmpty() && metadata.objects().stream().anyMatch(objects::contains)) {
            matched++;
        }
        if (!components.isEmpty() && metadata.components().stream().anyMatch(components::contains)) {
            matched++;
        }
        return (double) matched / mentioned;
    }

    /**
     * 旧版本的块压到新版本最好的块之下；新版本不在头部时不动
     */
    static void keepNewVersionsFirst(List<RetrievedChunk> boosted, Map<String, DocumentGovernance> governance) {
        Map<String, Float> bestByStandardNo = new HashMap<>();
        for (RetrievedChunk chunk : boosted) {
            DocumentGovernance doc = governance.get(chunk.getDocId());
            if (doc != null && doc.metadata() != null && doc.metadata().standardNo() != null) {
                bestByStandardNo.merge(doc.metadata().standardNo(), chunk.getScore(), Math::max);
            }
        }
        for (int i = 0; i < boosted.size(); i++) {
            RetrievedChunk chunk = boosted.get(i);
            DocumentGovernance doc = governance.get(chunk.getDocId());
            Float newest = doc == null || !doc.superseded() ? null : bestByStandardNo.get(doc.supersededBy());
            if (newest != null && chunk.getScore() >= newest) {
                boosted.set(i, chunk.toBuilder().score(newest - VERSION_GAP).build());
            }
        }
    }
}
