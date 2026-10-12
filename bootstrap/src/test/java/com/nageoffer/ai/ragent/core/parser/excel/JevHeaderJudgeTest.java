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

package com.nageoffer.ai.ragent.core.parser.excel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevProperties;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevHeaderJudgeTest {

    /**
     * 规则会判成表头：两格都是短文字
     */
    private static final HeaderJudge.Candidate SHORT_TEXT = new HeaderJudge.Candidate("词表", "样品说明",
            List.of(1, 2), List.of(List.of("名称", "说明"), List.of("原矿", "刚采出")));

    private MockWebServer server;
    private ExcelLayoutProperties.Jev settings;
    private JevHeaderJudge judge;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        JevProperties properties = new JevProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl(server.url("/").toString());
        properties.setMaxRetries(0);
        properties.setTimeout(Duration.ofMillis(500));
        settings = new ExcelLayoutProperties.Jev();
        judge = new JevHeaderJudge(new JevClient(new OkHttpClient(), properties), settings, RuleHeaderJudge.INSTANCE);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void confidentAnswersDecideAndUnsureOnesKeepTheRule() {
        server.enqueue(noul(0.05));
        server.enqueue(noul(0.93));
        server.enqueue(noul(0.5));

        HeaderJudge.Decision no = judge.judge(SHORT_TEXT);
        HeaderJudge.Decision yes = judge.judge(SHORT_TEXT);
        HeaderJudge.Decision unsure = judge.judge(SHORT_TEXT);

        assertFalse(no.header(), "P ≤ 0.2 时不管规则怎么说都判无表头");
        assertEquals(HeaderJudge.Source.JEV, no.source());
        assertEquals(0.05, no.probability());
        assertTrue(yes.header());
        assertEquals(HeaderJudge.Source.JEV, yes.source());
        assertTrue(unsure.header(), "介于 0.2 与 0.8 之间按规则");
        assertEquals(HeaderJudge.Source.JEV_UNSURE, unsure.source());
        assertEquals(0.5, unsure.probability());
    }

    @Test
    void failuresFallBackToTheRule() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"model\":\"jev-1.13.0\"}"));

        HeaderJudge.Decision serverError = judge.judge(SHORT_TEXT);
        HeaderJudge.Decision invalid = judge.judge(SHORT_TEXT);

        assertTrue(serverError.header());
        assertEquals(HeaderJudge.Source.JEV_FAILED, serverError.source());
        assertNull(serverError.probability());
        assertEquals(HeaderJudge.Source.JEV_FAILED, invalid.source());
    }

    @Test
    void sendsOnlyTheFirstRowsWithLongCellsCutAndEmptyColumnsDropped() throws Exception {
        settings.setMaxRows(3);
        settings.setMaxCellChars(5);
        server.enqueue(noul(0.1));
        HeaderJudge.Candidate candidate = new HeaderJudge.Candidate("矿样", null, List.of(4, 5, 6, 7), List.of(
                List.of("名称", "", "说明"),
                List.of("原矿", "", "刚采出来的矿石经过磨矿"),
                List.of("磁尾", "", "磁选"),
                List.of("精矿", "", "第四行不发")));

        judge.judge(candidate);

        JsonObject body = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals("jev-1.13.0", body.get("model").getAsString());
        JsonObject state = body.getAsJsonObject("state");
        assertEquals("矿样", state.get("sheet").getAsString());
        assertFalse(state.has("title"), "没有表题就不发");
        assertEquals(strings("名称", "说明"), state.getAsJsonArray("first_row"));
        JsonArray below = state.getAsJsonArray("rows_below");
        assertEquals(2, below.size());
        assertEquals(strings("原矿", "刚采出来的…"), below.get(0));
        JsonObject question = body.getAsJsonObject("questions").getAsJsonObject(JevHeaderJudge.QUESTION_ID);
        assertEquals("noul", question.get("type").getAsString());
        assertTrue(question.getAsJsonObject("criteria").has("true"));
        assertTrue(question.getAsJsonObject("criteria").has("false"));
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static MockResponse noul(double probability) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"model\":\"jev-1.13.0\",\"answers\":{\"" + JevHeaderJudge.QUESTION_ID + "\":{\"type\":\"noul\",\"noul\":"
                        + probability + "}},\"usage\":{\"input_tokens\":120,\"output_tokens\":0}}");
    }
}
