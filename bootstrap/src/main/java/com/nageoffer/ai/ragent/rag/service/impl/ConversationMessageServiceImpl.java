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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 对话消息服务实现类。
 *
 * <p>负责：
 * <ul>
 *   <li>消息写入（{@link #addMessage}）：将 USER 或 ASSISTANT 消息落库，返回自动生成的消息 ID。</li>
 *   <li>消息查询（{@link #listMessages}）：分页/限量查询指定会话的消息列表，并关联用户点赞/点踩状态。</li>
 *   <li>摘要写入（{@link #addMessageSummary}）：把 LLM 生成的对话摘要存入 conversation_summary 表。</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackService feedbackService;

    /**
     * 写入一条对话消息并返回其数据库主键 ID。
     *
     * @param conversationMessage 包含 conversationId、userId、role、content 的消息业务对象
     * @return 新插入消息的雪花 ID（字符串形式）；SSE FINISH 事件用此 ID 通知前端
     */
    @Override
    public String addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
        return messageDO.getId();
    }

    /**
     * 查询指定会话的消息列表，并附加用户对 ASSISTANT 消息的点赞/点踩反馈。
     *
     * <p>查询前先验证会话归属（conversationId + userId），不存在或不属于该用户时返回空列表。
     * 返回结果按 {@code order} 指定方向排序，{@code limit} 限制最多返回的条数。</p>
     *
     * @param conversationId 要查询的会话 ID
     * @param userId         当前用户 ID，用于归属校验
     * @param limit          最大返回条数；{@code null} 时不限制
     * @param order          排序方向：{@code ASC} 按时间升序（默认），{@code DESC} 按时间降序
     * @return 消息 VO 列表，每条 ASSISTANT 消息附带用户投票状态
     */
    @Override
    public List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        //在conversation 表中进行查询当前用户名下，没有被删除的这个会话
        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            return List.of();
        }

        boolean asc = order == null || order == ConversationMessageOrder.ASC;
        //查询具体消息表,根据创建时间排序，排序方向由 asc 决定
        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderBy(true, asc, ConversationMessageDO::getCreateTime)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        if (!asc) {
            Collections.reverse(records);
        }

        //从消息列表里筛选出助手消息 得到他们的id
        List<String> assistantMessageIds = records.stream()
                //只要角色是 assistant，不区分大小写
                .filter(record -> "assistant".equalsIgnoreCase(record.getRole()))
                //取出这些消息的 ID
                .map(ConversationMessageDO::getId)
                .toList();
        //查询用户对这些 assistant 消息的反馈 返回结果 MessageFeedbackDO::getMessageId ： MessageFeedbackDO::getVote,
        Map<String, Integer> votesByMessageId = feedbackService.getUserVotes(userId, assistantMessageIds);

        List<ConversationMessageVO> result = new ArrayList<>();
        //把数据库实体对象 ConversationMessageDO 转换为展示/传输对象 ConversationMessageVO
        for (ConversationMessageDO record : records) {
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .vote(votesByMessageId.get(record.getId()))
                    .createTime(record.getCreateTime())
                    .build();
            result.add(vo);
        }

        return result;
    }

    /**
     * 保存 LLM 生成的对话摘要。
     *
     * <p>摘要由 {@link JdbcConversationMemorySummaryService} 异步生成后调用本方法落库。
     * 同一会话可存在多个历史版本摘要，查询时取主键最大（最新）的一条。</p>
     *
     * @param conversationSummary 包含 conversationId、userId、摘要内容和覆盖截止消息 ID 的摘要业务对象
     */
    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class);
        conversationSummaryMapper.insert(conversationSummaryDO);
    }
}
