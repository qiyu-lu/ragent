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

package com.nageoffer.ai.ragent.rag.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 系统全量设置视图对象
 * 聚合 RAG、AI 模型、文件上传等所有配置，供前端一次拉取完成初始化
 */
@Setter
@Getter
public class SystemSettingsVO {

    /** RAG 配置块（向量默认参数、查询改写、限流、对话记忆） */
    private RagSettings rag;

    /** AI 模型配置块（提供商、chat/embedding/rerank 模型组、熔断、流式） */
    private AISettings ai;

    /** 文件上传限制配置块（单文件大小、请求总大小） */
    private UploadSettings upload;

    public SystemSettingsVO(RagSettings rag, AISettings ai, UploadSettings upload) {
        this.rag = rag;
        this.ai = ai;
        this.upload = upload;
    }

    public static SystemSettingsVOBuilder builder() {
        return new SystemSettingsVOBuilder();
    }

    public static class SystemSettingsVOBuilder {
        private RagSettings rag;
        private AISettings ai;
        private UploadSettings upload;

        public SystemSettingsVOBuilder rag(RagSettings rag) {
            this.rag = rag;
            return this;
        }

        public SystemSettingsVOBuilder ai(AISettings ai) {
            this.ai = ai;
            return this;
        }

        public SystemSettingsVOBuilder upload(UploadSettings upload) {
            this.upload = upload;
            return this;
        }

        public SystemSettingsVO build() {
            return new SystemSettingsVO(rag, ai, upload);
        }
    }

    /** 文件上传大小限制配置 */
    @Data
    @Builder
    public static class UploadSettings {
        /** 单文件上传大小上限（字节），来自 spring.servlet.multipart.max-file-size */
        private Long maxFileSize;
        /** 单次请求总上传大小上限（字节），来自 spring.servlet.multipart.max-request-size */
        private Long maxRequestSize;
    }

    /** AI 模型配置（提供商映射 + 三类模型组 + 熔断策略 + 流式分块） */
    @Data
    @Builder
    public static class AISettings {
        /** AI 提供商配置映射，key 为提供商名称（如 openai、qwen） */
        private Map<String, ProviderConfig> providers;
        /** 聊天对话模型组 */
        private ModelGroup chat;
        /** 向量嵌入模型组 */
        private ModelGroup embedding;
        /** 重排序模型组 */
        private ModelGroup rerank;
        /** 模型选择熔断策略（故障阈值 + 熔断持续时间） */
        private Selection selection;
        /** 流式响应分块配置 */
        private Stream stream;

        /** 单个 AI 提供商的连接配置 */
        @Data
        @Builder
        public static class ProviderConfig {
            /** 提供商基础 URL */
            private String url;
            /** API 密钥 */
            private String apiKey;
            /** 端点映射，key 为端点类型，value 为完整路径 */
            private Map<String, String> endpoints;
        }

        /** 模型组配置（默认模型 + 深度思考模型 + 候选列表） */
        @Data
        @Builder
        public static class ModelGroup {
            /** 默认使用的模型标识 ID */
            private String defaultModel;
            /** 深度思考（Chain-of-Thought）模型标识 ID */
            private String deepThinkingModel;
            /** 候选模型列表，按 priority 升序排列，系统自动路由 */
            private List<ModelCandidate> candidates;
        }

        /** 单个候选模型的详细配置 */
        @Data
        @Builder
        public static class ModelCandidate {
            /** 模型唯一标识符（路由键） */
            private String id;
            /** 所属提供商名称，对应 providers 中的 key */
            private String provider;
            /** 模型名称（传给 API 的 model 字段） */
            private String model;
            /** 模型专用 URL（覆盖提供商基础 URL） */
            private String url;
            /** 向量维度，仅 embedding 模型有效 */
            private Integer dimension;
            /** 优先级，数值越小越优先被选用（默认 100） */
            private Integer priority;
            /** 是否启用此候选模型 */
            private Boolean enabled;
            /** 是否支持思考链（Deep Thinking）功能 */
            private Boolean supportsThinking;
        }

        /** 模型选择熔断策略配置 */
        @Data
        @Builder
        public static class Selection {
            /** 连续失败次数阈值，超过后触发熔断（默认 2） */
            private Integer failureThreshold;
            /** 熔断器打开持续时间（毫秒），期间跳过该模型（默认 30000） */
            private Long openDurationMs;
        }

