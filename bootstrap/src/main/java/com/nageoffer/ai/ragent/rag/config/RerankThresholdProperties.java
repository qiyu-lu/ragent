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
 * 重排分阈值（knowledge-quality 计划 §6 的 S3-thr 臂）：Rerank 之后丢掉重排分低于阈值的块，默认关闭
 * <p>
 * 只看 Rerank 模型实际打过分的块；没被打分的融合尾部一并丢掉，免得被请求级选择补回来。
 * Rerank 回退成 noop 时头部是融合分，不能和阈值比，此时不过滤
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.search.rerank-threshold")
public class RerankThresholdProperties {

    private boolean enabled = false;

    /**
     * 重排分下限（qwen3-rerank 的分数在 0～1），调参集取 {0.1, 0.2, 0.3}
     */
    private double minScore = 0.2;
}
