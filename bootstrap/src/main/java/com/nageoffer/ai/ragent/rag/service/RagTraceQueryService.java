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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;

import java.util.List;

/**
 * RAG Trace 查询服务
 */
public interface RagTraceQueryService {

    /**
     * 分页查询链路运行记录，支持按 traceId/conversationId/taskId/status 过滤
     */
    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request);

    /**
     * 查询指定链路的运行详情（含节点列表）；traceId 不存在时返回 null
     */
    RagTraceDetailVO detail(String traceId);

    /**
     * 查询指定链路的所有执行节点，按 startTime ASC 排序
     */
    List<RagTraceNodeVO> listNodes(String traceId);
}
