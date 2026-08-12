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
import com.nageoffer.ai.ragent.ironore.model.CandidateTaskTemplateView;
import com.nageoffer.ai.ragent.ironore.model.TaskExecutionView;
import com.nageoffer.ai.ragent.ironore.service.IronOreTaskTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class IronOreTaskController {

    private final IronOreTaskTemplateService taskTemplateService;

    @PostMapping("/iron-ore/task-templates")
    public Result<CandidateTaskTemplateView> create(@RequestBody CreateTaskRequest request) {
        return Results.success(taskTemplateService.createDraft(request.sourceMessageId(), request.docId()));
    }

    @GetMapping("/iron-ore/conversations/{conversationId}/task-templates")
    public Result<List<CandidateTaskTemplateView>> list(@PathVariable String conversationId) {
        return Results.success(taskTemplateService.listByConversation(conversationId));
    }

    @GetMapping("/iron-ore/messages/{sourceMessageId}/task-templates")
    public Result<List<CandidateTaskTemplateView>> listBySource(@PathVariable String sourceMessageId) {
        return Results.success(taskTemplateService.listBySourceMessage(sourceMessageId));
    }

    @PostMapping("/iron-ore/task-templates/{taskId}/approve")
    public Result<CandidateTaskTemplateView> approve(@PathVariable String taskId) {
        return Results.success(taskTemplateService.approve(taskId));
    }

    @PostMapping("/iron-ore/task-templates/{taskId}/simulate")
    public Result<TaskExecutionView> simulate(@PathVariable String taskId) {
        return Results.success(taskTemplateService.simulate(taskId));
    }

    public record CreateTaskRequest(String sourceMessageId, String docId) {
    }
}
