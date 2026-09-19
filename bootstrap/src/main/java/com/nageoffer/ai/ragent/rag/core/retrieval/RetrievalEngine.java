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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.RetrievalSelectionDiagnostics;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库），并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;

    /**
     * 检索方法：根据子问题意图列表执行知识库检索
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
        return retrieve(subIntents, null);
    }

    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, RetrievalCapture capture) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .kbChunks(List.of())
                    .intentChunks(Map.of())
                    .build();
        }

        // 一次算好请求级检索预算。recall/candidate 是每个子问题的候选质量预算；contextTopK 是整次请求最终
        // 进入 LLM 的总额度，必须在子问题之间分摊。旧实现给每个子问题各发一份 TopK，2~3 个子问题会把
        // 配置的 10 条膨胀到 20~30 条，Context Precision 和 token 成本都随拆分次数恶化。
        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget requestBudget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        List<RetrievalBudget> questionBudgets = allocateQuestionBudgets(requestBudget, subIntents.size());
        boolean fairRefillEnabled = searchProperties.isRequestLevelRefillEnabled();
        List<CompletableFuture<SubQuestionContext>> tasks = new ArrayList<>(subIntents.size());
        for (int i = 0; i < subIntents.size(); i++) {
            SubQuestionIntent subIntent = subIntents.get(i);
            RetrievalBudget questionBudget = questionBudgets.get(i);
            boolean kbSkipped = questionBudget.contextTopK() <= 0;
            RetrievalBudget candidateBudget = fairRefillEnabled && !kbSkipped
                    ? requestBudget
                    : questionBudget;
            tasks.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(subIntent, candidateBudget, kbSkipped, capture);
                            } catch (Exception e) {
                                if (capture != null) {
                                    capture.record(subIntent.subQuestion(), "subquestion-failed", List.of(), 0,
                                            e.getClass().getSimpleName());
                                }
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", subIntent.subQuestion(), e);
                                return new SubQuestionContext(subIntent, KnowledgeRetrievalResult.empty(), false);
                            }
                        },
                        ragContextExecutor
                ));
        }
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        List<Integer> initialBudgets = questionBudgets.stream()
                .map(RetrievalBudget::contextTopK)
                .toList();
        RequestSelection requestSelection = selectRequestChunks(
                contexts,
                initialBudgets,
                requestBudget.contextTopK(),
                fairRefillEnabled);
        List<RetrievedChunk> kbChunks = requestSelection.orderedUniqueChunks();
        if (capture != null) {
            capture.record("", "request-final", kbChunks, 0, null);
        }
        Set<String> selectedKeys = kbChunks.stream()
                .map(RetrievedChunkKey::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Set<String>> attributionByChunkKey = mergeSelectedAttribution(contexts, selectedKeys);
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new KnowledgeRetrievalResult(
                kbChunks, attributionByChunkKey, Set.of()).groupByIntent(MULTI_CHANNEL_KEY);

        Set<String> eligibleIntentIds = new LinkedHashSet<>();
        List<RenderedSubQuestion> renderedContexts = new ArrayList<>(contexts.size());
        for (int i = 0; i < contexts.size(); i++) {
            SubQuestionContext context = contexts.get(i);
            List<NodeScore> kbIntents = NodeScoreFilters.kb(context.intent().nodeScores());
            List<RetrievedChunk> selectedForQuestion = requestSelection.selectedByQuestion().get(i);
            List<RetrievedChunk> eligibilityEvidence = fairRefillEnabled
                    ? context.retrievalResult().chunks().stream()
                            .filter(chunk -> selectedKeys.contains(RetrievedChunkKey.of(chunk)))
                            .toList()
                    : selectedForQuestion;
            Set<String> questionEligible = context.kbSkipped()
                    ? Set.of()
                    : context.retrievalResult().retainChunks(eligibilityEvidence).eligibleIntentIds(kbIntents);
            eligibleIntentIds.addAll(questionEligible);
            String formattedKb = CollUtil.isEmpty(selectedForQuestion)
                    ? ""
                    : contextFormatter.formatKbContext(
                            kbIntents, questionEligible, selectedForQuestion, selectedForQuestion.size());
            renderedContexts.add(new RenderedSubQuestion(context.intent().subQuestion(), formattedKb));
        }

        boolean singleQuestion = renderedContexts.size() == 1;
        String kbContext;

        if (singleQuestion) {
            kbContext = StrUtil.emptyIfNull(renderedContexts.get(0).kbContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            int globalIndex = 0;
            for (RenderedSubQuestion context : renderedContexts) {
                if (StrUtil.isNotBlank(context.kbContext())) {
                    globalIndex++;
                    appendSection(kbBuilder, "sub-question-kb-wrapper", globalIndex, context.question(), context.kbContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
        }

        return RetrievalContext.builder()
                .kbContext(kbContext)
                .kbChunks(kbChunks)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .retrievalDiagnostics(requestSelection.diagnostics())
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent,
                                                       RetrievalBudget candidateBudget,
                                                       boolean kbSkipped,
                                                       RetrievalCapture capture) {
        KnowledgeRetrievalResult retrievalResult;
        if (kbSkipped) {
            log.warn("子问题超出请求级上下文额度，跳过 KB 检索，question={}", intent.subQuestion());
            retrievalResult = KnowledgeRetrievalResult.empty();
        } else {
            retrievalResult = capture == null
                    ? multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, candidateBudget)
                    : multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, candidateBudget, capture);
        }

        return new SubQuestionContext(intent, retrievalResult, kbSkipped);
    }

    private void appendSection(StringBuilder builder, String section, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    /**
     * 把请求级最终上下文额度近似均分给各子问题，余数按原顺序每题多分一条。
     * <p>
     * 开启公平回填时，该配额只约束请求级最终选择，不再提前丢弃每题候选池。若极端情况下子问题数
     * 超过 contextTopK，后面的子问题获得 0 条 KB 配额并显式告警，保证总额度这一产品契约不被突破。
     */
    private List<RetrievalBudget> allocateQuestionBudgets(RetrievalBudget requestBudget, int questionCount) {
        if (questionCount <= 0) {
            return List.of();
        }
        int base = requestBudget.contextTopK() / questionCount;
        int remainder = requestBudget.contextTopK() % questionCount;
        List<RetrievalBudget> budgets = new ArrayList<>(questionCount);
        for (int i = 0; i < questionCount; i++) {
            int questionTopK = base + (i < remainder ? 1 : 0);
            budgets.add(new RetrievalBudget(
                    requestBudget.recallBudget(),
                    requestBudget.candidateLimit(),
                    questionTopK));
        }
        if (questionCount > 1) {
            log.info("多子问题共享请求级上下文额度 - 子问题数: {}, 总 TopK: {}, 分配: {}",
                    questionCount, requestBudget.contextTopK(),
                    budgets.stream().map(RetrievalBudget::contextTopK).toList());
        }
        return budgets;
    }

    private RequestSelection selectRequestChunks(List<SubQuestionContext> contexts,
                                                  List<Integer> initialBudgets,
                                                  int requestTopK,
                                                  boolean fairRefillEnabled) {
        List<List<RetrievedChunk>> candidatesByQuestion = contexts.stream()
                .map(context -> context.retrievalResult().chunks())
                .toList();
        RequestLevelChunkSelector.SelectionResult fairSelection = RequestLevelChunkSelector.select(
                candidatesByQuestion, initialBudgets, requestTopK);

        List<List<RetrievedChunk>> selectedByQuestion;
        List<RetrievedChunk> orderedUniqueChunks;
        int refillAdded;
        int finalUniqueCount;
        int unfilledSlots;
        if (fairRefillEnabled) {
            selectedByQuestion = fairSelection.selectedByQuestion();
            // Prompt 按子问题分组渲染；canonical 列表必须使用同一顺序，保证来源、grounding 与评测中的
            // Hit@K 真正对应模型看到的 Chunk 顺序。轮询顺序只负责公平决定“选哪些”，不冒充 Prompt 排名。
            orderedUniqueChunks = distinctChunks(selectedByQuestion.stream()
                    .flatMap(List::stream)
                    .toList());
            refillAdded = fairSelection.refillAdded();
            finalUniqueCount = fairSelection.finalUniqueCount();
            unfilledSlots = fairSelection.unfilledSlots();
        } else {
            selectedByQuestion = selectLegacyPrefixes(candidatesByQuestion, initialBudgets);
            orderedUniqueChunks = distinctChunks(selectedByQuestion.stream()
                    .flatMap(List::stream)
                    .toList());
            refillAdded = 0;
            finalUniqueCount = orderedUniqueChunks.size();
            unfilledSlots = Math.max(0, requestTopK - finalUniqueCount);
        }

        RetrievalSelectionDiagnostics diagnostics = new RetrievalSelectionDiagnostics(
                fairRefillEnabled,
                requestTopK,
                initialBudgets,
                fairSelection.candidateCount(),
                fairSelection.candidateUniqueCount(),
                fairSelection.uniqueBeforeRefill(),
                refillAdded,
                finalUniqueCount,
                unfilledSlots);
        log.info("请求级上下文选择 - 公平回填: {}, 配额: {}, 候选: {}/{}, 旧前缀唯一: {}, "
                        + "相对旧版新增: {}, 最终唯一: {}, 未填: {}",
                fairRefillEnabled,
                initialBudgets,
                diagnostics.candidateCount(),
                diagnostics.candidateUniqueCount(),
                diagnostics.uniqueBeforeRefill(),
                diagnostics.refillAdded(),
                diagnostics.finalUniqueCount(),
                diagnostics.unfilledSlots());
        return new RequestSelection(selectedByQuestion, orderedUniqueChunks, diagnostics);
    }

    /**
     * 固定对照用的旧行为：每题只取配额前缀，跨题重复留下的空位不回填。
     */
    private List<List<RetrievedChunk>> selectLegacyPrefixes(List<List<RetrievedChunk>> candidatesByQuestion,
                                                            List<Integer> initialBudgets) {
        List<List<RetrievedChunk>> selected = new ArrayList<>(candidatesByQuestion.size());
        for (int i = 0; i < candidatesByQuestion.size(); i++) {
            List<RetrievedChunk> candidates = candidatesByQuestion.get(i);
            int limit = Math.min(initialBudgets.get(i), candidates.size());
            selected.add(List.copyOf(candidates.subList(0, limit)));
        }
        return List.copyOf(selected);
    }

    private List<RetrievedChunk> distinctChunks(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.stream()
                .filter(Objects::nonNull)
                .forEach(chunk -> distinct.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));
        return List.copyOf(distinct.values());
    }

    /**
     * 同一最终分片可以在多个子问题中命中不同意图；此处汇总它的全部归因，但正文只渲染一次。
     */
    private Map<String, Set<String>> mergeSelectedAttribution(List<SubQuestionContext> contexts,
                                                              Set<String> selectedKeys) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (SubQuestionContext context : contexts) {
            context.retrievalResult().intentIdsByChunkKey().forEach((chunkKey, intentIds) -> {
                if (!selectedKeys.contains(chunkKey) || CollUtil.isEmpty(intentIds)) {
                    return;
                }
                merged.computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>()).addAll(intentIds);
            });
        }
        return merged;
    }

    private record SubQuestionContext(SubQuestionIntent intent,
                                      KnowledgeRetrievalResult retrievalResult,
                                      boolean kbSkipped) {
    }

    private record RenderedSubQuestion(String question, String kbContext) {
    }

    private record RequestSelection(List<List<RetrievedChunk>> selectedByQuestion,
                                    List<RetrievedChunk> orderedUniqueChunks,
                                    RetrievalSelectionDiagnostics diagnostics) {

        private RequestSelection {
            selectedByQuestion = selectedByQuestion.stream().map(List::copyOf).toList();
            orderedUniqueChunks = List.copyOf(orderedUniqueChunks);
        }
    }
}
