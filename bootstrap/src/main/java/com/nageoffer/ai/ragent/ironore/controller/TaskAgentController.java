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

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ironore.agent.InspectionBusinessService;
import com.nageoffer.ai.ragent.ironore.agent.TaskAgentService;
import com.nageoffer.ai.ragent.ironore.agent.TaskKnowledge;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@RestController
@RequestMapping("/iron-ore/task-agent")
@RequiredArgsConstructor
public class TaskAgentController {
    private final TaskAgentService agent;
    private final InspectionBusinessService business;
    private final TaskKnowledge knowledge;

    @GetMapping("/documents")
    public Result<List<Document>> documents() { return Results.success(knowledge.documents()); }
    @GetMapping("/samples")
    public Result<List<Sample>> samples() { return Results.success(business.samples(owner())); }
    @GetMapping("/stations")
    public Result<List<Station>> stations() { return Results.success(business.stations(owner())); }
    @PostMapping("/demo-data")
    public Result<Void> initializeDemo() { business.initializeDemo(owner()); return Results.success(); }
    @PutMapping("/samples/{id}")
    public Result<Void> updateSample(@PathVariable String id, @RequestBody SampleUpdate request) {
        business.updateSample(owner(), id, request); return Results.success();
    }
    @GetMapping("/runs")
    public Result<List<Summary>> list() { return Results.success(agent.list(owner())); }
    @PostMapping("/runs")
    public Result<View> start(@Valid @RequestBody StartRequest request) { return Results.success(agent.start(owner(), request)); }
    @GetMapping("/runs/{id}")
    public Result<View> get(@PathVariable String id) { return Results.success(agent.get(id, owner())); }
    @PostMapping("/runs/{id}/advance")
    public Result<View> advance(@PathVariable String id) { return Results.success(agent.advance(id, owner())); }
    @PostMapping("/runs/{id}/reply")
    public Result<View> reply(@PathVariable String id, @Valid @RequestBody ReplyRequest request) {
        return Results.success(agent.reply(id, owner(), request.message()));
    }
    @PostMapping("/runs/{id}/approve")
    public Result<View> approve(@PathVariable String id, @RequestBody ApprovalRequest request) {
        return Results.success(agent.approve(id, owner(), request.revision()));
    }
    @PostMapping("/runs/{id}/cancel")
    public Result<View> cancel(@PathVariable String id) { return Results.success(agent.cancel(id, owner())); }

    private String owner() { return UserContext.requireUser().getUserId(); }
}
