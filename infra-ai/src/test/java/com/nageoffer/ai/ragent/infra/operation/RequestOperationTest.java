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

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestOperationTest {
    @Test void timedOutQueuedWorkNeverStartsAndDoesNotLeakScope() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var gate = new CountDownLatch(1);
        executor.submit(() -> { try { gate.await(); } catch (InterruptedException ignored) {} });
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        try (var operation = new RequestOperation(Duration.ofMillis(80), Map.of(), e -> {}, new ConcurrentHashMap<>())) {
            var failure = assertThrows(RequestOperation.Failure.class, () -> operation.execute(executor, "queue", () -> { called.set(true); return 1; }));
            assertEquals("RETRIEVAL_DEADLINE", failure.code);
            gate.countDown();
            assertNull(executor.submit(RequestOperation::current).get(1, TimeUnit.SECONDS));
            assertFalse(called.get());
        } finally { gate.countDown(); executor.shutdownNow(); }
    }
    @Test void emptyIsSuccessfulWhileErrorsStayVisibleAcrossExecutor() {
        var executor = Executors.newSingleThreadExecutor();
        List<Map<String,Object>> events = new CopyOnWriteArrayList<>();
        try (var operation = new RequestOperation(Duration.ofSeconds(2), Map.of("toolCallId", "id"), events::add, new ConcurrentHashMap<>())) {
            assertEquals(List.of(), operation.execute(executor, "vector", List::of));
            assertThrows(RequestOperation.Failure.class, () -> operation.execute(executor, "vector", () -> { throw new IllegalStateException("unavailable"); }));
            assertTrue(events.stream().anyMatch(e -> "COMPLETED".equals(e.get("status"))));
            assertTrue(events.stream().anyMatch(e -> "FAILED".equals(e.get("status"))));
            assertNull(RequestOperation.current());
        } finally { executor.shutdownNow(); }
    }
    @Test void jitteredDelayKeepsAFloorGrowsExponentiallyAndStopsAtTheCap() {
        assertEquals(150, RequestOperation.jitteredDelay(1, 300, 2000, () -> 0.0));
        assertEquals(299, RequestOperation.jitteredDelay(1, 300, 2000, () -> 0.999));
        assertEquals(300, RequestOperation.jitteredDelay(2, 300, 2000, () -> 0.0));
        assertEquals(1000, RequestOperation.jitteredDelay(40, 300, 2000, () -> 0.0));
        assertTrue(RequestOperation.jitteredDelay(40, 300, 2000, () -> 0.999) < 2000);
        var delays = new HashSet<Long>();
        var random = new Random(7);
        for (int i = 0; i < 20; i++) delays.add(RequestOperation.jitteredDelay(1, 300, 2000, random::nextDouble));
        assertTrue(delays.size() > 10, "concurrent retries must not share one delay");
        assertThrows(IllegalArgumentException.class, () -> RequestOperation.jitteredDelay(0, 300, 2000, () -> 0.0));
    }
}
