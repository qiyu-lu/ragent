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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver.ChunkMeta;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataEnrichmentPostProcessorTest {

    @Test
    void addsDocumentIdentityAndEmbeddingTextForRerankOnly() {
        ChunkMetadataResolver resolver = mock(ChunkMetadataResolver.class);
        RetrievedChunk chunk = RetrievedChunk.builder().id("chunk-1").text("用户可见正文").build();
        when(resolver.resolve(List.of("chunk-1"))).thenReturn(Map.of(
                "chunk-1", new ChunkMeta(
                        "doc-1", 3, "YS-T-360.3-氧化亚铁.pdf", "V1", null, null,
                        "paragraph", "测定原理 / 滴定终点\n稳定的紫红色为终点")));

        MetadataEnrichmentPostProcessor processor =
                new MetadataEnrichmentPostProcessor(resolver, mock(RAGConfigProperties.class));
        processor.process(List.of(chunk), List.of(), SearchContext.builder()
                .budget(RetrievalBudget.uniform(10))
                .build());

        assertEquals(8, processor.getOrder(), "必须在 order=10 的 Rerank 前完成富化");
        assertTrue(chunk.textForRanking().startsWith("YS-T-360.3-氧化亚铁\n"));
        assertTrue(chunk.textForRanking().contains("稳定的紫红色为终点"));
        assertEquals("用户可见正文", chunk.getText(), "结构化精排文本不得替换展示正文");
        assertEquals("doc-1", chunk.getDocId());
    }
}
