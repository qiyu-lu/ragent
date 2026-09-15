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

package com.nageoffer.ai.ragent.ironore.agent;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAgentService {
    static final int MAX_TURNS = 16;
    static final long LEASE_MILLIS = 180_000;
    private final TaskAgentStore store;
    private final TaskAgentPlanner planner;
    private final TaskAgentTools tools;
    private final TaskKnowledge knowledge;
    private final InspectionBusinessService business;

    public View start(String owner, StartRequest request) {
        business.sample(owner, request.sampleId());
        State state = new State();
        state.setGoal(request.goal().trim());
        state.setSampleId(request.sampleId());
        state.setDocument(knowledge.document(request.documentId()));
        state.setMessage("任务已建立，准备查询资料");
        return view(store.create(owner, state));
    }

    public List<Summary> list(String owner) { return store.list(owner); }
    public View get(String id, String owner) { return view(store.get(id, owner)); }

    public View advance(String id, String owner) {
        Run claimed = store.claim(id, owner, MAX_TURNS, LEASE_MILLIS);
        if (claimed == null) return get(id, owner);
        Decision decision;
        Outcome outcome;
        try {
            decision = planner.next(claimed.state(), tools.specifications());
        } catch (Exception failure) {
            log.warn("Task agent planning failed: run={}, type={}", id, failure.getClass().getSimpleName());
            return finish(claimed, Status.FAILED, "MODEL_ERROR", "模型调用或决策格式失败，可重试本任务", null);
        }
        try {
            outcome = tools.execute(claimed, decision);
        } catch (ClientException | IllegalArgumentException expected) {
            outcome = new Outcome(Status.READY, expected.getMessage(), Map.of("error", expected.getMessage()));
        } catch (Exception failure) {
            log.warn("Task agent tool failed: run={}, tool={}, type={}", id, decision.tool(), failure.getClass().getSimpleName());
            return finish(claimed, Status.FAILED, "TOOL_ERROR", "业务工具暂时不可用，可重试本任务", decision.tool());
        }
        Observation observation = new Observation(decision.tool(), outcome.message(), store.tree(outcome.result()));
        claimed.state().getObservations().add(observation);
        trimObservations(claimed.state());
        return finish(claimed, outcome.status(), "TOOL_RESULT", outcome.message(), observation);
    }

    private View finish(Run claimed, Status status, String type, String message, Object detail) {
        return store.transaction(() -> {
            Run current = store.lock(claimed.id(), claimed.ownerUserId());
            if (!store.ownsLease(claimed, current)) return view(current);
            Run updated = new Run(current.id(), current.ownerUserId(), current.status(), current.revision(), claimed.state(),
                    current.leaseToken(), current.leaseUntil(), current.createdAt(), current.updatedAt());
            return view(store.save(updated, status, type, message, detail));
        });
    }

    public View reply(String id, String owner, String message) {
        return store.transaction(() -> {
            Run run = store.lock(id, owner);
            if (run.status() != Status.WAITING_INPUT && run.status() != Status.WAITING_APPROVAL && run.status() != Status.FAILED) {
                throw new ClientException("当前任务状态不接受补充，请等待本步结束");
            }
            run.state().setProposal(null);
            run.state().setSampleInspected(false);
            run.state().setStationsListed(false);
            run.state().getObservations().add(new Observation("user_reply", message, store.tree(Map.of("message", message))));
            trimObservations(run.state());
            return view(store.save(run, Status.READY, "USER_REPLY", "已收到补充，将重新核对业务状态", message));
        });
    }

    public View approve(String id, String owner, long revision) {
        try {
            return store.transaction(() -> {
                Run run = store.lock(id, owner);
                if (run.status() == Status.COMPLETED) return view(run);
                if (run.status() != Status.WAITING_APPROVAL || run.revision() != revision) {
                    throw new StaleApprovalException();
                }
                tools.validateProposal(run.state(), run.state().getProposal());
                tools.validateEvidence(run.state());
                Submission submission = business.submit(run);
                return view(store.save(run, Status.COMPLETED, "SUBMITTED", "工位已预约，送检记录已创建", submission));
            });
        } catch (StaleApprovalException stale) {
            throw new ClientException("草稿已变化或当前不可确认，请刷新后核对");
        } catch (ClientException changed) {
            // The first transaction rolled back, including any slot/sample mutation.
            return store.transaction(() -> {
                Run run = store.lock(id, owner);
                if (run.status() != Status.WAITING_APPROVAL || run.revision() != revision) return view(run);
                run.state().setProposal(null);
                run.state().setEvidence(new java.util.ArrayList<>());
                run.state().setSampleInspected(false);
                run.state().setStationsListed(false);
                run.state().getObservations().add(new Observation("approval_conflict", changed.getMessage(), store.tree(Map.of("error", changed.getMessage()))));
                trimObservations(run.state());
                return view(store.save(run, Status.READY, "REPLAN", changed.getMessage(), null));
            });
        }
    }

    public View cancel(String id, String owner) {
        return store.transaction(() -> {
            Run run = store.lock(id, owner);
            if (run.status() == Status.COMPLETED || run.status() == Status.CANCELLED) return view(run);
            return view(store.save(run, Status.CANCELLED, "CANCELLED", "任务已取消", null));
        });
    }

    private View view(Run run) {
        return new View(run.id(), run.status(), run.revision(), run.state(), store.events(run.id()),
                business.submission(run.id(), run.ownerUserId()), run.leaseUntil());
    }

    private void trimObservations(State state) {
        if (state.getObservations().size() > 12) {
            state.setObservations(new java.util.ArrayList<>(state.getObservations().subList(state.getObservations().size() - 12, state.getObservations().size())));
        }
    }

    private static class StaleApprovalException extends RuntimeException { }
}
