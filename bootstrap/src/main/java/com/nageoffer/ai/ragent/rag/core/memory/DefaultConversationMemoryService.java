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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 默认的对话记忆编排服务。
 *
 * <p>该类本身不负责具体的持久化和摘要生成，而是协调
 * {@link ConversationMemoryStore} 与 {@link ConversationMemorySummaryService}：
 * 读取时合并“历史摘要 + 近期原始消息”，写入时在保存消息后检查是否需要压缩历史。</p>
 *
 * <p>读取摘要和近期消息采用相互独立的降级策略：摘要失败时仍可返回近期消息，
 * 近期消息失败时则返回空上下文，避免单个存储组件异常直接中断整条聊天链路。</p>
 */
@Slf4j
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    /**
     * 负责近期原始对话消息的读取和追加。
     */
    private final ConversationMemoryStore memoryStore;

    /**
     * 负责历史摘要的读取、装饰以及压缩触发。
     */
    private final ConversationMemorySummaryService summaryService;

    public DefaultConversationMemoryService(ConversationMemoryStore memoryStore,
                                            ConversationMemorySummaryService summaryService) {
        this.memoryStore = memoryStore;
        this.summaryService = summaryService;
    }

    /**
     * 加载提供给查询改写和 LLM Prompt 使用的对话上下文。
     *
     * <p>正常返回顺序为：可选的历史摘要在第一个位置，后面跟随存储层返回的近期原始消息。
     * 方法对外保证不返回 {@code null}；参数无效或整体加载异常时返回空列表。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @return 摘要与近期消息组成的上下文列表
     */
    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        // 对话和用户共同确定一份记忆，任一标识缺失都无法安全查询。
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        try {
            /*
             * 摘要和近期历史之间没有读取依赖，因此使用 CompletableFuture 并行加载，
             * 减少两次存储查询串行执行产生的总等待时间。
             *
             * 这里没有显式传入 Executor，因此任务运行在 CompletableFuture 默认的
             * ForkJoinPool.commonPool()；后续若存储查询阻塞明显，需要关注公共线程池占用。
             */
            // CompletableFuture 可以实现并行查询来提升性能，
            CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
                    () -> loadSummaryWithFallback(conversationId, userId)
            );
            CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
                    () -> loadHistoryWithFallback(conversationId, userId)
            );

            /*
             * allOf 只负责等待两个任务结束，不直接携带它们的结果，因此在 thenApply 中
             * 分别 join 原 Future 取值。两个分支已在内部完成异常降级，正常情况下这里不会
             * 因某一个数据源失败而中断合并。
             */
            return CompletableFuture.allOf(summaryFuture, historyFuture) //等 summary + history 都完成
                    //合并结果
                    .thenApply(v -> {
                        ChatMessage summary = summaryFuture.join();//取 summary
                        List<ChatMessage> history = historyFuture.join(); //取 history
                        log.debug("加载对话记忆 - conversationId: {}, userId: {}, 摘要: {}, 历史消息数: {}, 耗时: {}ms",
                                conversationId, userId, summary != null, history.size(), System.currentTimeMillis() - startTime);
                        //合并
                        return attachSummary(summary, history);
                    })
                    .join(); //阻塞当前线程，直到两个异步任务都完成 + thenApply 执行完
        } catch (Exception e) {
            // 兜底处理 Future 调度、结果合并等外围异常，对聊天主流程统一表现为空历史。
            log.error("加载对话记忆失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 加载最新历史摘要。
     *
     * <p>摘要属于上下文增强信息，不应因为摘要表或摘要服务异常阻断近期消息读取，
     * 因此任何异常都记录为警告并降级为 {@code null}。</p>
     *
     * @return 最新摘要；不存在或加载失败时返回 {@code null}
     */
    private ChatMessage loadSummaryWithFallback(String conversationId, String userId) {
        try {
            return summaryService.loadLatestSummary(conversationId, userId);
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载尚未被摘要替代的近期原始消息。
     *
     * <p>同时处理存储实现返回 {@code null} 和直接抛出异常两种情况，
     * 对上层统一转换成不可变空列表。</p>
     *
     * @return 近期原始消息；无数据或加载失败时返回空列表
     */
    private List<ChatMessage> loadHistoryWithFallback(String conversationId, String userId) {
        try {
            List<ChatMessage> history = memoryStore.loadHistory(conversationId, userId);
            return history != null ? history : List.of();
        } catch (Exception e) {
            log.error("加载历史记录失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 追加一条对话消息，并在写入成功返回后触发摘要压缩检查。
     *
     * <p>压缩服务会自行判断消息角色和阈值，通常只有 assistant 消息会异步触发摘要。
     * 本方法不吞掉存储或压缩异常：下游异常会继续向调用方传播。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @param message        待保存的消息
     * @return 存储层生成的消息 ID；标识无效时返回 {@code null}
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        // 与 load 保持相同的记忆标识约束，避免写入无法归属的对话消息。
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }

        /*
         * 顺序不能颠倒：摘要判断需要看到包含当前消息在内的最新对话状态。
         * memoryStore.append 抛出异常时不会继续触发压缩，避免摘要覆盖未成功保存的消息。
         */
        String messageId = memoryStore.append(conversationId, userId, message);
        summaryService.compressIfNeeded(conversationId, userId, message);
        return messageId;
    }

    /**
     * 将历史摘要放在近期消息之前，形成提供给大模型的完整上下文。
     *
     * <p>没有可用摘要时原样返回近期消息；摘要存在时会先由摘要服务装饰成适合放入
     * Prompt 的消息，再创建新列表拼接，避免修改存储层返回的原列表。</p>
     *
     * <p>注意：按当前实现，只存在摘要而没有近期消息时仍返回空列表，
     * 即摘要不会单独作为上下文返回。这是现有行为，本次仅通过注释明确。</p>
     */
    private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
        // CollUtil.isEmpty 同时覆盖 null 和空集合，确保调用方始终得到非 null 结果。
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        if (summary == null) {
            // 没有摘要时不做复制，直接沿用存储层返回的近期消息列表。
            return messages;
        }

        // 摘要必须位于近期消息之前，使模型先获得压缩后的远期背景，再读取最近对话。
        List<ChatMessage> result = new ArrayList<>();
        result.add(summaryService.decorateIfNeeded(summary));
        result.addAll(messages);
        return result;
    }
}
