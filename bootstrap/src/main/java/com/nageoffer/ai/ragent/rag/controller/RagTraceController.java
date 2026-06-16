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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG Trace 查询控制器
 * <p>
 * RAG Trace 是基于 AOP 注解的链路追踪系统：
 * - {@code @RagTraceRoot} 标记一次完整请求入口，AOP 切面自动创建 TraceRun 记录（状态 RUNNING→SUCCESS/ERROR）
 * - {@code @RagTraceNode} 标记 RAG 内部各执行阶段（意图解析、查询改写、向量检索等），切面按调用层级记录 TraceNode
 * - 链路 ID（traceId）通过 TransmittableThreadLocal 在异步线程间透传，节点父子关系靠节点栈（push/pop）维护
 * - 本控制器仅提供只读查询接口，写入由 RagTraceRecordService 在 AOP 切面中自动完成
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     * 支持按 traceId / conversationId / taskId / status 精确过滤；
     * 结果按 startTime 倒序排列（最新请求在前）
     *
     * @param request 分页参数（current、size）及可选的精确过滤条件
     * @return 分页后的链路运行记录列表（含触发用户、耗时、状态等）
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询单条链路详情（包含运行记录 + 所有执行节点）
     * 节点按 startTime ASC、id ASC 顺序排列，反映实际执行顺序
     *
     * @param traceId 链路 ID（来自 t_rag_trace_run.trace_id）
     * @return 运行记录 + 节点列表的组合视图；traceId 不存在时返回 null
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.detail(traceId));
    }

    /**
     * 仅查询指定链路的执行节点列表
     * 节点按 startTime ASC、id ASC 顺序排列
     *
     * @param traceId 链路 ID
     * @return 节点列表（含类名、方法名、深度、耗时、状态等）
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }
}
