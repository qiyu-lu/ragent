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
    void restartMarksLostExecutorsInterruptedAndRetainsWaitingInputAndEvidenceState() {
        var queued = run();
        var running = run();
        var active = claim(running);
        store.event(active, "SOURCE_READ", "saved", Map.of(), Map.of("modelCalls", 1));
        var waiting = run();
        store.finish(claim(waiting), Status.WAITING_INPUT, Map.of("question", "Which?"), Map.of(), null);
        store.interruptOrphans();
        assertEquals(Status.INTERRUPTED, store.get(queued.id(), owner).status());
        assertEquals(Status.INTERRUPTED, store.get(running.id(), owner).status());
        assertEquals(1, store.get(running.id(), owner).usage().get("modelCalls"));
        assertEquals(Status.WAITING_INPUT, store.get(waiting.id(), owner).status());
        assertFalse(store.finish(active, Status.COMPLETED, Map.of(), Map.of(), null));
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
        var service = new ResearchRunService(store, runner, new ResearchProperties(), jdbc, json);
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
        var service = new ResearchRunService(store, session -> { throw new IllegalStateException("provider failed"); }, new ResearchProperties(), jdbc, json);
        try {
            var created = service.create(new ResearchRunService.CreateRequest(conversation, "failed", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of()));
            await(() -> store.get(created.id(), owner).status().terminal());
            assertEquals(Status.FAILED, service.get(created.id()).status());
            assertEquals("RESEARCH_EXECUTION_FAILED", service.get(created.id()).errorSummary());
            assertThrows(ClientException.class, () -> service.create(new ResearchRunService.CreateRequest("foreign", "foreign", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of())));
            assertThrows(ClientException.class, () -> service.create(new ResearchRunService.CreateRequest(conversation, "scope", "compare", ResearchBrief.OutputType.REPORT, List.of(), List.of(kb), List.of("outside"))));
        } finally { service.close(); }
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("Async research did not settle");
            Thread.sleep(20);
        }
    }
}
