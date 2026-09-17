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

package com.nageoffer.ai.ragent.research.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.research.model.ResearchEvent;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import com.nageoffer.ai.ragent.research.service.ResearchRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rag/research/runs")
@RequiredArgsConstructor
public class ResearchRunController {
    private final ResearchRunService service;

    @PostMapping
    public Result<ResearchRun> create(@Valid @RequestBody ResearchRunService.CreateRequest request) {
        return Results.success(service.create(request));
    }

    @GetMapping("/{runId}")
    public Result<ResearchRun> get(@PathVariable String runId) { return Results.success(service.get(runId)); }

    /** P3 提供持久事件分页回放；P6 接入 SSE 订阅，读取事件不调度执行。 */
    @GetMapping("/{runId}/events")
    public Result<List<ResearchEvent>> events(@PathVariable String runId,
                                              @RequestParam(defaultValue = "0") long after,
                                              @RequestParam(defaultValue = "100") int limit) {
        return Results.success(service.events(runId, after, limit));
    }

    @PostMapping("/{runId}/input")
    public Result<ResearchRun> input(@PathVariable String runId, @Valid @RequestBody InputRequest request) {
        return Results.success(service.input(runId, request.revision(), request.answer()));
    }

    @PostMapping("/{runId}/cancel")
    public Result<ResearchRun> cancel(@PathVariable String runId) { return Results.success(service.cancel(runId)); }

    public record InputRequest(@Min(0) long revision, @NotBlank @Size(max = 10000) String answer) { }
}
