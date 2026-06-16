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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.ChunkStrategyVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口，以及分块策略枚举查询
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     * 校验名称唯一性后，在 MySQL、S3（对象存储）和 Milvus（向量库）中同步初始化资源
     *
     * @param requestParam 包含知识库名称、嵌入模型和 Milvus Collection 名称
     * @return 新创建知识库的 ID
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     * 仅允许修改知识库名称；校验新名称在系统内唯一（排除当前知识库自身）
     *
     * @param kbId         路径变量，知识库 ID
     * @param requestParam 包含新名称的请求体
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库（逻辑删除）
     * 要求知识库下不存在未删除文档，否则拒绝删除并提示先清理文档
     *
     * @param kbId 路径变量，知识库 ID
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     * 根据 ID 查询单条知识库记录，返回名称、嵌入模型、Collection 名称等基本信息
     *
     * @param kbId 路径变量，知识库 ID
     * @return 知识库详情 VO
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     * 支持按名称模糊过滤，结果按更新时间倒序排列，并附带每个知识库的文档数量
     *
     * @param requestParam 分页参数（current、size）及可选的名称过滤条件
     * @return 分页后的知识库列表
     */
    //这个参数会有 ： current=1&size=200 这里没有写是由于使用mybatis-plus的page分页
    //requestParam.getCurrent(), requestParam.getSize()
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        return Results.success(knowledgeBaseService.pageQuery(requestParam));
    }

    /**
     * 查询系统支持的分块策略列表
     * 遍历 ChunkingMode 枚举，仅返回对外可见（visible=true）的策略，
     * 每条记录包含策略标识（value）、展示名称（label）和默认配置参数（defaultConfig）
     *
     * @return 可用分块策略列表
     */
    @GetMapping("/knowledge-base/chunk-strategies")
    public Result<List<ChunkStrategyVO>> listChunkStrategies() {
        List<ChunkStrategyVO> list = Arrays.stream(ChunkingMode.values())
                .filter(ChunkingMode::isVisible)
                .map(mode -> new ChunkStrategyVO(mode.getValue(), mode.getLabel(), mode.getDefaultConfig()))
                .toList();
        return Results.success(list);
    }
}
