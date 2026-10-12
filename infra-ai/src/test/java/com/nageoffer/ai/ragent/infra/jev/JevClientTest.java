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

package com.nageoffer.ai.ragent.infra.jev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JevClientTest {

    private MockWebServer server;
    private JevProperties properties;
    private JevClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new JevProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl(server.url("/").toString());
        properties.setInitialBackoff(Duration.ofMillis(1));
        properties.setTimeout(Duration.ofMillis(500));
        client = new JevClient(new OkHttpClient(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendsTypedQuestionsAndReadsEveryAnswerType() throws Exception {
        server.enqueue(json("""
                {"model":"jev-1.13.0","answers":{
                  "header":{"type":"noul","noul":0.9312},
                  "team":{"type":"choice","choice":"billing","probabilities":{"billing":0.7,"technical":0.3},"confidence":0.81},
                  "urgency":{"type":"score","score":1.4,"legend":{"0":"low","1":"mid","2":"high"},
                             "probabilities":{"0":0.1,"1":0.4,"2":0.5},"confidence":0.6}},
                 "usage":{"input_tokens":140,"output_tokens":3}}
                """));
        Map<String, JevQuestion> questions = new LinkedHashMap<>();
        questions.put("header", new JevQuestion.Noul("Is `first_row` a header?", "column names", null));
        questions.put("team", new JevQuestion.Choice("Who owns it?", Map.of("billing", "refunds", "technical", "bugs")));
        questions.put("urgency", new JevQuestion.Score("How urgent?", List.of("low", "mid", "high")));

        JevResult result = client.systemOne(Map.of("rows", List.of(List.of("序号", "名称"))), questions);

        assertEquals("jev-1.13.0", result.model());
        assertEquals(0.9312, ((JevAnswer.Noul) result.answers().get("header")).probability());
        assertEquals("billing", ((JevAnswer.Choice) result.answers().get("team")).choice());
        assertEquals(1.4, ((JevAnswer.Score) result.answers().get("urgency")).score());
        assertEquals(140, result.inputTokens());

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/systemone", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("jev-1.13.0", body.get("model").getAsString(), "默认钉版本，不用 jev-latest");
        assertEquals("序号", body.getAsJsonObject("state").getAsJsonArray("rows").get(0).getAsJsonArray().get(0).getAsString());
        JsonObject header = body.getAsJsonObject("questions").getAsJsonObject("header");
        assertEquals("noul", header.get("type").getAsString());
        assertEquals("column names", header.getAsJsonObject("criteria").get("true").getAsString());
        assertFalse(header.getAsJsonObject("criteria").has("false"), "没给的说明不发");
        assertEquals("choice", body.getAsJsonObject("questions").getAsJsonObject("team").get("type").getAsString());
        assertEquals("high", body.getAsJsonObject("questions").getAsJsonObject("urgency")
                .getAsJsonArray("criteria").get(2).getAsString());
    }

    @Test
    void retriesRateLimitAndOverloadThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(529).setBody("{\"error\":\"overloaded\"}"));
        server.enqueue(noul(0.2));

        JevResult result = client.systemOne("state", Map.of("q", new JevQuestion.Noul("Is it?", null, null)));

        assertEquals(0.2, ((JevAnswer.Noul) result.answers().get("q")).probability());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void stopsAfterTheConfiguredRetries() {
        properties.setMaxRetries(1);
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(noul(0.5));

        ModelClientException error = assertThrows(ModelClientException.class,
                () -> client.systemOne("state", Map.of("q", new JevQuestion.Noul("Is it?", null, null))));

        assertEquals(ModelClientErrorType.SERVER_ERROR, error.getErrorType());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void doesNotRetryAValidationErrorAndKeepsTheFieldInTheMessage() {
        server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("{\"detail\":[{\"loc\":[\"body\",\"model\"],\"msg\":\"field required\"}]}"));

        ModelClientException error = assertThrows(ModelClientException.class,
                () -> client.systemOne("state", Map.of("q", new JevQuestion.Noul("Is it?", null, null))));

        assertEquals(ModelClientErrorType.CLIENT_ERROR, error.getErrorType());
        assertTrue(error.getMessage().contains("field required"), error.getMessage());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void timesOutAndRetriesASlowResponse() {
        properties.setMaxRetries(1);
        server.enqueue(noul(0.9).setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(noul(0.9));

        JevResult result = client.systemOne("state", Map.of("q", new JevQuestion.Noul("Is it?", null, null)));

        assertEquals(0.9, ((JevAnswer.Noul) result.answers().get("q")).probability());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void rejectsAnswersThatDoNotMatchTheQuestions() {
        Map<String, JevQuestion> noulQuestion = Map.of("q", new JevQuestion.Noul("Is it?", null, null));
        server.enqueue(json("{\"model\":\"jev-1.13.0\",\"answers\":{\"other\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{}}"));
        server.enqueue(json("{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":1.5}},\"usage\":{}}"));
        server.enqueue(json("{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\"}},\"usage\":{}}"));
        server.enqueue(json("{\"model\":\"jev-1.13.0\",\"answers\":{\"c\":{\"type\":\"choice\",\"choice\":\"a\","
                + "\"probabilities\":{\"a\":0.6,\"b\":0.3},\"confidence\":0.5}},\"usage\":{}}"));

        for (String reason : List.of("题号", "概率超出", "答案类型")) {
            ModelClientException error = assertThrows(ModelClientException.class, () -> client.systemOne("s", noulQuestion));
            assertEquals(ModelClientErrorType.INVALID_RESPONSE, error.getErrorType());
            assertTrue(error.getMessage().contains(reason), error.getMessage());
        }
        ModelClientException sum = assertThrows(ModelClientException.class, () -> client.systemOne("s",
                Map.of("c", new JevQuestion.Choice("Which?", Map.of("a", "A", "b", "B")))));
        assertTrue(sum.getMessage().contains("之和"), sum.getMessage());
        assertEquals(4, server.getRequestCount(), "应答无效不重试");
    }

    @Test
    void acceptsProbabilitiesRoundedToFourDecimals() {
        server.enqueue(json("{\"model\":\"jev-1.13.0\",\"answers\":{\"c\":{\"type\":\"choice\",\"choice\":\"a\","
                + "\"probabilities\":{\"a\":0.3333,\"b\":0.3333,\"c\":0.3333},\"confidence\":0.1}},\"usage\":{}}"));

        JevResult result = client.systemOne("s",
                Map.of("c", new JevQuestion.Choice("Which?", Map.of("a", "A", "b", "B", "c", "C"))));

        assertEquals("a", ((JevAnswer.Choice) result.answers().get("c")).choice());
    }

    @Test
    void refusesToCallWithoutAKey() {
        assumeTrue(System.getenv("TYPESAFE_API_KEY") == null && System.getenv("JEV_API_KEY") == null,
                "环境里有密钥，配置为空时会读到它");
        JevProperties empty = new JevProperties();
        empty.setApiKey(" ");
        empty.setBaseUrl(server.url("/").toString());
        JevClient unconfigured = new JevClient(new OkHttpClient(), empty);

        assertFalse(unconfigured.isConfigured());
        assertThrows(ModelClientException.class,
                () -> unconfigured.systemOne("s", Map.of("q", new JevQuestion.Noul("Is it?", null, null))));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void refusesMalformedQuestionsBeforeCalling() {
        assertThrows(IllegalArgumentException.class,
                () -> client.systemOne("s", Map.of("c", new JevQuestion.Choice("Which?", Map.of("a", "A")))));
        assertThrows(IllegalArgumentException.class,
                () -> client.systemOne("s", Map.of("q", new JevQuestion.Noul(" ", null, null))));
        assertThrows(IllegalArgumentException.class, () -> client.systemOne("s", Map.of()));
        assertEquals(0, server.getRequestCount());
    }

    private static MockResponse noul(double probability) {
        return json("{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":" + probability
                + "}},\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
