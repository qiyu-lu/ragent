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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.ScoreBlendProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalCapture;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 评测检索接口：走与问答相同的检索链路，但不生成回答，只返回每个子问题的候选块与请求级最终选择
 * <p>
 * 首次请求（不带 subQuestions）在线改写并把子问题落盘；之后用同一批子问题回放，隔离在线改写的随机性。
 * 鉴权与问答一致：评测脚本用固定评测用户登录，检索范围仍是该用户可读的知识库
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ragent.eval", name = "enabled", havingValue = "true")
public class EvalController {

    static final String MODE_REWRITE = "rewrite";
    static final String MODE_REPLAY = "replay";

    private final QueryRewriteService queryRewriteService;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final ScoreBlendProperties scoreBlendProperties;
    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    @PostMapping("/rag/eval/replay")
    public Result<EvalResponse> replay(@RequestBody EvalReplayRequest request) {
        long started = System.currentTimeMillis();
        String question = requireQuestion(request);

        String mode;
        String rewrittenQuestion = null;
        List<String> subQuestions;
        if (CollUtil.isEmpty(request.subQuestions())) {
            RewriteResult rewrite = queryRewriteService.rewriteWithSplit(question, List.of());
            // 与 StreamChatPipeline 相同：拆分为空时退回改写后的整句
            subQuestions = CollUtil.isNotEmpty(rewrite.subQuestions())
                    ? rewrite.subQuestions()
                    : List.of(rewrite.rewrittenQuestion());
            rewrittenQuestion = rewrite.rewrittenQuestion();
            mode = MODE_REWRITE;
            appendRewriteLog(question, rewrite, subQuestions);
        } else {
            subQuestions = normalizeSubQuestions(request.subQuestions());
            mode = MODE_REPLAY;
        }

        RetrievalCapture capture = new RetrievalCapture();
        RetrievalContext context = retrievalEngine.retrieve(subQuestions, capture);
        return Results.success(EvalCandidateAssembler.assemble(question, mode, rewrittenQuestion, subQuestions,
                context, capture, rerankTopN(subQuestions.size()), System.currentTimeMillis() - started));
    }

    /**
     * 每个子问题送给 Rerank 模型的 topN，与 {@code RetrievalEngine#allocateQuestionBudgets} 同一算法：
     * 请求级公平回填开启时每题都是请求级 TopK；关闭时按子问题数均分，余数按顺序每题多一条。
     * 分数融合打开时 Rerank 给整个候选池打分，头部就是整个池（不超过候选池上限）
     */
    List<Integer> rerankTopN(int questionCount) {
        int requestTopK = searchProperties.getDefaultTopK();
        if (questionCount <= 0) {
            return List.of();
        }
        if (scoreBlendProperties.isEnabled()) {
            int limit = searchProperties.getFusion().getRerankCandidateLimit();
            return Collections.nCopies(questionCount, limit > 0 ? limit : Integer.MAX_VALUE);
        }
        if (searchProperties.isRequestLevelRefillEnabled()) {
            return Collections.nCopies(questionCount, requestTopK);
        }
        int base = requestTopK / questionCount;
        int remainder = requestTopK % questionCount;
        List<Integer> budgets = new ArrayList<>(questionCount);
        for (int i = 0; i < questionCount; i++) {
            budgets.add(base + (i < remainder ? 1 : 0));
        }
        return budgets;
    }

    private static String requireQuestion(EvalReplayRequest request) {
        if (request == null || StrUtil.isBlank(request.question())) {
            throw new ClientException("评测的 question 不能为空");
        }
        return request.question().trim();
    }

    private List<String> normalizeSubQuestions(List<String> raw) {
        int max = evalProperties.getMaxSubQuestions();
        if (raw.size() > max) {
            throw new ClientException("评测的 subQuestions 不能超过 " + max + " 个");
        }
        List<String> normalized = raw.stream().map(StrUtil::trim).toList();
        if (normalized.stream().anyMatch(StrUtil::isBlank)) {
            throw new ClientException("评测的 subQuestions 不能包含空值");
        }
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new ClientException("评测的 subQuestions 不能重复");
        }
        return normalized;
    }

    /**
     * 落盘失败只记日志：评测结果仍以接口返回为准，脚本会把 subQuestions 一并保存
     */
    private void appendRewriteLog(String question, RewriteResult rewrite, List<String> subQuestions) {
        String target = evalProperties.getRewriteLog();
        if (StrUtil.isBlank(target)) {
            return;
        }
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("timestamp", Instant.now().toString());
            line.put("question", question);
            line.put("rewrittenQuestion", rewrite.rewrittenQuestion());
            line.put("subQuestions", subQuestions);
            Path path = Path.of(target);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, objectMapper.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("评测改写落盘失败 path={}", target, e);
        }
    }
}
