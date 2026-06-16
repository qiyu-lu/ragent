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
public class IntentNodeCreateRequest {

    /**
     * 知识库 ID（kind=0 KB/RAG 类型时必填，用于查询对应的 collectionName）
     */
    private String kbId;

    /**
     * 意图唯一业务标识，如 group-hr / biz-oa-intro（全局不可重复）
     */
    private String intentCode;

    /**
     * 节点展示名称
     */
    private String name;

    /**
     * 节点层级：0=DOMAIN（顶层领域），1=CATEGORY（分类），2=TOPIC（具体主题）
     */
    private Integer level;

    /**
     * 父节点的 intentCode；根节点此字段为 null
     */
    private String parentCode;

    /**
     * 节点描述，用于意图分类时的语义理解
     */
    private String description;

    /**
     * 示例问题列表，辅助 LLM 判断用户输入是否属于该意图
     */
    private List<String> examples;

    /**
     * MCP 工具 ID，仅 kind=2（MCP 类型）时有意义
     */
    private String mcpToolId;

    /**
     * 节点级检索 TopK；null 表示回退使用全局默认值，必须为正整数
     */
    private Integer topK;

    /**
     * 节点类型：0=KB（RAG 检索），1=SYSTEM（系统回复），2=MCP（实时数据）
     */
    private Integer kind;

    /**
     * 同层排序权重，值越小越靠前，默认 0
     */
    private Integer sortOrder;

    /**
     * 是否启用：1=启用，0=停用；默认 1
     */
    private Integer enabled;

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
}
