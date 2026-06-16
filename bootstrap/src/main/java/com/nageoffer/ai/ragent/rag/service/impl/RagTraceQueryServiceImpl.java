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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG Trace 查询服务实现
 */
@Service
@RequiredArgsConstructor
public class RagTraceQueryServiceImpl implements RagTraceQueryService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;
    private final UserMapper userMapper;

    @Override
    public IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request) {
        // 默认按 startTime 倒序，最新请求排在前面
        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class)
                .orderByDesc(RagTraceRunDO::getStartTime);

        // 以下过滤条件均为精确匹配，空白时不加条件
        if (StrUtil.isNotBlank(request.getTraceId())) {
            wrapper.eq(RagTraceRunDO::getTraceId, request.getTraceId());
        }
        if (StrUtil.isNotBlank(request.getConversationId())) {
            wrapper.eq(RagTraceRunDO::getConversationId, request.getConversationId());
        }
        if (StrUtil.isNotBlank(request.getTaskId())) {
            wrapper.eq(RagTraceRunDO::getTaskId, request.getTaskId());
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(RagTraceRunDO::getStatus, request.getStatus());
        }

        IPage<RagTraceRunDO> pageResult = runMapper.selectPage(request, wrapper);
        // 批量加载本页所有记录的用户名，避免 N+1 查询
        Map<String, String> usernameMap = loadUsernameMap(pageResult.getRecords());
        return pageResult.convert(run -> toRunVO(run, usernameMap));
    }

    @Override
    public RagTraceDetailVO detail(String traceId) {
        // 按 traceId 查运行记录（traceId 唯一，limit 1 防止多条异常数据破坏查询）
        RagTraceRunDO run = runMapper.selectOne(Wrappers.lambdaQuery(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId)
                .last("limit 1"));
        if (run == null) {
            return null;
        }
        Map<String, String> usernameMap = loadUsernameMap(List.of(run));
        return RagTraceDetailVO.builder()
                .run(toRunVO(run, usernameMap))
                // 复用 listNodes，节点已按 startTime/id 排序
                .nodes(listNodes(traceId))
                .build();
    }

    @Override
    public List<RagTraceNodeVO> listNodes(String traceId) {
        // 按 startTime ASC + id ASC 排序，保证节点顺序与实际执行顺序一致
        // 同一毫秒内启动的节点用 id（雪花 ID，天然有序）兜底排序
        List<RagTraceNodeDO> nodes = nodeMapper.selectList(Wrappers.lambdaQuery(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .orderByAsc(RagTraceNodeDO::getStartTime)
                .orderByAsc(RagTraceNodeDO::getId));
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * DO → RunVO 转换，username 由外部预批量加载的 usernameMap 提供
     */
    private RagTraceRunVO toRunVO(RagTraceRunDO run, Map<String, String> usernameMap) {
        String username = resolveUsername(run.getUserId(), usernameMap);
        return RagTraceRunVO.builder()
                .traceId(run.getTraceId())
                .traceName(run.getTraceName())
                .entryMethod(run.getEntryMethod())
                .conversationId(run.getConversationId())
                .taskId(run.getTaskId())
                .userId(run.getUserId())
                .username(username)
                .status(run.getStatus())
                .errorMessage(run.getErrorMessage())
                .durationMs(run.getDurationMs())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .build();
    }

    /**
     * 批量加载用户名，避免 N+1 查询：
     * 1. 从 runs 中收集所有非 null 的 userId
     * 2. IN 查询 t_user 获取 id→username 映射（只 select 两列，减少网络 IO）
     * 3. 有重复 key 时保留先出现的值（(left, right) -> left）
     */
    private Map<String, String> loadUsernameMap(List<RagTraceRunDO> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> userIds = runs.stream()
                .map(RagTraceRunDO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UserDO> users = userMapper.selectList(Wrappers.lambdaQuery(UserDO.class)
                .in(UserDO::getId, userIds)
                .select(UserDO::getId, UserDO::getUsername));
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }

        return users.stream().collect(Collectors.toMap(
                user -> String.valueOf(user.getId()),
                UserDO::getUsername,
                (left, right) -> left
        ));
    }

    /**
     * 安全地从 usernameMap 中取用户名：userId 为空或 map 为空时返回 null
     */
    private String resolveUsername(String userId, Map<String, String> usernameMap) {
        if (StrUtil.isBlank(userId) || usernameMap == null || usernameMap.isEmpty()) {
            return null;
        }
        return usernameMap.get(userId);
    }

    /**
     * DO → NodeVO 转换（字段一一映射，无业务逻辑）
     */
    private RagTraceNodeVO toNodeVO(RagTraceNodeDO node) {
        return RagTraceNodeVO.builder()
                .traceId(node.getTraceId())
                .nodeId(node.getNodeId())
                .parentNodeId(node.getParentNodeId())
                .depth(node.getDepth())
                .nodeType(node.getNodeType())
                .nodeName(node.getNodeName())
                .className(node.getClassName())
                .methodName(node.getMethodName())
                .status(node.getStatus())
                .errorMessage(node.getErrorMessage())
                .durationMs(node.getDurationMs())
                .startTime(node.getStartTime())
                .endTime(node.getEndTime())
                .build();
    }
}
