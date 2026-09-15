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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.MetadataEnrichmentPostProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Paired ablation: same vector/keyword candidates, existing fusion and model implementations. */
@RestController
@RequiredArgsConstructor
@Profile("pooled-eval")
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class HybridEvalController {
    private final PooledEvalController pooled;
    private final MetadataEnrichmentPostProcessor enrichment;
    private final RerankService rerank;

    @PostMapping("/rag/eval/pooled/hybrid-compare")
    public Result<Comparison> compare(@RequestBody PooledEvalController.Request request) {
        if (request.rewrite()) {
            throw new IllegalArgumentException("paired raw-query comparison only");
        }
        // Delegates authentication and database/collection isolation before any retrieval.
        var hybrid = pooled.evaluate(request).getData();
        var baselines = new ArrayList<RetrievalCapture.Stage>();
        for (String channel : List.of("VectorSearch", "KeywordSearch")) {
            var source = hybrid.stages().stream().filter(s -> s.stage().equals("channel-" + channel))
                    .findFirst().orElseThrow(() -> new IllegalStateException("missing channel " + channel));
            var capture = new RetrievalCapture();
            long start = System.nanoTime();
            try {
                if (source.failure() != null) {
                    capture.record(request.question(), channel + "-rerank", List.of(), 0, source.failure());
                } else {
                    var chunks = source.chunks().stream().map(c -> RetrievedChunk.builder()
                            .id(c.id()).text(c.text()).score(c.score()).collectionName(c.collectionName()).build()).toList();
                    enrichment.process(chunks, List.of(), null);
                    var ranked = rerank.rerank(request.question(), chunks, 10);
                    capture.record(request.question(), channel + "-rerank", ranked,
                            (System.nanoTime() - start) / 1_000_000, null);
                }
            } catch (Exception e) {
                capture.record(request.question(), channel + "-rerank", List.of(),
                        (System.nanoTime() - start) / 1_000_000, e.getClass().getSimpleName());
            }
            baselines.addAll(capture.stages());
        }
        return Results.success(new Comparison(hybrid, baselines));
    }

    public record Comparison(PooledEvalController.Response hybrid, List<RetrievalCapture.Stage> baselines) { }
}
