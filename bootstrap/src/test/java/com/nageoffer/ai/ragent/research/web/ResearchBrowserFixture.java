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

package com.nageoffer.ai.ragent.research.web;

import com.fasterxml.jackson.databind.*;
import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.web.*;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.token.HeuristicTokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.controller.ResearchRunController;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.runtime.*;
import com.nageoffer.ai.ragent.research.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import okhttp3.mockwebserver.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 浏览器 fixture：真实研究接口/SDK/隔离 PG；登录、检索、阅读、普通问答和模型响应受控。仅在 test-classes。 */
@Configuration
@ImportAutoConfiguration({
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class,
        org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class
})
@Import(GlobalExceptionHandler.class)
public class ResearchBrowserFixture {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    static final String OWNER = "fixture-owner";
    public static void main(String[] args) {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/research_p3_[a-zA-Z0-9_]+")) throw new IllegalArgumentException("Disposable local database required");
        SpringApplication.run(ResearchBrowserFixture.class, args);
    }
    @Bean DriverManagerDataSource dataSource() { return new DriverManagerDataSource(System.getenv("RESEARCH_P3_TEST_URL"), System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD")); }
    @Bean ResearchProperties limits() { return new ResearchProperties(); }
    @Bean ResearchRunStore store(JdbcTemplate jdbc, DriverManagerDataSource data) { return new ResearchRunStore(jdbc, JSON, new DataSourceTransactionManager(data)); }
    @Bean ResearchEvidenceStore evidence(JdbcTemplate jdbc) { return new ResearchEvidenceStore(jdbc, JSON); }
    @Bean ResearchModelFactory models(ResearchProperties limits, MockWebServer server) {
        var config = new AIModelProperties();
        var provider = new AIModelProperties.ProviderConfig(); provider.setUrl(server.url("/").toString()); provider.setApiKey("fixture-only"); provider.setEndpoints(Map.of("chat", "/v1/chat/completions"));
        config.getProviders().put("fixture", provider);
        var candidate = new AIModelProperties.ModelCandidate(); candidate.setId("research-flash"); candidate.setModel("fixture-local"); candidate.setProvider("fixture"); candidate.setSupportsToolCalling(true);
        config.getChat().setCandidates(List.of(candidate)); return new ResearchModelFactory(config, limits);
    }
    @Bean(destroyMethod = "shutdown") MockWebServer modelServer() throws Exception {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                MODEL_CALLS.incrementAndGet();
                try { return modelResponse(JSON.readTree(request.getBody().readUtf8())); }
                catch (Exception error) { error.printStackTrace(); return new MockResponse().setResponseCode(500).setBody("fixture-response-error"); }
            }
        }); server.start(); return server;
    }
    @Bean KnowledgeSearchService search(ResearchEvidenceStore evidence) {
        var search = mock(KnowledgeSearchService.class);
        when(search.validateDocumentScope(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(2) == null ? List.of() : i.getArgument(2));
        when(search.search(anyString(), anyString(), anyString(), anyString(), anyList(), any(), anyInt())).thenAnswer(i -> {
            String run = i.getArgument(0), owner = i.getArgument(1), task = i.getArgument(2), query = i.getArgument(3);
            String doc = query.contains("doc-b") ? "doc-b" : "doc-a";
            var record = new EvidenceRecord(run, "ev-" + run + "-" + doc, "fixture-kb", doc, doc + ".md", "v1", List.of(doc + "-chunk"), "fixture-hash",
                    doc.equals("doc-a") ? "准备前必须确认资料范围。" : "操作步骤：配置延迟参数为 7 ms。未提供温度要求。",
                    Map.of("sectionPath", doc.equals("doc-a") ? "前置条件" : "操作方法"), task, false, false, EvidenceRecord.SourceExtent.CHUNK);
            evidence.save(owner, new EvidenceSnapshot(record, record.text(), "fixture-metadata"));
            return List.of(new KnowledgeSearchHit(record.evidenceId(), record.kbId(), doc, record.documentName(), "v1", record.text(), false, record.sourceLocation(), record.sourceExtent()));
        }); return search;
    }
    @Bean SourceReader reader(ResearchEvidenceStore evidence) {
        var reader = mock(SourceReader.class);
        when(reader.read(anyString(), anyString(), anyString(), any())).thenAnswer(i -> new SourceReadResult(evidence.markRead(i.getArgument(1), evidence.find(i.getArgument(0), i.getArgument(1), i.getArgument(2))), SourceReadResult.SourceState.CURRENT));
        when(reader.read(anyString(), anyString(), anyString(), any(), anyList())).thenAnswer(i -> new SourceReadResult(evidence.markRead(i.getArgument(1), evidence.find(i.getArgument(0), i.getArgument(1), i.getArgument(2))), SourceReadResult.SourceState.CURRENT));
        return reader;
    }
    @Bean ResearchAgentFactory runner(ResearchModelFactory models, ResearchProperties limits, KnowledgeSearchService search, SourceReader reader) { return new ResearchAgentFactory(models, limits, search, reader, JSON, new HeuristicTokenCounterService()); }
    @Bean ResearchArtifactGenerator generator(ResearchModelFactory models, ResearchProperties limits, ResearchEvidenceStore evidence) { return new ResearchArtifactGenerator(models, limits, evidence, new PlanDraftValidator(), JSON, new HeuristicTokenCounterService()); }
    @Bean ResearchCompletionService completion(ResearchRunStore store, ResearchArtifactGenerator generator) { return new ResearchCompletionService(store, generator, JSON); }
    @Bean ResearchRunService service(ResearchRunStore store, ResearchAgentFactory runner, ResearchProperties limits, JdbcTemplate jdbc, ResearchCompletionService completion, ResearchEvidenceStore evidence) { return new ResearchRunService(store, runner, limits, jdbc, JSON, completion, evidence); }
    @Bean ResearchEventStreamService streams(ResearchRunStore store) { return new ResearchEventStreamService(store); }
    @Bean ResearchRunController controller(ResearchRunService service, ResearchEventStreamService streams) { return new ResearchRunController(service, streams); }
    @Bean FilterRegistrationBean<OncePerRequestFilter> userFilter() {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                UserContext.set(LoginUser.builder().userId("fixture-foreign".equals(request.getHeader("Authorization")) ? "fixture-foreign" : OWNER).username("browser fixture").role("user").build());
                try { chain.doFilter(request, response); } finally { UserContext.clear(); }
            }
        }; return new FilterRegistrationBean<>(filter);
    }

    static MockResponse modelResponse(JsonNode request) throws Exception {
        JsonNode input = null;
        StringBuilder observations = new StringBuilder();
        for (JsonNode message : request.path("messages")) {
            if (message.path("role").asText().equals("user") && input == null) input = JSON.readTree(message.path("content").asText());
            if (message.path("role").asText().equals("tool")) observations.append(message.path("content").asText());
        }
        boolean worker = input.has("task");
        List<String> ids = new ArrayList<>();
        var matcher = Pattern.compile("ev-[a-zA-Z0-9-]+").matcher(observations);
        while (matcher.find()) if (!ids.contains(matcher.group())) ids.add(matcher.group());
        Object delta;
        if (!request.has("tools")) {
            List<String> sourceIds = new ArrayList<>(); input.path("evidence").forEach(e -> sourceIds.add(e.path("evidenceId").asText()));
            sourceIds.sort(String::compareTo);
            Map<String, Object> payload = new LinkedHashMap<>(); payload.put("title", input.path("brief").path("outputType").asText().equals("PLAN") ? "资料准备计划草稿" : "跨文档条件与操作比较"); payload.put("gaps", List.of("资料未提供温度要求"));
            if (input.path("brief").path("outputType").asText().equals("PLAN")) {
                Map<String, Object> missing = new LinkedHashMap<>(); missing.put("name", "温度"); missing.put("value", null); missing.put("unit", null); missing.put("evidenceIds", List.of());
                payload.put("sections", List.of()); payload.put("plan", Map.of("prerequisites", List.of(Map.of("text", "确认资料范围", "evidenceIds", List.of(sourceIds.get(0)))),
                        "steps", List.of(Map.of("order", 1, "action", "配置延迟参数", "evidenceIds", List.of(sourceIds.get(1)), "parameters", List.of(Map.of("name", "延迟", "value", "7", "unit", "ms", "evidenceIds", List.of(sourceIds.get(1))), missing))), "resources", List.of(), "cautions", List.of(), "pendingItems", List.of()));
            } else { payload.put("sections", List.of(Map.of("heading", "条件与操作", "text", "资料 A 要求确认范围，资料 B 指定延迟为 7 ms。", "evidenceIds", sourceIds))); payload.put("plan", null); }
            delta = Map.of("role", "assistant", "content", JSON.writeValueAsString(payload));
        } else {
            String goal = worker ? input.path("task").path("goal").asText() : input.path("brief").path("goal").asText();
            String tool; Object arguments;
            if (worker && goal.contains("cancel")) return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
            if (!worker && goal.contains("waiting") && !input.path("savedResearchState").has("latestUserInput")) { tool = "ask_user"; arguments = Map.of("question", "本次计划的预算时限是多少？"); }
            else if (!worker && observations.isEmpty()) { tool = "conduct_research"; arguments = Map.of("tasks", List.of(
                    Map.of("goal", (goal.contains("slow") ? "slow-" : goal.contains("cancel") ? "cancel-" : "") + "doc-a 条件", "dimensions", List.of("前置条件"), "expectedOutput", "带引用发现", "documentIds", List.of("doc-a")),
                    Map.of("goal", (goal.contains("slow") ? "slow-" : goal.contains("cancel") ? "cancel-" : "") + "doc-b 操作", "dimensions", List.of("参数"), "expectedOutput", "带引用发现", "documentIds", List.of("doc-b")))); }
            else if (worker && observations.isEmpty()) { tool = "search_knowledge"; arguments = Map.of("query", goal, "limit", 1); }
            else if (worker && !observations.toString().contains("sourceState")) { tool = "read_source"; arguments = Map.of("evidence_id", ids.get(0)); }
            else { tool = "finish_research"; arguments = Map.of("findings", List.of(Map.of("statement", worker && goal.contains("doc-a") ? "确认资料范围" : "延迟参数 7 ms", "evidenceIds", ids)), "gaps", List.of("资料未提供温度要求"), "conflicts", List.of()); }
            delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", UUID.randomUUID().toString(), "type", "function", "function", Map.of("name", tool, "arguments", JSON.writeValueAsString(arguments)))));
        }
        var frame = Map.of("id", "fixture", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", request.has("tools") ? "tool_calls" : "stop")), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 30, "total_tokens", 130));
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBodyDelay(350, TimeUnit.MILLISECONDS).setBody("data: " + JSON.writeValueAsString(frame) + "\n\ndata: [DONE]\n\n");
    }

    @RestController static class FixtureController {
        final JdbcTemplate jdbc;
        FixtureController(JdbcTemplate jdbc) { this.jdbc = jdbc; jdbc.update("INSERT INTO t_knowledge_base(id,name,embedding_model,collection_name,created_by) VALUES ('fixture-kb','双文档测试资料','fixture','fixture','fixture')");
            for (String doc : List.of("doc-a", "doc-b")) jdbc.update("INSERT INTO t_knowledge_document(id,kb_id,doc_name,document_key,file_url,file_type,created_by) VALUES (?,'fixture-kb',?,?,?,'md','fixture')", doc, doc + ".md", doc, "fixture:" + doc);
        }
        @GetMapping("/user/me") Object user() { return Results.success(Map.of("userId", OWNER, "username", "browser fixture", "role", "user")); }
        @GetMapping("/conversations") Object conversations() { return Results.success(jdbc.query("SELECT conversation_id,title,last_time FROM t_conversation WHERE user_id=? AND deleted=0 ORDER BY last_time DESC", (rs, row) -> Map.of("conversationId", rs.getString(1), "title", rs.getString(2), "lastTime", rs.getTimestamp(3).toInstant().toString()), OWNER)); }
        @GetMapping("/conversations/{id}/messages") Object messages() { return Results.success(List.of()); }
        @GetMapping("/knowledge-base") Object scope() { return Results.success(Map.of("records", List.of(Map.of("id", "fixture-kb", "name", "双文档测试资料", "collectionName", "fixture")))); }
        @GetMapping("/rag/sample-questions") Object questions() { return Results.success(List.of()); }
        @GetMapping("/fixtures/summary") Object summary() { return Results.success(Map.of("modelCalls", MODEL_CALLS.get(), "runs", jdbc.queryForList("SELECT id,status,conversation_id,client_request_id,artifact IS NOT NULL AS has_artifact FROM t_research_run ORDER BY create_time"))); }
        @GetMapping(value="/rag/v3/chat",produces="text/event-stream") SseEmitter qa(@RequestParam String question, @RequestParam(required=false) String conversationId) throws Exception {
            String id = conversationId == null ? "fixture-qa" : conversationId;
            if (conversationId == null) jdbc.update("INSERT INTO t_conversation(id,conversation_id,user_id,title,last_time) VALUES ('fixture-qa','fixture-qa',?,'普通问答测试',CURRENT_TIMESTAMP)", OWNER);
            var emitter = new SseEmitter(); emitter.send(SseEmitter.event().name("meta").data(Map.of("conversationId", id, "taskId", "fixture-task")));
            emitter.send(SseEmitter.event().name("message").data(Map.of("type", "response", "delta", "普通问答路径可用")));
            emitter.send(SseEmitter.event().name("finish").data(Map.of("messageId", "fixture-message"))); emitter.send(SseEmitter.event().name("done").data(Map.of())); emitter.complete(); return emitter;
        }
    }
}
