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
import com.nageoffer.ai.ragent.infra.operation.RequestOperation;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final RetrievalScopeResolver retrievalScopeResolver;
    private final Executor ragRetrievalExecutor;
    private final SearchChannelProperties searchProperties;

    /**
     * 执行多通道检索（仅 KB 场景）
     * <p>
     * 按子问题逐个调用，范围为当前用户可读的全部有效知识库
     *
     * @param question 子问题
     * @param budget   检索预算（召回扇出 / Rerank 候选池上限 / 最终条数）
     * @return 后处理后的 Chunk
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public KnowledgeRetrievalResult retrieveKnowledgeChannels(String question,
                                                               RetrievalBudget budget) {
        return retrieveKnowledgeChannels(question, budget, null);
    }

    public KnowledgeRetrievalResult retrieveKnowledgeChannels(String question,
                                                               RetrievalBudget budget,
                                                               RetrievalCapture capture) {
        SearchContext context = buildSearchContext(question, budget);

        return retrieve(question, context, capture, false);
    }

    /**
     * 研究专用入口：服务端提供范围，召回前生效，不走联网通道。
     * 当前只使用可回查持久块的向量与关键词通道。
     */
    public KnowledgeRetrievalResult retrieveScopedKnowledgeChannels(String query,
                                                                    RetrievalBudget budget,
                                                                    List<String> allowedCollections) {
        return retrieveScopedKnowledgeChannels(query, budget, allowedCollections, List.of());
    }

    public KnowledgeRetrievalResult retrieveScopedKnowledgeChannels(String query,
                                                                    RetrievalBudget budget,
                                                                    List<String> allowedCollections,
                                                                    List<String> allowedDocumentIds) {
        Objects.requireNonNull(allowedCollections, "必须指定知识库范围");
        Objects.requireNonNull(allowedDocumentIds, "必须指定文档范围");
        if (allowedDocumentIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("文档范围不能包含空标识");
        }
        if (allowedCollections.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("知识库范围不能包含空名称");
        }
        if (allowedCollections.isEmpty()) {
            return KnowledgeRetrievalResult.empty();
        }
        SearchContext context = SearchContext.builder()
                .originalQuestion(query).rewrittenQuestion(query)
                .budget(budget)
                .documentIds(allowedDocumentIds.stream().distinct().toList())
                .retrievalScope(RetrievalScope.of(allowedCollections.stream().distinct().toList()))
                .build();
        return retrieve(query, context, null, true);
    }

    private KnowledgeRetrievalResult retrieve(String question, SearchContext context,
                                               RetrievalCapture capture, boolean sourceBound) {

        List<SearchChannelResult> channelResults = executeSearchChannels(context, sourceBound);
        if (sourceBound && channelResults.stream().flatMap(result -> result.getChunks().stream())
                .anyMatch(chunk -> !context.getRetrievalScope().targetCollections().contains(chunk.getCollectionName())
                        || (!context.getDocumentIds().isEmpty() && !context.getDocumentIds().contains(chunk.getDocId())))) {
            throw new IllegalStateException("检索通道返回了研究范围外的来源");
        }
        if (capture != null) {
            channelResults.forEach(result -> capture.record(question,
                    "channel-" + result.getChannelName(), result.getChunks(), result.getLatencyMs(),
                    result.getChunks().isEmpty() ? "empty-or-failed-channel" : null));
        }
        if (CollUtil.isEmpty(channelResults)) {
            return KnowledgeRetrievalResult.empty();
        }

        List<RetrievedChunk> chunks = executePostProcessors(channelResults, context, capture);
        return new KnowledgeRetrievalResult(chunks);
    }

    private List<SearchChannelResult> executeSearchChannels(SearchContext context, boolean sourceBound) {
        // 按通道类型枚举序做稳定排序：通道并行执行、下游融合（RRF）与归因均与顺序无关，
        // 这里排序仅为日志/派发顺序稳定可复现，不承载任何检索优先级语义
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> !sourceBound || channel.getType() == SearchChannelType.VECTOR)
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(channel -> channel.getType().ordinal()))
                .toList();

        var operation = RequestOperation.current();
        if (sourceBound && operation != null) {
            if (enabledChannels.isEmpty()) throw new RequestOperation.Failure("channels", "NO_RETRIEVAL_CHANNEL", false, null);
            // Each channel is bounded and cancellable. Errors are not empty search results.
            return enabledChannels.stream().map(channel -> operation.execute(ragRetrievalExecutor,
                    "channel." + channel.getName(), () -> channel.search(context))).toList();
        }
        if (enabledChannels.isEmpty()) {
            // 全站无任何知识召回、退化为裸 LLM，属配置事故而非正常降级，不能静默
            log.warn("没有任何启用的检索通道，本次不做知识召回；请检查 rag.search.channels.*.enabled 与对应后端开关");
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        long channelTimeoutMs = searchProperties.getChannels().getTimeoutMs();
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> withTimeout(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return channel.emptyResult(0);
                            }
                        },
                        ragRetrievalExecutor
                ), channel, channelTimeoutMs))
                .toList();

        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context,
                                                       RetrievalCapture capture) {
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        for (SearchResultPostProcessor processor : enabledProcessors) {
            long started = System.nanoTime();
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();
                if (capture != null) {
                    capture.record(context.getMainQuestion(), "post-" + processor.getName(), chunks,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null);
                }

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                if (RequestOperation.current() != null) throw RequestOperation.failure("post." + processor.getName(), e);
                if (capture != null) {
                    capture.record(context.getMainQuestion(), "post-" + processor.getName(), chunks,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), e.getClass().getSimpleName());
                }
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    /**
     * 通道级超时：超过预算的通道按空结果降级，不让最慢一条钳制同一子问题里其余通道的融合
     * 只放弃结果、不中断执行，任务仍在池内跑完，超时值过小等于整路白算
     */
    private CompletableFuture<SearchChannelResult> withTimeout(CompletableFuture<SearchChannelResult> future,
                                                               SearchChannel channel, long timeoutMs) {
        if (timeoutMs <= 0) {
            return future;
        }
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof TimeoutException) {
                        log.warn("检索通道 {} 超过通道级超时 {}ms，放弃其结果，其余通道照常融合", channel.getName(), timeoutMs);
                    } else {
                        log.error("检索通道 {} 异步执行失败", channel.getName(), cause);
                    }
                    return channel.emptyResult(0);
                });
    }

    /**
     * 构建检索上下文
     * 作用域在此处算一次挂进上下文，各通道只读不判，保证同一子问题内各通道的检索范围一致
     */
    private SearchContext buildSearchContext(String question, RetrievalBudget budget) {
        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .budget(budget)
                .retrievalScope(retrievalScopeResolver.resolve())
                .build();
    }
}
