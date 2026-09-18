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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.ResearchRunStore;
import com.nageoffer.ai.ragent.research.service.SourceReader;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 接管续跑的协议证据：由持久事件重建的请求与首次执行同一步的请求逐字节相同；只基于本地 HTTP 桩。 */
class ResearchResumeTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockWebServer server;
    private ResearchProperties limits;
    private ResearchModelFactory models;
    private KnowledgeSearchService search;
    private SourceReader reader;
    private ResearchRun run;
    private int responses;

    /** 按序记录 store.event 收到的事件与每次随事件持久化的 usage，等价于数据库里的事件表与 usage 列。 */
    private static class Recorder {
        final List<ResearchEvent> events = new ArrayList<>();
        final List<Map<String, Object>> usage = new ArrayList<>();
        synchronized boolean add(String task, String type, Map<String, Object> payload, Map<String, Object> snapshot) {
            events.add(new ResearchEvent(events.size() + 1, task, type, type, payload, Instant.now()));
            usage.add(snapshot);
            return true;
        }
    }

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
        var brief = new ResearchBrief("Find X, then research the dependent entity", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb"));
        run = new ResearchRun("run", "conversation", "request", brief, ResearchRun.Status.RUNNING, 1, 1, Map.of(), null, Map.of(), null, Instant.now(), null);
        search = mock(KnowledgeSearchService.class);
        reader = mock(SourceReader.class);
        when(search.search(eq("run"), eq("owner"), eq("main"), anyString(), anyList(), any(), anyInt()))
                .thenAnswer(i -> List.of(hit(i.getArgument(3).equals("X") ? "ev-one" : "ev-two")));
        when(reader.read(eq("run"), eq("owner"), anyString(), any()))
                .thenAnswer(i -> new SourceReadResult(evidence(i.getArgument(2)), SourceReadResult.SourceState.CURRENT));
    }

    @AfterEach void close() throws Exception { server.shutdown(); }

    private ResearchSession session(Recorder recorder, Map<String, Object> usage) {
        var store = mock(ResearchRunStore.class);
        when(store.current(any())).thenReturn(true);
        when(store.event(any(), anyString(), anyString(), anyMap(), anyMap()))
                .thenAnswer(i -> recorder.add("main", i.getArgument(1), i.getArgument(3), i.getArgument(4)));
        return new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"), new ResearchBudget(limits, usage), new ResearchControl());
    }
    private ResearchAgentFactory factory() { return new ResearchAgentFactory(models, limits, search, reader, json, new HeuristicTokenCounterService(), false); }
    private KnowledgeSearchHit hit(String id) { return new KnowledgeSearchHit(id, "kb", "doc", "paper", "V1", "candidate", false, Map.of(), EvidenceRecord.SourceExtent.CHUNK); }
    private EvidenceRecord evidence(String id) { return new EvidenceRecord("run", id, "kb", "doc", "paper", "V1", List.of("chunk"), "hash", "X identifies Mercury; the dependent source gives a parameter of 7 ms.", Map.of(), "main", false, true, EvidenceRecord.SourceExtent.CHUNK); }

    private void script(int from) throws Exception {
        var steps = List.<Object[]>of(
                new Object[]{"search-1", "search_knowledge", Map.of("query", "X", "limit", 1)},
                new Object[]{"read-1", "read_source", Map.of("evidence_id", "ev-one", "mode", "CHUNK")},
                new Object[]{"search-2", "search_knowledge", Map.of("query", "Mercury parameter", "limit", 1)},
                new Object[]{"read-2", "read_source", Map.of("evidence_id", "ev-two")},
                new Object[]{"finish", "finish_research", Map.of("findings", List.of(Map.of("statement", "Parameter is 7 ms.", "evidenceIds", List.of("ev-one", "ev-two"))), "gaps", List.of(), "conflicts", List.of())});
        for (var step : steps.subList(from, steps.size())) tool((String) step[0], (String) step[1], (Map<String, Object>) step[2]);
    }

    /** 首次执行完整跑完，返回每次模型请求体与事件记录。 */
    private List<String> original(Recorder recorder) throws Exception {
        script(0);
        var outcome = factory().run(session(recorder, Map.of()));
        assertEquals(List.of("ev-one", "ev-two"), outcome.result().findings().get(0).evidenceIds());
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 5; i++) bodies.add(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        return bodies;
    }

    /** 截取到第 cut 个事件（含），追加接管时的 RUN_STARTED，得到重建的历史与当时已持久化的 usage。 */
    private Object[] crashAfter(Recorder recorder, int cut) {
        var events = new ArrayList<>(recorder.events.subList(0, cut + 1));
        events.add(new ResearchEvent(cut + 2, "main", "RUN_STARTED", "", Map.of("epoch", 2, "takeover", 1), Instant.now()));
        return new Object[]{ResearchHistory.from(events), recorder.usage.get(cut)};
    }
    private int index(Recorder recorder, String type, String callId) {
        for (int i = 0; i < recorder.events.size(); i++) {
            var e = recorder.events.get(i);
            if (e.type().equals(type) && callId.equals(e.payload().get("toolCallId"))) return i;
        }
        throw new AssertionError(type + " " + callId);
    }

    private ResearchSession resumed(Recorder recorder, int cut) {
        var crash = crashAfter(recorder, cut);
        var session = session(new Recorder(), (Map<String, Object>) crash[1]);
        session.resume((ResearchHistory) crash[0], this::evidence, json);
        return session;
    }

    @Test
    void resumingAfterACompletedStepSendsTheSameNextRequestAndRedoesNothing() throws Exception {
        var recorder = new Recorder();
        var bodies = original(recorder);
        var session = resumed(recorder, index(recorder, "TOOL_ENDED", "read-1"));
        assertEquals(Set.of("ev-one"), session.delivered().keySet());
        script(2);
        clearInvocations(search, reader);
        var outcome = factory().run(session);
        assertEquals(List.of("ev-one", "ev-two"), outcome.result().findings().get(0).evidenceIds());
        assertEquals(bodies.get(2), server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8(),
                "the first request after takeover must be byte-identical to the original third request");
        assertEquals(3, server.getRequestCount() - 5);
        verify(search, times(1)).search(anyString(), anyString(), anyString(), eq("Mercury parameter"), anyList(), any(), anyInt());
        verify(search, never()).search(anyString(), anyString(), anyString(), eq("X"), anyList(), any(), anyInt());
        verify(reader, never()).read(anyString(), anyString(), eq("ev-one"), any());
    }

    @Test
    void anInFlightToolCallIsDroppedAndOnlyTheModelCallThatProducedItIsRepeated() throws Exception {
        var recorder = new Recorder();
        var bodies = original(recorder);
        var session = resumed(recorder, index(recorder, "TOOL_STARTED", "search-2"));
        assertEquals(1, ((ResearchHistory) crashAfter(recorder, index(recorder, "TOOL_STARTED", "search-2"))[0]).droppedToolCalls());
        script(2);
        factory().run(session);
        JsonNode original = json.readTree(bodies.get(2));
        JsonNode repeated = json.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
        // 在途的那次调用已计入预算，所以只有末尾临时提醒里的剩余次数少一次；缓存前缀逐字节相同。
        var reminder = original.path("messages").size() - 1;
        ((com.fasterxml.jackson.databind.node.ArrayNode) original.path("messages")).remove(reminder);
        ((com.fasterxml.jackson.databind.node.ArrayNode) repeated.path("messages")).remove(reminder);
        assertEquals(original, repeated);
        assertEquals(3, server.getRequestCount() - 5, "one model call is repeated: the one whose tool call never finished");
    }

    @Test
    void aFinishedResearchIsNotSentToTheModelAgain() throws Exception {
        var recorder = new Recorder();
        original(recorder);
        int concluded = 0;
        for (int i = 0; i < recorder.events.size(); i++) if (recorder.events.get(i).type().equals("RESEARCH_CONCLUDED")) concluded = i;
        var session = resumed(recorder, concluded);
        var outcome = factory().run(session);
        assertEquals("Parameter is 7 ms.", outcome.result().findings().get(0).statement());
        assertEquals(5, server.getRequestCount());
    }

    @Test
    void handoverStopsAtTheNextStepBoundaryWithoutInterruptingTheCurrentOne() throws Exception {
        script(0);
        var recorder = new Recorder() {
            ResearchSession session;
            @Override synchronized boolean add(String task, String type, Map<String, Object> payload, Map<String, Object> snapshot) {
                // 第一次检索进行中收到停机：这一步照常结束，下一次模型调用开始前停下。
                if (type.equals("TOOL_STARTED")) session.control.requestHandover();
                return super.add(task, type, payload, snapshot);
            }
        };
        var session = session(recorder, Map.of());
        recorder.session = session;
        var error = assertThrows(RuntimeException.class, () -> factory().run(session));
        Throwable root = error;
        while (!(root instanceof ResearchControl.HandoverRequested) && root.getCause() != null) root = root.getCause();
        assertInstanceOf(ResearchControl.HandoverRequested.class, root);
        assertEquals(1, server.getRequestCount());
        assertEquals(List.of("RESEARCH_STARTED", "MODEL_STARTED", "MODEL_ENDED", "TOOL_STARTED", "RETRIEVAL_PHASE", "TOOL_ENDED"),
                recorder.events.stream().map(ResearchEvent::type).toList());
        assertEquals(1, ResearchHistory.from(recorder.events).steps().size(), "the finished step is what the next owner resumes from");
    }

    @Test
    void shutdownFailuresAreRecognisedWhereverTheyAreWrapped() {
        assertTrue(ResearchControl.shuttingDown(new IllegalStateException(new RuntimeException(new ResearchControl.HandoverRequested()))));
        assertTrue(ResearchControl.shuttingDown(new RuntimeException(new io.agentscope.core.shutdown.AgentShuttingDownException())));
        assertFalse(ResearchControl.shuttingDown(new java.util.concurrent.CancellationException("RESEARCH_CANCELLED")),
                "a user cancellation is not a shutdown");
    }

    @Test
    void historyKeepsCompletedCallsOfAPartlyFinishedTurnAndStartsAfterTheLatestUserInput() {
        List<ResearchEvent> events = new ArrayList<>();
        java.util.function.BiConsumer<String, Map<String, Object>> add = (type, payload) ->
                events.add(new ResearchEvent(events.size() + 1, "main", type, type, payload, Instant.now()));
        add.accept("RESEARCH_STARTED", Map.of("request", "before input"));
        add.accept("TOOL_STARTED", Map.of("toolCallId", "old", "tool", "search_knowledge", "arguments", Map.of("query", "old"), "batch", List.of("old")));
        add.accept("TOOL_ENDED", Map.of("toolCallId", "old", "tool", "search_knowledge", "status", "SUCCESS", "output", "[]"));
        add.accept("INPUT_RECEIVED", Map.of("input", "A"));
        add.accept("RUN_STARTED", Map.of("epoch", 2));
        add.accept("RESEARCH_STARTED", Map.of("request", "after input"));
        add.accept("TOOL_STARTED", Map.of("toolCallId", "a", "tool", "search_knowledge", "arguments", Map.of("query", "a"), "batch", List.of("a", "b")));
        add.accept("TOOL_ENDED", Map.of("toolCallId", "a", "tool", "search_knowledge", "status", "SUCCESS", "output", "[]"));
        add.accept("TOOL_STARTED", Map.of("toolCallId", "b", "tool", "search_knowledge", "arguments", Map.of("query", "b"), "batch", List.of("a", "b")));
        events.add(new ResearchEvent(events.size() + 1, "worker-1", "TOOL_ENDED", "", Map.of("toolCallId", "b", "status", "SUCCESS", "output", "worker"), Instant.now()));
        add.accept("RUN_STARTED", Map.of("epoch", 3, "takeover", 1));
        var history = ResearchHistory.from(events);
        assertEquals("after input", history.request());
        assertEquals(List.of("a"), history.steps().stream().map(ResearchHistory.Step::toolCallId).toList());
        assertEquals(1, history.droppedToolCalls());
        var messages = history.messages("research-main");
        assertEquals(3, messages.size());
        assertEquals(1, messages.get(1).getContent().size(), "the assistant turn keeps only the finished call");
        assertNull(ResearchHistory.from(events.subList(0, 3).stream().map(e -> new ResearchEvent(e.sequence(), e.taskId(), e.type(), e.summary(),
                e.type().equals("RESEARCH_STARTED") ? Map.of() : e.payload(), e.createdAt())).toList()), "legacy events without the request start over");
    }

    private void tool(String id, String name, Map<String, Object> arguments) throws Exception {
        String body = json.writeValueAsString(Map.of("id", "provider-" + (++responses), "object", "chat.completion.chunk", "created", 1,
                "model", "fixture-native", "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "tool_calls",
                        List.of(Map.of("index", 0, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))),
                        "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + body + "\n\ndata: [DONE]\n\n"));
    }
}
