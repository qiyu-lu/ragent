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

package com.nageoffer.ai.ragent.ingestion.controller.request;

import lombok.Data;

import java.util.List;

/**
 * 摄取管道更新请求对象
 * name/description 采用 Patch 语义：非 null 才写入数据库；
 * nodes 采用全量替换语义：非 null 时先删除该流水线全部旧节点，再重新插入
 */
@Data
public class IngestionPipelineUpdateRequest {

    /**
     * 管道名称（Patch：非 null 时才更新）
     */
    private String name;

    /**
     * 管道描述信息（Patch：非 null 时才更新，传空字符串可清空描述）
     */
    private String description;

    /**
     * 管道节点配置列表（全量替换：非 null 时删除旧节点后重新插入；null 表示不修改节点）
     */
    private List<IngestionPipelineNodeRequest> nodes;
}
