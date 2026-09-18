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

package com.nageoffer.ai.ragent.infra.operation;

import okhttp3.*;
import java.net.*;
import java.util.*;

/** Per-attempt transport timings; intentionally excludes URL, headers, body and remote addresses. */
public final class HttpPhaseTrace extends okhttp3.EventListener {
    private final long started = System.nanoTime();
    private final Map<String, Long> times = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String last = "queued";
    private volatile String protocol;
    private volatile String connectionId;
    private volatile Boolean connectionReused;
    private void phase(String name) { last = name; times.put(name, (System.nanoTime() - started) / 1_000_000); }
    @Override public void dnsStart(Call call, String domain) { phase("dnsStart"); }
    @Override public void dnsEnd(Call call, String domain, List<InetAddress> addresses) { phase("dnsEnd"); }
    @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) { phase("connectStart"); }
    @Override public void secureConnectStart(Call call) { phase("tlsStart"); }
    @Override public void secureConnectEnd(Call call, Handshake handshake) { phase("tlsEnd"); }
    @Override public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, Protocol protocol) {
        if (protocol != null) this.protocol = protocol.toString();
        phase("connectEnd");
    }
    @Override public void connectionAcquired(Call call, Connection connection) {
        protocol = connection.protocol().toString();
        connectionId = Integer.toHexString(System.identityHashCode(connection));
        connectionReused = !times.containsKey("connectStart");
        phase("connectionAcquired");
    }
    @Override public void requestHeadersEnd(Call call, Request request) { phase("requestHeadersEnd"); }
    @Override public void requestBodyEnd(Call call, long size) { phase("requestBodyEnd"); }
    @Override public void responseHeadersStart(Call call) { phase("responseHeadersStart"); }
    @Override public void responseHeadersEnd(Call call, Response response) { phase("responseHeadersEnd"); }
    @Override public void responseBodyEnd(Call call, long size) { phase("responseBodyEnd"); }
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastPhase", last);
        result.put("phaseMillis", Map.copyOf(times));
        if (protocol != null) result.put("protocol", protocol);
        if (connectionId != null) result.put("connectionId", connectionId);
        if (connectionReused != null) result.put("connectionReused", connectionReused);
        return Map.copyOf(result);
    }
}
