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
 * 词项分与重排分融合（knowledge-quality 计划 §6 的 S3-blend 臂，借鉴 RAGFlow 有重排模型时"重排分替换向量分、词项分保留"），默认关闭
 * <p>
 * 打开后 Rerank 给整个候选池打分（不只取前 contextTopK），每块的最终分 = α × BM25 / 本子问题 BM25 最大值 + (1 − α) × 重排分，
 * 全文通道没召回的块词项分为 0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.search.score-blend")
public class ScoreBlendProperties {

    private boolean enabled = false;

    /**
     * 词项分的权重，调参集取 {0.3, 0.5, 0.7}
     */
    private double alpha = 0.5;
}
