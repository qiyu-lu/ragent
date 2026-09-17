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
import com.nageoffer.ai.ragent.research.service.ResearchEventStreamService;
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
    private final ResearchEventStreamService streams;

    @PostMapping
    public Result<ResearchRun> create(@Valid @RequestBody ResearchRunService.CreateRequest request) {
        return Results.success(service.create(request));
    }

    @GetMapping
    public Result<List<ResearchRun>> list(@RequestParam String conversationId) {
        return Results.success(service.list(conversationId));
    }

    @PostMapping("/{runId}/regenerate")
    public Result<ResearchRun> regenerate(@PathVariable String runId, @Valid @RequestBody RegenerateRequest request) {
        return Results.success(service.regenerate(runId, request.clientRequestId()));
    }

    @GetMapping("/{runId}")
    public Result<ResearchRun> get(@PathVariable String runId) { return Results.success(service.get(runId)); }

    @GetMapping("/{runId}/sources")
    public Result<List<com.nageoffer.ai.ragent.research.model.EvidenceRecord>> sources(@PathVariable String runId) {
        return Results.success(service.sources(runId));
    }

    /** P3 提供持久事件分页回放；P6 接入 SSE 订阅，读取事件不调度执行。 */
    @GetMapping(value = "/{runId}/events", produces = "application/json")
    public Result<List<ResearchEvent>> events(@PathVariable String runId,
                                              @RequestParam(defaultValue = "0") long after,
                                              @RequestParam(defaultValue = "100") int limit) {
        return Results.success(service.events(runId, after, limit));
    }

    @GetMapping(value = "/{runId}/events", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@PathVariable String runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            jakarta.servlet.http.HttpServletResponse response) {
        long cursor;
        try { cursor = lastEventId == null ? after : Math.max(after, Long.parseLong(lastEventId)); }
        catch (NumberFormatException invalid) { throw new com.nageoffer.ai.ragent.framework.exception.ClientException("事件游标无效"); }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.subscribe(runId, cursor);
    }

    @PostMapping("/{runId}/input")
    public Result<ResearchRun> input(@PathVariable String runId, @Valid @RequestBody InputRequest request) {
        return Results.success(service.input(runId, request.revision(), request.answer()));
    }

    @PostMapping("/{runId}/cancel")
    public Result<ResearchRun> cancel(@PathVariable String runId) { return Results.success(service.cancel(runId)); }

    /** SSE 客户端关闭后的响应已不可写，不能再由通用异常处理器返回 JSON。 */
    @ExceptionHandler({org.springframework.web.context.request.async.AsyncRequestNotUsableException.class, java.io.IOException.class})
    public Result<Void> disconnectedStream(jakarta.servlet.http.HttpServletRequest request, Exception failure) {
        if (failure instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException
                || "GET".equals(request.getMethod()) && request.getRequestURI().endsWith("/events")
                && request.getHeader("Accept") != null && request.getHeader("Accept").contains("text/event-stream")) return null;
        // JSON 解析异常也可能以 IOException 为根因，普通接口仍须返回失败信息。
        return Results.failure();
    }

    public record InputRequest(@Min(0) long revision, @NotBlank @Size(max = 10000) String answer) { }
    public record RegenerateRequest(@NotBlank @Size(max = 128) String clientRequestId) { }
}
