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

package com.nageoffer.ai.ragent.research.runtime;

import com.nageoffer.ai.ragent.research.model.SourceReadResult;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.SourceReader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/** 只注册三种工具，从 schema 层排除追问与再次委派。 */
public class ResearchWorkerTools {
    private final ResearchTools tools;
    public ResearchWorkerTools(ResearchSession session, KnowledgeSearchService search, SourceReader reader) {
        tools = new ResearchTools(session, search, reader);
    }
    @Tool(name = "search_knowledge", description = "Search only this worker's saved scope, limit 1—8 (default 5). Start with one query covering your dimensions and immediately read relevant candidates before further searching. Follow evidence with a targeted dependent query when needed.")
    public Object search(@ToolParam(name = "query") String query,
                         @ToolParam(name = "document_ids", required = false) List<String> documents,
                         @ToolParam(name = "limit", required = false, description = "1 to 8, defaults to 5; never request 10") Integer limit) {
        return tools.search(query, documents, limit);
    }
    @Tool(name = "read_source", description = "Read a candidate returned to this worker. Copy the returned evidence ID exactly; CHUNK or NEIGHBORS only.")
    public Object read(@ToolParam(name = "evidence_id") String id,
                       @ToolParam(name = "mode", required = false) SourceReadResult.ReadMode mode) {
        return tools.read(id, mode);
    }
    @Tool(name = "finish_research", description = "Return compressed findings with actual read evidence IDs, source gaps and conflicts. Missing user conditions also belong in gaps. At most 8 items of each kind, at most 8000 total text characters; preserve numbers, units and conditions.")
    public Object finish(@ToolParam(name = "findings") List<SubtaskResult.Finding> findings,
                         @ToolParam(name = "gaps") List<String> gaps,
                         @ToolParam(name = "conflicts") List<String> conflicts) {
        return tools.finish(findings, gaps, conflicts);
    }
}
