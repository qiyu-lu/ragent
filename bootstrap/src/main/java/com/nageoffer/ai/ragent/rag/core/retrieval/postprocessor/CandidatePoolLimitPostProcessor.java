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
 * 融合后的候选池成本守卫。
 * <p>
 * 正常 RRF 路径已按 candidateLimit 截断；本处理器放在 Metadata/Rerank 之前，为关闭融合或融合失败的
 * 路径提供同一成本上限，避免先完成回表和模型调用再截断。它不执行最终 {@code contextTopK}；请求最终
 * 条数由上层统一决定。
 */
@Component
public class CandidatePoolLimitPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "CandidatePoolLimit";
    }

    @Override
    public int getOrder() {
        return 6;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int limit = context.getBudget().candidateLimit();
        if (limit <= 0 || chunks.size() <= limit) {
            return chunks;
        }
        return List.copyOf(chunks.subList(0, limit));
    }
}
