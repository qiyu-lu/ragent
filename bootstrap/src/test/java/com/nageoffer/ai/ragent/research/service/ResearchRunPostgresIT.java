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

package com.nageoffer.ai.ragent.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import com.nageoffer.ai.ragent.research.model.ResearchRun.Status;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.runtime.ResearchRunner;
import com.nageoffer.ai.ragent.research.runtime.ResearchSession;
import com.nageoffer.ai.ragent.research.runtime.ResearchBudget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RESEARCH_P3_TEST_URL", matches = ".+")
class ResearchRunPostgresIT {
    private JdbcTemplate jdbc;
    private ResearchRunStore store;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private String owner, kb, conversation;

    @BeforeEach
    void setup() {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only a random local research_p3_ database is allowed");
        }
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        store = new ResearchRunStore(jdbc, json, new DataSourceTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        owner = "p3-" + suffix;
        kb = "kb-" + suffix;
        conversation = "conv-" + suffix;
        jdbc.update("INSERT INTO t_conversation (id, conversation_id, user_id, title) VALUES (?, ?, ?, 'P3 fixture')", conversation, conversation, owner);
        jdbc.update("INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by) VALUES (?, 'P3 fixture', 'fixture', ?, ?)", kb, kb, owner);
        UserContext.set(LoginUser.builder().userId(owner).username("p3-test").build());
    }

    @AfterEach void clearUser() { UserContext.clear(); }
    private ResearchBrief brief() { return new ResearchBrief("compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb)); }
    private ResearchRun run() { return store.create(owner, conversation, UUID.randomUUID().toString(), brief()); }
    private ResearchRunStore.Claim claim(ResearchRun run) { return store.claim(run.id(), owner, Duration.ofSeconds(300)).orElseThrow(); }

    @Test
    void concurrentDuplicateRequestsCreateOnlyOneRunAndOneQueueEvent() throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        String request = UUID.randomUUID().toString();
        var gate = new CountDownLatch(1);
        List<Future<ResearchRun>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) results.add(workers.submit(() -> {
                gate.await();
                return store.create(owner, conversation, request, brief());
            }));
            gate.countDown();
            String id = results.get(0).get(10, TimeUnit.SECONDS).id();
            for (var result : results) assertEquals(id, result.get(10, TimeUnit.SECONDS).id());
            assertEquals(1, store.events(id, owner, 0, 100).size());
            assertThrows(ClientException.class, () -> store.create(owner, conversation, request,
                    new ResearchBrief("different", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb))));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void activeLeaseRejectsDuplicatesAndOldEpochCannotPublish() {
        var run = run();
        var old = claim(run);
        assertTrue(store.claim(run.id(), owner, Duration.ofSeconds(300)).isEmpty());
        jdbc.update("UPDATE t_research_run SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", run.id());
        var replacement = claim(run);
        assertFalse(store.event(old, "OLD", "late", Map.of(), Map.of()));
        assertFalse(store.finish(old, Status.COMPLETED, Map.of("result", "late"), Map.of(), null));
        assertTrue(store.finish(replacement, Status.COMPLETED, Map.of("result", "current"), Map.of(), null));
        assertEquals("current", store.get(run.id(), owner).state().get("result"));
    }

    @Test
    void cancelledRunRejectsLateWritesAndRepeatedCancelIsIdempotent() {
        var run = run();
        var execution = claim(run);
        var cancelled = store.cancel(run.id(), owner);
        assertEquals(cancelled.revision(), store.cancel(run.id(), owner).revision());
        assertFalse(store.finish(execution, Status.COMPLETED, Map.of("result", "late"), Map.of(), null));
        assertFalse(store.event(execution, "OLD", "late", Map.of(), Map.of()));
        store.cancelledLocally(execution, Map.of("modelCalls", 1));
        store.cancelledLocally(execution, Map.of("modelCalls", 2));
        assertEquals(1, store.get(run.id(), owner).usage().get("modelCalls"));
        assertEquals(1, store.events(run.id(), owner, 0, 100).stream().filter(e -> e.type().equals("LOCAL_EXECUTION_ENDED")).count());
        assertFalse(store.get(run.id(), owner).state().containsKey("result"));
    }

