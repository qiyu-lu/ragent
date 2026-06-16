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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeTreeVO {

    /** 节点主键 ID（雪花 ID） */
    private String id;

    /** 意图唯一业务标识，如 group-hr */
    private String intentCode;

    /** 节点展示名称 */
    private String name;

    /** 节点层级：0=DOMAIN，1=CATEGORY，2=TOPIC */
    private Integer level;

    /** 父节点的 intentCode；根节点为 null */
    private String parentCode;

    /** 节点描述，用于意图分类的语义理解 */
    private String description;

    /** 示例问题（JSON 数组字符串，前端可解析展示） */
    private String examples;

    /** Milvus Collection 名称（kind=0 KB 类型时有效） */
    private String collectionName;

    /** 节点级检索 TopK（null 时使用全局默认值） */
    private Integer topK;

    /** 节点类型：0=KB(RAG)，1=SYSTEM，2=MCP */
    private Integer kind;

    /** 同层排序权重，值越小越靠前 */
    private Integer sortOrder;

    /** 是否启用：1=启用，0=停用 */
    private Integer enabled;

    /**
     * MCP 工具 ID（仅对 kind=2 有意义）
     */
    private String mcpToolId;

    /**
     * 短规则片段（可选）
     */
    private String promptSnippet;

    /**
     * 场景用的完整 Prompt 模板（可选）
     */
    private String promptTemplate;

    /**
     * 参数提取提示词模板（MCP模式专属）
     */
    private String paramPromptTemplate;

    private List<IntentNodeTreeVO> children;
}
