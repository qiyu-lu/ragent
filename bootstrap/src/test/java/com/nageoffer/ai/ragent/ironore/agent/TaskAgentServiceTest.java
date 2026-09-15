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

package com.nageoffer.ai.ragent.ironore.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskAgentServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TaskAgentTestDatabase database;
    private DataSourceTransactionManager manager;
    private TaskAgentStore store;
    private InspectionBusinessService business;
    private TaskAgentTools tools;
    private TaskAgentService agent;
    private final ArrayDeque<Decision> decisions = new ArrayDeque<>();
    private boolean evidenceChanged;
    private final Document document = new Document("doc-1", "送检示例规程", "V1.0-demo");
    private final TaskKnowledge knowledge = new TaskKnowledge() {
        public List<Document> documents() { return List.of(document); }
        public Document document(String id) { return document; }
        public List<Evidence> search(Document doc, String query) {
            return List.of(new Evidence("e1", doc.id(), doc.version(), "送检", "B2:C3", "核对标签、交接资料并预约匹配项目的工位", "hash-1"));
        }
        public void validate(Document doc, List<Evidence> evidence) {
            if (evidenceChanged) throw new ClientException("规程证据已经改变");
        }
    };

    @BeforeEach
    void setup() {
        database = new TaskAgentTestDatabase();
        new ResourceDatabasePopulator(new ClassPathResource("db/260915_task_agent.sql")).execute(database.source);
        jdbc = new JdbcTemplate(database.source);
        manager = new DataSourceTransactionManager(database.source);
        store = new TaskAgentStore(jdbc, json, manager);
        business = new InspectionBusinessService(jdbc, store);
        business.initializeDemo("alice");
        tools = new TaskAgentTools(knowledge, business, json);
        agent = service((state, specs) -> decisions.removeFirst());
    }

    @AfterEach
    void closeDatabase() { database.close(); }

    @Test
    void completesRealBookingOnlyAfterApprovalAndReplaysLostResponse() {
        View pending = draft("sample-ready");
        assertEquals(Status.WAITING_APPROVAL, pending.status());
        assertEquals(0, submissions());
        assertNull(business.stations("alice").get(0).reservationRunId());
        View completed = agent.approve(pending.id(), "alice", pending.revision());
        assertEquals(Status.COMPLETED, completed.status());
        assertEquals("SUBMITTED", business.sample("alice", "sample-ready").status());
        assertEquals(completed.id(), business.stations("alice").get(0).reservationRunId());
        // Rebuild service/store: the successful HTTP response may have been lost.
        TaskAgentStore reloaded = new TaskAgentStore(jdbc, json, manager);
        TaskAgentService restarted = new TaskAgentService(reloaded, (state, specs) -> { throw new AssertionError(); }, tools, knowledge, business);
        View replay = restarted.approve(pending.id(), "alice", pending.revision());
        assertEquals(completed.submission(), replay.submission());
        assertEquals(1, submissions());
    }

    @Test
    void pausesForMissingDataAndContinuesFromPersistedObservations() {
        View run = start("sample-incomplete");
        decisions.add(decision("inspect_sample", Map.of()));
        decisions.add(decision("ask_user", Map.of("question", "请核对样品标签")));
        agent.advance(run.id(), "alice");
        assertEquals(Status.WAITING_INPUT, agent.advance(run.id(), "alice").status());
        business.updateSample("alice", "sample-incomplete", new SampleUpdate(true, true));
        agent = service((state, specs) -> decisions.removeFirst());
        agent.reply(run.id(), "alice", "已在资料面板完成标签核对");
        addDraftDecisions("station-02");
        View pending = advanceFour(run.id());
        assertTrue(pending.state().getObservations().stream().anyMatch(o -> "user_reply".equals(o.tool())));
        assertEquals(Status.COMPLETED, agent.approve(run.id(), "alice", pending.revision()).status());
    }

    @Test
    void chatClaimDoesNotReplaceActualSampleReadiness() {
        View run = start("sample-incomplete");
        addDraftDecisions("station-01");
        View result = advanceFour(run.id());
        assertEquals(Status.READY, result.status());
        assertNull(result.state().getProposal());
        assertEquals(0, submissions());
    }

    @Test
    void changedSlotInvalidatesApprovalAndAllowsAnotherPlan() {
        View pending = draft("sample-ready");
        jdbc.update("UPDATE t_task_agent_station SET reservation_run_id='other-run' WHERE owner_user_id='alice' AND id='station-01'");
        View conflict = agent.approve(pending.id(), "alice", pending.revision());
        assertEquals(Status.READY, conflict.status());
        assertNull(conflict.state().getProposal());
        assertEquals(0, submissions());
        addDraftDecisions("station-02");
        View revised = advanceFour(pending.id());
        assertThrows(ClientException.class, () -> agent.approve(pending.id(), "alice", pending.revision()));
        assertEquals(Status.COMPLETED, agent.approve(revised.id(), "alice", revised.revision()).status());
        assertEquals("station-02", business.submission(revised.id(), "alice").stationId());
    }

    @Test
    void concurrentApprovalsCannotDoubleBookOneStation() throws Exception {
        View first = draft("sample-ready");
        business.updateSample("alice", "sample-incomplete", new SampleUpdate(true, true));
        View second = draft("sample-incomplete");
        CountDownLatch bothValidated = new CountDownLatch(2);
        InspectionBusinessService racingBusiness = new InspectionBusinessService(jdbc, store) {
            @Override public void validate(Run run, Proposal proposal) {
                super.validate(run, proposal);
                bothValidated.countDown();
                try { assertTrue(bothValidated.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }
        };
        agent = new TaskAgentService(store, (state, specs) -> null, tools, knowledge, racingBusiness);
        CountDownLatch start = new CountDownLatch(1);
        var approvals = List.of(first, second).stream().map(pending -> CompletableFuture.supplyAsync(() -> {
            try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return agent.approve(pending.id(), "alice", pending.revision());
        })).toList();
        start.countDown();
        List<View> results = List.of(approvals.get(0).get(10, TimeUnit.SECONDS), approvals.get(1).get(10, TimeUnit.SECONDS));
        assertEquals(1, results.stream().filter(view -> view.status() == Status.COMPLETED).count());
        assertEquals(1, results.stream().filter(view -> view.status() == Status.READY).count());
        assertEquals(1, submissions());
        assertEquals(1, business.samples("alice").stream().filter(sample -> "SUBMITTED".equals(sample.status())).count());
    }

    @Test
    void changedEvidencePreventsBusinessWrites() {
        View pending = draft("sample-ready");
        evidenceChanged = true;
        View result = agent.approve(pending.id(), "alice", pending.revision());
        assertEquals(Status.READY, result.status());
        assertTrue(result.state().getEvidence().isEmpty());
        assertEquals(0, submissions());
        assertNull(business.stations("alice").get(0).reservationRunId());
    }

    @Test
    void rollsBackBookingAndSampleChangeWhenCompletionFails() {
        View pending = draft("sample-ready");
        InspectionBusinessService failing = new InspectionBusinessService(jdbc, store) {
            @Override public Submission submit(Run run) {
                super.submit(run);
                throw new ClientException("注入：写入后、事务提交前失败");
            }
        };
        TaskAgentService failingAgent = new TaskAgentService(store, (state, specs) -> null, tools, knowledge, failing);
        assertEquals(Status.READY, failingAgent.approve(pending.id(), "alice", pending.revision()).status());
        assertEquals(0, submissions());
        assertEquals("REGISTERED", business.sample("alice", "sample-ready").status());
        assertNull(business.stations("alice").get(0).reservationRunId());
    }

    @Test
    void rejectsUnsupportedToolsAndUnseenEvidence() {
        View run = start("sample-ready");
        decisions.add(decision("approve", Map.of()));
        assertEquals(Status.READY, agent.advance(run.id(), "alice").status());
        decisions.add(decision("inspect_sample", Map.of()));
        decisions.add(decision("list_stations", Map.of()));
        decisions.add(decision("propose_submission", proposal("station-01", "invented-evidence")));
        for (int i = 0; i < 3; i++) agent.advance(run.id(), "alice");
        assertNull(agent.get(run.id(), "alice").state().getProposal());
        assertEquals(0, submissions());
    }

    @Test
    void preventsProposingBeforeBusinessInspection() {
        View run = start("sample-ready");
        decisions.add(decision("search_procedure", Map.of("query", "送检要求")));
        decisions.add(decision("propose_submission", proposal("station-01", "e1")));
        agent.advance(run.id(), "alice");
        View result = agent.advance(run.id(), "alice");
        assertEquals(Status.READY, result.status());
        assertNull(result.state().getProposal());
    }

    @Test
    void incompatibleStationDoesNotBecomeApprovalDraft() {
        View run = start("sample-ready");
        addDraftDecisions("station-03");
        assertEquals(Status.READY, advanceFour(run.id()).status());
        assertEquals(0, submissions());
    }

    @Test
    void lateModelResultCannotOverwriteCancellation() {
        View run = start("sample-ready");
        agent = service((state, specs) -> {
            agent.cancel(run.id(), "alice");
            return decision("inspect_sample", Map.of());
        });
        View cancelled = agent.advance(run.id(), "alice");
        assertEquals(Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.state().getObservations().isEmpty());
        assertEquals(Status.CANCELLED, agent.advance(run.id(), "alice").status());
    }

    @Test
    void activeLeasePreventsDuplicateModelCallAndExpiredLeaseCanResume() {
        View run = start("sample-ready");
        store.claim(run.id(), "alice", 16, 180_000);
        AtomicInteger calls = new AtomicInteger();
        agent = service((state, specs) -> { calls.incrementAndGet(); return decision("inspect_sample", Map.of()); });
        assertEquals(Status.RUNNING, agent.advance(run.id(), "alice").status());
        assertEquals(0, calls.get());
        jdbc.update("UPDATE t_task_agent_run SET lease_until=0 WHERE id=?", run.id());
        assertEquals(Status.READY, agent.advance(run.id(), "alice").status());
        assertEquals(1, calls.get());
    }

    @Test
    void supersededWorkerCannotPublishItsResult() {
        View run = start("sample-ready");
        AtomicReference<String> replacementToken = new AtomicReference<>();
        agent = service((state, specs) -> {
            jdbc.update("UPDATE t_task_agent_run SET lease_until=0 WHERE id=?", run.id());
            replacementToken.set(store.claim(run.id(), "alice", 16, 180_000).leaseToken());
            return decision("inspect_sample", Map.of());
        });
        assertEquals(Status.RUNNING, agent.advance(run.id(), "alice").status());
        assertEquals(replacementToken.get(), store.get(run.id(), "alice").leaseToken());
        assertTrue(store.get(run.id(), "alice").state().getObservations().isEmpty());
    }

    @Test
    void operatorChangesInvalidateOldApproval() {
        View pending = draft("sample-ready");
        agent.reply(pending.id(), "alice", "请重新选择工位");
        assertThrows(ClientException.class, () -> agent.approve(pending.id(), "alice", pending.revision()));
        assertEquals(0, submissions());
    }

    @Test
    void allTaskOperationsEnforceOwner() {
        View run = start("sample-ready");
        assertTrue(agent.list("bob").isEmpty());
        assertThrows(ClientException.class, () -> agent.get(run.id(), "bob"));
        assertThrows(ClientException.class, () -> agent.advance(run.id(), "bob"));
        assertThrows(ClientException.class, () -> agent.cancel(run.id(), "bob"));
        assertThrows(ClientException.class, () -> agent.approve(run.id(), "bob", 0));
        assertThrows(ClientException.class, () -> business.updateSample("bob", "sample-ready", new SampleUpdate(true, true)));
    }

    @Test
    void boundsRepeatedInvalidActions() {
        View run = start("sample-ready");
        AtomicInteger calls = new AtomicInteger();
        agent = service((state, specs) -> { calls.incrementAndGet(); return decision("unknown", Map.of()); });
        for (int i = 0; i <= TaskAgentService.MAX_TURNS; i++) agent.advance(run.id(), "alice");
        assertEquals(TaskAgentService.MAX_TURNS, calls.get());
        assertEquals(Status.FAILED, agent.get(run.id(), "alice").status());
    }

    @Test
    void modelFailureIsPersistedAndCanBeRetried() {
        View run = start("sample-ready");
        agent = service((state, specs) -> { throw new IllegalStateException("provider unavailable"); });
        assertEquals(Status.FAILED, agent.advance(run.id(), "alice").status());
        agent.reply(run.id(), "alice", "服务恢复，请继续");
        agent = service((state, specs) -> decision("inspect_sample", Map.of()));
        assertEquals(Status.READY, agent.advance(run.id(), "alice").status());
    }

    @Test
    void demoInitializationDoesNotResetExistingRecords() {
        View pending = draft("sample-ready");
        agent.approve(pending.id(), "alice", pending.revision());
        business.initializeDemo("alice");
        assertEquals("SUBMITTED", business.sample("alice", "sample-ready").status());
        assertEquals(pending.id(), business.stations("alice").get(0).reservationRunId());
    }

    private TaskAgentService service(TaskAgentPlanner planner) { return new TaskAgentService(store, planner, tools, knowledge, business); }
    private View start(String sample) { return agent.start("alice", new StartRequest("按规程办理水分检测送检", document.id(), sample)); }
    private View draft(String sample) { View run = start(sample); addDraftDecisions("station-01"); return advanceFour(run.id()); }
    private View advanceFour(String id) { View result = null; for (int i = 0; i < 4; i++) result = agent.advance(id, "alice"); return result; }
    private void addDraftDecisions(String station) {
        decisions.add(decision("inspect_sample", Map.of()));
        decisions.add(decision("search_procedure", Map.of("query", "送检资料与工位要求")));
        decisions.add(decision("list_stations", Map.of()));
        decisions.add(decision("propose_submission", proposal(station, "e1")));
    }
    private Map<String, Object> proposal(String station, String evidence) {
        return Map.of("title", "样品送检", "stationId", station, "requirements", List.of(Map.of("text", "核对标签和交接资料", "evidenceIds", List.of(evidence))));
    }
    private Decision decision(String tool, Object args) { return new Decision(tool, json.valueToTree(args), "执行下一步"); }
    private int submissions() { return jdbc.queryForObject("SELECT COUNT(*) FROM t_task_agent_submission", Integer.class); }
}
