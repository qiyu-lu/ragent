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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseGrantRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.enums.KbPermission;
import com.nageoffer.ai.ragent.knowledge.enums.KbVisibility;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口；每个按 ID 操作的接口先经 {@link KnowledgeAccessService} 判定权限
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeAccessService accessService;

    /**
     * 创建知识库
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        accessService.requireKb(kbId, KbPermission.READ);
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     */
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        return Results.success(knowledgeBaseService.pageQuery(requestParam,
                accessService.accessibleKbIds(accessService.current(), KbPermission.READ)));
    }

    /**
     * 修改知识库可见性
     */
    @PutMapping("/knowledge-base/{kb-id}/visibility")
    public Result<Void> updateVisibility(@PathVariable("kb-id") String kbId,
                                         @RequestParam("value") KbVisibility visibility) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        accessService.setVisibility(kbId, visibility);
        return Results.success();
    }

    /**
     * 查询知识库授权
     */
    @GetMapping("/knowledge-base/{kb-id}/grants")
    public Result<List<KnowledgeAccessService.Grant>> listGrants(@PathVariable("kb-id") String kbId) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        return Results.success(accessService.listGrants(kbId));
    }

    /**
     * 授予或改写授权
     */
    @PostMapping("/knowledge-base/{kb-id}/grants")
    public Result<String> grant(@PathVariable("kb-id") String kbId,
                                @RequestBody KnowledgeBaseGrantRequest requestParam) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        return Results.success(accessService.grant(kbId, requestParam.getSubjectType(),
                requestParam.getSubjectId(), requestParam.getPermission()));
    }

    /**
     * 撤销授权
     */
    @DeleteMapping("/knowledge-base/{kb-id}/grants/{grant-id}")
    public Result<Void> revoke(@PathVariable("kb-id") String kbId, @PathVariable("grant-id") String grantId) {
        accessService.requireKb(kbId, KbPermission.MANAGE);
        accessService.revoke(kbId, grantId);
        return Results.success();
    }
}
