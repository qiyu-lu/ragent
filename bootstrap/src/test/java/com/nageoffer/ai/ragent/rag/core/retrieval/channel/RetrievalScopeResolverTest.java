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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalScopeResolverTest {

    private static final List<String> ACTIVE = List.of("kb-finance", "kb-hr", "kb-tech");

    @Test
    @DisplayName("检索范围只含当前用户可读的有效库")
    void scopeContainsOnlyReadableCollections() {
        assertEquals(List.of("kb-hr"), resolveAs(List.of("kb-hr")).targetCollections());
    }

    @Test
    @DisplayName("可读但已失效的库不进入检索范围，顺序按有效库列表")
    void readableButInactiveCollectionIsExcluded() {
        RetrievalScope scope = resolveAs(List.of("kb-tech", "kb-retired", "kb-finance"));

        assertEquals(List.of("kb-finance", "kb-tech"), scope.targetCollections());
    }

    @Test
    @DisplayName("全部可读时检索全部有效库")
    void allReadableSearchesAllActiveCollections() {
        assertEquals(ACTIVE, resolveAs(ACTIVE).targetCollections());
    }

    @Test
    @DisplayName("没有任何可读库时作用域为空，不回退为全部库")
    void noReadableCollectionYieldsEmptyScope() {
        assertTrue(resolveAs(List.of()).targetCollections().isEmpty());
    }

    private static KnowledgeAccessService readable(List<String> collections) {
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        when(access.current()).thenReturn(KnowledgeAccessService.Subject.NOBODY);
        when(access.readableCollections(KnowledgeAccessService.Subject.NOBODY)).thenReturn(collections);
        return access;
    }

    private RetrievalScope resolveAs(List<String> readable) {
        KbCollectionProvider provider = mock(KbCollectionProvider.class);
        when(provider.listActiveCollections()).thenReturn(ACTIVE);
        return new RetrievalScopeResolver(provider, readable(readable)).resolve();
    }
}
