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

import com.fasterxml.jackson.databind.*;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.service.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SDK / 本地 HTTP 协议验证；不作为供应商质量成绩。 */
class ResearchWorkerNativeTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockWebServer server;
    private ResearchProperties limits;
    private ResearchAgentFactory factory;
    private ResearchSession session;
    private ResearchRunStore store;
    private KnowledgeSearchService search;
    private SourceReader reader;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private CountDownLatch workerEntered;
    private boolean failBeta, hangWorkers;

    @BeforeEach void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        limits = new ResearchProperties();
        store = mock(ResearchRunStore.class);
        when(store.current(any())).thenReturn(true);
        when(store.subtask(any(), anyString(), anyMap(), anyString(), anyMap())).thenReturn(true);
        search = mock(KnowledgeSearchService.class);
        when(search.validateDocumentScope(anyString(), anyString(), anyList())).thenAnswer(i -> List.of("doc"));
        when(search.search(eq("run"), eq("owner"), startsWith("worker-"), anyString(), anyList(), any(), anyInt()))
                .thenAnswer(i -> List.of(new KnowledgeSearchHit("ev-" + i.getArgument(2), "kb", "doc", "source", "V1", "candidate", false, Map.of(), EvidenceRecord.SourceExtent.CHUNK)));
        reader = mock(SourceReader.class);
        when(reader.read(eq("run"), eq("owner"), anyString(), any(), anyList())).thenAnswer(i -> {
            String id = i.getArgument(2);
            String role = id.endsWith("1") ? "ALPHA" : "BETA";
            return new SourceReadResult(new EvidenceRecord("run", id, "kb", "doc", "source", "V1", List.of("chunk"), "hash",
                    "PRIVATE_" + role + "_HISTORY: identify the next entity; its parameter is 7 ms.", Map.of(), id.substring(3), false, true, EvidenceRecord.SourceExtent.CHUNK), SourceReadResult.SourceState.CURRENT);
        });
        var brief = new ResearchBrief("Compare two independent objects", ResearchBrief.OutputType.REPORT, List.of("preserve units"), List.of("kb"), List.of("doc"));
        var run = new ResearchRun("run", "conv", "client", brief, ResearchRun.Status.RUNNING, 1, 1, Map.of(), null, Map.of(), null, Instant.now(), null);
        session = new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"), new ResearchBudget(limits, Map.of()), new ResearchControl());
        var config = new AIModelProperties();
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString()); provider.setApiKey("fixture-key");
        provider.setEndpoints(Map.of("chat", "/v1/chat/completions")); config.getProviders().put("fixture", provider);
        var candidate = new AIModelProperties.ModelCandidate(); candidate.setId("research-flash"); candidate.setModel("fixture-native");
        candidate.setProvider("fixture"); candidate.setSupportsToolCalling(true);
        var main = new AIModelProperties.ModelCandidate(); main.setId("fixture-main"); main.setModel("fixture-main-native");
        main.setProvider("fixture"); main.setSupportsToolCalling(true);
        limits.setMainModelId(main.getId());
        config.getChat().setCandidates(List.of(candidate, main));
        factory = new ResearchAgentFactory(new ResearchModelFactory(config, limits), limits, search, reader, json, new HeuristicTokenCounterService());
        workerEntered = new CountDownLatch(2);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try { return respond(json.readTree(request.getBody().readUtf8())); }
                catch (Exception error) { return new MockResponse().setResponseCode(500).setBody(error.toString()); }
            }
        });
    }
    @AfterEach void close() throws Exception { factory.close(); server.shutdown(); }

    private MockResponse respond(JsonNode body) throws Exception {
        requests.add(body);
        String task = "main";
        // 首条 user 是任务 JSON；末尾 user 是服务端临时提醒，不参与路由。
        for (JsonNode message : body.path("messages")) if (message.path("role").asText().equals("user")
                && message.path("content").asText().startsWith("{")) {
            JsonNode value = json.readTree(message.path("content").asText());
            if (value.has("task")) task = value.path("task").path("goal").asText();
        }
        int step = counts.computeIfAbsent(task, t -> new AtomicInteger()).incrementAndGet();
        if (task.equals("main")) {
            if (step == 1) return tool("delegate", "conduct_research", Map.of("tasks", List.of(
                    new ResearchTask("alpha", List.of("parameter"), "A concise cited finding", List.of("doc")),
                    new ResearchTask("beta", List.of("parameter"), "A concise cited finding", List.of("doc")))));
            return tool("main-finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Alpha is 7 ms.", "evidenceIds", List.of("ev-worker-1"))), "gaps", List.of(), "conflicts", List.of()));
        }
        if (step == 1) {
            workerEntered.countDown();
            if (!workerEntered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not overlap");
            if (hangWorkers) return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
            if (failBeta && task.equals("beta")) return new MockResponse().setResponseCode(400).setBody("fixture failure");
        }
        String id = task.equals("alpha") ? "ev-worker-1" : "ev-worker-2";
        return switch (step) {
            case 1 -> tool(task + "-search", "search_knowledge", Map.of("query", task, "limit", 1));
            case 2 -> tool(task + "-read", "read_source", Map.of("evidence_id", id));
            case 3 -> tool(task + "-follow", "search_knowledge", Map.of("query", task + " dependent entity parameter", "limit", 1));
            case 4 -> tool(task + "-read2", "read_source", Map.of("evidence_id", id));
            default -> tool(task + "-finish", "finish_research", Map.of("findings", List.of(Map.of("statement", task + " is 7 ms.", "evidenceIds", List.of(id))), "gaps", List.of(), "conflicts", List.of()));
        };
    }
    private MockResponse tool(String id, String name, Object arguments) throws Exception {
        String data = json.writeValueAsString(Map.of("id", "provider-" + id, "object", "chat.completion.chunk", "created", 1,
                "model", "fixture-native", "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls", "delta",
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function",
                Map.of("name", name, "arguments", json.writeValueAsString(arguments))))))), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + data + "\n\ndata: [DONE]\n\n");
    }

    @Test void workersReadAndFollowUpInIsolatedContextsAndMainGetsOnlyCompressedResults() throws Exception {
        var outcome = factory.run(session);
        assertEquals(SubtaskResult.Status.COMPLETED, outcome.result().status());
        assertEquals(2, session.results().size());
        assertTrue(session.delivered().isEmpty(), "Main did not read raw worker text");
        assertEquals(Set.of("ev-worker-1", "ev-worker-2"), session.acceptedEvidenceIds());
        assertEquals(12, session.budget.snapshot().get("modelCalls"));
        assertEquals(12, session.budget.snapshot().get("toolCalls"));
        assertEquals(2, session.budget.snapshot().get("workersCreated"));
        int forcedReads = 0;
        for (JsonNode request : requests) {
            Set<String> names = new HashSet<>(); request.path("tools").forEach(t -> names.add(t.path("function").path("name").asText()));
            String all = request.toString();
            if (names.contains("conduct_research")) {
                assertEquals("fixture-main-native", request.path("model").asText());
                assertEquals(5, names.size());
                assertFalse(all.contains("PRIVATE_ALPHA_HISTORY")); assertFalse(all.contains("PRIVATE_BETA_HISTORY"));
            } else {
                assertEquals("fixture-native", request.path("model").asText());
                if (request.path("tool_choice").path("function").path("name").asText().equals("read_source")) forcedReads++;
                assertEquals(Set.of("search_knowledge", "read_source", "finish_research"), names);
                assertFalse(all.contains("PRIVATE_ALPHA_HISTORY") && all.contains("PRIVATE_BETA_HISTORY"));
            }
        }
        assertEquals(2, forcedReads, "A successful search forces a read before another search");
        verify(search).search("run", "owner", "worker-1", "alpha dependent entity parameter", List.of(), List.of("doc"), 1);
        verify(search).search("run", "owner", "worker-2", "beta dependent entity parameter", List.of(), List.of("doc"), 1);
        var calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
        assertEquals(10, calls.stream().filter(c -> c.get("role").equals("worker")).count());
        assertTrue(calls.stream().filter(c -> c.get("role").equals("worker")).allMatch(c -> c.get("model").equals("fixture-native")));
        assertTrue(calls.stream().filter(c -> c.get("role").equals("main")).allMatch(c -> c.get("model").equals("fixture-main-native")));
        assertTrue(calls.stream().allMatch(c -> c.get("taskId") != null));
    }

    @Test void failedWorkerLeavesSuccessfulFindingsAndSeparateExecutionIssue() {
        failBeta = true;
        var result = factory.run(session).result();
        assertEquals(SubtaskResult.Status.PARTIAL, result.status());
        assertEquals("Alpha is 7 ms.", result.findings().get(0).statement());
        assertTrue(result.executionIssues().stream().anyMatch(g -> g.contains("WORKER_EXECUTION_FAILED")));
        assertTrue(result.gaps().isEmpty());
        assertEquals(8, session.budget.snapshot().get("modelCalls"));
        assertEquals(1, session.results().stream().filter(r -> r.status() == SubtaskResult.Status.FAILED).count());
    }

    @Test void cancellingParentCancelsBothActualSdkHttpSubscriptions() throws Exception {
        hangWorkers = true;
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> factory.run(session));
            assertTrue(workerEntered.await(10, TimeUnit.SECONDS));
            session.control.cancel();
            var error = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<Map<String, Object>> calls;
            do {
                calls = (List<Map<String, Object>>) session.budget.snapshot().get("calls");
                if (calls.stream().filter(c -> "CANCELLED".equals(c.get("status"))).count() == 2) break;
                Thread.sleep(10);
            } while (System.nanoTime() < until);
            assertEquals(3, calls.size());
            assertEquals(2, calls.stream().filter(c -> "CANCELLED".equals(c.get("status")) && "unknown".equals(c.get("usageStatus"))).count());
            assertTrue(session.results().isEmpty());
        } finally { executor.shutdownNow(); }
    }
}
