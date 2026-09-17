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

import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResearchBudgetTest {
    @Test
    void concurrentRequestsCannotSpendReservedFinalizationCalls() throws Exception {
        var limits = new ResearchProperties();
        limits.setMaxModelCalls(6);
        var budget = new ResearchBudget(limits, Map.of());
        var pool = Executors.newFixedThreadPool(8);
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (int i = 0; i < 50; i++) pool.submit(() -> {
                try { budget.acquireModel(false); accepted.incrementAndGet(); }
                catch (ResearchBudget.Exhausted expected) { }
            });
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(4, accepted.get());
            budget.acquireModel(true);
            budget.acquireModel(true);
            assertThrows(ResearchBudget.Exhausted.class, () -> budget.acquireModel(true));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void resumedBudgetRetainsUsageAndDoesNotInventZeroTokens() {
        var limits = new ResearchProperties();
        var first = new ResearchBudget(limits, Map.of());
        first.acquireModel(false);
        first.acquireTool();
        first.startCall("call", "model", 1200);
        first.finishCall("call", "CANCELLED", null, null, null, null);
        var resumed = new ResearchBudget(limits, first.snapshot());
        assertEquals(1, resumed.snapshot().get("modelCalls"));
        assertEquals(1, resumed.snapshot().get("toolCalls"));
        var call = ((java.util.List<Map<String, Object>>) resumed.snapshot().get("calls")).get(0);
        assertEquals("unknown", call.get("usageStatus"));
        assertFalse(call.containsKey("inputTokens"));
    }

    @Test
    void activeDurationIncludesEarlierExecutionsAndInvalidConfigFails() {
        var limits = new ResearchProperties();
        var budget = new ResearchBudget(limits, Map.of("activeMillis", 300001));
        assertThrows(ResearchBudget.Exhausted.class, () -> budget.acquireTool());
        limits.setReservedFinalizationModelCalls(16);
        assertThrows(IllegalArgumentException.class, () -> new ResearchBudget(limits, Map.of()));
    }
}
