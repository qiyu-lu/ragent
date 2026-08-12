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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.dao.entity.IronOreTaskTemplateDO;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionDispatchRequest;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload.RobotSkillStep;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将已批准的规程任务实例化为固定技能白名单。这里不调用模型，也不解析自然语言生成控制指令。
 */
@Component
@RequiredArgsConstructor
public class RobotMissionCompiler {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String MISSION_TYPE = "SAMPLE_TRANSPORT";
    private static final String SCOPE = "仅执行与候选任务关联的样品容器搬运演示；不执行烘干、称重或设备控制。";

    private final ObjectMapper objectMapper;

    public CompiledMission compile(String missionId,
                                   IronOreTaskTemplateDO task,
                                   RobotMissionDispatchRequest request) {
        requireNonEmptyTask(task);
        RobotMissionDispatchRequest safeRequest = request == null
                ? new RobotMissionDispatchRequest(null, null, null, null, null, null, null, null)
                : request;
        String missionType = StrUtil.blankToDefault(StrUtil.trim(safeRequest.missionType()), MISSION_TYPE);
        if (!MISSION_TYPE.equals(missionType)) {
            throw new ClientException("当前 Demo 只支持 SAMPLE_TRANSPORT 机器人任务");
        }
        boolean dryRun = safeRequest.dryRun() == null || safeRequest.dryRun();
        if (!dryRun) {
            throw new ClientException("当前 ROS1 Demo 只允许 dryRun=true，不允许控制真实设备");
        }

        String robotId = identifier(safeRequest.robotId(), "robot-demo-01", "robotId");
        String containerId = identifier(safeRequest.containerId(), "sample_bucket_01", "containerId");
        String source = identifier(safeRequest.sourceStationId(), "sampling_area", "sourceStationId");
        String target = identifier(safeRequest.targetStationId(), "center_laboratory", "targetStationId");
        String home = identifier(safeRequest.homeStationId(), "home", "homeStationId");
        boolean returnHome = safeRequest.returnHome() == null || safeRequest.returnHome();
        if (source.equals(target)) {
            throw new ClientException("搬运起点和终点不能相同");
        }

        List<RobotSkillStep> steps = new ArrayList<>();
        steps.add(step(1, "NAVIGATE_TO_STATION", Map.of("station_id", source), 30));
        steps.add(step(2, "TRANSPORT_CONTAINER", orderedMap(
                "container_id", containerId,
                "target_station_id", target), 60));
        if (returnHome) {
            steps.add(step(3, "NAVIGATE_TO_STATION", Map.of("station_id", home), 30));
        }

        RobotMissionPayload unsigned = new RobotMissionPayload(
                missionId,
                task.getId(),
                task.getDocumentVersion(),
                MISSION_TYPE,
                robotId,
                true,
                SCOPE,
                null,
                steps);
        String planHash = sha256(unsigned);
        RobotMissionPayload payload = new RobotMissionPayload(
                unsigned.missionId(), unsigned.taskTemplateId(), unsigned.documentVersion(), unsigned.missionType(),
                unsigned.robotId(), unsigned.dryRun(), unsigned.scope(), planHash, unsigned.steps());
        return new CompiledMission(payload, planHash);
    }

    private void requireNonEmptyTask(IronOreTaskTemplateDO task) {
        if (task == null || StrUtil.isBlank(task.getTemplateData())) {
            throw new ClientException("候选任务内容为空，无法生成机器人搬运子任务");
        }
        try {
            TaskTemplatePayload payload = objectMapper.readValue(task.getTemplateData(), TaskTemplatePayload.class);
            if (payload.steps().isEmpty()) {
                throw new ClientException("候选任务没有操作步骤，无法关联机器人搬运子任务");
            }
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("候选任务结构无效，无法生成机器人搬运子任务");
        }
    }

    private RobotSkillStep step(int order, String skillId, Map<String, String> parameters, int timeoutSeconds) {
        return new RobotSkillStep(order, skillId, parameters, timeoutSeconds);
    }

    private Map<String, String> orderedMap(String key1, String value1, String key2, String value2) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(key1, value1);
        result.put(key2, value2);
        return result;
    }

    private String identifier(String raw, String defaultValue, String field) {
        String value = StrUtil.blankToDefault(StrUtil.trim(raw), defaultValue);
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new ClientException(field + " 只能包含字母、数字、下划线和短横线，长度不超过 64");
        }
        return value;
    }

    private String sha256(RobotMissionPayload payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new ClientException("无法生成机器人任务指纹");
        }
    }

    public record CompiledMission(RobotMissionPayload payload, String planHash) {
    }
}
