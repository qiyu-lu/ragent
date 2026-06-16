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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.MemorySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 系统设置查询控制器
 * <p>
 * 将分散在多个 @ConfigurationProperties 类中的配置汇聚为一个 {@link SystemSettingsVO} 返回，
 * 供前端初始化全局设置页面时一次性获取所有配置项，避免多次请求。
 * <p>
 * 配置来源：
 * - {@code rag.default.*}        → 向量数据库默认参数（集合名称、维度、度量类型）
 * - {@code rag.query-rewrite.*}  → 查询改写功能开关与历史上下文限制
 * - {@code rag.rate-limit.*}     → 全局并发限流策略
 * - {@code rag.memory.*}         → 对话记忆轮数、TTL、摘要压缩等
 * - {@code ai.*}                 → AI 提供商、模型组（chat/embedding/rerank）及熔断/流式配置
 * - {@code spring.servlet.multipart.*} → 文件上传大小限制
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    /** 单文件上传大小上限，来自 spring.servlet.multipart.max-file-size，默认 50MB */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /** 单次请求总上传大小上限，来自 spring.servlet.multipart.max-request-size，默认 100MB */
    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    /**
     * 获取系统全量配置信息（只读）
     * 将 RAG、AI 模型、文件上传等配置汇聚为单一响应，前端一次拉取即可完成初始化
     *
     * @return 包含 upload / rag / ai 三大配置块的 SystemSettingsVO
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                // 文件上传大小限制（转换为字节数，方便前端直接校验）
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                // RAG 配置块：向量默认参数 + 查询改写 + 限流 + 记忆
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .maxHistoryMessages(ragConfigProperties.getQueryRewriteMaxHistoryMessages())
                                .maxHistoryChars(ragConfigProperties.getQueryRewriteMaxHistoryChars())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                // AI 配置块：提供商 + chat/embedding/rerank 模型组 + 熔断 + 流式
                .ai(toAISettings(aiModelProperties))
                .build();
        return Results.success(response);
    }

    /**
     * 将 RAGDefaultProperties 转换为 DefaultSettings VO
     * 包含向量集合名称、向量维度（需与 embedding 模型一致）、相似度度量类型
     */
    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .build();
    }

    /**
     * 将 MemoryProperties 转换为 MemorySettings VO
     * 包含历史保留轮数、缓存 TTL、摘要压缩开关及相关参数
     */
    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .ttlMinutes(props.getTtlMinutes())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    /**
     * 将 AIModelProperties 转换为 AISettings VO
     * 1. 遍历 providers map，逐项转换为 ProviderConfig（url / apiKey / endpoints）
     * 2. 转换 chat / embedding / rerank 三个模型组（各含默认模型 + 候选列表）
     * 3. 转换熔断策略（Selection）和流式分块配置（Stream）
     */
    private AISettings toAISettings(AIModelProperties props) {
        // 将提供商 Map 条目转换为 VO（保持原始 key）
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(v.getApiKey())
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                // selection / stream 为 null 时直接传 null，前端按需处理
                .selection(props.getSelection() == null ? null : AISettings.Selection.builder()
                        .failureThreshold(props.getSelection().getFailureThreshold())
                        .openDurationMs(props.getSelection().getOpenDurationMs())
                        .build())
                .stream(props.getStream() == null ? null : AISettings.Stream.builder()
                        .messageChunkSize(props.getStream().getMessageChunkSize())
                        .build())
                .build();
    }

    /**
     * 将单个 ModelGroup 转换为 VO（通用逻辑，chat/embedding/rerank 共用）
     * candidates 为 null 时保留 null，不转换为空列表
     */
    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .deepThinkingModel(group.getDeepThinkingModel())
                .candidates(group.getCandidates() == null ? null : group.getCandidates().stream()
                        .map(c -> AISettings.ModelCandidate.builder()
                                .id(c.getId())
                                .provider(c.getProvider())
                                .model(c.getModel())
                                .url(c.getUrl())
                                .dimension(c.getDimension())
                                .priority(c.getPriority())
                                .enabled(c.getEnabled())
                                .supportsThinking(c.getSupportsThinking())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
