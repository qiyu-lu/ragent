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

package com.nageoffer.ai.ragent.admin.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.core.fulltext.FullTextIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全文索引运维（knowledge-quality 计划 §6）：回填存量的 content_tsv 与词项统计，只给管理员（/admin/**）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/full-text")
public class FullTextIndexController {

    private final FullTextIndexer indexer;

    /**
     * 重建全文索引：不传 kbId 时重建全部未删除的库。打开全文通道前对存量跑一次；术语表或停用词改了之后也要重跑。
     * 与开关无关，可以先建好索引再打开通道
     */
    @PostMapping("/rebuild")
    public Result<FullTextIndexer.RebuildSummary> rebuild(@RequestParam(required = false) String kbId) {
        return Results.success(indexer.rebuild(kbId));
    }
}
