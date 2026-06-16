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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话聚合查询服务的默认实现。
 *
 * <p>统一封装对话消息、对话摘要和对话主体的只读查询，为记忆摘要压缩等上层流程
 * 提供按用户隔离的数据。具体 SQL 条件通过 MyBatis-Plus Lambda 查询构造器生成。</p>
 *
 * <p>所有查询都同时限定 {@code conversationId} 和 {@code userId}，前者定位业务会话，
 * 后者校验数据归属；同时显式过滤逻辑删除记录，避免摘要任务读取已经删除的历史数据。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationGroupServiceImpl implements ConversationGroupService {

    /**
     * 分别访问消息、摘要和对话主体三类数据。
     */
    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationMapper conversationMapper;

    /**
     * 查询最近的若干条用户消息。
     *
     * <p>摘要压缩以用户消息数量表示“对话轮次”，所以这里只查询 {@code role=user}，
     * 不把 assistant 回复计为独立轮次。结果按创建时间倒序排列，调用方可取列表最后一条
     * 作为这批近期轮次中最早的用户消息，并将其 ID 作为摘要截止边界。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @param limit          最多返回的用户消息数量
     * @return 最近的用户消息，按创建时间从新到旧排列
     */
    @Override
    public List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit) {
        // 无效查询条件不访问数据库，并统一返回不可变空列表。
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }

        /*
         * last 会把内容直接拼接到 MyBatis-Plus 生成的 SQL 末尾。这里的 limit 是经过
         * “大于 0”校验的 int，而不是外部传入的任意字符串，因此不存在字符串注入入口。
         */
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    /**
     * 查询两个消息 ID 之间、尚未被摘要覆盖的完整对话消息。
     *
     * <p>区间为开区间 {@code (afterId, beforeId)}：{@code afterId} 通常是上一版摘要
     * 已覆盖的最后一条消息，{@code beforeId} 是需要保留在原始历史中的近期消息边界。
     * 因此两个边界本身都不进入本次待摘要集合。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @param afterId        起始消息 ID，不包含；为 {@code null} 时不设置下界
     * @param beforeId       结束消息 ID，不包含；为 {@code null} 时不设置上界
     * @return ID 区间内的 user/assistant 消息，按 ID 升序排列
     */
    @Override
    public List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, String afterId, String beforeId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        /*
         * 摘要只接受可还原为 ChatMessage 的 user/assistant 消息。
         * system 或其他内部角色若存在，不会作为用户对话内容参与摘要。
         */
        var query = Wrappers.lambdaQuery(ConversationMessageDO.class)
                .eq(ConversationMessageDO::getConversationId, conversationId)
                .eq(ConversationMessageDO::getUserId, userId)
                .in(ConversationMessageDO::getRole, "user", "assistant")
                .eq(ConversationMessageDO::getDeleted, 0);

        /*
         * 这里只以 null 表示“无边界”，空字符串仍会进入 SQL 比较。
         * 当前调用方传入的是 null 或有效雪花 ID，因此保持现有契约。
         */
        if (afterId != null) {
            query.gt(ConversationMessageDO::getId, afterId);
        }
        if (beforeId != null) {
            query.lt(ConversationMessageDO::getId, beforeId);
        }

        /*
         * 消息主键由按时间递增的雪花 ID 生成，因此按 ID 升序可恢复消息产生顺序。
         * 上层会按该顺序转换成 user/assistant 消息并交给摘要模型。
         */
        return messageMapper.selectList(
                query.orderByAsc(ConversationMessageDO::getId)
        );
    }

    /**
     * 查找指定时间点及之前的最大消息 ID。
     *
     * <p>该方法用于兼容没有记录 {@code lastMessageId} 的旧摘要：先根据摘要创建/更新时间
     * 找到当时已经存在的最后一条消息，再把它作为下一次增量摘要的起点。</p>
     *
     * @param conversationId 对话 ID
     * @param userId         对话所属用户 ID
     * @param at             摘要创建或更新时间
     * @return 截止该时间点的最大消息 ID；无匹配记录时返回 {@code null}
     */
    @Override
    public String findMaxMessageIdAtOrBefore(String conversationId, String userId, java.util.Date at) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || at == null) {
            return null;
        }

        /*
         * 先用 createTime 保证消息确实在 at 之前产生，再按雪花 ID 倒序取最大值。
         * 该逻辑依赖消息 ID 与生成时间总体单调一致，而不是直接按 createTime 取第一条。
         */
        ConversationMessageDO record = messageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .le(ConversationMessageDO::getCreateTime, at)
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit 1")
        );
        return record == null ? null : record.getId();
    }

    /**
     * 统计当前对话中的用户发言轮数。
     *
     * <p>该数量用于判断是否达到开始摘要的阈值；一次 user 消息视为一轮，
     * assistant 消息不单独计数。</p>
     */
    @Override
    public long countUserMessages(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return 0;
        }

        // 摘要触发阈值按用户发言轮次数计算，不统计 assistant 回复。
        return messageMapper.selectCount(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
    }

    /**
     * 查询当前对话最新生成的摘要。
     *
     * <p>摘要主键同样使用雪花 ID，因此按 ID 倒序取第一条代表最近一次摘要版本。
     * 这里依据的是创建顺序，而不是摘要的更新时间。</p>
     */
    @Override
    public ConversationSummaryDO findLatestSummary(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }

        // 摘要记录按主键倒序取第一条，作为当前对话已生成的最新摘要。
        return summaryMapper.selectOne(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
                        .orderByDesc(ConversationSummaryDO::getId)
                        .last("limit 1")
        );
    }

    /**
     * 查询属于当前用户的会话主体。
     *
     * <p>{@code selectOne} 隐含数据库中同一用户下 {@code conversationId} 唯一的约束；
     * 若存在多条未删除记录，MyBatis-Plus 会抛出结果不唯一异常，而不是任取一条。</p>
     */
    @Override
    public ConversationDO findConversation(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }

        // conversationId 与 userId 联合限定归属，防止跨用户读取同名对话。
        //在数据库里查询一条会话记录，条件是：conversationId 匹配、userId 匹配，并且没有被逻辑删除
        return conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
    }
}
