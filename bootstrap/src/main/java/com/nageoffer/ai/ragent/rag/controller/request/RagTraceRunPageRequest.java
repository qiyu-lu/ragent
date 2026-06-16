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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/**
 * RAG Trace 运行记录分页请求（所有过滤字段均为精确匹配，空白时忽略）
 */
@Data
public class RagTraceRunPageRequest extends Page {

    /** 链路 ID，精确匹配；可用于定位单次请求的完整链路 */
    private String traceId;

    /** 会话 ID，精确匹配；可查询同一对话中所有 RAG 请求的链路 */
    private String conversationId;

    /** 任务 ID，精确匹配；可查询某个异步任务触发的链路 */
    private String taskId;

    /** 执行状态（RUNNING / SUCCESS / ERROR），精确匹配 */
    private String status;
}
