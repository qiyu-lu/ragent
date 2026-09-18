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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.operation.RequestOperation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelRoutingExecutorTest {
    private final AIModelProperties properties = new AIModelProperties();
    private final ModelHealthStore health = new ModelHealthStore(properties);
    private final ModelRoutingExecutor executor = new ModelRoutingExecutor(health);
    private final List<ModelTarget> targets = List.of(new ModelTarget("emb", new AIModelProperties.ModelCandidate(), null, null));
    private final AtomicInteger calls = new AtomicInteger();

    private Object call(Object result, RuntimeException error) {
        try (var operation = new RequestOperation(Duration.ofSeconds(5), Map.of(), e -> {}, new ConcurrentHashMap<>());
             var ignored = operation.bind()) {
            return executor.executeWithFallback(ModelCapability.EMBEDDING, targets, t -> "client", (client, target) -> {
                calls.incrementAndGet();
                if (error != null) throw error;
                return result;
            });
        }
    }

    @Test void deadlineScopedFailuresOpenTheCircuitAndLaterCallsFailFast() {
        var timeout = new ModelClientException("timeout", ModelClientErrorType.NETWORK_ERROR, null);
        for (int i = 0; i < properties.getSelection().getFailureThreshold(); i++) {
            var failure = assertThrows(RequestOperation.Failure.class, () -> call(null, timeout));
            assertTrue(failure.retryable);
        }
        var open = assertThrows(RequestOperation.Failure.class, () -> call("unused", null));
        assertEquals("CIRCUIT_OPEN", open.code);
        assertFalse(open.retryable);
        assertEquals(2, calls.get());
    }

    @Test void cancellationIsNotCountedAndReturnsTheHalfOpenProbe() {
        properties.getSelection().setOpenDurationMs(0L);
        var timeout = new ModelClientException("timeout", ModelClientErrorType.NETWORK_ERROR, null);
        for (int i = 0; i < properties.getSelection().getFailureThreshold(); i++) assertThrows(RequestOperation.Failure.class, () -> call(null, timeout));
        // The next call is the half-open probe; cancelling it must not leave the model marked as probing forever.
        assertThrows(CancellationException.class, () -> call(null, new CancellationException("RETRIEVAL_CANCELLED")));
        assertFalse(health.isUnavailable("emb"));
        assertEquals("ok", call("ok", null));
    }
}
