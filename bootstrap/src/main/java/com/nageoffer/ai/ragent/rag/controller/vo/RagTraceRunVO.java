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
 * RAG Trace 运行记录（一次完整 RAG 请求的摘要信息）
 */
@Data
@Builder
public class RagTraceRunVO {

    /** 链路唯一 ID（雪花 ID），全局标识一次 RAG 请求 */
    private String traceId;

    /** 链路名称，来自 @RagTraceRoot(name) 或触发方法名 */
    private String traceName;

    /** 触发入口方法，格式：类全限定名#方法名 */
    private String entryMethod;

    /** 会话 ID，来自 @RagTraceRoot(conversationIdArg) 指定的入参 */
    private String conversationId;

    /** 任务 ID，来自 @RagTraceRoot(taskIdArg) 指定的入参 */
    private String taskId;

    /** 触发用户 ID */
    private String userId;

    /** 触发用户名（从 t_user 关联，用于展示） */
    private String username;

    /** 运行状态：RUNNING / SUCCESS / ERROR */
    private String status;

    /** 错误信息（仅 ERROR 时有值，已截断至 maxErrorLength） */
    private String errorMessage;

    /** 总耗时（毫秒） */
    private Long durationMs;

    /** 请求开始时间 */
    private Date startTime;

    /** 请求结束时间（RUNNING 时为 null） */
    private Date endTime;
}
