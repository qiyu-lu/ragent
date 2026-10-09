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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 查询侧文档元数据加权（knowledge-quality 计划 §5.3），默认关闭，β 在调参集上取 {0.1, 0.2, 0.3}
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.search.metadata-boost")
public class MetadataBoostProperties {

    /**
     * 开关；Rerank 关闭时不生效（加在通道分上没有意义）
     */
    private boolean enabled = false;

    /**
     * 加权系数：最终分 = 重排分 + β × 匹配度，匹配度 ∈ [0, 1]
     */
    private double beta = 0.2;
}
