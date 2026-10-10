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

package com.nageoffer.ai.ragent.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalControllerTest {

    @Test
    void replayExposesChannelScoreRerankHeadAndFinalSelection() {
        Fixture fixture = fixture(2, false);
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class))).thenAnswer(invocation -> {
            RetrievalCapture capture = invocation.getArgument(1);
            capture.record("子问题", "channel-VectorSearch", List.of(chunk("a", 0.5F), chunk("b", 0.4F), chunk("c", 0.3F)), 3, null);
            capture.record("子问题", "post-Deduplication", List.of(chunk("a", 0.5F), chunk("b", 0.4F), chunk("c", 0.3F)), 0, null);
            // Rerank 头部为前两条且带模型分，尾部 c 仍带通道分
            capture.record("子问题", "post-Rerank", List.of(chunk("b", 0.9F), chunk("a", 0.8F), chunk("c", 0.3F)), 20, null);
            capture.record("", "request-final", List.of(chunk("b", 0.9F)), 0, null);
            return RetrievalContext.builder().kbChunks(List.of(chunk("b", 0.9F))).build();
        });

        EvalResponse response = fixture.controller()
                .replay(new EvalReplayRequest(" 原问题 ", List.of(" 子问题 "))).getData();

        assertEquals("replay", response.mode());
        assertEquals("原问题", response.question());
        assertNull(response.rewrittenQuestion());
        assertEquals(List.of("子问题"), response.subQuestions());
        assertEquals(List.of("b"), response.finalChunkIds());
        verify(fixture.queryRewriteService(), never()).rewriteWithSplit(any(), any());

        EvalResponse.SubQuestionResult result = response.results().get(0);
        assertEquals(2, result.rerankHeadSize());
        assertEquals(2, result.rerankScored());
        List<EvalResponse.Candidate> candidates = result.candidates();
        assertEquals(List.of("b", "a", "c"), candidates.stream().map(EvalResponse.Candidate::id).toList());

        EvalResponse.Candidate b = candidates.get(0);
        assertEquals(0.4F, b.channelScore());
        assertEquals(0.9F, b.rerankScore());
        assertTrue(b.rerankHead());
        assertTrue(b.finalSelected());
        assertEquals(0, b.finalRank());

        EvalResponse.Candidate a = candidates.get(1);
        assertEquals(0.5F, a.channelScore());
        assertEquals(0.8F, a.rerankScore());
        assertTrue(a.rerankHead());
        assertFalse(a.finalSelected());
        assertNull(a.finalRank());

        EvalResponse.Candidate c = candidates.get(2);
        assertEquals(0.3F, c.channelScore());
        assertNull(c.rerankScore(), "融合尾部没有模型分，不得把通道分冒充重排分");
        assertFalse(c.rerankHead());
        assertEquals(4, response.stages().size());
    }

    @Test
    void rewriteModeCallsRewriteServiceAndAppendsLog(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(10, false);
        Path logFile = tempDir.resolve("nested").resolve("rewrites.jsonl");
        fixture.evalProperties().setRewriteLog(logFile.toString());
        when(fixture.queryRewriteService().rewriteWithSplit("原问题", List.of()))
                .thenReturn(new RewriteResult("改写问题", List.of("子一", "子二")));
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class)))
                .thenReturn(RetrievalContext.builder().kbChunks(List.of()).build());

        EvalResponse response = fixture.controller().replay(new EvalReplayRequest("原问题", null)).getData();

        assertEquals("rewrite", response.mode());
        assertEquals("改写问题", response.rewrittenQuestion());
        assertEquals(List.of("子一", "子二"), response.subQuestions());
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(fixture.retrievalEngine()).retrieve(captor.capture(), any(RetrievalCapture.class));
        assertEquals(List.of("子一", "子二"), captor.getValue());
        assertEquals(2, response.results().size());
        assertTrue(response.results().get(0).candidates().isEmpty());

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        Map<?, ?> logged = new ObjectMapper().readValue(lines.get(0), Map.class);
        assertEquals("原问题", logged.get("question"));
        assertEquals(List.of("子一", "子二"), logged.get("subQuestions"));
    }

    @Test
    void rewriteWithoutSplitFallsBackToRewrittenQuestion() {
        Fixture fixture = fixture(10, false);
        when(fixture.queryRewriteService().rewriteWithSplit("原问题", List.of()))
                .thenReturn(new RewriteResult("改写问题", List.of()));
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class)))
                .thenReturn(RetrievalContext.builder().kbChunks(List.of()).build());
        fixture.evalProperties().setRewriteLog("");

        EvalResponse response = fixture.controller().replay(new EvalReplayRequest("原问题", List.of())).getData();

        assertEquals(List.of("改写问题"), response.subQuestions());
    }

    @Test
    void replayRejectsBlankDuplicateAndOversizedSubQuestions() {
        Fixture fixture = fixture(10, false);
        EvalController controller = fixture.controller();

        assertThrows(ClientException.class, () -> controller.replay(new EvalReplayRequest(" ", List.of("问题"))));
        assertThrows(ClientException.class, () -> controller.replay(null));
        assertThrows(ClientException.class, () -> controller.replay(new EvalReplayRequest("问题", List.of(" "))));
        assertThrows(ClientException.class, () -> controller.replay(new EvalReplayRequest("问题", List.of("重复", " 重复 "))));
        assertThrows(ClientException.class, () -> controller.replay(new EvalReplayRequest("问题", Collections.nCopies(11, "问题"))));
        verify(fixture.retrievalEngine(), never()).retrieve(anyList(), any(RetrievalCapture.class));
    }

    @Test
    void rerankTopNFollowsTheEngineBudgetAllocation() {
        assertEquals(List.of(10), fixture(10, false).controller().rerankTopN(1));
        assertEquals(List.of(5, 5), fixture(10, false).controller().rerankTopN(2));
        assertEquals(List.of(4, 3, 3), fixture(10, false).controller().rerankTopN(3));
        assertEquals(List.of(10, 10, 10), fixture(10, true).controller().rerankTopN(3));
        assertTrue(fixture(10, false).controller().rerankTopN(0).isEmpty());
    }

    @Test
    void rerankTopNCoversTheWholePoolWhenScoresAreBlended() {
        assertEquals(List.of(40, 40), fixture(10, false, true).controller().rerankTopN(2));
    }

    @Test
    void noopRerankAfterFusionIsVisibleAsZeroRescoredChunks() {
        // 两通道融合后头部带 RRF 分，与通道分不等；回退成 noop 时它和进入 Rerank 前的分数一样
        Fixture fixture = fixture(2, false);
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class))).thenAnswer(invocation -> {
            RetrievalCapture capture = invocation.getArgument(1);
            capture.record("子问题", "channel-VectorSearch", List.of(chunk("a", 0.5F)), 3, null);
            capture.record("子问题", "channel-FullTextSearch", List.of(chunk("b", 9.0F)), 2, null);
            capture.record("子问题", "post-Fusion", List.of(chunk("a", 0.0476F), chunk("b", 0.0476F)), 0, null);
            capture.record("子问题", "post-Rerank", List.of(chunk("a", 0.0476F), chunk("b", 0.0476F)), 1, null);
            return RetrievalContext.builder().kbChunks(List.of(chunk("a", 0.0476F))).build();
        });

        EvalResponse.SubQuestionResult result = fixture.controller()
                .replay(new EvalReplayRequest("原问题", List.of("子问题"))).getData().results().get(0);

        assertEquals(2, result.rerankHeadSize());
        assertEquals(0, result.rerankScored());
    }

    @Test
    void candidatesCarryEachChannelsOwnScore() {
        Fixture fixture = fixture(10, false);
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class))).thenAnswer(invocation -> {
            RetrievalCapture capture = invocation.getArgument(1);
            capture.record("子问题", "channel-VectorSearch", List.of(chunk("a", 0.5F), chunk("b", 0.4F)), 3, null);
            capture.record("子问题", "channel-FullTextSearch", List.of(chunk("c", 9.5F), chunk("a", 7.2F)), 2, null);
            capture.record("子问题", "post-Rerank", List.of(chunk("c", 0.9F), chunk("a", 0.8F), chunk("b", 0.3F)), 20, null);
            return RetrievalContext.builder().kbChunks(List.of(chunk("c", 0.9F), chunk("a", 0.8F))).build();
        });

        List<EvalResponse.Candidate> candidates = fixture.controller()
                .replay(new EvalReplayRequest("原问题", List.of("子问题"))).getData().results().get(0).candidates();

        EvalResponse.Candidate c = candidates.get(0);
        assertEquals(9.5F, c.channelScore(), "只被全文通道召回的块，通道分就是 BM25");
        assertEquals(Map.of("FullTextSearch", 9.5F), c.channelScores());
        EvalResponse.Candidate a = candidates.get(1);
        assertEquals(0.5F, a.channelScore());
        assertEquals(Map.of("VectorSearch", 0.5F, "FullTextSearch", 7.2F), a.channelScores());
        assertEquals(Map.of("VectorSearch", 0.4F), candidates.get(2).channelScores());
    }

    @Test
    void failedRerankStageLeavesRerankScoresEmpty() {
        Fixture fixture = fixture(10, false);
        when(fixture.retrievalEngine().retrieve(anyList(), any(RetrievalCapture.class))).thenAnswer(invocation -> {
            RetrievalCapture capture = invocation.getArgument(1);
            capture.record("子问题", "channel-VectorSearch", List.of(chunk("a", 0.5F)), 3, null);
            capture.record("子问题", "post-Rerank", List.of(chunk("a", 0.5F)), 9, "ModelClientException");
            return RetrievalContext.builder().kbChunks(List.of(chunk("a", 0.5F))).build();
        });

        EvalResponse response = fixture.controller().replay(new EvalReplayRequest("原问题", List.of("子问题"))).getData();

        EvalResponse.SubQuestionResult result = response.results().get(0);
        assertEquals(0, result.rerankHeadSize());
        assertNull(result.candidates().get(0).rerankScore());
        assertTrue(result.candidates().get(0).finalSelected());
        assertEquals("ModelClientException", response.stages().get(1).failure());
    }

    private static RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text("正文" + id).score(score).collectionName("kq").docId("doc-" + id).docName("文档" + id).build();
    }

    private static Fixture fixture(int defaultTopK, boolean refill) {
        return fixture(defaultTopK, refill, false);
    }

    private static Fixture fixture(int defaultTopK, boolean refill, boolean blend) {
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        RetrievalEngine retrieval = mock(RetrievalEngine.class);
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        searchProperties.setDefaultTopK(defaultTopK);
        searchProperties.setRequestLevelRefillEnabled(refill);
        EvalProperties evalProperties = new EvalProperties();
        evalProperties.setRewriteLog("");
        ScoreBlendProperties blendProperties = new ScoreBlendProperties();
        blendProperties.setEnabled(blend);
        EvalController controller = new EvalController(rewrite, retrieval, searchProperties, blendProperties,
                evalProperties, new ObjectMapper());
        return new Fixture(controller, rewrite, retrieval, evalProperties);
    }

    private record Fixture(EvalController controller,
                           QueryRewriteService queryRewriteService,
                           RetrievalEngine retrievalEngine,
                           EvalProperties evalProperties) {
    }
}
