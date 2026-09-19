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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import java.util.List;

/**
 * 检索作用域
 * <p>
 * 每个子问题算一次、各通道共读一份：本次检索能看到哪些知识库
 *
 * @param targetCollections 检索范围：启用中的知识库与当前用户可读知识库的交集
 */
public record RetrievalScope(List<String> targetCollections) {

    public RetrievalScope {
        targetCollections = targetCollections == null ? List.of() : List.copyOf(targetCollections);
    }

    public static RetrievalScope of(List<String> collections) {
        return new RetrievalScope(collections);
    }
}
