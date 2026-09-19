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

import com.nageoffer.ai.ragent.knowledge.service.KnowledgeAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 检索作用域解析器
 * <p>
 * 由引擎按子问题各算一次放进 {@link SearchContext}，各通道只读不判
 * <p>
 * 访问控制在这里、召回之前生效：「有效库」先与当前用户可读的库求交，
 * 通道拿到的库集合里没有不可读的库，也就不会在 TopK 里为它们留位置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalScopeResolver {

    private final KbCollectionProvider kbCollectionProvider;
    private final KnowledgeAccessService accessService;

    /**
     * 解析本次请求的检索作用域：启用中的知识库 ∩ 当前用户可读的知识库
     */
    public RetrievalScope resolve() {
        Set<String> readable = Set.copyOf(accessService.readableCollections(accessService.current()));
        List<String> collections = kbCollectionProvider.listActiveCollections().stream()
                .filter(readable::contains)
                .toList();
        log.info("检索范围：{} 个可读的有效知识库", collections.size());
        return RetrievalScope.of(collections);
    }
}
