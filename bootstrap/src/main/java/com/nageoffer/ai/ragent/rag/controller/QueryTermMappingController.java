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
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关键词映射管理控制器
 * 管理 RAG 查询改写阶段的术语归一化规则（Query Term Mapping）：
 * 用户输入的原始查询（sourceTerm）在进入向量检索前，会先经过 QueryTermMappingService
 * 按优先级顺序替换为标准化目标词（targetTerm），提升检索召回率。
 * 每次增删改后会立即触发内存缓存重载，无需重启服务即可生效。
 */
@RestController
@RequiredArgsConstructor
public class QueryTermMappingController {

    private final QueryTermMappingAdminService queryTermMappingAdminService;

    /**
     * 分页查询映射规则列表
     * 支持按 keyword 同时模糊匹配 sourceTerm 或 targetTerm（OR 关系）；
     * 结果按优先级升序（数值小的在前）、更新时间倒序排列
     *
     * @param requestParam 分页参数（current、size）及可选的 keyword 过滤
     * @return 分页后的映射规则列表
     */
    @GetMapping("/mappings")
    public Result<IPage<QueryTermMappingVO>> pageQuery(QueryTermMappingPageRequest requestParam) {
        return Results.success(queryTermMappingAdminService.pageQuery(requestParam));
    }

    /**
     * 查询单条映射规则详情
     *
     * @param id 路径变量，映射规则 ID
     * @return 映射规则详情 VO
     */
    @GetMapping("/mappings/{id}")
    public Result<QueryTermMappingVO> queryById(@PathVariable String id) {
        return Results.success(queryTermMappingAdminService.queryById(id));
    }

    /**
     * 创建映射规则
     * sourceTerm 和 targetTerm 均自动 trim，不允许纯空白；
     * matchType 默认 1（精确匹配），priority 默认 0，enabled 默认 true；
     * 创建成功后立即重载内存缓存，规则即刻生效
     *
     * @param requestParam 包含 sourceTerm、targetTerm、matchType、priority、enabled、remark
     * @return 新创建规则的 ID
     */
    @PostMapping("/mappings")
    public Result<String> create(@RequestBody QueryTermMappingCreateRequest requestParam) {
        return Results.success(queryTermMappingAdminService.create(requestParam));
    }

    /**
     * 更新映射规则（Patch 语义，仅更新请求体中非 null 的字段）
     * sourceTerm/targetTerm 若传值则自动 trim，且 trim 后不能为空；
     * 更新成功后立即重载内存缓存，规则即刻生效
     *
     * @param id           路径变量，映射规则 ID
     * @param requestParam 要更新的字段（均可选）
     */
    @PutMapping("/mappings/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody QueryTermMappingUpdateRequest requestParam) {
        queryTermMappingAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除映射规则（物理删除）
     * 删除后立即重载内存缓存，规则即刻失效
     *
     * @param id 路径变量，映射规则 ID
     */
    @DeleteMapping("/mappings/{id}")
    public Result<Void> delete(@PathVariable String id) {
        queryTermMappingAdminService.delete(id);
        return Results.success();
    }
}
