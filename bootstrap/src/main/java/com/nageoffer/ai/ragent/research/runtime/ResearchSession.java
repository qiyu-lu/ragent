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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.service.ResearchRunStore;

import java.util.*;
import java.util.concurrent.CancellationException;

/** 每个 Agent 独立保存已读证据与结论，父领取、预算和取消关系由服务端绑定。 */
public class ResearchSession {
    private static final int MAX_CONSECUTIVE_RETRIEVAL_FAILURES = 2;
    private static final String RETRIEVAL_FAILURE_LIMIT = "RETRIEVAL_FAILURE_LIMIT";
    public record Outcome(String question, SubtaskResult result) { }
    public final ResearchRunStore.Claim claim;
    public final ResearchBudget budget;
    public final ResearchControl control;
    public final String taskId;
    public final ResearchTask task;
    private final ResearchRunStore store;
    private final Map<String, EvidenceRecord> delivered = new LinkedHashMap<>();
    private final Set<String> candidates = new HashSet<>();
    private Set<String> latestCandidates = Set.of();
    private boolean awaitingRead;
    private final Set<String> readCandidates = new HashSet<>();
    private final Map<String, Integer> searchCounts = new HashMap<>();
    private final Map<String, String> candidateDocuments = new LinkedHashMap<>();

    public synchronized void beforeSearch(String query, List<String> documents) {
        if (retrievalBlocked()) throw new ClientException("RETRIEVAL_FAILURE_LIMIT: stop searching and finish with already-read evidence and execution issues. Changing the query does not repair an unavailable retrieval service.");
        if (query == null || query.isBlank() || query.length() > 10000) throw new ClientException("检索问题无效");
        if (requiresRead()) throw new ClientException("Read a relevant candidate before searching again. Unread IDs: " + unreadCandidates());
        String key = normalize(query) + "|" + (documents == null ? List.of() : documents.stream().sorted().toList());
        int count = searchCounts.getOrDefault(key, 0);
        if (count >= 2) throw new ClientException("REPEATED_SEARCH: inspect existing candidates or use a focused query for a specific unresolved fact.");
        searchCounts.put(key, count + 1);
    }
    public synchronized void candidateHits(List<com.nageoffer.ai.ragent.research.model.KnowledgeSearchHit> hits) {
        hits.forEach(h -> candidateDocuments.put(h.evidenceId(), h.docId()));
        candidates(hits.stream().map(com.nageoffer.ai.ragent.research.model.KnowledgeSearchHit::evidenceId).toList());
    }
    public synchronized void readCandidate(String requested) { readCandidates.add(requested); }
    public synchronized Map<String, Object> readingCoverage() {
        var docs = delivered.values().stream().map(EvidenceRecord::docId).distinct().sorted().toList();
        var unreadDocs = latestCandidates.stream().map(candidateDocuments::get).filter(Objects::nonNull)
                .filter(id -> !docs.contains(id)).distinct().sorted().toList();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("directlyReadDocumentIds", docs);
        coverage.put("latestCandidateDocumentsNotRead", unreadDocs);
        return coverage;
    }
    private final Map<String, SubtaskResult> results = new LinkedHashMap<>();
    private final Set<String> acceptedIds = new LinkedHashSet<>();
    private final Set<String> delegatedGoals = new HashSet<>();
    private int localModelCalls;
    private int finishRepairCallsRemaining = -1;
    private volatile Outcome outcome;
    private String retrievalIssue;
    private int consecutiveRetrievalFailures;
    volatile String activeToolCallId;

