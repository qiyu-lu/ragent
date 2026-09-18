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

package com.nageoffer.ai.ragent.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.research.model.ResearchRun.Status;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.runtime.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在线运行和真实联调共用最终状态处理，COMPLETED/PARTIAL 必须有校验后的产物。 */
@Service
@RequiredArgsConstructor
public class ResearchCompletionService {
    private final ResearchRunStore store;
    private final ResearchArtifactGenerator generator;
    private final ObjectMapper json;

    public void complete(ResearchSession session, ResearchSession.Outcome outcome, String researchError) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("promptVersion", ResearchAgentFactory.PROMPT_VERSION);
        state.put("artifactPromptVersion", ResearchArtifactGenerator.PROMPT_VERSION);
        state.put("readEvidenceIds", session.delivered().keySet());
        state.put("acceptedWorkerEvidenceIds", session.acceptedEvidenceIds());
        if (outcome.question() != null) {
            session.check();
            state.put("question", outcome.question());
            store.finish(session.claim, Status.WAITING_INPUT, state, session.budget.snapshot(), null);
            return;
        }
        state.put("researchResult", json.convertValue(outcome.result(), Map.class));
        state.put("executionIssues", outcome.result().executionIssues());
        try {
            var artifact = generator.generate(session, outcome.result());
            session.check();
            store.finish(session.claim, outcome.result().status() == SubtaskResult.Status.PARTIAL ? Status.PARTIAL : Status.COMPLETED,
                    state, json.convertValue(artifact, Map.class), session.budget.snapshot(), researchError);
        } catch (RuntimeException failure) {
            String reason = reason(failure);
            state.put("artifactError", reason);
            // 保留已研究的发现；非法/未完成产物不可发布。取消或旧 epoch 会被 store 拒绝写回。
            store.finish(session.claim, Status.FAILED, state, session.budget.snapshot(), reason);
        }
    }

    public void researchFailed(ResearchSession session, RuntimeException failure) {
        Throwable cause = root(failure);
        String reason = reason(failure);
        boolean bounded = !(cause instanceof java.util.concurrent.CancellationException);
        if ((bounded && !session.citableIds().isEmpty()) || !session.acceptedEvidenceIds().isEmpty()) {
            complete(session, new ResearchSession.Outcome(null, session.partial(reason)), reason);
        } else store.finish(session.claim, Status.FAILED, Map.of("executionIssues", session.partial(reason).executionIssues(), "researchResult", json.convertValue(session.partial(reason), Map.class),
                "readEvidenceIds", session.delivered().keySet(), "acceptedWorkerEvidenceIds", session.acceptedEvidenceIds()), session.budget.snapshot(), reason);
    }

    private static Throwable root(Throwable error) {
        while (error.getCause() != null && !(error instanceof ResearchBudget.Exhausted) && !(error instanceof com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure)) error = error.getCause();
        return error;
    }
    private static String reason(Throwable failure) {
        if ("ARTIFACT_VALIDATION_FAILED".equals(failure.getMessage())) return "ARTIFACT_VALIDATION_FAILED";
        Throwable cause = root(failure);
        if (cause instanceof ResearchBudget.Exhausted) return cause.getMessage();
        if (cause instanceof com.nageoffer.ai.ragent.infra.operation.RequestOperation.Failure operation) return operation.code;
        if (cause instanceof java.util.concurrent.TimeoutException) return "RESEARCH_TIMEOUT";
        if (cause instanceof java.util.concurrent.CancellationException) return "EXECUTION_CANCELLED";
        return "NATIVE_FINISH_REQUIRED".equals(cause.getMessage()) ? "NATIVE_FINISH_REQUIRED" : "RESEARCH_EXECUTION_FAILED";
    }
}
