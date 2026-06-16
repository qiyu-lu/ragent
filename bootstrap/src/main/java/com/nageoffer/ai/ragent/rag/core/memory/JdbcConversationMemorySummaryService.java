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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONVERSATION_SUMMARY_PROMPT_PATH;

/**
 * 基于 JDBC 的对话记忆摘要服务实现。
 *
 * <p>当一个会话的用户发言轮数达到配置阈值（{@code summary-start-turns}）后，
 * 异步地将较早的历史消息压缩成摘要，只保留最近若干轮的原始对话，从而控制
 * 传给大模型的 Token 总量。</p>
 *
 * <p>核心设计要点：
 * <ul>
 *   <li>摘要触发：每次 ASSISTANT 消息写入后异步检查，在专用线程池 {@code memorySummaryExecutor} 中执行。</li>
 *   <li>分布式锁：用 Redisson 防止多节点同时对同一会话生成摘要导致重复压缩。</li>
 *   <li>增量摘要：新摘要在已有摘要基础上合并，而不是全量重算，减少 LLM 调用开销。</li>
 *   <li>边界计算：cutoffId 表示"保留近期原文的起始"，afterId 表示"上次摘要的终止"，
 *       两者之间的消息即本次需要新增压缩的内容。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcConversationMemorySummaryService implements ConversationMemorySummaryService {

    private static final String SUMMARY_PREFIX = "对话摘要：";
    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";
    private static final Duration SUMMARY_LOCK_TTL = Duration.ofMinutes(5);

    private final ConversationGroupService conversationGroupService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final RedissonClient redissonClient;

    @Qualifier("memorySummaryThreadPoolExecutor")
    private final Executor memorySummaryExecutor;//专门用于执行记忆摘要任务的线程池
    //因为摘要任务可能包含阻塞操作，比如查数据库、调用大模型。如果放到公共线程池，可能影响其他 CompletableFuture 任务

    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }
        //只在 ASSISTANT 消息后触发摘要 ,因为一轮对话一般是用户提问，助手回答，当前助手回答完毕后，这一轮对话才算完整
        if (message.getRole() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        //开一个异步任务，在线程池 memorySummaryExecutor 中执行 doCompressIfNeeded(conversationId, userId)
        CompletableFuture.runAsync(() -> doCompressIfNeeded(conversationId, userId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("对话记忆摘要异步任务失败 - conversationId: {}, userId: {}",
                            conversationId, userId, ex);
                    return null;
                });
    }

    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);
        //SYSTEM 级别的聊天信息
        return toChatMessage(summary);
    }

    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }

        String content = summary.getContent().trim();
        if (content.startsWith(SUMMARY_PREFIX) || content.startsWith("摘要：")) {
            return summary;
        }
        return ChatMessage.system(SUMMARY_PREFIX + content);
    }

    //当一个会话的历史消息数量达到阈值后，把较早的一部分消息压缩成摘要，只保留最近若干轮原始对话，从而减少后续传给大模型的上下文长度。
    private void doCompressIfNeeded(String conversationId, String userId) {
        long startTime = System.currentTimeMillis();
        //对话达到多少轮/多少条用户消息后，开始触发摘要
        int triggerTurns = memoryProperties.getSummaryStartTurns();
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        if (maxTurns <= 0 || triggerTurns <= 0) {
            return;
        }

        String lockKey = SUMMARY_LOCK_PREFIX + buildLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!tryLock(lock)) {
            return;
        }
        try {
            //统计用户在指定对话中的消息数量 是 当前会话中用户发了多少条消息
            long total = conversationGroupService.countUserMessages(conversationId, userId);
            if (total < triggerTurns) {
                return;
            }
            //获取指定对话的最新摘要信息
            ConversationSummaryDO latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);
            //获取指定对话中最新的用户消息列表
            List<ConversationMessageDO> latestUserTurns = conversationGroupService.listLatestUserOnlyMessages(
                    conversationId,
                    userId,
                    maxTurns
            );
            if (latestUserTurns.isEmpty()) {
                return;
            }
            //计算 cutoffId：摘要截止位置 摘要应该压缩到哪一条消息为止
            //只摘要 cutoffId 之前的消息，cutoffId 之后的最近几轮保留原文
            String cutoffId = resolveCutoffId(latestUserTurns);
            if (StrUtil.isBlank(cutoffId)) {
                return;
            }
            //计算 afterId：从哪里开始摘要 本次摘要从哪条消息之后开始
            String afterId = resolveSummaryStartId(conversationId, userId, latestSummary);
            //如果已有摘要覆盖的位置已经超过或等于本次 cutoffId，说明没有新的可摘要消息，直接返回
            if (afterId != null && Long.parseLong(afterId) >= Long.parseLong(cutoffId)) {
                return;
            }
            //查询本次需要摘要的消息 查询当前会话中：
            //afterId 之后 cutoffId 之前/到 cutoffId 的消息
            List<ConversationMessageDO> toSummarize = conversationGroupService.listMessagesBetweenIds(
                    conversationId,
                    userId,
                    afterId,
                    cutoffId
            );
            if (CollUtil.isEmpty(toSummarize)) {
                return;
            }
            //获取本次摘要覆盖到的最后一条消息 ID 它后面会保存到摘要记录里  下一次摘要时知道从哪里继续 避免重复压缩。
            String lastMessageId = resolveLastMessageId(toSummarize);
            if (StrUtil.isBlank(lastMessageId)) {
                return;
            }

            //取已有摘要内容
            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
            //调用大模型生成摘要
            String summary = summarizeMessages(toSummarize, existingSummary);
            if (StrUtil.isBlank(summary)) {
                return;
            }
            //把新的摘要保存到数据库
            createSummary(conversationId, userId, summary, lastMessageId);
            log.info("摘要成功 - conversationId：{}，userId：{}，消息数：{}，耗时：{}ms",
                    conversationId, userId, toSummarize.size(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("摘要失败 - conversationId：{}，userId：{}", conversationId, userId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, SUMMARY_LOCK_TTL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        //应该是将ConversationMessageDO 格式的转换为 ChatMessage 格式的消息集合
        List<ChatMessage> histories = toHistoryMessages(messages);
        if (CollUtil.isEmpty(histories)) {
            return existingSummary;
        }

        int summaryMaxChars = memoryProperties.getSummaryMaxChars();
        List<ChatMessage> summaryMessages = new ArrayList<>();
        //构造摘要 Prompt
        String summaryPrompt = promptTemplateLoader.render(
                CONVERSATION_SUMMARY_PROMPT_PATH,
                Map.of("summary_max_chars", String.valueOf(summaryMaxChars))
        );
        summaryMessages.add(ChatMessage.system(summaryPrompt));

        if (StrUtil.isNotBlank(existingSummary)) {
            summaryMessages.add(ChatMessage.assistant(
                    "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
                            + existingSummary.trim()
            ));
        }
        summaryMessages.addAll(histories);
        summaryMessages.add(ChatMessage.user(
                "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤" + summaryMaxChars + "字符；仅一行。"
        ));

        ChatRequest request = ChatRequest.builder()
                .messages(summaryMessages)
                .temperature(0.3D)
                .topP(0.9D)
                .thinking(false)
                .build();
        try {
            //调用大模型
            String result = llmService.chat(request);
            log.info("对话摘要生成 - resultChars: {}", result.length());

            return result;
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            return existingSummary;
        }
    }
    /**
     * 将数据库消息 DO 列表转换成大模型可消费的 ChatMessage 列表。
     *
     * <p>只保留 user/assistant 角色的非空消息；system 等内部角色消息不传给摘要模型。</p>
     */
    private List<ChatMessage> toHistoryMessages(List<ConversationMessageDO> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        return messages.stream()
                .filter(item -> item != null
                        && StrUtil.isNotBlank(item.getContent())
                        && StrUtil.isNotBlank(item.getRole()))
                .map(item -> {
                    String role = item.getRole().toLowerCase();
                    if ("user".equals(role)) {
                        return ChatMessage.user(item.getContent());
                    } else if ("assistant".equals(role)) {
                        return ChatMessage.assistant(item.getContent());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 将数据库摘要 DO 转换成 SYSTEM 角色的 ChatMessage，供 load 流程组装上下文使用。
     *
     * @return 摘要的 ChatMessage；记录为 {@code null} 或内容为空时返回 {@code null}
     */
    private ChatMessage toChatMessage(ConversationSummaryDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(ChatMessage.Role.SYSTEM, record.getContent());
    }

    /**
     * 解析本次增量摘要的起始消息 ID（即上次摘要所覆盖的最后一条消息 ID）。
     *
     * <p>优先读取摘要记录中保存的 {@code lastMessageId}；若为 {@code null}（旧版摘要无此字段），
     * 则根据摘要的更新/创建时间查库确定当时的最大消息 ID 作为起点。</p>
     *
     * @return 起始 ID；{@code null} 表示从头开始（无已有摘要）
     */
    private String resolveSummaryStartId(String conversationId, String userId, ConversationSummaryDO summary) {
        if (summary == null) {
            return null;
        }
        if (summary.getLastMessageId() != null) {
            return summary.getLastMessageId();
        }

        Date after = summary.getUpdateTime();
        if (after == null) {
            after = summary.getCreateTime();
        }
        return conversationGroupService.findMaxMessageIdAtOrBefore(conversationId, userId, after);
    }

    /**
     * 计算本次摘要的截止消息 ID（即近期保留区的起始用户消息 ID，不包含在摘要中）。
     *
     * <p>参数为"按时间倒序的最近 N 条用户消息"，列表末尾（索引最大）的是时间最早的。
     * 该条消息及之后的内容将保留原文，不纳入本次压缩范围。</p>
     *
     * @param latestUserTurns 按时间倒序排列的最近用户消息列表
     * @return 截止消息 ID；列表为空时返回 {@code null}
     */
    private String resolveCutoffId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        // 倒序列表的最后一个就是最早的
        ConversationMessageDO oldest = latestUserTurns.get(latestUserTurns.size() - 1);
        return oldest == null ? null : oldest.getId();
    }

    /**
     * 从待摘要消息列表中取出最后一条有效消息的 ID。
     *
     * <p>该 ID 保存在新生成的摘要记录中作为 {@code lastMessageId}，
     * 下次摘要时用它作为增量起点，避免重复压缩同一段历史。</p>
     *
     * @return 最后一条有效消息的 ID；列表为空或全为 {@code null} 时返回 {@code null}
     */
    private String resolveLastMessageId(List<ConversationMessageDO> toSummarize) {
        for (int i = toSummarize.size() - 1; i >= 0; i--) {
            ConversationMessageDO item = toSummarize.get(i);
            if (item != null && item.getId() != null) {
                return item.getId();
            }
        }
        return null;
    }

    /**
     * 将 LLM 生成的摘要内容持久化到数据库。
     *
     * @param conversationId  对话 ID
     * @param userId          用户 ID
     * @param content         摘要文本
     * @param lastMessageId   本次摘要覆盖的最后一条消息 ID，供下次增量摘要使用
     */
    private void createSummary(String conversationId,
                               String userId,
                               String content,
                               String lastMessageId) {
        ConversationSummaryBO summaryRecord = ConversationSummaryBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(content)
                .lastMessageId(lastMessageId)
                .build();
        conversationMessageService.addMessageSummary(summaryRecord);
    }

    /**
     * 构造摘要任务的 Redis 分布式锁 Key，以 userId + conversationId 联合确保同一会话同时只有一个节点压缩。
     */
    private String buildLockKey(String conversationId, String userId) {
        return userId.trim() + ":" + conversationId.trim();
    }
}
