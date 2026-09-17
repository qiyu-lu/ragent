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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.SourceReadResult;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.SourceReader;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 只注册这四个工具；run、owner、task 与实际知识库范围均由执行器绑定。 */
public class ResearchTools {
    private final ResearchSession session;
    private final KnowledgeSearchService search;
    private final SourceReader reader;

    public ResearchTools(ResearchSession session, KnowledgeSearchService search, SourceReader reader) {
        this.session = session;
        this.search = search;
        this.reader = reader;
    }

    @Tool(name = "search_knowledge", description = "Search the allowed knowledge scope. Returns candidate IDs and truncated excerpts; read_source is required before citing.")
    public Object search(
            @ToolParam(name = "query") String query,
            @ToolParam(name = "document_ids", required = false, description = "Normally OMIT: the server already applies the saved document scope. Only narrow by copying exact allowed IDs; never guess IDs.") List<String> documents,
            @ToolParam(name = "limit", required = false, description = "1 to 8, defaults to 5") Integer limit) {
        return guarded(() -> {
            int count = limit == null ? 5 : limit;
            if (count < 1 || count > 8) throw new ClientException("单次研究检索条数应为 1—8");
            return search.search(session.claim.run().id(), session.claim.owner(), "main", query,
                    List.of(), documents, count);
        });
    }

    @Tool(name = "read_source", description = "Read a saved candidate by evidence ID. CHUNK or NEIGHBORS only; returns a bounded pinned excerpt and genuine location/hash, not a full document.")
    public Object read(@ToolParam(name = "evidence_id") String id,
                       @ToolParam(name = "mode", required = false) SourceReadResult.ReadMode mode) {
        return guarded(() -> {
            var result = reader.read(session.claim.run().id(), session.claim.owner(), id,
                    mode == null ? SourceReadResult.ReadMode.CHUNK : mode);
            session.check();
            session.delivered(result.evidence());
            return result;
        });
    }

    @Tool(name = "ask_user", description = "Pause only when a missing USER condition prevents research. Ask one clear question. Missing source information belongs in gaps.")
    public Object ask(@ToolParam(name = "question") String question) {
        return guarded(() -> {
            if (question == null || question.isBlank() || question.length() > 2000) {
                throw new ClientException("必须提出一个长度合理的明确问题");
            }
            session.conclude(new ResearchSession.Outcome(question, null));
            return Map.of("status", "WAITING_INPUT", "question", question);
        });
    }

    @Tool(name = "finish_research", description = "Finish with evidence-grounded findings, gaps and conflicts. Each finding must cite IDs actually returned by read_source in this execution. Preserve numbers, units and conditions; never invent missing parameters.")
    public Object finish(@ToolParam(name = "findings") List<SubtaskResult.Finding> findings,
                         @ToolParam(name = "gaps") List<String> gaps,
                         @ToolParam(name = "conflicts") List<String> conflicts) {
        return guarded(() -> {
            if (findings == null || gaps == null || conflicts == null || findings.size() > 30
                    || gaps.size() > 30 || conflicts.size() > 30 || findings.isEmpty() && gaps.isEmpty()) {
                throw new ClientException("研究结果必须包含发现或资料缺口，且各项不能超过 30 条");
            }
            var delivered = session.delivered();
            for (var finding : findings) {
                if (finding == null || finding.statement() == null || finding.statement().isBlank()
                        || finding.statement().length() > 4000 || finding.evidenceIds() == null
                        || finding.evidenceIds().isEmpty() || finding.evidenceIds().size() > 12) {
                    throw new ClientException("发现必须包含明确陈述与 1—12 个已读证据 ID");
                }
                var unread = finding.evidenceIds().stream().filter(id -> !delivered.containsKey(id)).toList();
                if (!unread.isEmpty()) {
                    throw new ClientException("以下引用尚未通过本次 read_source 提供：" + unread
                            + "；已读取的 ID：" + delivered.keySet());
                }
            }
            if (java.util.stream.Stream.concat(gaps.stream(), conflicts.stream())
                    .anyMatch(text -> text == null || text.isBlank() || text.length() > 4000)) {
                throw new ClientException("缺口或冲突说明无效");
            }
            var result = new SubtaskResult("main", findings, gaps, conflicts, SubtaskResult.Status.COMPLETED);
            session.conclude(new ResearchSession.Outcome(null, result));
            return result;
        });
    }

    private Object guarded(Supplier<Object> action) {
        session.check();
        if (session.outcome() != null) return ToolResultBlock.error("研究已结束，不能继续调用工具");
        try {
            Object result = action.get();
            session.check();
            return result;
        } catch (ClientException | IllegalArgumentException e) {
            // 参数错误作为原生 tool result 回传，额度由 acting middleware 统一计数。
            return ToolResultBlock.error(e.getMessage());
        }
    }
}
