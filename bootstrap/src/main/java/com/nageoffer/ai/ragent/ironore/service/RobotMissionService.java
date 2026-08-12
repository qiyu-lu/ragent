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

package com.nageoffer.ai.ragent.ironore.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreRobotMissionDO;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreRobotMissionMapper;
import com.nageoffer.ai.ragent.ironore.dao.mapper.IronOreTaskTemplateMapper;
import com.nageoffer.ai.ragent.ironore.gateway.RobotGatewayClient;
import com.nageoffer.ai.ragent.ironore.gateway.RobotGatewayException;
import com.nageoffer.ai.ragent.ironore.gateway.RobotGatewaySnapshot;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionDispatchRequest;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionStatus;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionView;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionView.RobotMissionEvent;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplateStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 已批准候选任务与 ROS1 网关之间的边界。数据库先记录任务，再进行外部派发，网关失败不会抹掉审计记录。
 */
@Service
@RequiredArgsConstructor
public class RobotMissionService {

    private final IronOreTaskTemplateMapper taskTemplateMapper;
    private final IronOreRobotMissionMapper robotMissionMapper;
    private final RobotMissionCompiler missionCompiler;
    private final RobotGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    public RobotMissionView dispatch(String taskId, RobotMissionDispatchRequest request) {
        IronOreTaskTemplateDO task = requireApprovedOwnedTask(taskId);
        String userId = UserContext.requireUser().getUserId();
        IronOreRobotMissionDO row = findByTask(taskId, userId);
        if (row == null) {
            String missionId = IdWorker.getIdStr();
            RobotMissionCompiler.CompiledMission compiled = missionCompiler.compile(missionId, task, request);
            String username = UserContext.getUsername();
            row = IronOreRobotMissionDO.builder()
                    .id(missionId)
                    .taskTemplateId(taskId)
                    .ownerUserId(userId)
                    .robotId(compiled.payload().robotId())
                    .status(RobotMissionStatus.READY.name())
                    .planHash(compiled.planHash())
                    .missionData(writeJson(compiled.payload()))
                    .gatewayState("{}")
                    .currentStep(0)
                    .totalSteps(compiled.payload().steps().size())
                    .statusMessage("机器人任务已编译，等待 ROS1 网关接收。")
                    .createdBy(username)
                    .updatedBy(username)
                    .build();
            robotMissionMapper.insert(row);
        } else {
            RobotMissionCompiler.CompiledMission requested = missionCompiler.compile(row.getId(), task, request);
            if (!requested.planHash().equals(row.getPlanHash())) {
                throw new ClientException("该候选任务已经生成机器人任务，不能用不同参数重复派发");
            }
            RobotMissionStatus status = parseStatus(row.getStatus());
            if (status.active()) {
                return refresh(row);
            }
            if (status.terminal()) {
                return toView(row, null);
            }
        }

        RobotMissionPayload mission = readJson(row.getMissionData(), RobotMissionPayload.class);
        try {
            RobotGatewaySnapshot snapshot = gatewayClient.dispatch(mission);
            return persistSnapshot(row, snapshot);
        } catch (RobotGatewayException e) {
            persistDispatchFailure(row, e.getMessage());
            throw new ClientException("机器人任务已记录，但 ROS1 网关未接收：" + e.getMessage());
        }
    }

    public RobotMissionView getByTask(String taskId) {
        requireOwnedTask(taskId);
        String userId = UserContext.requireUser().getUserId();
        IronOreRobotMissionDO row = findByTask(taskId, userId);
        return row == null ? null : refresh(row);
    }

    public RobotMissionView cancel(String missionId) {
        IronOreRobotMissionDO row = requireOwnedMission(missionId);
        RobotMissionStatus status = parseStatus(row.getStatus());
        if (!status.active()) {
            return toView(row, null);
        }
        try {
            return persistSnapshot(row, gatewayClient.cancel(missionId));
        } catch (RobotGatewayException e) {
            throw new ClientException("取消 ROS1 机器人任务失败：" + e.getMessage());
        }
    }

    private RobotMissionView refresh(IronOreRobotMissionDO row) {
        RobotMissionStatus status = parseStatus(row.getStatus());
        if (!status.active()) {
            return toView(row, null);
        }
        try {
            return persistSnapshot(row, gatewayClient.status(row.getId()));
        } catch (RobotGatewayException e) {
            return toView(row, "ROS1 网关暂不可用，显示最近一次状态：" + e.getMessage());
        }
    }

