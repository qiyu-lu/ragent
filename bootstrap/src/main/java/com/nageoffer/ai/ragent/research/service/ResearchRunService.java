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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.model.ResearchEvent;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import com.nageoffer.ai.ragent.research.model.ResearchRun.Status;
import com.nageoffer.ai.ragent.research.runtime.ResearchAgentFactory;
import com.nageoffer.ai.ragent.research.runtime.ResearchBudget;
import com.nageoffer.ai.ragent.research.runtime.ResearchControl;
import com.nageoffer.ai.ragent.research.runtime.ResearchRunner;
import com.nageoffer.ai.ragent.research.runtime.ResearchSession;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ResearchRunService {
    public record CreateRequest(@Size(max = 64) String conversationId,
                                 @NotBlank @Size(max = 128) String clientRequestId,
                                 @NotBlank @Size(max = 10000) String goal,
                                 @NotNull ResearchBrief.OutputType outputType,
                                 @Size(max = 20) List<@NotBlank @Size(max = 4000) String> constraints,
                                 @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 64) String> allowedKbIds,
                                 @Size(max = 200) List<@NotBlank @Size(max = 64) String> allowedDocIds) { }

    private static class Execution {
        final ResearchControl control = new ResearchControl();
    }

    private final ResearchRunStore store;
    private final ResearchRunner runner;
    private final ResearchProperties properties;
    private final ResearchCompletionService completion;
    private final ResearchEvidenceStore evidenceStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ThreadPoolExecutor tasks;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public ResearchRunService(ResearchRunStore store, ResearchRunner runner, ResearchProperties properties,
                               JdbcTemplate jdbc, ObjectMapper json, ResearchCompletionService completion, ResearchEvidenceStore evidenceStore) {
        properties.validate();
        this.store = store;
        this.runner = runner;
        this.properties = properties;
        this.jdbc = jdbc;
        this.json = json;
        this.completion = completion;
        this.evidenceStore = evidenceStore;
        AtomicInteger thread = new AtomicInteger();
        this.tasks = new ThreadPoolExecutor(properties.getMaxConcurrentRuns(), properties.getMaxConcurrentRuns(),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                action -> {
                    Thread worker = new Thread(action, "research-run-" + thread.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { store.interruptOrphans(); }

    public ResearchRun create(CreateRequest request) {
        String owner = owner();
        ResearchBrief brief;
        try { brief = new ResearchBrief(request.goal(), request.outputType(), request.constraints(),
                request.allowedKbIds(), request.allowedDocIds()); }
        catch (IllegalArgumentException e) { throw new ClientException(e.getMessage()); }
        // 幂等回查仍核对最初请求指纹，不因源文档后来停用而阻止读取已有任务。
        if (store.findRequest(owner, request.clientRequestId()).isEmpty()) validateScope(owner, request.conversationId(), brief);
        ResearchRun run = store.create(owner, request.conversationId(), request.clientRequestId(), brief);
        if (run.status() == Status.QUEUED) schedule(run, owner, UserContext.requireUser());
        return store.get(run.id(), owner);
    }

    public ResearchRun get(String id) { return store.get(id, owner()); }
    public List<com.nageoffer.ai.ragent.research.model.EvidenceRecord> sources(String id) {
        store.get(id, owner());
        return evidenceStore.readSources(id, owner());
    }
    public List<ResearchEvent> events(String id, long after, int limit) { return store.events(id, owner(), after, limit); }

    public List<ResearchRun> list(String conversationId) {
        validateConversation(owner(), conversationId);
        return store.list(conversationId, owner());
    }

    public ResearchRun regenerate(String id, String clientRequestId) {
        var previous = get(id);
        if (!previous.status().terminal()) throw new ClientException("运行尚未结束");
        var brief = previous.brief();
        return create(new CreateRequest(previous.conversationId(), clientRequestId, brief.goal(), brief.outputType(),
                brief.constraints(), brief.allowedKbIds(), brief.allowedDocIds()));
    }

    public ResearchRun input(String id, long revision, String answer) {
        String owner = owner();
        ResearchRun run = store.input(id, owner, revision, answer);
        schedule(run, owner, UserContext.requireUser());
        return store.get(id, owner);
    }

    public ResearchRun cancel(String id) {
        String owner = owner();
        ResearchRun run = store.cancel(id, owner);
        if (run.status() == Status.CANCELLED) {
            Execution active = executions.get(id);
            if (active != null) active.control.cancel();
        }
        return store.get(id, owner);
    }

    private void schedule(ResearchRun run, String owner, LoginUser user) {
        Execution execution = new Execution();
        if (executions.putIfAbsent(run.id(), execution) != null) return;
        try { tasks.execute(() -> execute(run.id(), owner, user, execution)); }
        catch (java.util.concurrent.RejectedExecutionException e) {
            executions.remove(run.id(), execution);
            store.rejectQueued(run.id(), owner);
        }
    }

    private void execute(String id, String owner, LoginUser user, Execution execution) {
        ResearchRunStore.Claim claim = null;
        ResearchSession session = null;
        UserContext.set(user);
        try {
            execution.control.check();
            claim = store.claim(id, owner, Duration.ofSeconds(properties.getMaxDurationSeconds() + 30L)).orElse(null);
            if (claim == null) return;
            session = new ResearchSession(store, claim, new ResearchBudget(properties, claim.run().usage()), execution.control);
            try {
                ResearchSession.Outcome outcome = runner.run(session);
                completion.complete(session, outcome, null);
            } catch (RuntimeException failure) {
                completion.researchFailed(session, failure);
            }
        } catch (RuntimeException failure) {
            if (claim != null && session != null) completion.researchFailed(session, failure);
        } finally {
            if (claim != null && session != null) store.cancelledLocally(claim, session.budget.snapshot());
            executions.remove(id, execution);
            UserContext.clear();
            // WAITING_INPUT 写入后 input 可能抢先到达，避免旧执行尚未移除导致恢复调度丢失。
            ResearchRun current = store.get(id, owner);
            if (current.status() == Status.QUEUED && !tasks.isShutdown()) schedule(current, owner, user);
        }
    }

    private void validateScope(String owner, String conversation, ResearchBrief brief) {
        if (conversation != null && !conversation.isBlank()) validateConversation(owner, conversation);
        for (String kb : brief.allowedKbIds()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM t_knowledge_base WHERE id = ? AND deleted = 0 AND collection_name IS NOT NULL AND collection_name <> '')",
                    Boolean.class, kb))) throw new ClientException("研究知识库不可用");
        }
        // 当前知识库是全局共享；归属保护针对研究运行与会话，不虚构文档租户 ACL。
        for (String doc : brief.allowedDocIds()) {
            List<String> bases = jdbc.query("SELECT kb_id FROM t_knowledge_document WHERE id = ? AND deleted = 0 AND enabled = 1",
                    (rs, row) -> rs.getString(1), doc);
            if (bases.size() != 1 || !brief.allowedKbIds().contains(bases.get(0))) throw new ClientException("研究文档不可用或超出知识库范围");
        }
    }

    private void validateConversation(String owner, String conversation) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM t_conversation WHERE conversation_id = ? AND user_id = ? AND deleted = 0)",
                Boolean.class, conversation, owner))) throw new ClientException("会话不存在或无权访问");
    }

    private String owner() { return UserContext.requireUser().getUserId(); }

    @PreDestroy
    public void close() {
        store.interruptOrphans();
        executions.values().forEach(execution -> execution.control.cancel());
        tasks.shutdownNow();
    }
}
