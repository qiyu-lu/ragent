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
            assertEquals("required", body.path("tool_choice").asText());
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
    void contextTrimmingRetainsGoalAndWholeToolResultPairs() {
        limits.setMaxInputTokens(1024);
        var bounded = new BoundedResearchModel(models.create(), session, limits, new Semaphore(1), json, new HeuristicTokenCounterService());
        var system = Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build();
        var goal = Msg.builder().role(MsgRole.USER).textContent("goal").build();
        var old = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("old").name("search_knowledge").input(Map.of("query", "old")).build()).build();
        var oldResult = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("old", "search_knowledge", TextBlock.builder().text("x ".repeat(8000)).build())).build();
        var latest = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("latest").name("read_source").input(Map.of("evidence_id", "ev-one")).build()).build();
        var latestResult = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("latest", "read_source", TextBlock.builder().text("7 ms").build())).build();
        assertEquals(List.of(system, goal, latest, latestResult), bounded.trim(List.of(system, goal, old, oldResult, latest, latestResult), List.of()));
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
                        "function", Map.of("name", "search_knowledge", "arguments", "{\"query\":\"X\"}"))))))));
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

    private void tool(String id, String name, Map<String, Object> arguments) throws Exception {
        response(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
    }

    private void response(Map<String, Object> delta, String finish) throws Exception {
        String body = json.writeValueAsString(Map.of("id", "provider-" + responses.incrementAndGet(), "object", "chat.completion.chunk", "created", 1,
                "model", "fixture-native", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + body + "\n\ndata: [DONE]\n\n"));
    }
}
