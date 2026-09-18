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

package com.nageoffer.ai.ragent.research.eval;

import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.runtime.ResearchRunner;
import com.nageoffer.ai.ragent.research.runtime.ResearchSession;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.SourceReader;

import java.util.List;
import java.util.Map;

/** 固定模型对照的一次知识检索路径；不包含生产聊天的改写、意图/MCP 和回退。 */
public class OneShotResearchRunner implements ResearchRunner {
    private final KnowledgeSearchService search;
    private final SourceReader reader;

    public OneShotResearchRunner(KnowledgeSearchService search, SourceReader reader) {
        this.search = search;
        this.reader = reader;
    }

    @Override public ResearchSession.Outcome run(ResearchSession session) {
        session.check();
        session.budget.acquireTool();
        String query = session.claim.run().brief().goal();
        session.event("TOOL_STARTED", "一次知识检索", Map.of("tool", "search_knowledge", "arguments", Map.of("query", query, "limit", 10)));
        var hits = session.retrieve(() -> search.search(session.claim.run().id(), session.claim.owner(), "main", query, List.of(), List.of(), 10));
        session.event("TOOL_ENDED", "检索上下文已取得", Map.of("tool", "search_knowledge", "evidenceIds", hits.stream().map(h -> h.evidenceId()).toList()));
        for (var hit : hits) {
            // 仅固定命中块的正文，不追加查询或邻接展开；读取检查来源和实际生成输入。
            session.delivered(reader.read(session.claim.run().id(), session.claim.owner(), hit.evidenceId()).evidence());
        }
        return new ResearchSession.Outcome(null, new SubtaskResult("main", List.of(),
                hits.isEmpty() ? List.of("No matching source was retrieved.") : List.of(), List.of(), SubtaskResult.Status.COMPLETED));
    }
}
