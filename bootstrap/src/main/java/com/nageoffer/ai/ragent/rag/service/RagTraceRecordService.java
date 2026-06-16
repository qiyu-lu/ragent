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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG Trace 写入服务，供 AOP 切面调用
 * 不对外暴露 REST 接口，只由 RagTraceAspect 在拦截到 @RagTraceRoot / @RagTraceNode 时调用
 */
public interface RagTraceRecordService {

    /**
     * 链路开始：insert 一条状态为 RUNNING 的 TraceRun 记录
     */
    void startRun(RagTraceRunDO run);

    /**
     * 链路结束：将 TraceRun 状态更新为 SUCCESS 或 ERROR，并补充耗时信息
     */
    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 节点开始：insert 一条状态为 RUNNING 的 TraceNode 记录
     */
    void startNode(RagTraceNodeDO node);

    /**
     * 节点结束：将 TraceNode 状态更新为 SUCCESS 或 ERROR，并补充耗时信息
     */
    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}
