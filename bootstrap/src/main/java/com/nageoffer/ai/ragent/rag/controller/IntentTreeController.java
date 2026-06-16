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

import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeBatchRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 意图树控制器
 * 提供意图节点树的查询、创建、更新、删除及批量操作接口。
 * 意图树是 RAG 路由的核心配置，每次增删改后会自动刷新 Redis 缓存。
 */
@RestController
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    /**
     * 获取完整的意图节点树
     * 返回所有未删除节点按层级（DOMAIN→CATEGORY→TOPIC）递归组装的完整树结构，
     * 同层节点按 sortOrder 升序、id 升序排列
     *
     * @return 根节点列表（每个根节点携带完整子树）
     */
    @GetMapping("/intent-tree/trees")
    public Result<List<IntentNodeTreeVO>> tree() {
        return Results.success(intentTreeService.getFullTree());
    }

    /**
     * 创建意图节点
     * 校验 intentCode 唯一性，TOPIC 级 KB 类型节点必须绑定知识库（kbId 必填）；
     * 创建成功后自动清除 Redis 意图树缓存
     *
     * @param requestParam 节点创建参数（intentCode、name、level、kind、kbId 等）
     * @return 新节点的 ID
     */
    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        return Results.success(intentTreeService.createNode(requestParam));
    }

    /**
     * 更新意图节点（Patch 语义，仅更新请求体中非 null 的字段）
     * 更新成功后自动清除 Redis 意图树缓存
     *
     * @param id           路径变量，节点 ID
     * @param requestParam 要更新的字段（name、description、examples、topK 等，均可选）
     */
    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable String id, @RequestBody IntentNodeUpdateRequest requestParam) {
        intentTreeService.updateNode(id, requestParam);
    }

    /**
     * 删除单个意图节点（逻辑删除）
     * 底层使用 MyBatis-Plus removeById，触发 @TableLogic；
     * 删除后自动清除 Redis 意图树缓存
     *
     * @param id 路径变量，节点 ID
     */
    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
    }

    /**
     * 批量启用节点
     * 将指定节点的 enabled 字段设为 1；不检查子节点状态，可单独启用
     *
     * @param requestParam 包含要启用的节点 ID 列表
     */
    @PostMapping("/intent-tree/batch/enable")
    public void batchEnable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchEnableNodes(requestParam.getIds());
    }

    /**
     * 批量停用节点
     * 将指定节点的 enabled 字段设为 0；若某节点存在已启用的子节点未包含在本次操作中，
     * 则拒绝操作并提示需要将子节点一并选入
     *
     * @param requestParam 包含要停用的节点 ID 列表
     */
    @PostMapping("/intent-tree/batch/disable")
    public void batchDisable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDisableNodes(requestParam.getIds());
    }

    /**
     * 批量删除节点（逻辑删除）
     * 若某节点存在未包含在本次操作中的子节点，则拒绝删除，避免产生孤儿节点；
     * 子节点中若有已启用的节点会提前报错并给出提示
     *
     * @param requestParam 包含要删除的节点 ID 列表
     */
    @PostMapping("/intent-tree/batch/delete")
    public void batchDelete(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDeleteNodes(requestParam.getIds());
    }
}
