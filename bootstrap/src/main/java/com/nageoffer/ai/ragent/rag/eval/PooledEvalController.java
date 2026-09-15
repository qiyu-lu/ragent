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

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Admin-only diagnostic entry; requires a dedicated public corpus database and profile. */
@RestController
@RequiredArgsConstructor
@Profile("pooled-eval")
@ConditionalOnProperty(prefix = "ragent.eval.pooled", name = "enabled", havingValue = "true")
public class PooledEvalController {
    private final JdbcTemplate jdbcTemplate;
    private final QueryRewriteService queryRewriteService;
    private final RetrievalEngine retrievalEngine;
    private final KnowledgeDocumentService documentService;

    /** Same consumer business operation, without RocketMQ transport; public evaluation DB only. */
    @PostMapping("/rag/eval/pooled/ingest/{docId}")
    public Result<String> ingest(@PathVariable String docId) {
        StpUtil.checkRole("admin");
        checkIsolation();
        int claimed = jdbcTemplate.update("""
                UPDATE t_knowledge_document SET status='running', update_time=now()
                WHERE id=? AND deleted=0 AND status IN ('pending','failed')
                AND kb_id IN (SELECT id FROM t_knowledge_base WHERE deleted=0 AND collection_name LIKE 'cs_pool_%')
                """, docId);
        if (claimed != 1) {
            throw new IllegalStateException("document must be pending or failed in the isolated corpus");
        }
        documentService.executeChunk(docId);
        return Results.success(jdbcTemplate.queryForObject(
                "SELECT status FROM t_knowledge_document WHERE id=?", String.class, docId));
    }

    void checkIsolation() {
        String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.matches("ragent_eval_pool_[a-z0-9_]+")) {
            throw new IllegalStateException("pooled evaluation requires a dedicated database");
        }
        List<String> collections = jdbcTemplate.queryForList(
                "SELECT collection_name FROM t_knowledge_base WHERE deleted=0", String.class);
        if (collections.size() != 1 || !collections.get(0).startsWith("cs_pool_")) {
            throw new IllegalStateException("pooled evaluation requires exactly one cs_pool_ knowledge base");
        }
        Integer foreignVectors = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_knowledge_vector WHERE collection_name <> ?", Integer.class, collections.get(0));
        if (foreignVectors == null || foreignVectors != 0) {
            throw new IllegalStateException("unexpected vectors outside the public corpus");
        }
    }

    @PostMapping("/rag/eval/pooled")
    public Result<Response> evaluate(@RequestBody Request request) {
        StpUtil.checkRole("admin");
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > 2000) {
            throw new IllegalArgumentException("question must contain 1..2000 characters");
        }
        checkIsolation();
        long started = System.nanoTime();
        List<String> questions;
        if (request.rewrite()) {
            var rewritten = queryRewriteService.rewriteWithSplit(request.question(), List.of());
            questions = rewritten.subQuestions() == null || rewritten.subQuestions().isEmpty()
                    ? List.of(rewritten.rewrittenQuestion()) : rewritten.subQuestions();
        } else {
            questions = List.of(request.question());
        }
        if (questions.size() > 4 || questions.stream().anyMatch(q -> q == null || q.isBlank())) {
            throw new IllegalStateException("invalid or excessive rewritten subquestions");
        }
        long rewriteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        RetrievalCapture capture = new RetrievalCapture();
        // Intent deliberately off: public text-only corpus; no MCP or unrelated knowledge routing.
        var context = retrievalEngine.retrieve(questions.stream().map(q -> new SubQuestionIntent(q, List.of())).toList(), capture);
        boolean degraded = capture.stages().stream().anyMatch(s -> s.failure() != null);
        return Results.success(new Response(request.question(), questions, request.rewrite(),
                context.getKbContext(), capture.stages(), degraded, rewriteMs,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    public record Request(String question, boolean rewrite) { }

    public record Response(String question, List<String> subQuestions, boolean rewriteEnabled,
                           String renderedContext, List<RetrievalCapture.Stage> stages,
                           boolean degraded, long rewriteMs, long totalMs) { }
}
