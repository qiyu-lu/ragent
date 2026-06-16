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

package com.nageoffer.ai.ragent.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库采集任务控制层
 * 提供文档摄入任务的创建、查询接口。
 * 任务执行为同步阻塞模式：接口调用会等待整条流水线（fetcher→parser→chunker→indexer）
 * 全部跑完后才返回结果，适合文件较小的场景。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionTaskController {

    private final IngestionTaskService taskService;

    /**
     * 创建并同步执行采集任务（JSON 方式指定文档来源）
     * 通过 source.type 指定文档来源类型（file/url/feishu/s3），
     * 流水线执行完成后同步返回任务 ID、最终状态和生成的 Chunk 数量
     *
     * @param request 包含 pipelineId、source（来源类型+位置+凭证）、vectorSpaceId（可选）
     * @return 摄入结果概要（taskId、status、chunkCount、message）
     */
    @PostMapping("/ingestion/tasks")
    public Result<IngestionResult> create(@RequestBody IngestionTaskCreateRequest request) {
        return Results.success(taskService.execute(request));
    }

    /**
     * 上传文件并同步触发采集任务（multipart 方式）
     * 服务端通过魔数（magic bytes）自动检测文件 MIME 类型，无需客户端传 Content-Type；
     * 文件字节直接传入流水线，不落本地磁盘
     *
     * @param pipelineId 请求参数，指定执行此次摄入的流水线 ID
     * @param file       multipart 文件部分，Part 名称固定为 "file"
     * @return 摄入结果概要（taskId、status、chunkCount、message）
     */
    @SneakyThrows
    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<IngestionResult> upload(@RequestParam(value = "pipelineId") String pipelineId,
                                          @RequestPart("file") MultipartFile file) {
        return Results.success(taskService.upload(pipelineId, file));
    }

    /**
     * 根据任务 ID 查询任务详情
     * 返回任务基本信息、最终状态、Chunk 数量、错误信息及节点运行日志摘要（不含节点输出体）
     *
     * @param id 路径变量，任务 ID
     * @return 任务详情 VO
     */
    @GetMapping("/ingestion/tasks/{id}")
    public Result<IngestionTaskVO> get(@PathVariable String id) {
        return Results.success(taskService.get(id));
    }

    /**
     * 查询任务各节点的运行记录
     * 按节点执行顺序（nodeOrder 升序）返回每个节点的类型、状态、耗时、消息及输出摘要
     *
     * @param id 路径变量，任务 ID
     * @return 节点运行记录列表（含 success/failed/skipped 状态）
     */
    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Result<List<IngestionTaskNodeVO>> nodes(@PathVariable String id) {
        return Results.success(taskService.listNodes(id));
    }

    /**
     * 分页查询采集任务列表
     * 支持按任务状态过滤（pending/running/completed/failed），结果按创建时间倒序
     *
     * @param pageNo   当前页码，默认 1
     * @param pageSize 每页条数，默认 10
     * @param status   状态过滤（可选，不传则查全部）
     * @return 分页后的任务列表
     */
    @GetMapping("/ingestion/tasks")
    public Result<IPage<IngestionTaskVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                               @RequestParam(value = "status", required = false) String status) {
        return Results.success(taskService.page(new Page<>(pageNo, pageSize), status));
    }
}
