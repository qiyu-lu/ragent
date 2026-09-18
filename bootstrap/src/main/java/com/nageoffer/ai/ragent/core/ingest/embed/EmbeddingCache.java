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

import java.util.Collection;
import java.util.Map;

/**
 * 内容寻址的嵌入缓存：键是（模型、维度、向量文本的 SHA-256），与知识库和文档无关
 * <p>
 * 只是省钱的手段，不是正确性的一部分：实现抛出的异常由调用方吞掉并退回上游，所以写缓存不必与块写入同事务
 */
public interface EmbeddingCache {

    /**
     * 不缓存：离线导入等自带续跑机制的调用方使用
     */
    EmbeddingCache NONE = new EmbeddingCache() {
        @Override
        public Map<String, float[]> lookup(String modelId, int dimension, Collection<String> textHashes) {
            return Map.of();
        }

        @Override
        public void store(String modelId, int dimension, Map<String, float[]> vectorsByHash) {
        }
    };

    /**
     * 按哈希批量取向量，并把命中项标记为最近使用
     *
     * @return 命中的哈希 → 向量；未命中的哈希不出现
     */
    Map<String, float[]> lookup(String modelId, int dimension, Collection<String> textHashes);

    /**
     * 写入新向量；同键已存在时保留先写入的向量，只刷新最近使用时间
     */
    void store(String modelId, int dimension, Map<String, float[]> vectorsByHash);
}
