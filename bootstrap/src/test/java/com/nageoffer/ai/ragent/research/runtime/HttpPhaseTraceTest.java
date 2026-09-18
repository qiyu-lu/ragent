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

import com.nageoffer.ai.ragent.infra.operation.HttpPhaseTrace;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpPhaseTraceTest {
    @Test void actualConnectionReuseIsRecordedWithoutHeadersOrAddresses() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("ok"));
            server.enqueue(new MockResponse().setBody("ok"));
            var base = new OkHttpClient();
            try {
                var first = new HttpPhaseTrace();
                var second = new HttpPhaseTrace();
                var request = new Request.Builder().url(server.url("/private-source"))
                        .header("Authorization", "Bearer local-fixture-secret").build();
                for (var trace : new HttpPhaseTrace[]{first, second}) {
                    try (var response = base.newBuilder().eventListener(trace).build().newCall(request).execute()) {
                        assertEquals("ok", response.body().string());
                    }
                }
                assertEquals("http/1.1", first.snapshot().get("protocol"));
                assertEquals(false, first.snapshot().get("connectionReused"));
                assertEquals(true, second.snapshot().get("connectionReused"));
                assertEquals(first.snapshot().get("connectionId"), second.snapshot().get("connectionId"));
                assertEquals("responseBodyEnd", second.snapshot().get("lastPhase"));
                assertFalse(second.snapshot().toString().contains("local-fixture-secret"));
                assertFalse(second.snapshot().toString().contains("private-source"));
                assertFalse(second.snapshot().toString().contains("localhost"));
            } finally {
                base.connectionPool().evictAll();
                base.dispatcher().executorService().shutdownNow();
            }
        }
    }

    @Test void missingResponseHeadersRetainsActualProtocolAndRequestWritePhase() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var trace = new HttpPhaseTrace();
            var client = new OkHttpClient.Builder().eventListener(trace).callTimeout(250, TimeUnit.MILLISECONDS).build();
            try {
                var request = new Request.Builder().url(server.url("/"))
                        .post(RequestBody.create("{}", MediaType.get("application/json"))).build();
                assertThrows(IOException.class, () -> client.newCall(request).execute());
                assertEquals("http/1.1", trace.snapshot().get("protocol"));
                assertNotNull(trace.snapshot().get("connectionId"));
                var phases = (java.util.Map<?, ?>) trace.snapshot().get("phaseMillis");
                assertTrue(phases.containsKey("requestBodyEnd"));
                assertFalse(phases.containsKey("responseHeadersEnd"));
            } finally {
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            }
        }
    }
}
