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

package com.nageoffer.ai.ragent.rag.controller.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
/**
 * Patch 语义：所有字段均可选，非 null 才会被更新到数据库
 */
public class IntentNodeUpdateRequest {

    /** 节点展示名称 */
    private String name;

    /** 节点层级：0=DOMAIN，1=CATEGORY，2=TOPIC */
    private Integer level;

    /** 父节点 intentCode */
    private String parentCode;

    /** 节点描述 */
    private String description;

    /** 示例问题列表（全量覆盖，非追加） */
    private List<String> examples;

    /** Milvus Collection 名称（直接指定，优先于 kbId 推导） */
    private String collectionName;

    /** 节点级 TopK，null 表示沿用全局默认 */
    private Integer topK;

    /** 节点类型：0=KB，1=SYSTEM，2=MCP */
    private Integer kind;

    /** 同层排序权重 */
    private Integer sortOrder;

    /** 是否启用：1=启用，0=停用 */
    private Integer enabled;

    /** 短规则片段（可选，注入到 Prompt 头部） */
    private String promptSnippet;

    /** 场景完整 Prompt 模板（可选） */
    private String promptTemplate;

    /** 参数提取提示词模板（MCP 模式专属） */
    private String paramPromptTemplate;
}
