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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索链末端的最终条数守卫。
 * <p>
 * Rerank 开启时通常已经返回 TopK；关闭或异常跳过时，融合池仍可能有 candidateLimit 条。本处理器让
 * {@code contextTopK} 始终是实际输出契约，而不是依赖某个可关闭处理器的副作用。
 */
@Component
public class FinalTopKPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "FinalTopK";
    }

    @Override
    public int getOrder() {
        return 15;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int topK = context.getBudget().contextTopK();
        if (topK <= 0) {
            return List.of();
        }
        return chunks.size() > topK ? List.copyOf(chunks.subList(0, topK)) : chunks;
    }
}
