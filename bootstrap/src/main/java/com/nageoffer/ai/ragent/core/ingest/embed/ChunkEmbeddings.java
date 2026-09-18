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

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;

import java.util.List;

/**
 * 一次向量化的结果与计数
 *
 * @param chunks 已向量化的块，与入参一一对应且顺序一致
 * @param stats  缓存命中与上游调用计数
 */
public record ChunkEmbeddings(List<EmbeddedChunk> chunks, EmbeddingStats stats) {

    public static final ChunkEmbeddings EMPTY = new ChunkEmbeddings(List.of(), EmbeddingStats.ZERO);

    /**
     * @param chunks        块数
     * @param cacheHits     向量取自缓存的块数
     * @param upstreamTexts 送往上游的文本条数（未命中的块去重后）
     */
    public record EmbeddingStats(int chunks, int cacheHits, int upstreamTexts) {

        public static final EmbeddingStats ZERO = new EmbeddingStats(0, 0, 0);

        public int cacheMisses() {
            return chunks - cacheHits;
        }
    }
}
