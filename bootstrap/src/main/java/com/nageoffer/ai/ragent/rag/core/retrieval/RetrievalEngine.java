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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.mcp.McpExtractionResult;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.RetrievalSelectionDiagnostics;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final McpParameterExtractor mcpParameterExtractor;
    private final McpToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
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
                                return buildSubQuestionContext(subIntent, candidateBudget, kbSkipped);
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", subIntent.subQuestion(), e);
                                return new SubQuestionContext(subIntent, "", KnowledgeRetrievalResult.empty(), false);
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
            renderedContexts.add(new RenderedSubQuestion(
                    context.intent().subQuestion(), formattedKb, context.mcpContext()));
        }

        boolean singleQuestion = renderedContexts.size() == 1;
        String kbContext;
        String mcpContext;

        if (singleQuestion) {
            RenderedSubQuestion only = renderedContexts.get(0);
            kbContext = StrUtil.emptyIfNull(only.kbContext()).trim();
            mcpContext = StrUtil.emptyIfNull(only.mcpContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            StringBuilder mcpBuilder = new StringBuilder();
            int globalIndex = 0;
            for (RenderedSubQuestion context : renderedContexts) {
                boolean hasKb = StrUtil.isNotBlank(context.kbContext());
                boolean hasMcp = StrUtil.isNotBlank(context.mcpContext());
                if (hasKb || hasMcp) {
                    globalIndex++;
                }
                if (hasKb) {
                    appendSection(kbBuilder, "sub-question-kb-wrapper", globalIndex, context.question(), context.kbContext());
                }
                if (hasMcp) {
                    appendSection(mcpBuilder, "sub-question-mcp-wrapper", globalIndex, context.question(), context.mcpContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
            mcpContext = mcpBuilder.toString().trim();
        }

        return RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbContext)
                .kbChunks(kbChunks)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .retrievalDiagnostics(requestSelection.diagnostics())
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent,
                                                       RetrievalBudget candidateBudget,
                                                       boolean kbSkipped) {
        List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores());
        KnowledgeRetrievalResult retrievalResult;
        if (kbSkipped) {
            log.warn("子问题超出请求级上下文额度，跳过 KB 检索，question={}", intent.subQuestion());
            retrievalResult = KnowledgeRetrievalResult.empty();
        } else {
            retrievalResult = multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, candidateBudget);
        }

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent, mcpContext, retrievalResult, kbSkipped);
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

    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        Map<String, List<CallToolResult>> toolResults = executeMcpTools(question, mcpIntents);
        if (toolResults.isEmpty()) {
            return "";
        }

        return contextFormatter.formatMcpContext(toolResults, mcpIntents);
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

    /**
     * 执行 MCP 工具调用，返回按 toolId 分组的结果
     */
    private Map<String, List<CallToolResult>> executeMcpTools(String question,
                                                              List<NodeScore> mcpIntentScores) {
        if (CollUtil.isEmpty(mcpIntentScores)) {
            return Map.of();
        }

        List<CompletableFuture<ToolOutput>> futures = mcpIntentScores.stream()
                .map(ns -> CompletableFuture.supplyAsync(
                        () -> {
                            String toolId = ns.getNode().getMcpToolId();
                            try {
                                CallToolResult result = executeSingleMcpTool(question, ns.getNode());
                                return result == null ? null : new ToolOutput(toolId, result);
                            } catch (Exception e) {
                                log.error("MCP 工具调用异常, toolId: {}", toolId, e);
                                return new ToolOutput(toolId, CallToolResult.builder()
                                        .content(List.of(new TextContent("工具调用异常: " + e.getMessage())))
                                        .isError(true)
                                        .build());
                            }
                        },
                        mcpBatchExecutor
                ))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ToolOutput::toolId,
                        Collectors.mapping(ToolOutput::result, Collectors.toList())
                ));
    }

    private CallToolResult executeSingleMcpTool(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        McpToolExecutor executor = executorOpt.get();
        Tool tool = executor.getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        McpExtractionResult extraction = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        // 按提参结局分流：仅 SUCCESS 才真正调用远端工具，缺必填参 / 提取失败均不调用、改注入提示进上下文
        return switch (extraction.status()) {
            case SUCCESS -> executor.execute(extraction.params() != null ? extraction.params() : new HashMap<>());
            case NEED_CLARIFICATION -> clarificationResult(toolId, extraction.missingRequired());
            case FAILED -> extractionFailedResult(toolId);
        };
    }

    /**
     * 缺必填参数（用户未提供）：不调用工具，注入结构化提示让 LLM 在回答中主动向用户追问
     * <p>
     * isError=false 使其作为正文进入上下文（而非「工具调用失败」段），便于 LLM 直接据此追问
     */
    private CallToolResult clarificationResult(String toolId, List<String> missingRequired) {
        String missing = CollUtil.isNotEmpty(missingRequired) ? String.join("、", missingRequired) : "必要信息";
        log.info("MCP 缺少必填参数，跳过工具调用并注入澄清提示, toolId: {}, missing: {}", toolId, missingRequired);
        String note = String.format(
                "调用工具【%s】需要参数：%s，但用户问题中未提供。请在回答中主动向用户询问这些信息，不要编造。",
                toolId, missing);
        return CallToolResult.builder()
                .content(List.of(new TextContent(note)))
                .isError(false)
                .build();
    }

    /**
     * 提取失败（协议畸形 / 值非法）：不调用工具，注入失败提示（isError=true 进「工具调用失败」段）
     */
    private CallToolResult extractionFailedResult(String toolId) {
        log.warn("MCP 参数提取失败，跳过工具调用, toolId: {}", toolId);
        return CallToolResult.builder()
                .content(List.of(new TextContent("未能为工具【" + toolId + "】提取到有效参数，已跳过调用。")))
                .isError(true)
                .build();
    }

    private record ToolOutput(String toolId, CallToolResult result) {
    }

    private record SubQuestionContext(SubQuestionIntent intent,
                                      String mcpContext,
                                      KnowledgeRetrievalResult retrievalResult,
                                      boolean kbSkipped) {
    }

    private record RenderedSubQuestion(String question, String kbContext, String mcpContext) {
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
