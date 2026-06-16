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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final ContextFormatter contextFormatter;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor ragContextExecutor;
    @Qualifier("mcpBatchThreadPoolExecutor")
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     *
     * @param subIntents 子问题意图列表，包含每个子问题及其相关的意图节点和评分
     * @param topK       需要返回的最相关结果数量，若 ≤0 则使用默认值
     * @return RetrievalContext 检索上下文，包含知识库上下文、MCP上下文和分组的检索块
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> buildSubQuestionContext(
                                si,
                                resolveSubQuestionTopK(si, finalTopK)
                        ),
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();

        for (SubQuestionContext context : contexts) {
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }

    /**
     * 针对单个子问题，分别执行 KB 检索和 MCP 工具调用并组装结果。
     *
     * <p>KB 检索走多通道引擎，MCP 工具调用在专属线程池中并行执行。
     * 两路结果互相独立，其中一路为空不影响另一路。</p>
     */
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK) {
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMCPIntents(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK);

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则：
     * 1. 命中 KB 意图节点且配置了节点级 topK：取最大值（多意图保守放大）
     * 2. 没有任何可用节点级 topK：回退到全局 topK
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return filterKbIntents(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    /** 按"--- 子问题 / 相关文档"格式将单个子问题的检索结果追加到汇总 Builder 中。 */
    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("---\n")
                .append("**子问题**：").append(question).append("\n\n")
                .append("**相关文档**：\n")
                .append(context).append("\n\n");
    }

    /** 过滤出得分达标、类型为 MCP 且配置了 mcpToolId 的意图，供 MCP 工具调用使用。 */
    private List<NodeScore> filterMCPIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /** 过滤出得分达标、类型为 KB（或 kind 为 null 时默认视为 KB）的意图，供知识库检索使用。 */
    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    /**
     * 并行调用所有命中的 MCP 工具，并将成功结果格式化为上下文字符串。
     *
     * <p>全部工具调用失败时返回空字符串，不会让后续流程以空 MCP 上下文组装 Prompt。</p>
     */
    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        List<MCPResponse> responses = executeMcpTools(question, mcpIntents);
        if (responses.isEmpty() || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }

        return contextFormatter.formatMcpContext(responses, mcpIntents);
    }

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    /**
     * 批量并行执行 MCP 工具请求。
     *
     * <p>每个意图构建一个 {@link MCPRequest} 后提交到 {@code mcpBatchExecutor} 线程池异步执行，
     * 最后统一等待所有结果返回。单个工具失败不影响其他工具。</p>
     */
    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
        List<MCPRequest> requests = mcpIntentScores.stream()
                .map(ns -> buildMcpRequest(question, ns.getNode()))
                .filter(Objects::nonNull)
                .toList();

        if (requests.isEmpty()) {
            return List.of();
        }

        // 并行执行所有 MCP 工具调用
        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> executeSingleMcpTool(request), mcpBatchExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 执行单个 MCP 工具请求。
     *
     * <p>从注册表中查找对应的执行器后调用 {@code execute}；
     * 工具不存在或调用抛出异常时均返回 {@link MCPResponse#error} 而非向上抛出。</p>
     */
    private MCPResponse executeSingleMcpTool(MCPRequest request) {
        String toolId = request.getToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具执行失败, 工具不存在: {}", toolId);
            return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
        }

        try {
            return executorOpt.get().execute(request);
        } catch (Exception e) {
            log.error("MCP 工具执行异常, toolId: {}", toolId, e);
            return MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用异常: " + e.getMessage());
        }
    }

    /**
     * 根据用户问题和意图节点构建 MCP 工具调用请求。
     *
     * <p>通过 {@link MCPParameterExtractor} 从问题中抽取工具所需参数（如时间、地点、编号等），
     * 工具执行器不存在时返回 {@code null}（调用方会过滤掉 null 请求）。</p>
     */
    private MCPRequest buildMcpRequest(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        MCPTool tool = executorOpt.get().getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        return MCPRequest.builder()
                .toolId(toolId)
                .userQuestion(question)
                .parameters(params != null ? params : new HashMap<>())
                .build();
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
