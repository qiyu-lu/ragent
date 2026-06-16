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

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * RAG Trace 执行节点明细（一次链路中某个阶段方法的执行记录）
 */
@Data
@Builder
public class RagTraceNodeVO {

    /** 所属链路 ID */
    private String traceId;

    /** 节点唯一 ID（雪花 ID），在同一链路内唯一 */
    private String nodeId;

    /** 父节点 ID（null 表示根节点直接子节点，即 depth=0 的节点） */
    private String parentNodeId;

    /** 节点调用深度（0=最外层，越深说明嵌套越深） */
    private Integer depth;

    /**
     * 节点类型，来自 @RagTraceNode(type)，默认为 "METHOD"；
     * 常见值：INTENT（意图解析）、REWRITE（查询改写）、RETRIEVE（向量检索）、LLM（模型调用）
     */
    private String nodeType;

    /** 节点展示名称，来自 @RagTraceNode(name) 或方法名 */
    private String nodeName;

    /** 被拦截方法所在类的全限定名 */
    private String className;

    /** 被拦截方法名 */
    private String methodName;

    /** 节点执行状态：RUNNING / SUCCESS / ERROR */
    private String status;

    /** 错误信息（仅 ERROR 时有值，已截断至 maxErrorLength） */
    private String errorMessage;

    /** 节点耗时（毫秒） */
    private Long durationMs;

    /** 节点开始时间 */
    private Date startTime;

    /** 节点结束时间（RUNNING 时为 null） */
    private Date endTime;
}
