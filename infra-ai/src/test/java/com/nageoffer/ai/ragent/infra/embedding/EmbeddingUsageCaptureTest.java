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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingUsageCaptureTest {
    @Test void recordsProviderUsageAndRestoresThePreviousObserver() throws Exception {
        var http = mock(OkHttpClient.class); var call = mock(Call.class);
        when(http.newCall(any())).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> response("{\"data\":[{\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}"));
        var client = new SiliconFlowEmbeddingClient(http);
        List<Map<String,Object>> outer = new ArrayList<>(), inner = new ArrayList<>();
        try (var first = new EmbeddingUsageCapture(outer::add)) {
            try (var second = new EmbeddingUsageCapture(inner::add)) { client.embed("text", target()); }
            client.embed("text", target());
        }
        client.embed("text", target());
        assertEquals(2, inner.size()); assertEquals(2, outer.size());
        assertEquals("STARTED", inner.get(0).get("request_state"));
        assertEquals(inner.get(0).get("call_id"), inner.get(1).get("call_id"));
        assertEquals(true, inner.get(1).get("success"));
        assertEquals("provider", inner.get(1).get("usage_status"));
        assertEquals(7D, ((Map<?,?>) inner.get(1).get("usage")).get("total_tokens"));
        assertEquals("request-1", inner.get(1).get("request_id"));
        assertFalse(inner.get(0).toString().contains("secret"));
    }
    @Test void missingUsageAndNetworkFailuresRemainUnknown() throws Exception {
        var http = mock(OkHttpClient.class); var call = mock(Call.class);
        when(http.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response("{\"data\":[{\"embedding\":[1,2]}]}"))
                .thenThrow(new IOException("offline"));
        List<Map<String,Object>> records = new ArrayList<>();
        try (var capture = new EmbeddingUsageCapture(records::add)) {
            var client = new SiliconFlowEmbeddingClient(http);
            client.embed("text", target());
            assertThrows(RuntimeException.class, () -> client.embed("text", target()));
        }
        assertEquals(4, records.size()); assertNull(records.get(1).get("usage"));
        assertEquals("unknown", records.get(3).get("usage_status"));
        assertEquals(false, records.get(3).get("success"));
    }
    @Test void invalidProviderBodyIsRecordedAsFailureEvenWithHttp200() throws Exception {
        var http = mock(OkHttpClient.class); var call = mock(Call.class);
        when(http.newCall(any())).thenReturn(call); when(call.execute()).thenReturn(response("{}"));
        List<Map<String,Object>> records = new ArrayList<>();
        try (var capture = new EmbeddingUsageCapture(records::add)) {
            assertThrows(RuntimeException.class, () -> new SiliconFlowEmbeddingClient(http).embed("text", target()));
        }
        assertEquals(false, records.get(1).get("success"));
    }
    private Response response(String body) {
        return new Response.Builder().request(new Request.Builder().url("http://localhost").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("x-request-id","request-1")
                .body(ResponseBody.create(body,MediaType.get("application/json"))).build();
    }
    private ModelTarget target() {
        var candidate = new AIModelProperties.ModelCandidate(); candidate.setModel("Qwen/Qwen3-Embedding-8B"); candidate.setDimension(2);
        var provider = new AIModelProperties.ProviderConfig(); provider.setUrl("http://localhost");
        provider.setApiKey("secret"); provider.setEndpoints(Map.of("embedding","/embeddings"));
        return new ModelTarget("fixture",candidate,provider,null);
    }
}
