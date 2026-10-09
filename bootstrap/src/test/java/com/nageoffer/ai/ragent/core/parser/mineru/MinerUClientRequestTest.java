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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerUClientRequestTest {

    private static final String TICKET = "{\"code\":0,\"data\":{\"batch_id\":\"b-1\",\"file_urls\":[\"http://upload\"]}}";

    private static JsonNode submit(String modelVersion, boolean ocr) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(TICKET));
            server.start();
            MinerUProperties properties = new MinerUProperties();
            properties.setApiUrl(server.url("/api/v4").toString());
            properties.setApiKey("test-key");
            BatchUploadTicket ticket = new MinerUClient(new OkHttpClient(), mapper, properties)
                    .requestUpload(new BatchSubmitRequest("si.pdf", "doc-1", ocr, true, true, "ch", modelVersion));
            assertEquals("b-1", ticket.batchId());
            RecordedRequest request = server.takeRequest();
            assertEquals("/api/v4/file-urls/batch", request.getPath());
            return mapper.readTree(request.getBody().readUtf8());
        }
    }

    @Test
    void sendsModelVersionOnlyWhenConfigured() throws Exception {
        JsonNode pinned = submit("pipeline", true);
        assertEquals("pipeline", pinned.path("model_version").asText());
        assertTrue(pinned.path("files").get(0).path("is_ocr").asBoolean());

        JsonNode serviceDefault = submit(null, false);
        assertFalse(serviceDefault.has("model_version"));
        assertFalse(serviceDefault.path("files").get(0).path("is_ocr").asBoolean());
    }
}