    public <T> T retrieve(java.util.function.Supplier<T> action) {
        check();
        try (var operation = new com.nageoffer.ai.ragent.infra.operation.RequestOperation(budget.retrievalTimeout(),
                Map.of("runId", claim.run().id(), "taskId", taskId,
                        "toolCallId", activeToolCallId == null ? UUID.randomUUID().toString() : activeToolCallId),
                event -> event("RETRIEVAL_PHASE", "检索阶段状态", event), budget.embeddingCache);
             var cancellation = control.bindInterrupt(operation::cancel);
             var binding = operation.bind()) {
            T result = operation.measure("search_knowledge", action);
            synchronized (this) { retrievalIssue = null; consecutiveRetrievalFailures = 0; }
            return result;
        } catch (com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure failure) {
            synchronized (this) {
                retrievalIssue = "RETRIEVAL_FAILED:" + failure.code + "@" + failure.phase;
                consecutiveRetrievalFailures++;
            }
            if (retrievalBlocked()) event("RETRIEVAL_UNAVAILABLE", "检索连续失败，停止继续补查",
                    Map.of("consecutiveFailures", consecutiveRetrievalFailures, "code", failure.code, "phase", failure.phase));
            throw failure;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public ResearchSession(ResearchRunStore store, ResearchRunStore.Claim claim,
                            ResearchBudget budget, ResearchControl control) {
        this(store, claim, budget, control, "main", null);
    }

    private ResearchSession(ResearchRunStore store, ResearchRunStore.Claim claim,
                            ResearchBudget budget, ResearchControl control, String taskId, ResearchTask task) {
        this.store = store;
        this.claim = claim;
        this.budget = budget;
        this.control = control;
        this.taskId = taskId;
        this.task = task;
    }

    public ResearchSession worker(String taskId, ResearchTask task) {
        if (!main()) throw new ClientException("子任务不能继续委派");
        return new ResearchSession(store, claim, budget, new ResearchControl(), taskId, task);
    }
    public boolean main() { return task == null; }
    public List<String> documents() { return main() ? claim.run().brief().allowedDocIds() : task.documentIds(); }

    public void check() {
        control.check();
        budget.checkTime();
        if (!store.current(claim)) throw new CancellationException("RESEARCH_LEASE_SUPERSEDED");
    }

    public void event(String type, String summary, Map<String, Object> payload) {
        // 把快照读取与短事务串行化，避免并行事件用旧 usage 覆盖新计数。
        synchronized (budget) {
            if (control.cancelled()) return;
            if (main()) store.event(claim, type, summary, payload, budget.snapshot());
            else store.event(claim, taskId, type, summary, payload, budget.snapshot());
        }
    }

    public boolean checkpoint(ResearchSession worker, String status, SubtaskResult result, ObjectMapper json) {
        Set<String> readIds = worker.delivered().keySet();
        synchronized (budget) {
            check();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("task", worker.task);
            state.put("status", status);
            state.put("readEvidenceIds", readIds);
            if (result != null) state.put("result", json.convertValue(result, Map.class));
            return store.subtask(claim, worker.taskId, state, "SUBTASK_" + status, budget.snapshot());
        }
    }

    public synchronized void candidates(Collection<String> ids) {
        candidates.addAll(ids);
        latestCandidates = Set.copyOf(ids);
        awaitingRead = !unreadCandidates().isEmpty();
    }
    public synchronized boolean requiresRead() { return awaitingRead; }
    public synchronized Set<String> unreadCandidates() {
        Set<String> unread = new LinkedHashSet<>(latestCandidates);
        unread.removeAll(delivered.keySet());
        unread.removeAll(readCandidates);
        return Set.copyOf(unread);
    }
    public synchronized void requireReadable(String id) {
        if (!main() && !candidates.contains(id)) throw new ClientException("子任务只能阅读自身检索返回的证据 ID");
    }
    public synchronized void delivered(EvidenceRecord evidence) {
        check();
        if (!claim.run().id().equals(evidence.runId()) || !evidence.read()
                || !claim.run().brief().allowedKbIds().contains(evidence.kbId())
                || !documents().isEmpty() && !documents().contains(evidence.docId())) {
            throw new ClientException("读取结果不属于当前任务范围");
        }
        delivered.put(evidence.evidenceId(), evidence);
        awaitingRead = false;
    }

    public synchronized Map<String, EvidenceRecord> delivered() { return Map.copyOf(delivered); }
    public synchronized void validateResult(SubtaskResult result) {
        if (!taskId.equals(result.taskId()) || result.findings().size() > 8 || result.gaps().size() > 8
                || result.conflicts().size() > 8 || result.findings().stream().anyMatch(f -> f == null
                || f.statement() == null || f.statement().isBlank() || f.evidenceIds().isEmpty()
                || f.evidenceIds().size() > 12 || !delivered.keySet().containsAll(f.evidenceIds()))) {
            throw new IllegalArgumentException("子任务结果身份或已读引用无效");
        }
    }
    public synchronized Set<String> citableIds() {
        Set<String> ids = new LinkedHashSet<>(delivered.keySet());
        ids.addAll(acceptedIds);
        return Set.copyOf(ids);
    }
    public synchronized void accept(SubtaskResult result, Set<String> readIds) {
        check();
        if (!main() || results.containsKey(result.taskId())) return;
        List<String> ids = result.findings().stream().flatMap(f -> f.evidenceIds().stream()).distinct().toList();
        if (!readIds.containsAll(ids)) throw new IllegalArgumentException("子任务结果包含未读引用");
        results.put(result.taskId(), result);
        acceptedIds.addAll(ids);
    }

    /** 人工补充后仅恢复已提交、带已读 ID 证明的压缩结果。 */
    public void restoreResults(ObjectMapper json) {
        if (claim.run().state().get("subtasks") instanceof Map<?, ?> saved) {
            for (var entry : saved.values()) if (entry instanceof Map<?, ?> state) {
                if (state.get("task") instanceof Map<?, ?> task) delegatedGoals.add(normalize(task.get("goal").toString()));
                if (state.get("result") == null) continue;
                SubtaskResult result = json.convertValue(state.get("result"), SubtaskResult.class);
                Set<String> ids = new HashSet<>();
                if (state.get("readEvidenceIds") instanceof Collection<?> reads) reads.forEach(id -> ids.add(id.toString()));
                accept(result, ids);
            }
        }
    }
    public synchronized int reserveTasks(List<ResearchTask> tasks) {
        if (retrievalBlocked()) throw new ClientException("RETRIEVAL_FAILURE_LIMIT: do not delegate more searches to an unavailable retrieval service; finish with execution issues.");
        Set<String> goals = new HashSet<>();
        for (var task : tasks) {
            String goal = normalize(task.goal());
            if (!goals.add(goal) || delegatedGoals.contains(goal)) throw new ClientException("子目标已委派，请使用已有结果或提出具体补查目标");
        }
        int first = budget.acquireWorkers(tasks.size());
        delegatedGoals.addAll(goals);
        return first;
    }
    private static String normalize(String goal) { return goal.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    public synchronized List<SubtaskResult> results() { return List.copyOf(results.values()); }
    public synchronized Set<String> acceptedEvidenceIds() { return Set.copyOf(acceptedIds); }
    public synchronized boolean workerFailure() {
        return results.values().stream().anyMatch(r -> r.status() != SubtaskResult.Status.COMPLETED);
    }
    public synchronized List<String> executionIssues() {
        Set<String> issues = new LinkedHashSet<>();
        if (retrievalIssue != null) issues.add(retrievalIssue);
        if (consecutiveRetrievalFailures >= MAX_CONSECUTIVE_RETRIEVAL_FAILURES) issues.add(RETRIEVAL_FAILURE_LIMIT);
        results.values().stream().flatMap(r -> r.executionIssues().stream()).forEach(issues::add);
        return List.copyOf(issues);
    }
    public synchronized boolean retrievalBlocked() {
        return consecutiveRetrievalFailures >= MAX_CONSECUTIVE_RETRIEVAL_FAILURES
                || citableIds().isEmpty() && !results.isEmpty()
                && results.values().stream().allMatch(r -> r.executionIssues().contains(RETRIEVAL_FAILURE_LIMIT));
    }
    public synchronized List<String> workerGaps() {
        return results.values().stream().filter(r -> r.status() != SubtaskResult.Status.COMPLETED)
                .flatMap(r -> r.gaps().stream().map(g -> "[" + r.taskId() + "] " + g)).distinct().toList();
    }

    public synchronized void acquireModel(int workerLimit) {
        if (finishRepairCallsRemaining == 0) throw new IllegalStateException("NATIVE_FINISH_REQUIRED");
        if (main()) budget.acquireModel(false);
        else {
            if (localModelCalls >= workerLimit) throw new ResearchBudget.Exhausted("WORKER_LOCAL_MODEL_BUDGET");
            budget.acquireWorkerModel();
        }
        localModelCalls++;
        if (finishRepairCallsRemaining > 0) finishRepairCallsRemaining--;
    }

    /** 同一 Agent/上下文内最多再请求两次原生结束；仍扣原有全局和 worker 额度。 */
    public synchronized void requestFinishRepair() {
        check();
        if (finishRepairCallsRemaining == 0) throw new IllegalStateException("NATIVE_FINISH_REQUIRED");
        if (finishRepairCallsRemaining < 0) finishRepairCallsRemaining = 2;
    }

    public synchronized boolean finishingRepair() { return finishRepairCallsRemaining >= 0; }
    public synchronized int remainingModelCalls(int workerLimit) {
        int global = budget.explorationCallsRemaining() - (main() ? 0 : 1);
        int available = main() ? global : Math.min(global, workerLimit - localModelCalls);
        return Math.max(0, finishRepairCallsRemaining < 0 ? available : Math.min(available, finishRepairCallsRemaining));
    }
    public synchronized void conclude(Outcome outcome) {
        check();
        if (this.outcome != null) throw new IllegalStateException("研究已结束");
        this.outcome = outcome;
    }
    public Outcome outcome() { return outcome; }

    public synchronized SubtaskResult partial(String reason) {
        List<SubtaskResult.Finding> findings = results.values().stream().flatMap(r -> r.findings().stream()).limit(30).toList();
        List<String> gaps = new ArrayList<>(results.values().stream().flatMap(r -> r.gaps().stream()).distinct().limit(29).toList());
        List<String> issues = new ArrayList<>(executionIssues());
        issues.add(reason);
        List<String> conflicts = results.values().stream().flatMap(r -> r.conflicts().stream()).distinct().limit(30).toList();
        return new SubtaskResult(taskId, findings, gaps, conflicts, SubtaskResult.Status.PARTIAL, issues);
    }
}
