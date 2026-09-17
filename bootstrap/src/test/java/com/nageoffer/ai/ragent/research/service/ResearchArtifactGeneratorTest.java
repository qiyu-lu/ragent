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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.runtime.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SDK/本地 HTTP 证明生成协议和取消，不代表供应商质量评分。 */
class ResearchArtifactGeneratorTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockWebServer server;
    private ResearchProperties limits;
    private ResearchModelFactory models;
    private ResearchEvidenceStore evidence;
    private ResearchRunStore store;
    private ResearchSession session;
    private ResearchArtifactGenerator generator;

    @BeforeEach void setup() throws Exception {
        server = new MockWebServer(); server.start();
        limits = new ResearchProperties();
        var config = new AIModelProperties();
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString()); provider.setApiKey("fixture-key");
        provider.setEndpoints(Map.of("chat", "/v1/chat/completions")); config.getProviders().put("fixture", provider);
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("research-flash"); candidate.setModel("fixture-artifact"); candidate.setProvider("fixture"); candidate.setSupportsToolCalling(true);
        config.getChat().setCandidates(List.of(candidate)); models = new ResearchModelFactory(config, limits);
        store = mock(ResearchRunStore.class); when(store.current(any())).thenReturn(true);
        evidence = mock(ResearchEvidenceStore.class);
        when(evidence.find(eq("run"), eq("owner"), anyString())).thenAnswer(i -> new EvidenceSnapshot(item(i.getArgument(2)), "snapshot", "metadata"));
        session = session(ResearchBrief.OutputType.REPORT, Map.of());
        session.delivered(item("ev-a")); session.delivered(item("ev-b"));
        generator = new ResearchArtifactGenerator(models, limits, evidence, new PlanDraftValidator(), json, new HeuristicTokenCounterService());
    }
    private ResearchSession session(ResearchBrief.OutputType type, Map<String, Object> usage) {
        var brief = new ResearchBrief("比较并整理步骤", type, List.of("用户预算 2 小时"), List.of("kb"));
        var run = new ResearchRun("run", "conversation", "request", brief, ResearchRun.Status.RUNNING, 1, 1, Map.of(), null, usage, null, Instant.now(), null);
        return new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"), new ResearchBudget(limits, usage), new ResearchControl());
    }
    private EvidenceRecord item(String id) { return new EvidenceRecord("run", id, "kb", id.equals("ev-a") ? "doc-a" : "doc-b", id + ".md", "v1", List.of(id + "-chunk"), "hash", "真实参数 7 ms，前提和限制。", Map.of("sectionPath", "Methods"), "main", false, true, EvidenceRecord.SourceExtent.CHUNK); }
    private SubtaskResult result() { return new SubtaskResult("main", List.of(new SubtaskResult.Finding("参数 7 ms", List.of("ev-a"))), List.of("保留的资料缺口"), List.of("来源口径不同"), SubtaskResult.Status.COMPLETED); }
    private Map<String, Object> report(String id) { return Map.of("title", "比较报告", "sections", List.of(Map.of("heading", "条件", "text", "参数是 7 ms", "evidenceIds", List.of(id, "ev-b"))), "gaps", List.of()); }
    private void response(Object body) throws Exception {
        String content = body instanceof String text ? text : json.writeValueAsString(body);
        var delta = Map.of("id", "response", "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content), "finish_reason", "stop")), "usage", Map.of("prompt_tokens", 50, "completion_tokens", 25, "total_tokens", 75));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + json.writeValueAsString(delta) + "\n\ndata: [DONE]\n\n"));
    }
    @AfterEach void close() throws Exception { server.shutdown(); }

    @Test void reportUsesTwoDocumentsAndServerCitationMappingWithProviderUsage() throws Exception {
        response(report("ev-a"));
        var artifact = generator.generate(session, result());
        assertEquals(List.of("doc-a", "doc-b"), artifact.citations().stream().map(ResearchArtifact.Citation::docId).toList());
        assertTrue(artifact.markdown().contains("[1](#cite-1)[2](#cite-2)"));
        assertEquals("user_input", artifact.userConstraints().get(0).source());
        assertEquals(result().gaps(), artifact.gaps()); assertEquals(result().conflicts(), artifact.conflicts());
        var request = json.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(request.has("tools"));
        assertFalse(request.toString().contains("Server budget reminder"));
        var call = ((List<Map<String, Object>>) session.budget.snapshot().get("calls")).get(0);
        assertEquals("finalization", call.get("role")); assertEquals("provider", call.get("usageStatus"));
    }

    @Test void finalInputOmitsCorpusMetadataWhilePublicationKeepsSourceIdentity() throws Exception {
        var original = item("ev-a");
        var record = new EvidenceRecord(original.runId(), original.evidenceId(), original.kbId(), original.docId(),
                original.documentName(), "qasper-v0.3", original.chunkIds(), original.contentHash(), original.text(),
                Map.of("dataset", "qasper", "split", "validation", "section_path", List.of("Experiment Setup"),
                        "sheetName", "参数表", "source_paragraph_id", "original-paragraph"), original.retrievedByTaskId(),
                original.truncated(), original.read(), original.sourceExtent());
        when(evidence.find("run", "owner", "ev-a")).thenReturn(new EvidenceSnapshot(record, "snapshot", "metadata"));
        response(report("ev-a"));
        var artifact = generator.generate(session, result());
        var request = json.readTree(server.takeRequest().getBody().readUtf8());
        var input = json.readTree(request.path("messages").get(1).path("content").asText());
        var provided = input.path("evidence").get(0);
        assertEquals(record.text(), provided.path("text").asText());
        assertEquals("ev-a", provided.path("evidenceId").asText());
        assertEquals("Experiment Setup", provided.path("sourceContext").path("section_path").get(0).asText());
        assertEquals("参数表", provided.path("sourceContext").path("sheetName").asText());
        assertFalse(provided.toString().contains("qasper"));
        assertFalse(provided.has("sourceLocation"));
        assertEquals("qasper-v0.3", artifact.citations().get(0).documentVersion());
        assertEquals("qasper", artifact.citations().get(0).sourceLocation().get("dataset"));
        assertEquals("original-paragraph", artifact.citations().get(0).sourceLocation().get("source_paragraph_id"));
    }

    @Test void planRequiresEachParameterEvidenceAndPreservesMissingValuesAndUserConstraints() throws Exception {
        session = session(ResearchBrief.OutputType.PLAN, Map.of()); session.delivered(item("ev-a")); session.delivered(item("ev-b"));
        Map<String, Object> missing = new HashMap<>(); missing.put("name", "温度"); missing.put("value", null); missing.put("unit", null); missing.put("evidenceIds", List.of());
        var plan = Map.of("prerequisites", List.of(Map.of("text", "前置条件", "evidenceIds", List.of("ev-a"))),
                "steps", List.of(Map.of("order", 1, "action", "按资料准备", "evidenceIds", List.of("ev-b"), "parameters", List.of(missing,
                        Map.of("name", "延迟", "value", "7", "unit", "ms", "evidenceIds", List.of("ev-b"))))),
                "resources", List.of(), "cautions", List.of(), "pendingItems", List.of());
        response(Map.of("title", "计划草稿", "sections", List.of(), "plan", plan, "gaps", List.of()));
        var artifact = generator.generate(session, result());
        assertNull(artifact.plan().steps().get(0).parameters().get(0).value());
        assertEquals("7", artifact.plan().steps().get(0).parameters().get(1).value());
        assertTrue(artifact.plan().pendingItems().get(0).contains("温度"));
        assertEquals(2, artifact.citations().size());
    }

    @Test void invalidReferenceIsRepairedOnceWithinReservedCalls() throws Exception {
        session = session(ResearchBrief.OutputType.REPORT, Map.of("modelCalls", 14)); session.delivered(item("ev-a")); session.delivered(item("ev-b"));
        response(report("unread-or-foreign")); response(report("ev-a"));
        var artifact = generator.generate(session, result());
        assertEquals(16, session.budget.snapshot().get("modelCalls")); assertEquals(2, artifact.citations().size());
        server.takeRequest(); var repair = json.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(repair.toString().contains("REFERENCE_MUST_BELONG"));
        assertTrue(repair.toString().contains("sections[0].evidenceIds"));
        assertTrue(repair.toString().contains("unknown evidenceIds: [unread-or-foreign]"));
        assertTrue(repair.toString().contains("Allowed evidenceIds:"));
        assertEquals(0, session.budget.explorationCallsRemaining());
        assertThrows(ResearchBudget.Exhausted.class, () -> session.budget.acquireModel(true));
    }

    @Test void uncitedComparisonGapCanBeMovedOutOfSectionsOnTheSingleRepair() throws Exception {
        var initial = Map.of("title", "比较报告", "sections", List.of(
                Map.of("heading", "论文 A", "text", "参数是 7 ms", "evidenceIds", List.of("ev-a")),
                Map.of("heading", "论文 B", "text", "论文 B 的资料不可用", "evidenceIds", List.of())), "gaps", List.of());
        var corrected = Map.of("title", "比较报告", "sections", List.of(
                Map.of("heading", "论文 A", "text", "参数是 7 ms", "evidenceIds", List.of("ev-a"))),
                "gaps", List.of("论文 B 的资料不可用，无法比较"));
        response(initial); response(corrected);
        var artifact = generator.generate(session, result());
        assertEquals(1, artifact.sections().size()); assertEquals(1, artifact.citations().size());
        assertTrue(artifact.gaps().contains("论文 B 的资料不可用，无法比较"));
        assertEquals(2, server.getRequestCount());
        server.takeRequest(); var repair = json.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(repair.toString().contains("EVIDENCE_IDS_REQUIRED at sections[1].evidenceIds"));
        assertTrue(repair.toString().contains("remove uncited sections/items"));
        verify(store).event(any(), eq("FINALIZATION_VALIDATION_FAILED"), anyString(), argThat(data ->
                data.get("rawOutput").toString().contains("论文 B") && Boolean.FALSE.equals(data.get("rawOutputTruncated"))), anyMap());
    }

    @Test void parameterReferenceFailureIdentifiesItsPathAndBoundsInternalInvalidOutput() throws Exception {
        session = session(ResearchBrief.OutputType.PLAN, Map.of()); session.delivered(item("ev-a"));
        var plan = Map.of("prerequisites", List.of(), "resources", List.of(), "cautions", List.of(), "pendingItems", List.of(),
                "steps", List.of(Map.of("order", 1, "action", "按资料准备", "evidenceIds", List.of("ev-a"), "parameters", List.of(
                        Map.of("name", "延迟", "value", "7", "unit", "ms", "evidenceIds", List.of("foreign"))))));
        String invalid = json.writeValueAsString(Map.of("title", "计划", "sections", List.of(), "plan", plan, "gaps", List.of())) + " ".repeat(20000);
        response(invalid); response(invalid);
        assertThrows(IllegalStateException.class, () -> generator.generate(session, result()));
        assertEquals(2, server.getRequestCount());
        verify(store, times(2)).event(any(), eq("FINALIZATION_VALIDATION_FAILED"), anyString(), argThat(data ->
                data.get("reason").toString().contains("plan.steps[0].parameters[0].evidenceIds")
                        && data.get("rawOutput").toString().length() <= 16000 && Boolean.TRUE.equals(data.get("rawOutputTruncated"))), anyMap());
        verify(store, never()).finish(any(), any(), anyMap(), anyMap(), anyMap(), any());
    }

    @Test void twoInvalidJsonResponsesFailWithoutPublishingArtifact() throws Exception {
        response("{bad json"); response("{still bad");
        assertThrows(IllegalStateException.class, () -> generator.generate(session, result()));
        assertEquals(2, server.getRequestCount());
        verify(store, times(2)).event(any(), eq("FINALIZATION_VALIDATION_FAILED"), anyString(), anyMap(), anyMap());
        verify(store, never()).finish(any(), any(), anyMap(), anyMap(), anyMap(), any());
    }

    @Test void cancellationClosesFinalizationSubscriptionAndReleasesSharedQuota() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> generator.generate(session, result()));
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); session.control.cancel();
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals(limits.getMaxConcurrentModelCalls(), models.quota().availablePermits());
            var call = ((List<Map<String, Object>>) session.budget.snapshot().get("calls")).get(0);
            assertEquals("CANCELLED", call.get("status")); assertEquals("unknown", call.get("usageStatus"));
        } finally { pool.shutdownNow(); }
    }

    @Test void candidateNeverReadCannotEnterGenerationEvenIfItExistsInDatabase() throws Exception {
        var unread = item("ev-a").withReadText("candidate", false);
        unread = new EvidenceRecord(unread.runId(), unread.evidenceId(), unread.kbId(), unread.docId(), unread.documentName(), unread.documentVersion(), unread.chunkIds(), unread.contentHash(), unread.text(), unread.sourceLocation(), "main", false, false, unread.sourceExtent());
        when(evidence.find("run", "owner", "ev-a")).thenReturn(new EvidenceSnapshot(unread, "snapshot", "metadata"));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(session, result()));
        assertEquals(0, server.getRequestCount());
    }

    @Test void failedFinalizationHttpCallIsCountedWithoutRetryOrInventedUsageAndReleasesQuota() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("fixture rejected"));
        assertThrows(RuntimeException.class, () -> generator.generate(session, result()));
        assertEquals(1, server.getRequestCount());
        assertEquals(1, session.budget.snapshot().get("modelCalls"));
        var call = ((List<Map<String, Object>>) session.budget.snapshot().get("calls")).get(0);
        assertEquals("FAILED", call.get("status")); assertEquals("unknown", call.get("usageStatus"));
        assertEquals(limits.getMaxConcurrentModelCalls(), models.quota().availablePermits());
    }
}
