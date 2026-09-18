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
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.service.*;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchWorkerCoordinatorTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private ResearchProperties limits;
    private ResearchRunStore store;
    private KnowledgeSearchService search;
    private ResearchSession parent;
    @BeforeEach void setup() {
        limits = new ResearchProperties();
        store = mock(ResearchRunStore.class);
        when(store.current(any())).thenReturn(true);
        when(store.subtask(any(), anyString(), anyMap(), anyString(), anyMap())).thenReturn(true);
        search = mock(KnowledgeSearchService.class);
        when(search.validateDocumentScope(anyString(), anyString(), anyList())).thenAnswer(i -> List.of("doc"));
        var run = new ResearchRun("run", "conv", "client", new ResearchBrief("goal", ResearchBrief.OutputType.PLAN, List.of(), List.of("kb"), List.of("doc")),
                ResearchRun.Status.RUNNING, 1, 1, Map.of(), null, Map.of(), null, Instant.now(), null);
        parent = new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"), new ResearchBudget(limits, Map.of()), new ResearchControl());
    }
    private ResearchTask task(String goal) { return new ResearchTask(goal, List.of("prerequisites"), "concise cited facts", List.of("doc")); }
    private ResearchSession.Outcome finish(ResearchSession child) {
        return new ResearchSession.Outcome(null, new SubtaskResult(child.taskId, List.of(), List.of("Not specified"), List.of(), SubtaskResult.Status.COMPLETED));
    }

    @Test void dedicatedPoolRunsFourTasksInTwoWavesAndRejectsDuplicatesAndFurtherWorkers() throws Exception {
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        var firstWave = new CountDownLatch(2);
        try (var coordinator = new ResearchWorkerCoordinator(limits, search, child -> {
            assertSame(parent.budget, child.budget);
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
            try {
                assertTrue(Thread.currentThread().getName().startsWith("research-worker-"));
                firstWave.countDown(); assertTrue(firstWave.await(5, TimeUnit.SECONDS));
                return finish(child);
            } catch (InterruptedException error) { throw new RuntimeException(error); }
            finally { active.decrementAndGet(); }
        }, json)) {
            assertInstanceOf(List.class, coordinator.tools(parent).conduct(List.of(task("one"), task("two"), task("three"), task("four"))).block());
            assertEquals(2, maximum.get()); assertEquals(4, parent.results().size());
            assertTrue(parent.results().stream().allMatch(r -> r.status() == SubtaskResult.Status.COMPLETED));
            assertEquals(4, parent.budget.snapshot().get("workersCreated"));
            assertInstanceOf(ToolResultBlock.class, coordinator.tools(parent).conduct(List.of(task("five"))).block());
        }
    }

    @Test void duplicateAndInvalidScopesNeverAllocateWorkerBudgetOrLaunchRunner() {
        var calls = new AtomicInteger();
        try (var coordinator = new ResearchWorkerCoordinator(limits, search, child -> { calls.incrementAndGet(); return finish(child); }, json)) {
            assertInstanceOf(ToolResultBlock.class, coordinator.tools(parent).conduct(List.of(task("one"), task("one"))).block());
            when(search.validateDocumentScope(anyString(), anyString(), eq(List.of("outside")))).thenThrow(new ClientException("outside scope"));
            assertInstanceOf(ToolResultBlock.class, coordinator.tools(parent).conduct(List.of(new ResearchTask("bad", List.of("dimension"), "finding", List.of("outside")))).block());
            assertEquals(0, parent.budget.snapshot().get("workersCreated")); assertEquals(0, calls.get());
            coordinator.tools(parent).conduct(List.of(task("one"))).block();
            assertInstanceOf(ToolResultBlock.class, coordinator.tools(parent).conduct(List.of(task("one"))).block());
            assertEquals(1, calls.get());
        }
    }

    @Test void timeoutReturnsStructuredFailureAndLateCallableCannotPublishASecondResult() throws Exception {
        limits.setWorkerTimeoutSeconds(1);
        CountDownLatch release = new CountDownLatch(1), lateReturned = new CountDownLatch(1);
        try (var coordinator = new ResearchWorkerCoordinator(limits, search, child -> {
            // 故意模拟不响应中断的外部调用，验证回调隔离。
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            lateReturned.countDown();
            return finish(child);
        }, json)) {
            var returned = (List<SubtaskResult>) coordinator.tools(parent).conduct(List.of(task("one"))).block();
            assertEquals(SubtaskResult.Status.FAILED, returned.get(0).status());
            assertEquals(List.of("WORKER_TIMEOUT"), returned.get(0).executionIssues());
            assertTrue(returned.get(0).gaps().isEmpty());
            release.countDown(); assertTrue(lateReturned.await(5, TimeUnit.SECONDS));
            assertEquals(SubtaskResult.Status.FAILED, parent.results().get(0).status());
            verify(store, times(1)).subtask(any(), eq("worker-1"), anyMap(), eq("SUBTASK_FAILED"), anyMap());
            verify(store, never()).subtask(any(), anyString(), anyMap(), eq("SUBTASK_COMPLETED"), anyMap());
        } finally { release.countDown(); }
    }

    @Test void oneReadAfterSearchAllowsFollowUpWithoutTreatingEveryCandidateAsMandatory() {
        var child = parent.worker("worker-1", task("one"));
        child.candidates(List.of("ev-one", "ev-two"));
        assertTrue(child.requiresRead());
        child.delivered(new EvidenceRecord("run", "ev-one", "kb", "doc", "source", "V1", List.of("chunk"), "hash", "7 ms",
                Map.of(), "worker-1", false, true, EvidenceRecord.SourceExtent.CHUNK));
        assertFalse(child.requiresRead()); assertEquals(Set.of("ev-two"), child.unreadCandidates());
        child.candidates(List.of("ev-one"));
        assertFalse(child.requiresRead(), "Repeated candidates should not force another read");
        child.candidates(List.of("ev-new"));
        assertTrue(child.requiresRead());
    }

    @Test void workerCannotReadOtherWorkersCandidatesExpandScopeAskOrDelegate() {
        var child = parent.worker("worker-1", task("one"));
        var reader = mock(SourceReader.class);
        var tools = new ResearchTools(child, search, reader);
        assertInstanceOf(ToolResultBlock.class, tools.read("other-workers-id", SourceReadResult.ReadMode.CHUNK));
        assertInstanceOf(ToolResultBlock.class, tools.search("q", List.of("outside"), 1));
        assertInstanceOf(ToolResultBlock.class, tools.ask("Question?"));
        assertThrows(ClientException.class, () -> child.worker("grandchild", task("two")));
        verifyNoInteractions(reader);
    }

    @Test void workerUsesCapturedUserAndRejectsUnreadResultsBeforePersisting() {
        var user = com.nageoffer.ai.ragent.framework.context.LoginUser.builder().userId("owner").username("first").build();
        com.nageoffer.ai.ragent.framework.context.UserContext.set(user);
        var observedUser = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var coordinator = new ResearchWorkerCoordinator(limits, search, child -> {
            observedUser.set(com.nageoffer.ai.ragent.framework.context.UserContext.getUserId());
            return new ResearchSession.Outcome(null, new SubtaskResult(child.taskId,
                    List.of(new SubtaskResult.Finding("unread assertion", List.of("fake"))), List.of(), List.of(), SubtaskResult.Status.COMPLETED));
        }, json)) {
            var tools = coordinator.tools(parent);
            com.nageoffer.ai.ragent.framework.context.UserContext.clear();
            var results = (List<SubtaskResult>) tools.conduct(List.of(task("one"))).block();
            assertEquals(SubtaskResult.Status.FAILED, results.get(0).status());
            assertEquals("owner", observedUser.get());
            assertTrue(parent.citableIds().isEmpty());
            verify(store, never()).subtask(any(), anyString(), anyMap(), eq("SUBTASK_COMPLETED"), anyMap());
        } finally { com.nageoffer.ai.ragent.framework.context.UserContext.clear(); }
    }

    @Test void restoredWorkerFindingsRetainCitationProofWithoutClaimingMainReadHistory() {
        var result = new SubtaskResult("worker-1", List.of(new SubtaskResult.Finding("7 ms under condition X", List.of("ev"))), List.of(), List.of(), SubtaskResult.Status.COMPLETED);
        var run = parent.claim.run();
        var restored = new ResearchRun(run.id(), run.conversationId(), run.clientRequestId(), run.brief(), run.status(), run.revision(), run.epoch(),
                Map.of("subtasks", Map.of("worker-1", Map.of("task", Map.of("goal", "one"), "result", json.convertValue(result, Map.class), "readEvidenceIds", List.of("ev")))), null, Map.of(), null, Instant.now(), null);
        var session = new ResearchSession(store, new ResearchRunStore.Claim(restored, "owner", "lease"), new ResearchBudget(limits, Map.of("workersCreated", 1)), new ResearchControl());
        session.restoreResults(json);
        assertEquals(Set.of("ev"), session.citableIds()); assertTrue(session.delivered().isEmpty());
        assertThrows(ClientException.class, () -> session.reserveTasks(List.of(task("one"))));
        assertThrows(IllegalArgumentException.class, () -> parent.accept(result, Set.of("different-id")));
    }
}
