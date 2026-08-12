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

package com.nageoffer.ai.ragent.ironore.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * 发送给机器人网关的机器协议。它只包含预注册技能，不承载自然语言规程。
 */
public record RobotMissionPayload(
        String missionId,
        String taskTemplateId,
        String documentVersion,
        String missionType,
        String robotId,
        boolean dryRun,
        String scope,
        String planHash,
        List<RobotSkillStep> steps
) {

    public RobotMissionPayload {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record RobotSkillStep(
            Integer order,
            String skillId,
            Map<String, String> parameters,
            Integer timeoutSeconds
    ) {
        public RobotSkillStep {
            parameters = parameters == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }
}
