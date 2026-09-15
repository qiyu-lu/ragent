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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.GlobalExceptionHandler;
import com.nageoffer.ai.ragent.ironore.controller.TaskAgentController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP binding and business transactions with a supplied UserContext, without login middleware. */
class TaskAgentControllerTest {
    private static final String BASE = "/iron-ore/task-agent";
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayDeque<Decision> decisions = new ArrayDeque<>();
    private TaskAgentTestDatabase database;
    private JdbcTemplate jdbc;
    private MockMvc http;

    @BeforeEach
    void setup() {
        database = new TaskAgentTestDatabase();
        new ResourceDatabasePopulator(new ClassPathResource("db/260915_task_agent.sql")).execute(database.source);
        jdbc = new JdbcTemplate(database.source);
        TaskAgentStore store = new TaskAgentStore(jdbc, json, new DataSourceTransactionManager(database.source));
        InspectionBusinessService business = new InspectionBusinessService(jdbc, store);
        TaskKnowledge knowledge = mock(TaskKnowledge.class);
        Document doc = new Document("doc", "示例规程", "V1.0-demo");
        when(knowledge.document("doc")).thenReturn(doc);
        when(knowledge.search(eq(doc), anyString())).thenReturn(List.of(new Evidence("e1", "doc", "V1.0-demo", null, null, "核对标签后选择匹配项目的工位", "hash")));
        TaskAgentTools tools = new TaskAgentTools(knowledge, business, json);
        TaskAgentService agent = new TaskAgentService(store, (state, specs) -> decisions.removeFirst(), tools, knowledge, business);
        http = MockMvcBuilders.standaloneSetup(new TaskAgentController(agent, business, knowledge))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(LoginUser.builder().userId("alice").build());
    }

    @AfterEach
    void cleanup() { UserContext.clear(); database.close(); }

    @Test
    void httpFlowRequiresExplicitApprovalAndReturnsPersistedReceipt() throws Exception {
        JsonNode run = start();
        String id = run.path("id").asText();
        decisions.add(decision("inspect_sample", Map.of()));
        decisions.add(decision("search_procedure", Map.of("query", "送检资料要求")));
        decisions.add(decision("list_stations", Map.of()));
        decisions.add(decision("propose_submission", Map.of("title", "样品送检", "stationId", "station-01",
                "requirements", List.of(Map.of("text", "核对标签并预约匹配项目的工位", "evidenceIds", List.of("e1"))))));
        for (int step = 0; step < 4; step++) run = success(post(BASE + "/runs/{id}/advance", id));
        assertEquals("WAITING_APPROVAL", run.path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_task_agent_submission", Integer.class));
        String approval = json.writeValueAsString(Map.of("revision", run.path("revision").asLong()));
        JsonNode completed = success(post(BASE + "/runs/{id}/approve", id).content(approval));
        assertEquals("COMPLETED", completed.path("status").asText());
        assertEquals(id, completed.path("submission").path("runId").asText());
        JsonNode replay = success(post(BASE + "/runs/{id}/approve", id).content(approval));
        assertEquals(completed.path("submission"), replay.path("submission"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_task_agent_submission", Integer.class));
        assertEquals("COMPLETED", success(get(BASE + "/runs/{id}", id)).path("status").asText());
    }

    @Test
    void validatesInputBeforeCreatingRunsOrRecordingReplies() throws Exception {
        http.perform(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\" \",\"documentId\":\"doc\",\"sampleId\":\"sample-ready\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(not("0")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_task_agent_run", Integer.class));
        JsonNode run = start();
        http.perform(post(BASE + "/runs/{id}/reply", run.path("id").asText()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\" \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(not("0")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_task_agent_event", Integer.class));
    }

    @Test
    void takesTaskOwnerFromAuthenticatedContext() throws Exception {
        String id = start().path("id").asText();
        assertEquals("alice", jdbc.queryForObject("SELECT owner_user_id FROM t_task_agent_run WHERE id=?", String.class, id));
        UserContext.set(LoginUser.builder().userId("bob").build());
        assertTrue(success(get(BASE + "/runs")).isEmpty());
        http.perform(get(BASE + "/runs/{id}", id)).andExpect(jsonPath("$.code").value(not("0")));
        UserContext.clear();
        http.perform(post(BASE + "/runs/{id}/cancel", id)).andExpect(jsonPath("$.code").value(not("0")));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM t_task_agent_run WHERE id=?", String.class, id));
    }

    private JsonNode start() throws Exception {
        success(post(BASE + "/demo-data"));
        return success(post(BASE + "/runs").content(json.writeValueAsString(new StartRequest("办理水分检测送检", "doc", "sample-ready"))));
    }

    private JsonNode success(MockHttpServletRequestBuilder request) throws Exception {
        String response = http.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }

    private Decision decision(String tool, Object args) { return new Decision(tool, json.valueToTree(args), "执行下一步"); }
}
