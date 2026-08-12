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
 * RAG Trace 记录服务
 */
public interface RagTraceRecordService {

    void startRun(RagTraceRunDO run);

    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 将指定任务仍处于 RUNNING 的 trace run 收尾为 CANCELLED
     * <p>
     * 用户停止请求与 trace run 创建可能发生在不同实例或交错执行，因此停止入口和 run 创建后的取消检查
     * 都可以按 taskId 幂等调用；只有仍处于 RUNNING 的记录会被更新
     * </p>
     *
     * @param taskId  流式任务 ID
     * @param endTime 取消发生的时间
     * @return 是否确实有一行由 RUNNING 翻转为 CANCELLED
     */
    boolean cancelRunByTaskId(String taskId, Date endTime);

    void startNode(RagTraceNodeDO node);

    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}
