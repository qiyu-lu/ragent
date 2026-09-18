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
import com.nageoffer.ai.ragent.research.runtime.ResearchBudget;
import com.nageoffer.ai.ragent.research.runtime.ResearchControl;
import com.nageoffer.ai.ragent.research.runtime.ResearchRunner;
import com.nageoffer.ai.ragent.research.runtime.ResearchSession;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 研究任务的执行者。数据库是队列与事实来源：本实例只执行自己持有租约的任务，定时续租；
 * 租约过期（执行者失联）或排队中的任务由任一实例轮询领取。本地线程池满时任务留在库里等待，不判失败。
 */
@Slf4j
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
        volatile ResearchRunStore.Claim claim;
    }

    private final ResearchRunStore store;
    private final ResearchRunner runner;
    private final ResearchProperties properties;
    private final ResearchCompletionService completion;
    private final ResearchEvidenceStore evidenceStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ThreadPoolExecutor tasks;
    private final ScheduledExecutorService leases;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final String executorId;
    private final Duration lease;
    private volatile boolean accepting = true;

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
        this.executorId = java.lang.management.ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);
        this.lease = Duration.ofSeconds(properties.getLeaseSeconds());
        AtomicInteger thread = new AtomicInteger();
        this.tasks = new ThreadPoolExecutor(properties.getMaxConcurrentRuns(), properties.getMaxConcurrentRuns(),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                action -> {
                    Thread worker = new Thread(action, "research-run-" + thread.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.leases = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread worker = new Thread(action, "research-lease");
            worker.setDaemon(true);
            return worker;
        });
        leases.scheduleAtFixedRate(this::heartbeat, properties.getHeartbeatSeconds(), properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
    }

    /** 应用就绪后才开始领取库里的任务；测试可直接调用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startPolling() {
        leases.scheduleWithFixedDelay(this::poll, 0, properties.getPollSeconds(), TimeUnit.SECONDS);
    }

    public String executorId() { return executorId; }

    public ResearchRun create(CreateRequest request) {
        String owner = owner();
        ResearchBrief brief;
        try { brief = new ResearchBrief(request.goal(), request.outputType(), request.constraints(),
                request.allowedKbIds(), request.allowedDocIds()); }
        catch (IllegalArgumentException e) { throw new ClientException(e.getMessage()); }
        // 幂等回查仍核对最初请求指纹，不因源文档后来停用而阻止读取已有任务。
        if (store.findRequest(owner, request.clientRequestId()).isEmpty()) validateScope(owner, request.conversationId(), brief);
        ResearchRun run = store.create(owner, request.conversationId(), request.clientRequestId(), brief);
        if (run.status() == Status.QUEUED) schedule(run.id(), owner, UserContext.requireUser());
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
        store.input(id, owner, revision, answer);
        schedule(id, owner, UserContext.requireUser());
        return store.get(id, owner);
    }

    /** 取消写库即生效；执行在其他实例上时，对方下一次续租或写入被拒后自行停止。 */
    public ResearchRun cancel(String id) {
        String owner = owner();
        ResearchRun run = store.cancel(id, owner);
        if (run.status() == Status.CANCELLED) {
            Execution active = executions.get(id);
            if (active != null) active.control.cancel();
        }
        return store.get(id, owner);
    }

    /** 本地快速路径：创建或补充条件后直接交给本机线程池；池满时留在库里等轮询。 */
    private void schedule(String id, String owner, LoginUser user) {
        if (!accepting) return;
        Execution execution = new Execution();
        if (executions.putIfAbsent(id, execution) != null) return;
        try { tasks.execute(() -> execute(id, owner, user, execution)); }
        catch (RejectedExecutionException e) { executions.remove(id, execution); }
    }

    /** 按空闲槽位领取排队中与租约过期的任务；已被其他实例锁住的行由 SKIP LOCKED 跳过。 */
    void poll() {
        try {
            int free = properties.getMaxConcurrentRuns() - executions.size();
            if (!accepting || free < 1) return;
            for (var claim : store.claimAvailable(executorId, lease, free, properties.getMaxTakeovers())) {
                Execution execution = new Execution();
                execution.claim = claim;
                String id = claim.run().id();
                if (executions.putIfAbsent(id, execution) != null) { store.release(claim, "LOCAL_DUPLICATE"); continue; }
                try {
                    LoginUser user = user(claim.owner());
                    tasks.execute(() -> execute(id, claim.owner(), user, execution));
                } catch (RuntimeException e) {
                    executions.remove(id, execution);
                    store.release(claim, "LOCAL_REJECTED");
                }
            }
        } catch (RuntimeException e) {
            log.warn("Research run poll failed on {}", executorId, e);
        }
    }

    /** 续租失败说明任务已被接管、取消或结束：立即停止本地执行，旧执行者的迟到写入由 epoch 与 lease_token 拒绝。 */
    void heartbeat() {
        for (var entry : executions.entrySet()) {
            var claim = entry.getValue().claim;
            if (claim == null) continue;
            try {
                if (!store.renew(claim, lease)) {
                    log.info("Research lease lost for {} on {}; stopping the local execution", entry.getKey(), executorId);
                    entry.getValue().control.cancel();
                }
            } catch (RuntimeException e) {
                // 数据库暂时不可用时不自杀；租约过期前恢复即可续上，否则写入会被拒绝。
                log.warn("Research lease renewal failed for {}", entry.getKey(), e);
            }
        }
    }

    private void execute(String id, String owner, LoginUser user, Execution execution) {
        ResearchRunStore.Claim claim = execution.claim;
        ResearchSession session = null;
        UserContext.set(user);
        try {
            execution.control.check();
            if (claim == null) {
                if (!accepting) return;
                claim = store.claim(id, owner, executorId, lease, properties.getMaxTakeovers()).orElse(null);
                if (claim == null) return;
                execution.claim = claim;
            }
            session = new ResearchSession(store, claim, new ResearchBudget(properties, claim.run().usage()), execution.control);
            resume(session);
            try {
                ResearchSession.Outcome outcome = runner.run(session);
                completion.complete(session, outcome, null);
            } catch (RuntimeException failure) {
                ended(session, execution, failure);
            }
        } catch (RuntimeException failure) {
            if (claim != null && session != null) ended(session, execution, failure);
        } finally {
            if (claim != null && session != null) store.cancelledLocally(claim, session.budget.snapshot());
            executions.remove(id, execution);
            UserContext.clear();
            // WAITING_INPUT 写入后 input 可能抢先到达，避免旧执行尚未移除导致恢复调度丢失。
            ResearchRun current = store.get(id, owner);
            if (current.status() == Status.QUEUED && !tasks.isShutdown()) schedule(id, owner, user);
        }
    }

    /** 停机途中在步边界停下的执行把任务交还队列，由其他实例从已持久化的历史续跑；其余失败照常落库。 */
    private void ended(ResearchSession session, Execution execution, RuntimeException failure) {
        if ((execution.control.handoverRequested() || ResearchControl.shuttingDown(failure)) && !execution.control.cancelled()) {
            store.release(session.claim, "SHUTDOWN");
        }
        else completion.researchFailed(session, failure);
    }

    /** 本阶段已有工具历史（接管或停机交还后的再次领取）时，从事件恢复对话与本地状态，而不是从头执行。 */
    private void resume(ResearchSession session) {
        var claim = session.claim;
        List<ResearchEvent> events = new java.util.ArrayList<>();
        for (long after = 0; ; ) {
            var page = store.events(claim.run().id(), claim.owner(), after, 500);
            events.addAll(page);
            if (page.size() < 500) break;
            after = page.get(page.size() - 1).sequence();
        }
        var history = com.nageoffer.ai.ragent.research.runtime.ResearchHistory.from(events);
        if (history != null) session.resume(history, id -> evidenceStore.find(claim.run().id(), claim.owner(), id).evidence(), json);
    }

    /** 接管的任务没有原请求线程：按 owner_user_id 从用户表重建身份；用户已不存在时只保留 ID，不带角色。 */
    private LoginUser user(String owner) {
        var users = jdbc.query("SELECT id, username, role, avatar FROM t_user WHERE id = ? AND deleted = 0",
                (rs, row) -> LoginUser.builder().userId(rs.getString("id")).username(rs.getString("username"))
                        .role(rs.getString("role")).avatar(rs.getString("avatar")).build(), owner);
        return users.isEmpty() ? LoginUser.builder().userId(owner).build() : users.get(0);
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

    /**
     * 优雅停机：停止领取；在途任务不打断当前的模型调用或工具，在下一步开始前把租约交还队列，其他实例立即可领；
     * 宽限期内没走到步边界的直接交还并取消本地执行，被取消的执行因写保护无法把任务写成失败。
     */
    @PreDestroy
    public synchronized void close() {
        if (!accepting && tasks.isShutdown()) return;
        accepting = false;
        executions.values().forEach(execution -> execution.control.requestHandover());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getShutdownGraceSeconds());
        while (executions.values().stream().anyMatch(execution -> execution.claim != null) && System.nanoTime() < until) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        leases.shutdownNow();
        executions.values().forEach(execution -> {
            var claim = execution.claim;
            try { if (claim != null) store.release(claim, "SHUTDOWN_GRACE_EXPIRED"); }
            catch (RuntimeException e) { log.warn("Research lease release failed for {}", claim.run().id(), e); }
            execution.control.cancel();
        });
        tasks.shutdownNow();
    }
}
