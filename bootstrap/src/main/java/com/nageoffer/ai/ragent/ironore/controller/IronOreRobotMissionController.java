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

package com.nageoffer.ai.ragent.ironore.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionDispatchRequest;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionView;
import com.nageoffer.ai.ragent.ironore.service.RobotMissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class IronOreRobotMissionController {

    private final RobotMissionService robotMissionService;

    @PostMapping("/iron-ore/task-templates/{taskId}/robot-missions")
    public Result<RobotMissionView> dispatch(@PathVariable String taskId,
                                             @RequestBody(required = false) RobotMissionDispatchRequest request) {
        return Results.success(robotMissionService.dispatch(taskId, request));
    }

    @GetMapping("/iron-ore/task-templates/{taskId}/robot-mission")
    public Result<RobotMissionView> getByTask(@PathVariable String taskId) {
        return Results.success(robotMissionService.getByTask(taskId));
    }

    @PostMapping("/iron-ore/robot-missions/{missionId}/cancel")
    public Result<RobotMissionView> cancel(@PathVariable String missionId) {
        return Results.success(robotMissionService.cancel(missionId));
    }
}