        /** 流式响应分块配置 */
        @Data
        @Builder
        public static class Stream {
            /** 每次向客户端推送的字符块大小（默认 5） */
            private Integer messageChunkSize;
        }
    }

    /** RAG 向量数据库默认参数 */
    @Data
    @Builder
    public static class DefaultSettings {
        /** 默认向量集合名称（Milvus Collection / PgVector 表名） */
        private String collectionName;
        /** 向量维度，须与所用 embedding 模型输出维度一致（如 1536、2048） */
        private Integer dimension;
        /** 相似度度量类型：COSINE（余弦）/ L2（欧氏距离）/ IP（内积） */
        private String metricType;
    }

    /** 对话记忆配置（历史轮数、TTL、摘要压缩） */
    @Data
    @Builder
    public static class MemorySettings {
        /** 保留原文的最近轮数（user+assistant 视为一轮，默认 8） */
        private Integer historyKeepTurns;
        /** 对话历史缓存过期时间（分钟，默认 60） */
        private Integer ttlMinutes;
        /** 是否启用对话摘要压缩（超过阈值后将早期历史压缩为摘要，默认 false） */
        private Boolean summaryEnabled;
        /** 开始生成摘要的轮数阈值（默认 9，须 > historyKeepTurns） */
        private Integer summaryStartTurns;
        /** 摘要最大字符数（默认 200，范围 200~1000） */
        private Integer summaryMaxChars;
        /** 会话标题最大长度，用于 LLM 生成标题时的提示词约束（默认 30） */
        private Integer titleMaxLength;
    }

    /** RAG 整体配置块（包含向量默认参数、查询改写、限流、记忆四个子配置） */
    @Setter
    @Getter
    public static class RagSettings {
        /** 向量数据库默认配置（JSON key 为 "default"，避免与 Java 关键字冲突） */
        @JsonProperty("default")
        private DefaultSettings defaultConfig;
        /** 查询改写配置（开关 + 历史消息数/字符数上限） */
        private QueryRewriteSettings queryRewrite;
        /** 全局并发限流配置 */
        private RateLimitSettings rateLimit;
        /** 对话记忆配置 */
        private MemorySettings memory;

        public RagSettings(DefaultSettings defaultConfig, QueryRewriteSettings queryRewrite,
                           RateLimitSettings rateLimit, MemorySettings memory) {
            this.defaultConfig = defaultConfig;
            this.queryRewrite = queryRewrite;
            this.rateLimit = rateLimit;
            this.memory = memory;
        }

        public static RagSettingsBuilder builder() {
            return new RagSettingsBuilder();
        }

        public static class RagSettingsBuilder {
            private DefaultSettings defaultConfig;
            private QueryRewriteSettings queryRewrite;
            private RateLimitSettings rateLimit;
            private MemorySettings memory;

            public RagSettingsBuilder defaultConfig(DefaultSettings defaultConfig) {
                this.defaultConfig = defaultConfig;
                return this;
            }

            public RagSettingsBuilder queryRewrite(QueryRewriteSettings queryRewrite) {
                this.queryRewrite = queryRewrite;
                return this;
            }

            public RagSettingsBuilder rateLimit(RateLimitSettings rateLimit) {
                this.rateLimit = rateLimit;
                return this;
            }

            public RagSettingsBuilder memory(MemorySettings memory) {
                this.memory = memory;
                return this;
            }

            public RagSettings build() {
                return new RagSettings(defaultConfig, queryRewrite, rateLimit, memory);
            }
        }
    }

    /** 查询改写配置（控制是否在检索前通过 LLM 对用户问题进行语境改写） */
    @Setter
    @Getter
    public static class QueryRewriteSettings {
        /** 是否启用查询改写（默认 true） */
        private Boolean enabled;
        /** 改写时纳入的最大历史消息条数（默认 4，user+assistant 各算 1 条） */
        private Integer maxHistoryMessages;
        /** 改写时纳入的历史消息最大字符数（默认 500，防止超出上下文限制） */
        private Integer maxHistoryChars;

