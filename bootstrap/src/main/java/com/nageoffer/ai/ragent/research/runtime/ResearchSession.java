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

import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.service.ResearchRunStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** 一次领取的执行上下文，与 SDK 的可变对话上下文分别隔离。 */
public class ResearchSession {
    public record Outcome(String question, SubtaskResult result) { }
    public final ResearchRunStore.Claim claim;
    public final ResearchBudget budget;
    public final ResearchControl control;
    private final ResearchRunStore store;
    private final Map<String, EvidenceRecord> delivered = new LinkedHashMap<>();
    private volatile Outcome outcome;

    public ResearchSession(ResearchRunStore store, ResearchRunStore.Claim claim,
                            ResearchBudget budget, ResearchControl control) {
        this.store = store;
        this.claim = claim;
        this.budget = budget;
        this.control = control;
    }

    public void check() {
        control.check();
        budget.checkTime();
        if (!store.current(claim)) throw new CancellationException("RESEARCH_LEASE_SUPERSEDED");
    }

    public void event(String type, String summary, Map<String, Object> payload) {
        store.event(claim, type, summary, payload, budget.snapshot());
    }

    public synchronized void delivered(EvidenceRecord evidence) {
        delivered.put(evidence.evidenceId(), evidence);
    }

    public synchronized Map<String, EvidenceRecord> delivered() { return Map.copyOf(delivered); }
    public synchronized void conclude(Outcome outcome) {
        check();
        if (this.outcome != null) throw new IllegalStateException("研究已结束");
        this.outcome = outcome;
    }
    public Outcome outcome() { return outcome; }

    public synchronized SubtaskResult partial(String reason) {
        return new SubtaskResult("main", java.util.List.of(), java.util.List.of(reason),
                java.util.List.of(), SubtaskResult.Status.PARTIAL);
    }
}
