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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_KB_MIXED_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_ONLY_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RAG_ENTERPRISE_PROMPT_PATH;

/**
 * RAG Prompt 编排服务
 * <p>
 * 根据检索结果场景（KB / MCP / Mixed）选择模板，并构造最终发送给 LLM 的消息序列
 */
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    private static final String MCP_CONTEXT_HEADER = "## 动态数据片段";
    private static final String KB_CONTEXT_HEADER = "## 文档内容";

    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 生成系统提示词，并对模板格式做清理
     */
    public String buildSystemPrompt(PromptContext context) {
        PromptBuildPlan plan = plan(context);
        String template = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());
        return StrUtil.isBlank(template) ? "" : PromptTemplateUtils.cleanupPrompt(template);
    }

    /**
     * 构造发送给 LLM 的完整消息列表（system + evidence + history + user）
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(context);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            messages.add(ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, context.getMcpContext())));
        }
        if (StrUtil.isNotBlank(context.getKbContext())) {
            messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())));
        }
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        // 多子问题场景下，显式编号以降低模型漏答风险
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            StringBuilder userMessage = new StringBuilder();
            userMessage.append("请基于上述文档内容，回答以下问题：\n\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                userMessage.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
            }
            messages.add(ChatMessage.user(userMessage.toString().trim()));
        } else if (StrUtil.isNotBlank(question)) {
            messages.add(ChatMessage.user(question));
        }

        return messages;
    }

    /**
     * 根据 KB 意图及其实际检索片段，决定本次应使用的系统 Prompt 模板。
     *
     * <p>规则：
     * <ol>
     *   <li>先剔除没有命中任何检索片段的意图（打分命中但向量检索未返回内容）。</li>
     *   <li>单个有效意图且该意图节点配置了自定义模板 → 使用节点自定义模板。</li>
     *   <li>其余情况（多意图或无自定义模板）→ 返回 null，调用方使用场景默认模板。</li>
     * </ol>
     * </p>
     */
    private PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        // 1) 先剔除“未命中检索”的意图
        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            // 没有任何可用意图：无基模板（上层可根据业务选择 fallback）
            return new PromptPlan(Collections.emptyList(), null);
        }

        // 2) 单 / 多意图的模板与片段策略
        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：使用模板本身
                return new PromptPlan(retained, tpl);
            } else {
                // 单意图 + 无模板：走默认模板
                return new PromptPlan(retained, null);
            }
        } else {
            // 多意图：统一默认模板
            return new PromptPlan(retained, null);
        }
    }

    /**
     * 根据检索上下文（是否有 MCP / KB 证据）选择对应的 Prompt 规划策略。
     *
     * <p>MCP-only、KB-only、Mixed 三种场景对应不同的系统提示词和参数组合。
     * 两路均为空时抛出 {@link IllegalStateException}，防止无证据情况下进入 LLM 调用。</p>
     */
    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    /** 纯知识库场景：调用 planPrompt 决定是否使用节点自定义模板，否则加载企业 RAG 默认模板。 */
    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = planPrompt(context.getKbIntents(), context.getIntentChunks());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /** 纯 MCP 工具场景：单个 MCP 意图节点配置了自定义模板时优先使用，否则加载 MCP 默认模板。 */
    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /** KB + MCP 混合场景：固定使用混合场景默认模板，不读取节点自定义模板。 */
    private PromptBuildPlan planMixed(PromptContext context) {
        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /**
     * 按场景类型加载对应的默认系统 Prompt 模板。
     *
     * <p>模板路径常量定义在 {@code RAGConstant} 中：
     * KB_ONLY → 企业 RAG 模板，MCP_ONLY → MCP 默认模板，MIXED → 混合模板。</p>
     */
    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> promptTemplateLoader.load(RAG_ENTERPRISE_PROMPT_PATH);
            case MCP_ONLY -> promptTemplateLoader.load(MCP_ONLY_PROMPT_PATH);
            case MIXED -> promptTemplateLoader.load(MCP_KB_MIXED_PROMPT_PATH);
            case EMPTY -> "";
        };
    }

    /** 在证据内容前拼接区块标题（如 "## 文档内容"），便于模型区分 MCP 数据和 KB 文档。 */
    private String formatEvidence(String header, String body) {
        return header + "\n" + body.trim();
    }

    // === 工具方法 ===

    /**
     * 从意图节点提取用于映射检索结果的 key
     */
    private static String nodeKey(IntentNode node) {
        if (node == null) return "";
        if (StrUtil.isNotBlank(node.getId())) return node.getId();
        return String.valueOf(node.getId());
    }

}
