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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 表格分块配置（table-structure 计划 §3）。默认关闭，评测过门槛后再打开
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion.table-chunking")
public class TableChunkingProperties {

    /**
     * 打开时 {@link TableChunker} 按行内容定界：切点由行内容的哈希决定，插、删、改一行只让附近几块的向量缓存失效；
     * 关闭时按行累加到预算再切，插一行会让该表后面的块全部换文本
     */
    private boolean contentDefined = false;
}
