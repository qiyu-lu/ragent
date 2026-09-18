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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 所有角色共用一个原子预算；等待人工输入不计入活动执行时长。 */
public class ResearchBudget {
    public static class Exhausted extends RuntimeException {
        public Exhausted(String reason) { super(reason); }
    }

    private final ResearchProperties limits;
    private final long activeStarted = System.nanoTime();
    private final long previousActiveMillis;
    final java.util.concurrent.ConcurrentMap<String, List<List<Float>>> embeddingCache = new java.util.concurrent.ConcurrentHashMap<>();
    private int modelCalls;
    private int toolCalls;
    private int workersCreated;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public ResearchBudget(ResearchProperties limits, Map<String, Object> saved) {
        limits.validate();
        this.limits = limits;
        this.modelCalls = number(saved, "modelCalls").intValue();
        this.toolCalls = number(saved, "toolCalls").intValue();
        this.workersCreated = number(saved, "workersCreated").intValue();
        this.previousActiveMillis = number(saved, "activeMillis").longValue();
        if (saved.get("calls") instanceof List<?> records) {
            for (Object record : records) calls.add(new LinkedHashMap<>((Map<String, Object>) record));
        }
    }

    public synchronized void acquireModel(boolean finalization) {
        if (finalization) checkTime(); else explorationRemaining();
        int cap = limits.getMaxModelCalls() - (finalization ? 0 : limits.getReservedFinalizationModelCalls());
        if (modelCalls >= cap) throw new Exhausted("MODEL_CALL_BUDGET");
        modelCalls++;
    }

    public synchronized void acquireTool() {
        explorationRemaining();
        if (toolCalls >= limits.getMaxToolCalls()) throw new Exhausted("TOOL_CALL_BUDGET");
        toolCalls++;
    }

    /** worker 仍扣全局探索额度，并额外留一次主 Agent 整合调用。 */
    public synchronized void acquireWorkerModel() {
        if (explorationCallsRemaining() <= 1) throw new Exhausted("WORKER_MODEL_BUDGET");
        acquireModel(false);
    }

    public synchronized int explorationCallsRemaining() {
        return Math.max(0, limits.getMaxModelCalls() - limits.getReservedFinalizationModelCalls() - modelCalls);
    }

    public synchronized int acquireWorkers(int count) {
        checkTime();
        if (count < 1 || workersCreated + count > limits.getMaxTotalWorkers()) {
            throw new Exhausted("WORKER_COUNT_BUDGET");
        }
        int first = workersCreated + 1;
        workersCreated += count;
        return first;
    }

    public synchronized void startCall(String callId, String model, int estimatedInputTokens) {
        startCall(callId, model, estimatedInputTokens, "main", "main");
    }

    public synchronized void startCall(String callId, String model, int estimatedInputTokens, String role, String taskId) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", callId);
        call.put("model", model);
        call.put("role", role);
        call.put("taskId", taskId);
        call.put("status", "STARTED");
        call.put("usageStatus", "unknown");
        call.put("estimatedInputTokens", estimatedInputTokens);
        calls.add(call);
    }

    public synchronized void finishCall(String callId, String status, String requestId,
                                        Integer inputTokens, Integer outputTokens, Integer cachedTokens) {
        Map<String, Object> call = calls.stream().filter(c -> callId.equals(c.get("callId")))
                .findFirst().orElseThrow();
        call.put("status", status);
        if (requestId != null) call.put("requestId", requestId);
        if (inputTokens != null && outputTokens != null) {
            call.put("usageStatus", "provider");
            call.put("inputTokens", inputTokens);
            call.put("outputTokens", outputTokens);
            call.put("cachedTokens", cachedTokens);
        }
    }

    public Duration remaining() {
        long remaining = limits.getMaxDurationSeconds() * 1000L - activeMillis();
        if (remaining <= 0) throw new Exhausted("RUN_DURATION_BUDGET");
        return Duration.ofMillis(remaining);
    }

    public Duration explorationRemaining() {
        long reserve = Math.min(30_000, limits.getMaxDurationSeconds() * 1000L / 5);
        long millis = remaining().toMillis() - reserve;
        if (millis <= 0) throw new Exhausted("EXPLORATION_DURATION_BUDGET");
        return Duration.ofMillis(millis);
    }
    public Duration retrievalTimeout() {
        return Duration.ofMillis(Math.min(explorationRemaining().toMillis(),
                Math.max(100, limits.getToolTimeoutSeconds() * 1000L - 2000)));
    }
    public void checkTime() { remaining(); }

    private long activeMillis() {
        return previousActiveMillis + (System.nanoTime() - activeStarted) / 1_000_000;
    }

    public synchronized Map<String, Object> snapshot() {
        return Map.of("modelCalls", modelCalls, "toolCalls", toolCalls, "workersCreated", workersCreated, "activeMillis", activeMillis(),
                "calls", calls.stream().map(LinkedHashMap::new).toList());
    }

    private Number number(Map<String, Object> saved, String key) {
        return saved.get(key) instanceof Number number ? number : 0;
    }
}
