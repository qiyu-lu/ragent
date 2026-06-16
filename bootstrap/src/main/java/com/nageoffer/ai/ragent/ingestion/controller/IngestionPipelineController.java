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
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据摄入流水线控制层
 * 管理文档摄入（Ingestion）流水线的增删改查；
 * 流水线由一组有序节点（fetcher→parser→chunker→indexer 等）构成，
 * 供文档上传任务执行时引用，驱动文档解析、分块和向量化的完整处理链路。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionPipelineController {

    private final IngestionPipelineService pipelineService;

    /**
     * 创建数据摄入流水线
     * 同时写入流水线基本信息（t_ingestion_pipeline）和节点列表（t_ingestion_pipeline_node）；
     * 流水线名称全局唯一，重复时返回错误
     *
     * @param request 流水线名称、描述及节点配置列表
     * @return 创建成功的流水线详情（含节点列表）
     */
    @PostMapping("/ingestion/pipelines")
    public Result<IngestionPipelineVO> create(@RequestBody IngestionPipelineCreateRequest request) {
        return Results.success(pipelineService.create(request));
    }

    /**
     * 更新数据摄入流水线
     * name/description 采用 Patch 语义（非 null 才更新）；
     * nodes 若不为 null，则对该流水线的节点列表执行全量替换（先删后插）
     *
     * @param id      路径变量，流水线 ID
     * @param request 要更新的字段；nodes 为 null 时不修改节点
     * @return 更新后的流水线详情
     */
    @PutMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> update(@PathVariable String id,
                                              @RequestBody IngestionPipelineUpdateRequest request) {
        return Results.success(pipelineService.update(id, request));
    }

    /**
     * 查询单个数据摄入流水线详情
     * 返回流水线基本信息及其全部节点配置（settings/condition 以 JsonNode 形式透传）
     *
     * @param id 路径变量，流水线 ID
     * @return 流水线详情 VO
     */
    @GetMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> get(@PathVariable String id) {
        return Results.success(pipelineService.get(id));
    }

    /**
     * 分页查询数据摄入流水线列表
     * 支持按名称关键字模糊过滤，结果按更新时间倒序；
     * 每条记录同时包含该流水线的节点列表
     *
     * @param pageNo   当前页码，默认 1
     * @param pageSize 每页条数，默认 10
     * @param keyword  名称关键字（可选，模糊匹配）
     * @return 分页后的流水线列表
     */
    @GetMapping("/ingestion/pipelines")
    public Result<IPage<IngestionPipelineVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(pipelineService.page(new Page<>(pageNo, pageSize), keyword));
    }

    /**
     * 删除数据摄入流水线（逻辑删除）
     * 流水线记录标记 deleted=1；其关联节点记录执行物理删除（DELETE）
     *
     * @param id 路径变量，流水线 ID
     */
    @DeleteMapping("/ingestion/pipelines/{id}")
    public Result<Void> delete(@PathVariable String id) {
        pipelineService.delete(id);
        return Results.success();
    }
}