    @Test
    void completionRacingCancellationAlwaysPublishesOneConsistentTerminalState() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                var run = run();
                var execution = claim(run);
                var gate = new CountDownLatch(1);
                var finish = workers.submit(() -> { gate.await(); return store.finish(execution, Status.COMPLETED, Map.of("result", "valid"), Map.of(), null); });
                var cancel = workers.submit(() -> { gate.await(); return store.cancel(run.id(), owner); });
                gate.countDown();
                boolean published = finish.get(10, TimeUnit.SECONDS);
                cancel.get(10, TimeUnit.SECONDS);
                var terminal = store.get(run.id(), owner);
                assertEquals(published ? Status.COMPLETED : Status.CANCELLED, terminal.status());
                assertEquals(published, terminal.state().containsKey("result"));
                assertEquals(1, store.events(run.id(), owner, 0, 100).stream()
                        .filter(e -> e.type().equals("COMPLETED") || e.type().equals("CANCEL_REQUESTED")).count());
            }
        } finally { workers.shutdownNow(); }
    }

    @Test
    void everyOperationEnforcesOwnerAndInputUsesRevision() {
        var run = run();
        var claim = claim(run);
        assertTrue(store.finish(claim, Status.WAITING_INPUT, Map.of("question", "Which method?"), Map.of("modelCalls", 2), null));
        var waiting = store.get(run.id(), owner);
        String other = "other";
        assertThrows(ClientException.class, () -> store.get(run.id(), other));
        assertThrows(ClientException.class, () -> store.events(run.id(), other, 0, 100));
        assertThrows(ClientException.class, () -> store.claim(run.id(), other, Duration.ofSeconds(30)));
        assertThrows(ClientException.class, () -> store.input(run.id(), other, waiting.revision(), "A"));
        assertThrows(ClientException.class, () -> store.cancel(run.id(), other));
        assertThrows(ClientException.class, () -> store.input(run.id(), owner, waiting.revision() - 1, "A"));
        var resumed = store.input(run.id(), owner, waiting.revision(), "A");
        assertEquals(List.of("A"), resumed.brief().constraints());
        assertEquals(2, resumed.usage().get("modelCalls"));
        assertFalse(resumed.state().containsKey("question"));
        assertThrows(ClientException.class, () -> store.input(run.id(), owner, waiting.revision(), "B"));
    }

    @Test
    void concurrentEventWritersAllocateContiguousSequencesInShortTransactions() throws Exception {
        var run = run();
        var execution = claim(run);
        var workers = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) results.add(workers.submit(() -> store.event(execution, "TOOL", "event", Map.of(), null)));
            for (var result : results) assertTrue(result.get(10, TimeUnit.SECONDS));
            var events = store.events(run.id(), owner, 0, 100);
            assertEquals(22, events.size());
            for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
            assertEquals(2, store.events(run.id(), owner, 20, 2).size());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void anotherInstanceStartingAndStoppingLeavesThisInstancesRunsAlone() throws Exception {
        var queued = run();
        var waiting = run();
        store.finish(claim(waiting), Status.WAITING_INPUT, Map.of("question", "Which?"), Map.of(), null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ResearchRunner blocking = session -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return new ResearchSession.Outcome(null, new SubtaskResult("main", List.of(), List.of("unavailable"), List.of(), SubtaskResult.Status.COMPLETED));
        };
        var instanceA = new ResearchRunService(store, blocking, new ResearchProperties(), jdbc, json, completion(), new ResearchEvidenceStore(jdbc, json));
        try {
            var running = instanceA.create(new ResearchRunService.CreateRequest(conversation, "instance-a", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long epoch = store.get(running.id(), owner).epoch();
            // 实例 B 启动再停止：此前两者都会把全库 QUEUED / RUNNING 标为 INTERRUPTED。
            new ResearchRunService(store, blocking, new ResearchProperties(), jdbc, json, completion(), new ResearchEvidenceStore(jdbc, json)).close();
            assertEquals(Status.RUNNING, store.get(running.id(), owner).status());
            assertEquals(epoch, store.get(running.id(), owner).epoch());
            assertEquals(Status.QUEUED, store.get(queued.id(), owner).status());
            assertEquals(Status.WAITING_INPUT, store.get(waiting.id(), owner).status());
            release.countDown();
            await(() -> store.get(running.id(), owner).status().terminal());
            assertEquals(epoch, store.get(running.id(), owner).epoch());
            assertTrue(store.events(running.id(), owner, 0, 100).stream().noneMatch(e -> e.type().equals("INTERRUPTED")));
        } finally { release.countDown(); instanceA.close(); }
    }

    @Test
    void finalArtifactAndPublicationEventCommitTogetherAndLateArtifactCannotOverwriteCancel() {
        var run = run();
        var active = claim(run);
        Map<String, Object> artifact = Map.of("outputType", "REPORT", "title", "已校验报告");
        assertTrue(store.finish(active, Status.COMPLETED, Map.of("researchResult", Map.of()), artifact, Map.of(), null));
        assertEquals(artifact, store.get(run.id(), owner).artifact());
        var events = store.events(run.id(), owner, 0, 100);
        assertEquals("ARTIFACT", events.get(events.size() - 2).type());
        assertEquals(artifact, events.get(events.size() - 2).payload());
        assertFalse(store.finish(active, Status.COMPLETED, Map.of(), Map.of("title", "重复回调"), Map.of(), null));
        var cancelled = run();
        var old = claim(cancelled);
        store.cancel(cancelled.id(), owner);
        assertFalse(store.finish(old, Status.COMPLETED, Map.of(), artifact, Map.of(), null));
        assertNull(store.get(cancelled.id(), owner).artifact());
        assertTrue(store.events(cancelled.id(), owner, 0, 100).stream().noneMatch(e -> e.type().equals("ARTIFACT")));
    }

    @Test
    void firstResearchCreatesOneConversationAtomicallyAndOwnerListsOnlyTheirRuns() {
        var first = store.create(owner, null, "new-research", brief());
        assertEquals(first.id(), store.create(owner, null, "new-research", brief()).id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_conversation WHERE conversation_id = ? AND user_id = ?", Integer.class, first.conversationId(), owner));
        assertEquals(List.of(first.id()), store.list(first.conversationId(), owner).stream().map(ResearchRun::id).toList());
        assertTrue(store.list(first.conversationId(), "foreign").isEmpty());
    }

    private ResearchCompletionService completion() {
        return new ResearchCompletionService(store, org.mockito.Mockito.mock(ResearchArtifactGenerator.class), json);
    }

    @Test
    void readSourcesSurviveCancellationAndUnreadCandidatesOrOtherOwnersAreExcluded() {
        var run = run();
        var evidence = new ResearchEvidenceStore(jdbc, json);
        var read = new com.nageoffer.ai.ragent.research.model.EvidenceRecord(run.id(), "ev-read-" + run.id(), kb, "doc", "paper.md", "v1", List.of("chunk"), "hash", "已读正文", Map.of("chunk_index", 0), "main", false, true,
                com.nageoffer.ai.ragent.research.model.EvidenceRecord.SourceExtent.CHUNK);
        var unread = new com.nageoffer.ai.ragent.research.model.EvidenceRecord(run.id(), "ev-unread-" + run.id(), kb, "doc", "paper.md", "v1", List.of("chunk-2"), "hash-2", "检索摘要", Map.of("chunk_index", 1), "main", true, false, read.sourceExtent());
        evidence.save(owner, new com.nageoffer.ai.ragent.research.model.EvidenceSnapshot(read, "已读正文", "metadata"));
        evidence.save(owner, new com.nageoffer.ai.ragent.research.model.EvidenceSnapshot(unread, "完整但未读的正文", "metadata-2"));
        store.cancel(run.id(), owner);
        assertEquals(List.of(read.evidenceId()), evidence.readSources(run.id(), owner).stream().map(com.nageoffer.ai.ragent.research.model.EvidenceRecord::evidenceId).toList());
        assertThrows(ClientException.class, () -> evidence.readSources(run.id(), "foreign"));
    }

    @Test
    void serviceRunsWithoutAdvanceDoesNotHoldTransactionAndCancelledModelCannotOverwrite() throws Exception {
        var entered = new CountDownLatch(1);
        var returnModel = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ResearchRunner runner = session -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(returnModel.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return new ResearchSession.Outcome(null, new SubtaskResult("main", List.of(), List.of("unavailable"), List.of(), SubtaskResult.Status.COMPLETED));
        };
        var service = new ResearchRunService(store, runner, new ResearchProperties(), jdbc, json, completion(), new ResearchEvidenceStore(jdbc, json));
        try {
            var request = new ResearchRunService.CreateRequest(conversation, "same", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of());
            var created = service.create(request);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(created.id(), service.create(request).id());
            service.get(created.id());
            service.events(created.id(), 0, 100);
            assertEquals(1, calls.get());
            assertEquals(Status.CANCELLED, service.cancel(created.id()).status());
            returnModel.countDown();
            await(() -> Boolean.TRUE.equals(store.get(created.id(), owner).state().get("localExecutionEnded")));
            assertEquals(Status.CANCELLED, service.get(created.id()).status());
            assertFalse(service.get(created.id()).state().containsKey("researchResult"));
        } finally { returnModel.countDown(); service.close(); }
    }

    @Test
    void servicePersistsModelFailureAndRejectsForeignConversationOrScope() throws Exception {
        var service = new ResearchRunService(store, session -> { throw new IllegalStateException("provider failed"); }, new ResearchProperties(), jdbc, json, completion(), new ResearchEvidenceStore(jdbc, json));
        try {
            var created = service.create(new ResearchRunService.CreateRequest(conversation, "failed", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of()));
            await(() -> store.get(created.id(), owner).status().terminal());
            assertEquals(Status.FAILED, service.get(created.id()).status());
            assertEquals("RESEARCH_EXECUTION_FAILED", service.get(created.id()).errorSummary());
            assertThrows(ClientException.class, () -> service.create(new ResearchRunService.CreateRequest("foreign", "foreign", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of())));
            assertThrows(ClientException.class, () -> service.create(new ResearchRunService.CreateRequest(conversation, "scope", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of("outside"))));
        } finally { service.close(); }
    }

    @Test
    void subtaskCheckpointsAreFencedIdempotentAndRetainedAfterCancellation() {
        var run = run();
        var claim = claim(run);
        var completed = Map.<String, Object>of("status", "COMPLETED", "result", Map.of("taskId", "worker-1", "findings", List.of(), "gaps", List.of("missing")), "readEvidenceIds", List.of("ev"));
        assertTrue(store.subtask(claim, "worker-1", completed, "SUBTASK_COMPLETED", Map.of("workersCreated", 2)));
        assertFalse(store.subtask(claim, "worker-1", completed, "SUBTASK_COMPLETED", Map.of()));
        assertTrue(store.subtask(claim, "worker-2", Map.of("status", "RUNNING"), "SUBTASK_RUNNING", Map.of("workersCreated", 2)));
        var cancelled = store.cancel(run.id(), owner);
        var tasks = (Map<String, Map<String, Object>>) cancelled.state().get("subtasks");
        assertEquals("COMPLETED", tasks.get("worker-1").get("status"));
        assertEquals("CANCELLED", tasks.get("worker-2").get("status"));
        assertFalse(store.subtask(claim, "worker-2", completed, "SUBTASK_COMPLETED", Map.of()));
        assertTrue(store.events(run.id(), owner, 0, 500).stream().anyMatch(e -> e.taskId().equals("worker-1")));
        assertEquals(1, store.events(run.id(), owner, 0, 500).stream().filter(e -> e.type().equals("SUBTASK_COMPLETED")).count());
    }

    @Test
    void inputResumeRetainsWorkerCountAndValidatedFindingsWithoutReplayingHistory() {
        var run = run();
        var old = claim(run);
        var result = new SubtaskResult("worker-1", List.of(new SubtaskResult.Finding("7 ms under condition X", List.of("ev"))), List.of(), List.of(), SubtaskResult.Status.COMPLETED);
        var usage = Map.<String, Object>of("workersCreated", 2, "modelCalls", 6, "toolCalls", 6);
        assertTrue(store.subtask(old, "worker-1", Map.of("status", "COMPLETED", "task", Map.of("goal", "completed goal"),
                "result", json.convertValue(result, Map.class), "readEvidenceIds", List.of("ev")), "SUBTASK_COMPLETED", usage));
        assertTrue(store.finish(old, Status.WAITING_INPUT, Map.of("question", "Budget?"), usage, null));
        var waiting = store.get(run.id(), owner);
        var queued = store.input(run.id(), owner, waiting.revision(), "500 annotations");
        var fresh = claim(queued);
        assertTrue(fresh.run().epoch() > old.run().epoch());
        var session = new ResearchSession(store, fresh, new ResearchBudget(new ResearchProperties(), fresh.run().usage()),
                new com.nageoffer.ai.ragent.research.runtime.ResearchControl());
        session.restoreResults(json);
        assertEquals(Set.of("ev"), session.citableIds());
        assertTrue(session.delivered().isEmpty());
        assertEquals(6, session.budget.snapshot().get("modelCalls"));
        var task = new com.nageoffer.ai.ragent.research.runtime.ResearchTask("follow up", List.of("dimension"), "findings", List.of());
        assertEquals(3, session.reserveTasks(List.of(task)));
        assertThrows(ClientException.class, () -> session.reserveTasks(List.of(new com.nageoffer.ai.ragent.research.runtime.ResearchTask("completed goal", List.of("dimension"), "findings", List.of()))));
        assertFalse(store.subtask(old, "worker-2", Map.of("status", "COMPLETED"), "SUBTASK_COMPLETED", usage));
    }

    @Test
    void parentEndingAfterBudgetFailureClosesRemainingChildStateAndKeepsCheckpoints() {
        var run = run();
        var claim = claim(run);
        assertTrue(store.subtask(claim, "worker-1", Map.of("status", "RUNNING", "readEvidenceIds", List.of("ev")), "SUBTASK_RUNNING", Map.of("workersCreated", 1)));
        assertTrue(store.finish(claim, Status.PARTIAL, Map.of("reason", "RUN_DURATION_BUDGET"), Map.of("workersCreated", 1), "RUN_DURATION_BUDGET"));
        var tasks = (Map<String, Map<String, Object>>) store.get(run.id(), owner).state().get("subtasks");
        assertEquals("INTERRUPTED", tasks.get("worker-1").get("status"));
        assertEquals(List.of("ev"), tasks.get("worker-1").get("readEvidenceIds"));
        assertFalse(store.subtask(claim, "worker-1", Map.of("status", "COMPLETED"), "SUBTASK_COMPLETED", Map.of()));
    }

    @Test
    void parallelSessionsPersistMonotonicGlobalUsageAndIndependentTaskCheckpoints() throws Exception {
        var run = run();
        var claim = claim(run);
        var limits = new ResearchProperties();
        limits.setMaxToolCalls(100);
        var budget = new ResearchBudget(limits, Map.of());
        var main = new ResearchSession(store, claim, budget, new com.nageoffer.ai.ragent.research.runtime.ResearchControl());
        var task = new com.nageoffer.ai.ragent.research.runtime.ResearchTask("goal", List.of("dimension"), "finding", List.of());
        var a = main.worker("worker-1", task);
        var b = main.worker("worker-2", task);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                var worker = i % 2 == 0 ? a : b;
                futures.add(executor.submit(() -> { budget.acquireTool(); worker.event("TOOL_COUNTED", "count", Map.of()); }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(40, store.get(run.id(), owner).usage().get("toolCalls"));
            var events = store.events(run.id(), owner, 0, 500);
            assertEquals(42, events.size());
            for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
            assertEquals(20, events.stream().filter(e -> e.taskId().equals("worker-1")).count());
            assertTrue(main.checkpoint(a, "RUNNING", null, json));
            assertTrue(store.finish(claim, Status.PARTIAL, Map.of(), budget.snapshot(), "RUN_DURATION_BUDGET"));
            var saved = (Map<String, Map<String, Object>>) store.get(run.id(), owner).state().get("subtasks");
            assertEquals("INTERRUPTED", saved.get("worker-1").get("status"));
        } finally { executor.shutdownNow(); }
    }

    private ResearchRunService service(ResearchRunner runner, ResearchProperties properties) {
        return new ResearchRunService(store, runner, properties, jdbc, json, completion(), new ResearchEvidenceStore(jdbc, json));
    }
    private ResearchRunService.CreateRequest request(String id) {
        return new ResearchRunService.CreateRequest(conversation, id, "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of());
    }
    private void expireLease(String id) {
        jdbc.update("UPDATE t_research_run SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
    }
    private static ResearchSession.Outcome gapOnly() {
        return new ResearchSession.Outcome(null, new SubtaskResult("main", List.of(), List.of("unavailable"), List.of(), SubtaskResult.Status.COMPLETED));
    }
    private List<Map<String, Object>> starts(String id) {
        return store.events(id, owner, 0, 500).stream().filter(e -> e.type().equals("RUN_STARTED")).map(e -> e.payload()).toList();
    }
    /** 同库的其他用例可能留下更早的排队任务，轮询按空闲槽位先领旧任务，所以反复轮询直到目标任务结束。 */
    private void pollUntilTerminal(ResearchRunService service, String id, String runOwner) throws Exception {
        await(() -> { service.poll(); return store.get(id, runOwner).status().terminal(); });
    }
    private void contiguous(String id) {
        var events = store.events(id, owner, 0, 500);
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
    }

    @Test
    void expiredLeaseIsTakenOverByAnotherInstanceAndTheOldExecutorIsFencedOut() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lateWrite = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        ResearchRunner stalled = session -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            lateWrite.set(store.event(session.claim, "LATE", "late", Map.of(), Map.of()));
            return gapOnly();
        };
        // 同库里其他用例留下的排队任务也会被轮询领走，只按本用例的任务 ID 计数。
        var survivorRuns = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        ResearchRunner survivor = session -> {
            survivorRuns.add(session.claim.run().id());
            if (session.claim.owner().equals(owner)) assertEquals(owner, UserContext.requireUser().getUserId());
            return gapOnly();
        };
        var a = service(stalled, new ResearchProperties());
        var b = service(survivor, new ResearchProperties());
        try {
            var run = a.create(request("takeover"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            b.poll();
            assertFalse(survivorRuns.contains(run.id()), "a live lease must not be taken over");
            // 模拟实例 A 失联：它不再续租，租约过期。
            expireLease(run.id());
            pollUntilTerminal(b, run.id(), owner);
            assertEquals(1, survivorRuns.stream().filter(run.id()::equals).count());
            release.countDown();
            await(() -> lateWrite.get() != null);
            assertFalse(lateWrite.get());
            var started = starts(run.id());
            assertEquals(2, started.size());
            assertEquals(a.executorId(), started.get(0).get("executorId"));
            assertEquals(b.executorId(), started.get(1).get("executorId"));
            assertEquals(1, started.get(1).get("takeover"));
            assertEquals(a.executorId(), started.get(1).get("previousExecutorId"));
            assertTrue(store.events(run.id(), owner, 0, 500).stream().noneMatch(e -> e.type().equals("LATE")));
            contiguous(run.id());
        } finally { release.countDown(); a.close(); b.close(); }
    }

    @Test
    void heartbeatKeepsALongRunOwnedAndLostLeaseStopsTheLocalExecution() throws Exception {
        var limits = new ResearchProperties();
        limits.setLeaseSeconds(2);
        limits.setHeartbeatSeconds(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        ResearchRunner longRun = session -> {
            try {
                while (!release.await(100, TimeUnit.MILLISECONDS)) session.check();
            } catch (java.util.concurrent.CancellationException e) { stopped.countDown(); throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return gapOnly();
        };
        var a = service(longRun, limits);
        var polled = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var b = service(session -> { polled.add(session.claim.run().id()); return gapOnly(); }, limits);
        try {
            var run = a.create(request("heartbeat"));
            for (int i = 0; i < 4; i++) { Thread.sleep(1000); b.poll(); }
            assertFalse(polled.contains(run.id()));
            assertEquals(Status.RUNNING, store.get(run.id(), owner).status());
            assertEquals(1, starts(run.id()).size(), "renewed lease must survive four seconds against a two-second lease");
            store.cancel(run.id(), owner);
            // 取消在别的实例上发生时，本地执行靠续租失败或写保护停下。
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); a.close(); b.close(); }
    }

    @Test
    void runsWhoseExecutorKeepsDyingAreFailedAsPoisonAfterTheTakeoverLimit() {
        var run = run();
        for (int attempt = 0; attempt <= ResearchRunStore.DEFAULT_MAX_TAKEOVERS; attempt++) {
            assertTrue(store.claim(run.id(), owner, "executor-" + attempt, Duration.ofSeconds(30), ResearchRunStore.DEFAULT_MAX_TAKEOVERS).isPresent());
            expireLease(run.id());
        }
        assertTrue(store.claimAvailable("executor-last", Duration.ofSeconds(30), 10, ResearchRunStore.DEFAULT_MAX_TAKEOVERS).stream()
                .noneMatch(c -> c.run().id().equals(run.id())));
        var failed = store.get(run.id(), owner);
        assertEquals(Status.FAILED, failed.status());
        assertEquals("EXECUTOR_LOST", failed.errorSummary());
        assertEquals(4, jdbc.queryForObject("SELECT takeover_count FROM t_research_run WHERE id = ?", Integer.class, run.id()));
        contiguous(run.id());
    }

    @Test
    void takeoverInterruptsInFlightWorkersButKeepsCompletedCheckpoints() {
        var run = run();
        var old = claim(run);
        assertTrue(store.subtask(old, "worker-1", Map.of("status", "COMPLETED", "readEvidenceIds", List.of("ev")), "SUBTASK_COMPLETED", Map.of("workersCreated", 2)));
        assertTrue(store.subtask(old, "worker-2", Map.of("status", "RUNNING"), "SUBTASK_RUNNING", Map.of("workersCreated", 2)));
        expireLease(run.id());
        var fresh = store.claimAvailable("survivor", Duration.ofSeconds(30), 10, 3).stream().filter(c -> c.run().id().equals(run.id())).findFirst().orElseThrow();
        var tasks = (Map<String, Map<String, Object>>) fresh.run().state().get("subtasks");
        assertEquals("COMPLETED", tasks.get("worker-1").get("status"));
        assertEquals("INTERRUPTED", tasks.get("worker-2").get("status"));
        assertFalse(store.subtask(old, "worker-2", Map.of("status", "COMPLETED"), "SUBTASK_COMPLETED", Map.of()));
        assertFalse(store.renew(old, Duration.ofSeconds(30)));
        assertTrue(store.renew(fresh, Duration.ofSeconds(30)));
    }

    @Test
    void fullLocalQueueLeavesRunsQueuedForAnyInstanceToPoll() throws Exception {
        var limits = new ResearchProperties();
        limits.setMaxConcurrentRuns(1);
        limits.setQueueCapacity(1);
        var release = new CountDownLatch(1);
        ResearchRunner blocking = session -> {
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return gapOnly();
        };
        var a = service(blocking, limits);
        var b = service(session -> gapOnly(), new ResearchProperties());
        try {
            var first = a.create(request("q1"));
            await(() -> store.get(first.id(), owner).status() == Status.RUNNING);
            a.create(request("q2"));
            var third = a.create(request("q3"));
            assertEquals(Status.QUEUED, store.get(third.id(), owner).status(), "a full local queue no longer fails the run");
            pollUntilTerminal(b, third.id(), owner);
            assertNotEquals("RUN_QUEUE_FULL", store.get(third.id(), owner).errorSummary());
        } finally { release.countDown(); a.close(); b.close(); }
    }

    @Test
    void shutdownLetsTheCurrentStepFinishThenHandsTheRunBackWithoutCountingATakeover() throws Exception {
        var inStep = new CountDownLatch(1);
        var stepFinished = new java.util.concurrent.atomic.AtomicBoolean();
        ResearchRunner steps = session -> {
            while (true) {
                session.checkStep();
                inStep.countDown();
                // 一步进行中（模型调用或工具）：停机不打断它。
                try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                stepFinished.set(true);
            }
        };
        var a = service(steps, new ResearchProperties());
        var b = service(session -> gapOnly(), new ResearchProperties());
        try {
            var run = a.create(request("handover"));
            assertTrue(inStep.await(10, TimeUnit.SECONDS));
            long started = System.nanoTime();
            a.close();
            long closeMillis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(stepFinished.get(), "the step in progress completed before the lease was handed back");
            assertTrue(closeMillis < 5_000, "handover happens at the step boundary, not at the grace deadline: " + closeMillis);
            assertEquals(Status.QUEUED, store.get(run.id(), owner).status());
            var released = store.events(run.id(), owner, 0, 500).stream().filter(e -> e.type().equals("RUN_RELEASED")).toList();
            assertEquals(1, released.size());
            assertEquals("SHUTDOWN", released.get(0).payload().get("reason"));
            pollUntilTerminal(b, run.id(), owner);
            assertNotEquals("EXECUTION_CANCELLED", store.get(run.id(), owner).errorSummary());
            assertEquals(0, jdbc.queryForObject("SELECT takeover_count FROM t_research_run WHERE id = ?", Integer.class, run.id()));
            assertFalse(starts(run.id()).get(1).containsKey("takeover"));
            contiguous(run.id());
        } finally { a.close(); b.close(); }
    }

    @Test
    void aStepThatOutlastsTheGracePeriodIsHandedBackAndCancelled() throws Exception {
        var limits = new ResearchProperties();
        limits.setShutdownGraceSeconds(1);
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        ResearchRunner stuck = session -> {
            entered.countDown();
            try { while (true) { Thread.sleep(50); session.check(); } }
            catch (java.util.concurrent.CancellationException e) { cancelled.countDown(); throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); cancelled.countDown(); throw new java.util.concurrent.CancellationException(); }
        };
        var a = service(stuck, limits);
        try {
            var run = a.create(request("grace"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long started = System.nanoTime();
            a.close();
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 900);
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertEquals(Status.QUEUED, store.get(run.id(), owner).status(), "a cancelled local execution cannot fail a handed-back run");
            assertEquals("SHUTDOWN_GRACE_EXPIRED", store.events(run.id(), owner, 0, 500).stream()
                    .filter(e -> e.type().equals("RUN_RELEASED")).findFirst().orElseThrow().payload().get("reason"));
        } finally { a.close(); }
    }

    @Test
    void takeoverRestoresToolHistoryAndReadEvidenceFromTheDatabase() throws Exception {
        var run = run();
        var old = claim(run);
        var evidence = new ResearchEvidenceStore(jdbc, json);
        var read = new com.nageoffer.ai.ragent.research.model.EvidenceRecord(run.id(), "ev-resume-" + run.id(), kb, "doc", "paper.md", "v1", List.of("chunk"), "hash", "已读正文", Map.of("chunk_index", 0), "main", false, true,
                com.nageoffer.ai.ragent.research.model.EvidenceRecord.SourceExtent.CHUNK);
        evidence.save(owner, new com.nageoffer.ai.ragent.research.model.EvidenceSnapshot(read, "已读正文", "metadata"));
        assertTrue(store.event(old, "RESEARCH_STARTED", "started", Map.of("request", "{\"brief\":{}}"), Map.of("modelCalls", 1)));
        assertTrue(store.event(old, "TOOL_STARTED", "read", Map.of("toolCallId", "call-1", "tool", "read_source", "arguments", Map.of("evidence_id", read.evidenceId()), "batch", List.of("call-1")), Map.of("modelCalls", 1, "toolCalls", 1)));
        assertTrue(store.event(old, "SOURCE_READ", "read", Map.of("evidenceId", read.evidenceId()), Map.of("modelCalls", 1, "toolCalls", 1)));
        assertTrue(store.event(old, "TOOL_ENDED", "read", Map.of("toolCallId", "call-1", "tool", "read_source", "status", "SUCCESS", "output", "{}"), Map.of("modelCalls", 1, "toolCalls", 1)));
        assertTrue(store.event(old, "TOOL_STARTED", "search", Map.of("toolCallId", "call-2", "tool", "search_knowledge", "arguments", Map.of("query", "next"), "batch", List.of("call-2")), Map.of("modelCalls", 2, "toolCalls", 2)));
        expireLease(run.id());
        var restored = new java.util.concurrent.atomic.AtomicReference<ResearchSession>();
        var b = service(session -> { if (session.claim.run().id().equals(run.id())) restored.set(session); return gapOnly(); }, new ResearchProperties());
        try {
            pollUntilTerminal(b, run.id(), owner);
            var session = restored.get();
            assertEquals(List.of("call-1"), session.history().steps().stream().map(com.nageoffer.ai.ragent.research.runtime.ResearchHistory.Step::toolCallId).toList());
            assertEquals(1, session.history().droppedToolCalls());
            assertEquals(Set.of(read.evidenceId()), session.delivered().keySet());
            assertEquals(2, session.budget.snapshot().get("modelCalls"), "the budget continues from the persisted usage, including the lost call");
        } finally { b.close(); }
    }

    @Test
    void polledRunsRebuildTheOwnerFromTheUserTable() throws Exception {
        String userId = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000_000L);
        jdbc.update("INSERT INTO t_user (id, username, password, role) VALUES (?, ?, 'x', 'user')", userId, "u" + userId);
        jdbc.update("INSERT INTO t_conversation (id, conversation_id, user_id, title) VALUES (?, ?, ?, 'fixture')", "c" + userId, "c" + userId, userId);
        var seen = new java.util.concurrent.atomic.AtomicReference<com.nageoffer.ai.ragent.framework.context.LoginUser>();
        var b = service(session -> { if (session.claim.owner().equals(userId)) seen.set(UserContext.get()); return gapOnly(); }, new ResearchProperties());
        try {
            var run = store.create(userId, "c" + userId, "identity", brief());
            pollUntilTerminal(b, run.id(), userId);
            assertEquals(userId, seen.get().getUserId());
            assertEquals("u" + userId, seen.get().getUsername());
            assertEquals("user", seen.get().getRole());
        } finally { b.close(); }
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("Async research did not settle");
            Thread.sleep(20);
        }
    }
}
