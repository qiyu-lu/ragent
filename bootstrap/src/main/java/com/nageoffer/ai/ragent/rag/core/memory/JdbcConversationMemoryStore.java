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
import com.nageoffer.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的对话记忆存储实现。
 *
 * <p>直接读写关系型数据库（PostgreSQL），不使用本地缓存，
 * 负责对话的近期原始消息的持久化和查询，是记忆层的底层数据访问类。</p>
 *
 * <p>主要职责：
 * <ol>
 *   <li>{@link #loadHistory}：按配置的最大轮数查询最近消息，并做格式清洗</li>
 *   <li>{@link #append}：保存消息到数据库，用户消息同时触发会话记录的创建/更新</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    /** 管理会话元数据（创建/更新最后访问时间等）。 */
    private final ConversationService conversationService;
    /** 负责具体消息记录的增删查。 */
    private final ConversationMessageService conversationMessageService;
    /** 读取 history-keep-turns 等记忆相关配置。 */
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    // 根据会话id和用户id查询当前用户在某个会话中的历史消息。
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        //获取最大历史消息数量 大模型上下文有限，无法一个会话中的所有历史都传给模型
        int maxMessages = resolveMaxHistoryMessages();
        //从数据库查询历史消息
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                //把数据库消息对象转换成大模型消息对象
                .map(this::toChatMessage)
                //过滤，不是所有数据库中的消息都适合放进大模型上下文，过滤掉消息内容为空，只保留 USER 和 ASSISTANT的消息
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());
        //对历史消息做“清洗和规范化”，保证传给大模型的历史上下文是有效的，并且不要以 ASSISTANT 消息开头。
        return normalizeHistory(result);
    }

    /**
     * 保存一条消息，并在写入用户消息时同步更新会话的最后活跃时间。
     *
     * <p>用户消息需要触发会话记录的创建（首次对话）或更新（更新 last_time），
     * 助手消息只写入消息表，不修改会话元信息。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @param message        要保存的消息（USER 或 ASSISTANT）
     * @return 数据库自动生成的消息主键 ID；用于后续 SSE FINISH 事件中携带 messageId
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateRequest conversation = ConversationCreateRequest.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        return new ChatMessage(role, record.getContent());
    }

    //对历史消息做“清洗和规范化”，保证传给大模型的历史上下文是有效的，并且不要以 ASSISTANT 消息开头。
    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> cleaned = messages.stream()
                .filter(this::isHistoryMessage)
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        int start = 0;
        //去掉开头连续的 ASSISTANT 消息  要找到第一条不是 ASSISTANT 的消息，通常就是第一条 USER 消息
        while (start < cleaned.size() && cleaned.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        //如果全是 ASSISTANT，返回空列表
        if (start >= cleaned.size()) {
            return List.of();
        }
        //返回从第一个非 ASSISTANT 开始的子列表
        return cleaned.subList(start, cleaned.size());
    }

    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
