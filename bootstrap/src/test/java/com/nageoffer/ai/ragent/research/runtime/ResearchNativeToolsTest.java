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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.ResearchRunStore;
import com.nageoffer.ai.ragent.research.service.SourceReader;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TextBlock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SDK 与本地 HTTP 桩验证协议；不作为真实供应商效果证据。 */
class ResearchNativeToolsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockWebServer server;
    private ResearchProperties limits;
    private ResearchModelFactory models;
    private ResearchRunStore store;
    private KnowledgeSearchService search;
    private SourceReader reader;
    private ResearchSession session;
    private final AtomicInteger responses = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        limits = new ResearchProperties();
        var config = new AIModelProperties();
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("local-fixture-key");
        provider.setEndpoints(Map.of("chat", "/compatible-mode/v1/chat/completions"));
        config.getProviders().put("fixture", provider);
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("research-flash");
        candidate.setModel("fixture-native");
        candidate.setProvider("fixture");
        candidate.setSupportsToolCalling(true);
        config.getChat().setCandidates(List.of(candidate));
        models = new ResearchModelFactory(config, limits);
        store = mock(ResearchRunStore.class);
        when(store.current(any())).thenReturn(true);
        when(store.event(any(), anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);
        var brief = new ResearchBrief("Find X, then research the dependent entity", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb"));
        var run = new ResearchRun("run", "conversation", "request", brief, ResearchRun.Status.RUNNING,
                1, 1, Map.of(), null, Map.of(), null, Instant.now(), null);
        session = new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"),
                new ResearchBudget(limits, Map.of()), new ResearchControl());
        search = mock(KnowledgeSearchService.class);
        reader = mock(SourceReader.class);
        when(search.search(eq("run"), eq("owner"), eq("main"), anyString(), anyList(), any(), anyInt()))
                .thenAnswer(i -> List.of(hit(i.getArgument(3).equals("X") ? "ev-one" : "ev-two")));
        when(reader.read(eq("run"), eq("owner"), anyString(), any()))
                .thenAnswer(i -> new SourceReadResult(evidence(i.getArgument(2)), SourceReadResult.SourceState.CURRENT));
    }

    @AfterEach void close() throws Exception { server.shutdown(); }
    private ResearchAgentFactory factory() { return new ResearchAgentFactory(models, limits, search, reader, json, new HeuristicTokenCounterService()); }
    private KnowledgeSearchHit hit(String id) { return new KnowledgeSearchHit(id, "kb", "doc", "paper", "V1", "candidate", false, Map.of(), EvidenceRecord.SourceExtent.CHUNK); }
    private EvidenceRecord evidence(String id) { return new EvidenceRecord("run", id, "kb", "doc", "paper", "V1", List.of("chunk"), "hash", "X identifies Mercury; the dependent source gives a parameter of 7 ms.", Map.of(), "main", false, true, EvidenceRecord.SourceExtent.CHUNK); }

    @Test
    void singleResearcherCannotDelegateAndKeepsNativeFinishProtocol() throws Exception {
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("No supported answer"), "conflicts", List.of()));
        try (var serial = new ResearchAgentFactory(models, limits, search, reader, json, new HeuristicTokenCounterService(), false)) {
            assertEquals("No supported answer", serial.run(session).result().gaps().get(0));
        }
        var body = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals(4, body.path("tools").size());
        for (var schema : body.path("tools")) assertNotEquals("conduct_research", schema.path("function").path("name").asText());
        assertEquals("required", body.path("tool_choice").asText());
        assertEquals(1, session.budget.snapshot().get("modelCalls"));
    }

    @Test
    void nativeSearchReadAndDependentSearchPreserveToolCallIdsAndUsage() throws Exception {
        tool("search-1", "search_knowledge", Map.of("query", "X", "limit", 1));
        tool("read-1", "read_source", Map.of("evidence_id", "ev-one", "mode", "CHUNK"));
        tool("search-2", "search_knowledge", Map.of("query", "Mercury parameter", "limit", 1));
        tool("read-2", "read_source", Map.of("evidence_id", "ev-two"));
        tool("finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Parameter is 7 ms.", "evidenceIds", List.of("ev-one", "ev-two"))), "gaps", List.of(), "conflicts", List.of()));
        var outcome = factory().run(session);
        assertEquals(List.of("ev-one", "ev-two"), outcome.result().findings().get(0).evidenceIds());
        assertEquals(5, session.budget.snapshot().get("modelCalls"));
        assertEquals(5, session.budget.snapshot().get("toolCalls"));
        for (int i = 0; i < 5; i++) {
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/compatible-mode/v1/chat/completions", request.getPath());
            JsonNode body = json.readTree(request.getBody().readUtf8());
            assertEquals("fixture-native", body.path("model").asText());
            assertFalse(body.path("enable_thinking").asBoolean(true));
            assertEquals(5, body.path("tools").size());
            if (i == 1 || i == 3) assertEquals("read_source", body.path("tool_choice").path("function").path("name").asText());
            else assertEquals("required", body.path("tool_choice").asText());
            if (i > 0) {
                String expected = List.of("search-1", "read-1", "search-2", "read-2").get(i - 1);
                boolean matched = false;
                for (JsonNode message : body.path("messages")) {
                    if ("tool".equals(message.path("role").asText()) && expected.equals(message.path("tool_call_id").asText())) {
                        matched = true;
                        assertFalse(message.path("content").asText().isBlank());
                    }
                }
                assertTrue(matched, "Native tool result must retain its matching tool_call_id");
            }
        }
        var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
        assertTrue(calls.stream().allMatch(c -> "provider".equals(c.get("usageStatus"))));
        assertTrue(calls.stream().allMatch(c -> ((Number)c.get("inputTokens")).intValue() == 100));
        verify(search).search("run", "owner", "main", "Mercury parameter", List.of(), null, 1);
    }

    @Test
    void invalidEvidenceAndIllegalArgumentsReturnNativeErrorsAndCanBeCorrected() throws Exception {
        when(reader.read(eq("run"), eq("owner"), eq("fake"), any())).thenThrow(new ClientException("证据不存在或不属于本次研究任务"));
        tool("bad-read", "read_source", Map.of("evidence_id", "fake"));
        tool("bad-limit", "search_knowledge", Map.of("query", "X", "limit", 99));
        tool("missing", "finish_research", Map.of("findings", List.of(), "gaps", List.of("No reliable source"), "conflicts", List.of()));
        var outcome = factory().run(session);
        assertTrue(outcome.result().findings().isEmpty());
        assertEquals(List.of("No reliable source"), outcome.result().gaps());
        server.takeRequest(5, TimeUnit.SECONDS);
        String second = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(second.contains("bad-read"));
        assertTrue(second.contains("证据不存在"));
        String third = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(third.contains("bad-limit"));
        verifyNoInteractions(search);
    }

    @Test
    void finishCannotCiteUnreadCandidatesAndAgentMustRepair() throws Exception {
        tool("invalid-finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Guess", "evidenceIds", List.of("ev-one"))), "gaps", List.of(), "conflicts", List.of()));
        tool("repair", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Evidence not available"), "conflicts", List.of()));
        assertTrue(factory().run(session).result().findings().isEmpty());
        assertEquals(2, session.budget.snapshot().get("modelCalls"));
    }

    @Test
    void askUserReturnsAWaitingOutcomeThroughANativeTool() throws Exception {
        tool("ask", "ask_user", Map.of("question", "Which temperature range should the plan use?"));
        assertEquals("Which temperature range should the plan use?", factory().run(session).question());
        verifyNoInteractions(search, reader);
    }

    @Test void unresolvedRetrievalFailureCannotBeReportedAsCompletedSourceResearch() throws Exception {
        when(search.search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt()))
                .thenThrow(new com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure("embedding", "NETWORK_ERROR", true, null));
        tool("search-failed", "search_knowledge", Map.of("query", "latency"));
        tool("finish-after-failure", "finish_research", Map.of("findings", List.of(), "gaps", List.of(), "conflicts", List.of()));
        var result = factory().run(session).result();
        assertEquals(SubtaskResult.Status.PARTIAL, result.status());
        assertEquals(List.of("RETRIEVAL_FAILED:NETWORK_ERROR@embedding"), result.executionIssues());
        assertTrue(result.gaps().isEmpty(), "A failed service is an execution issue, not proof that the source lacks information");
    }

    @Test void successfulRetrievalAfterTransientFailureClearsTheUnresolvedExecutionIssue() {
        assertThrows(com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure.class, () -> session.retrieve(() -> {
            throw new com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure("embedding", "NETWORK_ERROR", true, null);
        }));
        assertFalse(session.executionIssues().isEmpty());
        assertEquals(List.of(), session.retrieve(List::of));
        assertTrue(session.executionIssues().isEmpty(), "A recovered API failure must not force a later legitimate source gap to fail");
        var result = (SubtaskResult) new ResearchTools(session, search, reader).finish(List.of(), List.of("The source lacks this parameter"), List.of());
        assertEquals(SubtaskResult.Status.COMPLETED, result.status());
    }

    @Test void consecutiveFailuresForceBoundedNativeFinishAndRejectFurtherQueries() throws Exception {
        when(search.search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt()))
                .thenThrow(new com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure("embedding", "NETWORK_ERROR", true, null));
        tool("first", "search_knowledge", Map.of("query", "latency"));
        tool("retry", "search_knowledge", Map.of("query", "response time"));
        // A provider ignoring tool_choice must still be unable to start another HTTP retrieval.
        tool("ignored-choice", "search_knowledge", Map.of("query", "yet another query"));
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Research could not access the service"), "conflicts", List.of()));
        var result = factory().run(session).result();
        assertEquals(SubtaskResult.Status.PARTIAL, result.status());
        assertTrue(result.executionIssues().contains("RETRIEVAL_FAILURE_LIMIT"));
        assertEquals(4, server.getRequestCount());
        verify(search, times(2)).search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt());
        for (int i = 0; i < 2; i++) server.takeRequest(5, TimeUnit.SECONDS);
        for (int i = 0; i < 2; i++) {
            var body = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("finish_research", body.path("tool_choice").path("function").path("name").asText());
        }
    }

    @Test void failedRetrievalWithoutEvidenceCannotBeConvertedIntoAUserQuestion() throws Exception {
        when(search.search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt()))
                .thenThrow(new com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure("embedding", "NETWORK_ERROR", true, null));
        tool("failed", "search_knowledge", Map.of("query", "latency"));
        tool("incorrect-ask", "ask_user", Map.of("question", "Please upload the sources again"));
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Service unavailable"), "conflicts", List.of()));
        var outcome = factory().run(session);
        assertNull(outcome.question());
        assertEquals(SubtaskResult.Status.PARTIAL, outcome.result().status());
        for (int i = 0; i < 2; i++) server.takeRequest(5, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8().contains("RETRIEVAL_EXECUTION_FAILURE"));
    }

    @Test void recoveredFailureResetsTheLimitAndPreservesPendingReadsWhenLaterSearchesFail() {
        var failure = new com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure("embedding", "NETWORK_ERROR", true, null);
        assertThrows(failure.getClass(), () -> session.retrieve(() -> { throw failure; }));
        session.retrieve(List::of);
        assertThrows(failure.getClass(), () -> session.retrieve(() -> { throw failure; }));
        assertFalse(session.retrievalBlocked());
        session.candidateHits(List.of(hit("ev-one")));
        assertThrows(failure.getClass(), () -> session.retrieve(() -> { throw failure; }));
        assertTrue(session.retrievalBlocked());
        var tools = new ResearchTools(session, search, reader);
        assertFalse(tools.read("ev-one", SourceReadResult.ReadMode.CHUNK) instanceof ToolResultBlock);
        assertTrue(session.citableIds().contains("ev-one"));
        var result = (SubtaskResult) tools.finish(List.of(new SubtaskResult.Finding("Parameter is 7 ms.", List.of("ev-one"))), List.of(), List.of());
        assertEquals(SubtaskResult.Status.PARTIAL, result.status());
        assertEquals(1, result.findings().size());
    }

    @Test void mainStopsDelegatingWhenEveryWorkerHitTheRetrievalLimitWithoutEvidence() {
        session.accept(new SubtaskResult("worker-1", List.of(), List.of(), List.of(), SubtaskResult.Status.PARTIAL,
                List.of("RETRIEVAL_FAILURE_LIMIT")), java.util.Set.of());
        assertTrue(session.retrievalBlocked());
        assertThrows(ClientException.class, () -> session.beforeSearch("retry", List.of()));
        assertThrows(ClientException.class, () -> session.reserveTasks(List.of()));
    }

    @Test
    void textualJsonCannotPretendToBeANativeToolCall() throws Exception {
        for (int i = 0; i < 3; i++) response(Map.of("role", "assistant", "content", "{\"tool\":\"ask_user\",\"question\":\"Which?\"}"), "stop");
        var failure = assertThrows(IllegalStateException.class, () -> factory().run(session));
        assertEquals("NATIVE_FINISH_REQUIRED", failure.getMessage());
        assertNull(session.outcome());
        assertEquals(3, server.getRequestCount());
        assertEquals(3, session.budget.snapshot().get("modelCalls"));
        verifyNoInteractions(search, reader);
    }

    @Test
    void nestedRequiredFieldsAndTypesAreRejectedBeforeDtoConversion() throws Exception {
        tool("missing", "finish_research", Map.of("findings", List.of(Map.of("statement", "A fact")), "gaps", List.of(), "conflicts", List.of()));
        tool("wrong-type", "finish_research", Map.of("findings", List.of(Map.of("statement", "A fact", "evidenceIds", "ev-one")), "gaps", List.of(), "conflicts", List.of()));
        tool("fixed", "finish_research", Map.of("findings", List.of(), "gaps", List.of("No supported fact"), "conflicts", List.of()));
        assertTrue(factory().run(session).result().findings().isEmpty());
        var first = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        var schema = java.util.stream.StreamSupport.stream(first.path("tools").spliterator(), false)
                .map(t -> t.path("function")).filter(t -> t.path("name").asText().equals("finish_research")).findFirst().orElseThrow();
        var required = schema.path("parameters").path("properties").path("findings").path("items").path("required");
        assertTrue(required.toString().contains("statement"));
        assertTrue(required.toString().contains("evidenceIds"));
        String feedback = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(feedback.contains("required property 'evidenceIds'"), feedback);
        assertTrue(feedback.contains("/findings/0"), feedback);
        assertTrue(feedback.contains("INVALID_TOOL_ARGUMENTS"), feedback);
        assertFalse(feedback.contains("ClassCastException"));
        String corrected = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(corrected.contains("array"));
        assertTrue(corrected.contains("/findings/0/evidenceIds"), corrected);
        assertFalse(corrected.contains("ClassCastException"));
    }

    @Test
    void textFinishIsRepairedNativelyWithExistingReadEvidence() throws Exception {
        tool("search", "search_knowledge", Map.of("query", "X"));
        tool("read", "read_source", Map.of("evidence_id", "ev-one"));
        response(Map.of("role", "assistant", "content", "The parameter is 7 ms."), "stop");
        tool("finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Parameter is 7 ms.", "evidenceIds", List.of("ev-one"))), "gaps", List.of(), "conflicts", List.of()));
        var result = factory().run(session).result();
        assertEquals(List.of("ev-one"), result.findings().get(0).evidenceIds());
        for (int i = 0; i < 3; i++) server.takeRequest(5, TimeUnit.SECONDS);
        var repair = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("finish_research", repair.path("tool_choice").path("function").path("name").asText());
        assertTrue(repair.path("messages").toString().contains("7 ms"));
        assertTrue(repair.path("messages").toString().contains("ev-one"));
        assertTrue(java.util.stream.StreamSupport.stream(repair.path("messages").spliterator(), false)
                .anyMatch(m -> "tool".equals(m.path("role").asText()) && "read".equals(m.path("tool_call_id").asText())
                        && m.path("content").asText().contains("X identifies Mercury")),
                "Repair must preserve the completed read tool result, not just a prior text answer");
        assertEquals(4, session.budget.snapshot().get("modelCalls"));
        verify(reader, times(1)).read(eq("run"), eq("owner"), eq("ev-one"), any());
        verify(store).event(any(), eq("NATIVE_FINISH_REPAIR"), anyString(), anyMap(), anyMap());
    }

    @Test
    void nullFindingsAndMissingStatementsAreNativeParameterErrors() throws Exception {
        tool("null", "finish_research", Map.of("findings", java.util.Collections.singletonList(null), "gaps", List.of(), "conflicts", List.of()));
        tool("missing-statement", "finish_research", Map.of("findings", List.of(Map.of("evidenceIds", List.of("ev-one"))), "gaps", List.of(), "conflicts", List.of()));
        tool("fixed", "finish_research", Map.of("findings", List.of(), "gaps", List.of("No supported fact"), "conflicts", List.of()));
        assertTrue(factory().run(session).result().findings().isEmpty());
        server.takeRequest(5, TimeUnit.SECONDS);
        String nullFeedback = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(nullFeedback.contains("/findings/0"), nullFeedback);
        String missingFeedback = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(missingFeedback.contains("required property 'statement'"), missingFeedback);
        assertFalse(missingFeedback.contains("ClassCastException"));
        verifyNoInteractions(search, reader);
    }

    @Test
    void repairErrorsStopAfterTwoCallsAndKeepFinalizationReserve() throws Exception {
        limits.setMaxModelCalls(5);
        session = new ResearchSession(store, session.claim, new ResearchBudget(limits, Map.of()), new ResearchControl());
        response(Map.of("role", "assistant", "content", "Finished."), "stop");
        for (int i = 0; i < 2; i++) tool("invalid-" + i, "finish_research",
                Map.of("findings", List.of(Map.of("statement", "Unsupported", "evidenceIds", List.of("unread"))), "gaps", List.of(), "conflicts", List.of()));
        var error = assertThrows(IllegalStateException.class, () -> factory().run(session));
        assertEquals("NATIVE_FINISH_REQUIRED", error.getMessage());
        assertNull(session.outcome());
        assertEquals(3, server.getRequestCount());
        session.budget.acquireModel(true);
        session.budget.acquireModel(true);
        verifyNoInteractions(search, reader);
    }

    @Test
    void nestedWorkerRequirementsAreValidatedBeforeConstructingTasks() throws Exception {
        tool("missing-task-fields", "conduct_research", Map.of("tasks", List.of(Map.of("goal", "Compare sources"))));
        tool("fixed", "finish_research", Map.of("findings", List.of(), "gaps", List.of("No supported fact"), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        server.takeRequest(5, TimeUnit.SECONDS);
        String feedback = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(feedback.contains("required property 'dimensions'"), feedback);
        assertTrue(feedback.contains("required property 'expectedOutput'"), feedback);
        assertFalse(feedback.contains("ClassCastException"));
        assertEquals(0, session.budget.snapshot().get("workersCreated"));
    }

    @Test
    void argumentFeedbackStaysEnglishOnAChineseHost() throws Exception {
        var host = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
        try { nestedWorkerRequirementsAreValidatedBeforeConstructingTasks(); }
        finally { java.util.Locale.setDefault(host); }
    }

    @Test
    void cancellationDuringFinishRepairStopsFurtherRequests() throws Exception {
        response(Map.of("role", "assistant", "content", "Finished."), "stop");
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> factory().run(session));
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            session.control.cancel();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertNull(session.outcome());
            assertEquals(2, server.getRequestCount());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void cancellationClosesTheSdkSubscriptionAndLeavesUnknownProviderUsage() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var workers = Executors.newSingleThreadExecutor();
        try {
            var pending = workers.submit(() -> factory().run(session));
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            session.control.cancel();
            var error = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
            assertEquals("CANCELLED", calls.get(0).get("status"));
            assertEquals("unknown", calls.get(0).get("usageStatus"));
            assertFalse(calls.get(0).containsKey("inputTokens"));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void compactionStubsOldestResultsOnceAndKeepsThePrefixStable() throws Exception {
        limits.setMaxInputTokens(1024);
        var bounded = new BoundedResearchModel(models.create(), session, limits, new Semaphore(1), json, new HeuristicTokenCounterService());
        var system = Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build();
        var goal = Msg.builder().role(MsgRole.USER).textContent("goal").build();
        var old = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("old").name("search_knowledge").input(Map.of("query", "old")).build()).build();
        var oldResult = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("old", "search_knowledge", TextBlock.builder()
                .text("[{\"evidenceId\":\"ev-old\",\"text\":\"" + "x ".repeat(8000) + "\"}]").build())).build();
        var latest = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("latest").name("read_source").input(Map.of("evidence_id", "ev-one")).build()).build();
        var latestResult = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("latest", "read_source", TextBlock.builder().text("7 ms").build())).build();
        var compacted = bounded.compact(List.of(system, goal, old, oldResult, latest, latestResult), List.of(), null);
        assertEquals(List.of(system, goal, old), compacted.subList(0, 3));
        assertEquals(List.of(latest, latestResult), compacted.subList(4, 6));
        var stub = compacted.get(3).getFirstContentBlock(ToolResultBlock.class);
        assertEquals("old", stub.getId(), "The stub must keep the tool_call pairing");
        String stubText = ((TextBlock) stub.getOutput().get(0)).getText();
        assertTrue(stubText.contains("ev-old") && stubText.contains("read_source"), stubText);
        var next = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("next").name("search_knowledge").input(Map.of("query", "next")).build()).build();
        var nextResult = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("next", "search_knowledge", TextBlock.builder().text("none").build())).build();
        var reminder = Msg.builder().role(MsgRole.USER).textContent("Server budget reminder: later").build();
        var later = bounded.compact(List.of(system, goal, old, oldResult, latest, latestResult, next, nextResult), List.of(), reminder);
        assertEquals(json.writeValueAsString(compacted), json.writeValueAsString(later.subList(0, 6)), "Later calls must reuse the compacted prefix");
        assertEquals(reminder, later.get(later.size() - 1));
        verify(store, times(1)).event(any(), eq("CONTEXT_COMPACTED"), anyString(), anyMap(), anyMap());
    }

    @Test
    void compactionDropsWholeOldestRoundsWhenStubsAreNotEnough() {
        limits.setMaxInputTokens(1024);
        var bounded = new BoundedResearchModel(models.create(), session, limits, new Semaphore(1), json, new HeuristicTokenCounterService());
        List<Msg> messages = new java.util.ArrayList<>(List.of(Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build(),
                Msg.builder().role(MsgRole.USER).textContent("goal").build()));
        for (int i = 0; i < 6; i++) {
            messages.add(Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("call-" + i).name("search_knowledge")
                    .input(Map.of("query", "q" + i + " " + "long query ".repeat(60))).build()).build());
            messages.add(Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("call-" + i, "search_knowledge", TextBlock.builder().text("none").build())).build());
        }
        var compacted = bounded.compact(messages, List.of(), null);
        assertTrue(compacted.size() < messages.size());
        assertEquals(messages.subList(0, 2), compacted.subList(0, 2));
        assertEquals(messages.subList(messages.size() - 2, messages.size()), compacted.subList(compacted.size() - 2, compacted.size()));
        for (int i = 2; i < compacted.size(); i += 2) {
            String call = compacted.get(i).getFirstContentBlock(ToolUseBlock.class).getId();
            assertEquals(call, compacted.get(i + 1).getFirstContentBlock(ToolResultBlock.class).getId(), "Whole rounds only");
        }
        messages.set(messages.size() - 2, Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("call-5").name("search_knowledge")
                .input(Map.of("query", "huge ".repeat(4000))).build()).build());
        var fresh = new BoundedResearchModel(models.create(), session, limits, new Semaphore(1), json, new HeuristicTokenCounterService());
        var error = assertThrows(ResearchBudget.Exhausted.class, () -> fresh.compact(messages, List.of(), null));
        assertEquals("MODEL_CONTEXT_BUDGET", error.getMessage());
    }

    @Test
    void systemPrefixIsByteStableAndTheReminderOnlyTrailsEachRequest() throws Exception {
        tool("search-1", "search_knowledge", Map.of("query", "X", "limit", 1));
        tool("read-1", "read_source", Map.of("evidence_id", "ev-one"));
        tool("search-2", "search_knowledge", Map.of("query", "Mercury parameter", "limit", 1));
        tool("read-2", "read_source", Map.of("evidence_id", "ev-two"));
        tool("finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Parameter is 7 ms.", "evidenceIds", List.of("ev-one", "ev-two"))), "gaps", List.of(), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        List<JsonNode> bodies = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) bodies.add(json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8()));
        for (int i = 0; i < bodies.size(); i++) {
            var messages = bodies.get(i).path("messages");
            assertEquals("system", messages.get(0).path("role").asText());
            assertFalse(messages.get(0).toString().contains("Server budget reminder"), "Volatile state must stay out of the system message");
            assertEquals(bodies.get(0).path("messages").get(0).toString(), messages.get(0).toString());
            assertEquals(bodies.get(0).path("tools").toString(), bodies.get(i).path("tools").toString());
            var last = messages.get(messages.size() - 1);
            assertEquals("user", last.path("role").asText());
            assertTrue(last.path("content").asText().startsWith("Server budget reminder:"));
            assertEquals(1, bodies.get(i).toString().split("Server budget reminder", -1).length - 1, "Old reminders must not enter memory");
            if (i == 0) continue;
            var previous = bodies.get(i - 1).path("messages");
            for (int m = 0; m < previous.size() - 1; m++) {
                assertEquals(previous.get(m).toString(), messages.get(m).toString(), "Call " + i + " must extend call " + (i - 1) + " byte for byte");
            }
        }
    }

    @Test
    void explicitCacheMarksSystemAndNewestHistoryAsContentBlocksAndRecordsWrites() throws Exception {
        limits.setExplicitPromptCache(true);
        cached(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "search-1", "type", "function",
                "function", Map.of("name", "search_knowledge", "arguments", "{\"query\":\"X\",\"limit\":1}")))), 0, 1949);
        cached(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "read-1", "type", "function",
                "function", Map.of("name", "read_source", "arguments", "{\"evidence_id\":\"ev-one\"}")))), 1949, 323);
        tool("finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Parameter is 7 ms.", "evidenceIds", List.of("ev-one"))), "gaps", List.of(), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        for (int i = 0; i < 3; i++) {
            var messages = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8()).path("messages");
            int newest = messages.size() - 2;
            for (int m = 0; m < messages.size(); m++) {
                var message = messages.get(m);
                assertFalse(message.has("cache_control"), "Bailian ignores message-level markers");
                boolean marked = message.path("content").isArray() && message.path("content").get(0).has("cache_control");
                assertEquals(m == 0 || m == newest, marked, "call " + i + " message " + m);
                if (marked) assertEquals("ephemeral", message.path("content").get(0).path("cache_control").path("type").asText());
            }
            assertTrue(messages.get(messages.size() - 1).path("content").isTextual(), "The volatile reminder is never cached");
        }
        var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
        assertEquals(1949, calls.get(0).get("cacheCreationTokens"));
        assertEquals("ephemeral", calls.get(0).get("cacheType"));
        assertEquals(323, calls.get(1).get("cacheCreationTokens"));
        assertEquals(1949, calls.get(1).get("cachedTokens"));
        assertFalse(calls.get(2).containsKey("cacheCreationTokens"));
        assertTrue(calls.stream().allMatch(c -> ((Number) c.get("durationMs")).longValue() >= 0
                && ((Number) c.get("firstTokenMs")).longValue() >= 0));
    }

    @Test
    void modelTimeoutCancelsHttpAndReturnsTheSharedQuota() throws Exception {
        limits.setModelCallTimeoutSeconds(1);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var factory = factory();
        assertThrows(RuntimeException.class, () -> factory.run(session));
        var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
        assertEquals("TIMED_OUT", calls.get(0).get("status"));
        assertEquals("unknown", calls.get(0).get("usageStatus"));
        assertTrue(((Number) calls.get(0).get("durationMs")).longValue() >= 900, "Failed calls still record how long they held the caller");
        assertFalse(calls.get(0).containsKey("firstTokenMs"), "No chunk arrived, so there is no first-token latency");
        tool("gap", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Missing source"), "conflicts", List.of()));
        assertNotNull(factory.run(session).result());
    }

    @Test
    void repeatedInvalidToolsStopBeforeSpendingFinalizationReserve() throws Exception {
        limits.setMaxModelCalls(4);
        session = new ResearchSession(store, session.claim, new ResearchBudget(limits, Map.of()), new ResearchControl());
        tool("invalid-1", "search_knowledge", Map.of("query", "X", "limit", 99));
        tool("invalid-2", "search_knowledge", Map.of("query", "X", "limit", 99));
        assertThrows(RuntimeException.class, () -> factory().run(session));
        assertEquals(2, session.budget.snapshot().get("modelCalls"));
        assertEquals(2, server.getRequestCount());
        verifyNoInteractions(search);
    }

    @Test
    void modelWithoutDeclaredToolCapabilityIsRejectedBeforeHttp() {
        var configuration = new AIModelProperties();
        var unsupported = new AIModelProperties.ModelCandidate();
        unsupported.setId("research-flash");
        unsupported.setModel("fixture");
        configuration.getChat().setCandidates(List.of(unsupported));
        assertThrows(IllegalStateException.class, () -> new ResearchModelFactory(configuration, limits).create());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void lastExplorationCallsRequireNativeFinishWhileFinalizationReserveRemains() throws Exception {
        limits.setMaxModelCalls(4);
        session = new ResearchSession(store, session.claim, new ResearchBudget(limits, Map.of()), new ResearchControl());
        tool("search", "search_knowledge", Map.of("query", "X", "limit", 1));
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Not enough source"), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        server.takeRequest(5, TimeUnit.SECONDS);
        JsonNode body = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("finish_research", body.path("tool_choice").path("function").path("name").asText());
        assertEquals(2, session.budget.snapshot().get("modelCalls"));
        session.budget.acquireModel(true);
        session.budget.acquireModel(true);
    }

    @Test
    void truncatedToolStreamIsDiscardedAndRetriedFromCompletedHistory() throws Exception {
        String partial = json.writeValueAsString(Map.of("id", "broken", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("tool_calls", List.of(Map.of("index", 0, "id", "never-execute", "type", "function",
                        "function", Map.of("name", "search_knowledge", "arguments", "{\"query\":\"X"))))))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + partial + "\n\n"));
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Insufficient evidence"), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        assertEquals(2, server.getRequestCount());
        String before = server.takeRequest().getBody().readUtf8();
        String after = server.takeRequest().getBody().readUtf8();
        assertEquals(json.readTree(before).path("messages").get(1), json.readTree(after).path("messages").get(1));
        assertFalse(after.contains("never-execute"));
        verifyNoInteractions(search, reader);
        var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
        assertEquals("FAILED", calls.get(0).get("status"));
        assertEquals("unknown", calls.get(0).get("usageStatus"));
        assertEquals("COMPLETED", calls.get(1).get("status"));
    }

    @Test
    void authenticationFailureIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"unauthorized\",\"type\":\"authentication_error\"}}"));
        assertThrows(RuntimeException.class, () -> factory().run(session));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void mainReadsCandidateBeforeFurtherSearchAndReceivesConciseSource() throws Exception {
        tool("search", "search_knowledge", Map.of("query", "X"));
        tool("premature", "search_knowledge", Map.of("query", "Y"));
        tool("read", "read_source", Map.of("evidence_id", "ev-one"));
        tool("finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "7 ms", "evidenceIds", List.of("ev-one"))), "gaps", List.of(), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        server.takeRequest();
        var forcedRead = json.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("read_source", forcedRead.path("tool_choice").path("function").path("name").asText());
        var feedback = server.takeRequest().getBody().readUtf8();
        assertTrue(feedback.contains("Read a relevant candidate before searching again"));
        verify(search, times(1)).search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt());
        assertEquals(List.of("doc"), session.readingCoverage().get("directlyReadDocumentIds"));
    }

    @Test
    void explicitThinkingAblationChangesOnlyRequestedProviderFlag() throws Exception {
        models.setThinkingForEvaluation(true);
        tool("finish", "finish_research", Map.of("findings", List.of(), "gaps", List.of("Missing source"), "conflicts", List.of()));
        assertNotNull(factory().run(session).result());
        var request = json.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(request.path("enable_thinking").asBoolean());
        assertEquals("fixture-native", request.path("model").asText());
        assertEquals(limits.getMaxOutputTokens(), request.path("max_tokens").asInt());
    }

    private void tool(String id, String name, Map<String, Object> arguments) throws Exception {
        response(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
    }

    private void cached(Map<String, Object> delta, int cachedTokens, int createdTokens) throws Exception {
        String body = json.writeValueAsString(Map.of("id", "provider-" + responses.incrementAndGet(), "object", "chat.completion.chunk", "created", 1,
                "model", "fixture-native", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 2436, "completion_tokens", 20, "total_tokens", 2456, "prompt_tokens_details",
                        Map.of("cached_tokens", cachedTokens, "cache_creation_input_tokens", createdTokens, "cache_type", "ephemeral"))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + body + "\n\ndata: [DONE]\n\n"));
    }

    private void response(Map<String, Object> delta, String finish) throws Exception {
        String body = json.writeValueAsString(Map.of("id", "provider-" + responses.incrementAndGet(), "object", "chat.completion.chunk", "created", 1,
                "model", "fixture-native", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + body + "\n\ndata: [DONE]\n\n"));
    }
}