        public QueryRewriteSettings(Boolean enabled, Integer maxHistoryMessages, Integer maxHistoryChars) {
            this.enabled = enabled;
            this.maxHistoryMessages = maxHistoryMessages;
            this.maxHistoryChars = maxHistoryChars;
        }

        public static QueryRewriteSettingsBuilder builder() {
            return new QueryRewriteSettingsBuilder();
        }

        public static class QueryRewriteSettingsBuilder {
            private Boolean enabled;
            private Integer maxHistoryMessages;
            private Integer maxHistoryChars;

            public QueryRewriteSettingsBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public QueryRewriteSettingsBuilder maxHistoryMessages(Integer maxHistoryMessages) {
                this.maxHistoryMessages = maxHistoryMessages;
                return this;
            }

            public QueryRewriteSettingsBuilder maxHistoryChars(Integer maxHistoryChars) {
                this.maxHistoryChars = maxHistoryChars;
                return this;
            }

            public QueryRewriteSettings build() {
                return new QueryRewriteSettings(enabled, maxHistoryMessages, maxHistoryChars);
            }
        }
    }

    /** 限流配置（当前仅包含全局并发限流） */
    @Setter
    @Getter
    public static class RateLimitSettings {
        /** 全局并发限流配置 */
        private GlobalRateLimit global;

        public RateLimitSettings(GlobalRateLimit global) {
            this.global = global;
        }

        public static RateLimitSettingsBuilder builder() {
            return new RateLimitSettingsBuilder();
        }

        public static class RateLimitSettingsBuilder {
            private GlobalRateLimit global;

            public RateLimitSettingsBuilder global(GlobalRateLimit global) {
                this.global = global;
                return this;
            }

            public RateLimitSettings build() {
                return new RateLimitSettings(global);
            }
        }
    }

    /**
     * 全局 RAG 请求并发限流配置
     * 基于 Redis 分布式信号量实现，超并发请求进入等待队列轮询
     */
    @Setter
    @Getter
    public static class GlobalRateLimit {
        /** 是否启用全局限流（默认 true） */
        private Boolean enabled;
        /** 最大并发请求数，超出后进入等待（默认 50） */
        private Integer maxConcurrent;
        /** 请求最长等待秒数，超时后返回限流错误（默认 20） */
        private Integer maxWaitSeconds;
        /** 许可自动释放时间（秒），防止获取许可的请求异常退出时永久占用（默认 600） */
        private Integer leaseSeconds;
        /** 等待队列轮询间隔（毫秒），控制等待中的请求检查许可的频率（默认 200） */
        private Integer pollIntervalMs;

        public GlobalRateLimit(Boolean enabled, Integer maxConcurrent, Integer maxWaitSeconds,
                               Integer leaseSeconds, Integer pollIntervalMs) {
            this.enabled = enabled;
            this.maxConcurrent = maxConcurrent;
            this.maxWaitSeconds = maxWaitSeconds;
            this.leaseSeconds = leaseSeconds;
            this.pollIntervalMs = pollIntervalMs;
        }

        public static GlobalRateLimitBuilder builder() {
            return new GlobalRateLimitBuilder();
        }

        public static class GlobalRateLimitBuilder {
            private Boolean enabled;
            private Integer maxConcurrent;
            private Integer maxWaitSeconds;
            private Integer leaseSeconds;
            private Integer pollIntervalMs;

            public GlobalRateLimitBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public GlobalRateLimitBuilder maxConcurrent(Integer maxConcurrent) {
                this.maxConcurrent = maxConcurrent;
                return this;
            }

            public GlobalRateLimitBuilder maxWaitSeconds(Integer maxWaitSeconds) {
                this.maxWaitSeconds = maxWaitSeconds;
                return this;
            }

            public GlobalRateLimitBuilder leaseSeconds(Integer leaseSeconds) {
                this.leaseSeconds = leaseSeconds;
                return this;
            }

            public GlobalRateLimitBuilder pollIntervalMs(Integer pollIntervalMs) {
                this.pollIntervalMs = pollIntervalMs;
                return this;
            }

            public GlobalRateLimit build() {
                return new GlobalRateLimit(enabled, maxConcurrent, maxWaitSeconds, leaseSeconds, pollIntervalMs);
            }
        }
    }
}
