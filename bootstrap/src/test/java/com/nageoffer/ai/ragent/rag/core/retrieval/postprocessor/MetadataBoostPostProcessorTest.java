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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataBoostPostProcessorTest {

    private static final TermDictionary TERMS = new TermDictionary(new DefaultResourceLoader(), "classpath:kq/terms.csv");

    private static DocumentMetadata metadata(String standardNo, int year, String object, String component) {
        String base = standardNo.substring(0, standardNo.lastIndexOf('-'));
        return new DocumentMetadata(standardNo, base, year, List.of(), List.of(object), List.of(component), List.of(), "extracted");
    }

    private static RetrievedChunk chunk(String id, String docId, float score) {
        return RetrievedChunk.builder().id(id).docId(docId).text(id).score(score).build();
    }

    private static MetadataBoostPostProcessor processor(Map<String, DocumentGovernance> governance, double beta) {
        MetadataBoostProperties properties = new MetadataBoostProperties();
        properties.setEnabled(true);
        properties.setBeta(beta);
        RAGConfigProperties rag = mock(RAGConfigProperties.class);
        when(rag.getRerankEnabled()).thenReturn(true);
        DocumentGovernanceResolver resolver = mock(DocumentGovernanceResolver.class);
        when(resolver.resolve(any(Collection.class))).thenReturn(governance);
        return new MetadataBoostPostProcessor(properties, rag, TERMS, resolver);
    }

    private static SearchContext context(String question, int topK) {
        return SearchContext.builder().originalQuestion(question).rewrittenQuestion(question)
                .budget(new RetrievalBudget(20, 40, topK)).build();
    }

    @Test
    void boostsTheDocumentThatMatchesBothObjectAndComponent() {
        Map<String, DocumentGovernance> governance = Map.of(
                "tfe-ore", new DocumentGovernance("tfe-ore", metadata("GB/T 6730.5-2007", 2007, "铁矿石", "全铁"), null, null),
                "tfe-ilm", new DocumentGovernance("tfe-ilm", metadata("YS/T 360.2-2011", 2011, "钛铁矿精矿", "全铁"), null, null),
                "feo-ilm", new DocumentGovernance("feo-ilm", metadata("YS/T 360.3-2011", 2011, "钛铁矿精矿", "氧化亚铁"), null, null));
        List<RetrievedChunk> input = List.of(chunk("a", "tfe-ore", 0.80F), chunk("b", "feo-ilm", 0.75F),
                chunk("c", "tfe-ilm", 0.72F), chunk("tail", "tfe-ilm", 0.99F));

        List<RetrievedChunk> output = processor(governance, 0.2)
                .process(input, List.of(), context("钛铁矿精矿测全铁用什么熔剂？", 3));

        assertEquals(List.of("c", "a", "b", "tail"), output.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.92F, output.get(0).getScore(), 1e-6);   // 0.72 + 0.2 × 1
        assertEquals(0.90F, output.get(1).getScore(), 1e-6);   // 0.80 + 0.2 × 0.5
        assertEquals(0.85F, output.get(2).getScore(), 1e-6);   // 0.75 + 0.2 × 0.5
        assertSame(input.get(3), output.get(3), "尾部不是模型打分，原样保留");
        assertEquals(0.72F, input.get(2).getScore(), "不改写输入对象");
    }

    @Test
    void questionsWithoutDomainTermsKeepTheRerankOrder() {
        Map<String, DocumentGovernance> governance = Map.of(
                "d", new DocumentGovernance("d", metadata("GB/T 6730.5-2007", 2007, "铁矿石", "全铁"), null, null));
        List<RetrievedChunk> input = List.of(chunk("a", "x", 0.9F), chunk("b", "d", 0.8F));
        List<RetrievedChunk> output = processor(governance, 0.3).process(input, List.of(), context("现场实验室有几台天平", 2));
        assertEquals(List.of("a", "b"), output.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.8F, output.get(1).getScore(), 1e-6);
    }

    @Test
    void supersededVersionNeverOutranksItsReplacement() {
        Map<String, DocumentGovernance> governance = Map.of(
                "old", new DocumentGovernance("old", metadata("GB/T 6730.10-1986", 1986, "铁矿石", "硅"), null, "GB/T 6730.10-2014"),
                "new", new DocumentGovernance("new", metadata("GB/T 6730.10-2014", 2014, "铁矿石", "硅"), null, null));
        List<RetrievedChunk> input = new ArrayList<>(List.of(chunk("o", "old", 0.95F), chunk("n", "new", 0.60F)));
        List<RetrievedChunk> output = processor(governance, 0.0).process(input, List.of(), context("硅含量怎么测", 2));
        assertEquals(List.of("n", "o"), output.stream().map(RetrievedChunk::getId).toList());
        assertTrue(output.get(1).getScore() < output.get(0).getScore());
    }

    @Test
    void matchIsTheShareOfMentionedDimensions() {
        DocumentMetadata ore = metadata("GB/T 6730.10-2014", 2014, "铁矿石", "硅");
        assertEquals(1.0, MetadataBoostPostProcessor.match(List.of("铁矿石"), List.of("硅"), ore));
        assertEquals(0.5, MetadataBoostPostProcessor.match(List.of("硫铁矿"), List.of("硅"), ore));
        assertEquals(1.0, MetadataBoostPostProcessor.match(List.of(), List.of("硅"), ore));
        assertEquals(0.0, MetadataBoostPostProcessor.match(List.of(), List.of(), ore));
        assertEquals(0.0, MetadataBoostPostProcessor.match(List.of("铁矿石"), List.of(), null));
    }

    @Test
    void disabledWithoutRerank() {
        MetadataBoostProperties properties = new MetadataBoostProperties();
        properties.setEnabled(true);
        RAGConfigProperties rag = mock(RAGConfigProperties.class);
        when(rag.getRerankEnabled()).thenReturn(false);
        assertFalse(new MetadataBoostPostProcessor(properties, rag, TERMS, mock(DocumentGovernanceResolver.class))
                .isEnabled(context("x", 1)));
        assertFalse(new MetadataBoostPostProcessor(new MetadataBoostProperties(), rag, TERMS,
                mock(DocumentGovernanceResolver.class)).isEnabled(context("x", 1)));
    }
}
