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

package com.nageoffer.ai.ragent.core.ingest.embed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 嵌入缓存配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion.embedding-cache")
public class EmbeddingCacheProperties {

    /**
     * 是否启用；关闭后每次入库都全量调用上游
     */
    private boolean enabled = true;

    /**
     * 容量上限（条），超出后按最近使用时间淘汰最旧的；0 表示不限。1536 维一条约 6 KB
     */
    private long maxEntries = 100_000;
}