    private RobotMissionView persistSnapshot(IronOreRobotMissionDO row, RobotGatewaySnapshot snapshot) {
        if (snapshot == null || !row.getId().equals(snapshot.missionId())) {
            throw new RobotGatewayException("ROS1 网关返回了不匹配的任务 ID");
        }
        RobotMissionStatus status = RobotMissionStatus.fromGateway(snapshot.status());
        Date now = new Date();
        IronOreRobotMissionDO update = IronOreRobotMissionDO.builder()
                .id(row.getId())
                .status(status.name())
                .gatewayState(writeJson(snapshot))
                .currentStep(snapshot.currentStep())
                .totalSteps(snapshot.totalSteps())
                .currentSkillId(StrUtil.blankToDefault(snapshot.currentSkillId(), null))
                .statusMessage(StrUtil.maxLength(StrUtil.nullToEmpty(snapshot.message()), 500))
                .dispatchedAt(row.getDispatchedAt() == null ? now : row.getDispatchedAt())
                .completedAt(status.terminal() ? now : row.getCompletedAt())
                .updatedBy(UserContext.getUsername())
                .updateTime(now)
                .build();
        robotMissionMapper.updateById(update);
        return toView(robotMissionMapper.selectById(row.getId()), null);
    }

    private void persistDispatchFailure(IronOreRobotMissionDO row, String message) {
        Date now = new Date();
        robotMissionMapper.updateById(IronOreRobotMissionDO.builder()
                .id(row.getId())
                .status(RobotMissionStatus.DISPATCH_FAILED.name())
                .statusMessage(StrUtil.maxLength(StrUtil.nullToEmpty(message), 500))
                .updatedBy(UserContext.getUsername())
                .updateTime(now)
                .build());
    }

    private IronOreTaskTemplateDO requireApprovedOwnedTask(String taskId) {
        IronOreTaskTemplateDO task = requireOwnedTask(taskId);
        if (TaskTemplateStatus.DRAFT.name().equals(task.getStatus())) {
            throw new ClientException("候选任务必须先由人工批准，才能生成机器人任务");
        }
        return task;
    }

    private IronOreTaskTemplateDO requireOwnedTask(String taskId) {
        IronOreTaskTemplateDO task = taskTemplateMapper.selectById(taskId);
        String userId = UserContext.requireUser().getUserId();
        if (task == null || !userId.equals(task.getOwnerUserId())) {
            throw new ClientException("候选任务不存在或无权访问");
        }
        return task;
    }

    private IronOreRobotMissionDO requireOwnedMission(String missionId) {
        IronOreRobotMissionDO row = robotMissionMapper.selectById(missionId);
        String userId = UserContext.requireUser().getUserId();
        if (row == null || !userId.equals(row.getOwnerUserId())) {
            throw new ClientException("机器人任务不存在或无权访问");
        }
        return row;
    }

    private IronOreRobotMissionDO findByTask(String taskId, String userId) {
        return robotMissionMapper.selectOne(new LambdaQueryWrapper<IronOreRobotMissionDO>()
                .eq(IronOreRobotMissionDO::getTaskTemplateId, taskId)
                .eq(IronOreRobotMissionDO::getOwnerUserId, userId)
                .last("LIMIT 1"));
    }

    private RobotMissionStatus parseStatus(String raw) {
        try {
            return RobotMissionStatus.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalStateException("未知机器人任务状态：" + raw, e);
        }
    }

    private RobotMissionView toView(IronOreRobotMissionDO row, String messageOverride) {
        RobotGatewaySnapshot snapshot = readSnapshot(row.getGatewayState());
        List<RobotMissionEvent> events = snapshot == null ? List.of() : snapshot.events();
        String message = StrUtil.isNotBlank(messageOverride) ? messageOverride : row.getStatusMessage();
        return new RobotMissionView(
                row.getId(),
                row.getTaskTemplateId(),
                row.getRobotId(),
                parseStatus(row.getStatus()),
                row.getPlanHash(),
                readJson(row.getMissionData(), RobotMissionPayload.class),
                row.getCurrentStep(),
                row.getTotalSteps(),
                row.getCurrentSkillId(),
                message,
                events,
                row.getDispatchedAt(),
                row.getCompletedAt(),
                row.getCreateTime());
    }

    private RobotGatewaySnapshot readSnapshot(String json) {
        if (StrUtil.isBlank(json) || "{}".equals(json.trim())) {
            return null;
        }
        return readJson(json, RobotGatewaySnapshot.class);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("机器人任务数据序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("机器人任务数据解析失败", e);
        }
    }
}
