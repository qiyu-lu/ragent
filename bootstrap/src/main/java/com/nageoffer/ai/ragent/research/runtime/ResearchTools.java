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

/** 主 Agent 的四个基础工具；run、owner、task 与实际知识库范围均由执行器绑定。 */
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
            List<String> selected = documents;
            if (!session.main()) {
                if (documents != null && !documents.isEmpty() && !session.documents().isEmpty()
                        && !session.documents().containsAll(documents)) throw new ClientException("搜索不能扩大子任务文档范围");
                if (documents == null || documents.isEmpty()) selected = session.documents();
            }
            var hits = search.search(session.claim.run().id(), session.claim.owner(), session.taskId, query,
                    List.of(), selected, count);
            session.check();
            session.candidates(hits.stream().map(com.nageoffer.ai.ragent.research.model.KnowledgeSearchHit::evidenceId).toList());
            return hits;
        });
    }

    @Tool(name = "read_source", description = "Read a saved candidate by evidence ID. CHUNK or NEIGHBORS only; returns a bounded pinned excerpt and genuine location/hash, not a full document.")
    public Object read(@ToolParam(name = "evidence_id") String id,
                       @ToolParam(name = "mode", required = false) SourceReadResult.ReadMode mode) {
        return guarded(() -> {
            session.requireReadable(id);
            var readMode = mode == null ? SourceReadResult.ReadMode.CHUNK : mode;
            var result = session.main() ? reader.read(session.claim.run().id(), session.claim.owner(), id, readMode)
                    : reader.read(session.claim.run().id(), session.claim.owner(), id, readMode, session.documents());
            session.check();
            session.delivered(result.evidence());
            var e = result.evidence();
            var source = new java.util.LinkedHashMap<String, Object>();
            source.put("evidenceId", e.evidenceId()); source.put("docId", e.docId()); source.put("docName", e.documentName());
            source.put("documentVersion", e.documentVersion()); source.put("sourceLocation", e.sourceLocation());
            source.put("excerpt", e.text()); source.put("chunkIds", e.chunkIds()); source.put("truncated", e.truncated());
            source.put("sourceExtent", e.sourceExtent()); source.put("sourceState", result.sourceState());
            session.event("SOURCE_READ", "已查阅来源：" + e.documentName(), source);
            return result;
        });
    }

    @Tool(name = "ask_user", description = "Pause only when a missing USER condition prevents research. Ask one clear question. Missing source information belongs in gaps.")
    public Object ask(@ToolParam(name = "question") String question) {
        return guarded(() -> {
            if (!session.main()) throw new ClientException("子任务的用户条件问题必须通过 gaps 返回");
            if (question == null || question.isBlank() || question.length() > 2000) {
                throw new ClientException("必须提出一个长度合理的明确问题");
            }
            session.conclude(new ResearchSession.Outcome(question, null));
            return Map.of("status", "WAITING_INPUT", "question", question);
        });
    }

    @Tool(name = "finish_research", description = "Finish with evidence-grounded findings, gaps and conflicts. Each finding must cite IDs actually read by this Agent or cited in validated worker results. Preserve numbers, units and conditions; never invent missing parameters.")
    public Object finish(@ToolParam(name = "findings") List<SubtaskResult.Finding> findings,
                         @ToolParam(name = "gaps") List<String> gaps,
                         @ToolParam(name = "conflicts") List<String> conflicts) {
        return guarded(() -> {
            if (findings == null || gaps == null || conflicts == null || findings.size() > 30
                    || gaps.size() > 30 || conflicts.size() > 30 || findings.isEmpty() && gaps.isEmpty()) {
                throw new ClientException("研究结果必须包含发现或资料缺口，且各项不能超过 30 条");
            }
            var delivered = session.citableIds();
            if (!session.main() && (findings.size() > 8 || gaps.size() > 8 || conflicts.size() > 8
                    || findings.stream().mapToInt(f -> f == null || f.statement() == null ? 0 : f.statement().length()).sum()
                    + java.util.stream.Stream.concat(gaps.stream(), conflicts.stream()).mapToInt(t -> t == null ? 0 : t.length()).sum() > 8000)) {
                throw new ClientException("子任务压缩结果每类最多 8 条，总文字最多 8000 字符");
            }
            for (int index = 0; index < findings.size(); index++) {
                var finding = findings.get(index);
                if (finding == null || finding.statement() == null || finding.statement().isBlank()
                        || finding.statement().length() > 4000 || finding.evidenceIds() == null
                        || finding.evidenceIds().isEmpty() || finding.evidenceIds().size() > 12) {
                    throw new ClientException("findings[" + index + "].statement/evidenceIds：发现必须包含明确陈述与 1—12 个已读证据 ID");
                }
                var unread = finding.evidenceIds().stream().filter(id -> !delivered.contains(id)).toList();
                if (!unread.isEmpty()) {
                    throw new ClientException("findings[" + index + "].evidenceIds：以下引用尚未通过本次 read_source 提供：" + unread
                            + "；已读取的 ID：" + delivered);
                }
            }
            if (java.util.stream.Stream.concat(gaps.stream(), conflicts.stream())
                    .anyMatch(text -> text == null || text.isBlank() || text.length() > 4000)) {
                throw new ClientException("缺口或冲突说明无效");
            }
            var combinedGaps = new java.util.ArrayList<>(gaps);
            session.workerGaps().stream().filter(g -> !combinedGaps.contains(g)).forEach(combinedGaps::add);
            var result = new SubtaskResult(session.taskId, findings, combinedGaps, conflicts,
                    session.workerFailure() ? SubtaskResult.Status.PARTIAL : SubtaskResult.Status.COMPLETED);
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
