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

package com.nageoffer.ai.ragent.ingestion.controller.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class IngestionPipelineNodeVO {

    /** 节点记录主键（数据库 ID） */
    private String id;

    /** 节点业务标识（流水线内唯一，如 "node-1"） */
    private String nodeId;

    /**
     * 节点类型（经枚举校验后的规范值）：
     * fetcher / parser / enhancer / chunker / enricher / indexer
     */
    private String nodeType;

    /** 节点配置参数（JSON 对象，不同 nodeType 结构不同，前端透传） */
    private JsonNode settings;

    /** 节点执行条件（JSON 对象，满足条件才执行；null 表示无条件执行） */
    private JsonNode condition;

    /** 下一个节点的 nodeId，定义节点链式执行顺序；末尾节点为 null */
    private String nextNodeId;
}
