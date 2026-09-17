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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** worker 使用专用有界线程池，父 Agent 不占用此池等待子任务。 */
public class ResearchWorkerCoordinator implements AutoCloseable {
    private final ResearchProperties properties;
    private final KnowledgeSearchService search;
    private final ResearchRunner runner;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final Scheduler scheduler;

    public ResearchWorkerCoordinator(ResearchProperties properties, KnowledgeSearchService search,
                                      ResearchRunner runner, ObjectMapper json) {
        this.properties = properties;
        this.search = search;
        this.runner = runner;
        this.json = json;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(properties.getMaxConcurrentWorkers(), properties.getMaxConcurrentWorkers(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()), r -> {
                    Thread thread = new Thread(r, "research-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        scheduler = Schedulers.fromExecutorService(workers);
    }

    public MainTools tools(ResearchSession session) { return new MainTools(session); }
    public class MainTools {
        private final ResearchSession parent;
        private final LoginUser user;
        MainTools(ResearchSession parent) { this.parent = parent; this.user = UserContext.get(); }
        @Tool(name = "conduct_research", description = "Main Agent only: submit 1—4 independent subgoals in one batch. Each task requires goal, dimensions, expectedOutput; documentIds is optional and may only narrow the saved scope. At most 2 workers execute concurrently and 4 can be created over the entire run. Returns compressed SubtaskResult values with read evidence IDs, never worker histories. Keep dependent research serial.")
        public Mono<Object> conduct(@ToolParam(name = "tasks") List<ResearchTask> tasks) {
            return Mono.defer(() -> delegate(parent, tasks, user)).onErrorResume(error -> {
                if (error instanceof ClientException || error instanceof IllegalArgumentException
                        || error instanceof ResearchBudget.Exhausted && "WORKER_COUNT_BUDGET".equals(error.getMessage())) {
                    return Mono.just(ToolResultBlock.error(error.getMessage()));
                }
                return Mono.error(error);
            });
        }
    }

    private Mono<Object> delegate(ResearchSession parent, List<ResearchTask> requests, LoginUser user) {
        parent.check();
        if (!parent.main() || parent.outcome() != null) throw new ClientException("仅运行中的主 Agent 可以委派");
        if (requests == null || requests.isEmpty() || requests.size() > 4 || requests.stream().anyMatch(Objects::isNull)) {
            throw new ClientException("一次委派必须包含 1—4 个明确的独立子任务");
        }
        List<ResearchTask> tasks = new ArrayList<>();
        Set<String> goals = new HashSet<>();
        for (ResearchTask request : requests) {
            if (!goals.add(request.goal().strip().toLowerCase(Locale.ROOT))) throw new ClientException("同批子目标不能重复");
            var docs = search.validateDocumentScope(parent.claim.run().id(), parent.claim.owner(), request.documentIds());
            tasks.add(new ResearchTask(request.goal(), request.dimensions(), request.expectedOutput(), docs));
        }
        int first = parent.reserveTasks(tasks);
        List<ResearchSession> sessions = new ArrayList<>();
        List<AutoCloseable> bindings = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.size(); i++) {
                var child = parent.worker("worker-" + (first + i), tasks.get(i));
                sessions.add(child);
                bindings.add(parent.control.bindInterrupt(child.control::cancel));
                if (!parent.checkpoint(child, "QUEUED", null, json)) throw new CancellationException("RESEARCH_LEASE_SUPERSEDED");
            }
            return Flux.fromIterable(sessions).flatMapSequential(child -> {
                Duration timeout = Duration.ofMillis(Math.min(parent.budget.remaining().toMillis(),
                        properties.getWorkerTimeoutSeconds() * 1000L));
                return Mono.fromCallable(() -> {
                    UserContext.set(user);
                    try {
                        child.check();
                        if (!parent.checkpoint(child, "RUNNING", null, json)) throw new CancellationException("RESEARCH_LEASE_SUPERSEDED");
                        var outcome = runner.run(child);
                        child.check();
                        if (outcome.question() != null || outcome.result() == null) throw new IllegalStateException("WORKER_RESULT_REQUIRED");
                        child.validateResult(outcome.result());
                        return outcome.result();
                    } finally { UserContext.clear(); }
                }).subscribeOn(scheduler).timeout(timeout)
                    .onErrorResume(error -> {
                        child.control.cancel();
                        parent.check();
                        Throwable root = error;
                        while (root.getCause() != null && !(root instanceof ResearchBudget.Exhausted)) root = root.getCause();
                        String reason = root instanceof ResearchBudget.Exhausted ? root.getMessage()
                                : root instanceof TimeoutException ? "WORKER_TIMEOUT" : "WORKER_EXECUTION_FAILED";
                        var status = child.delivered().isEmpty() ? SubtaskResult.Status.FAILED : SubtaskResult.Status.PARTIAL;
                        if (!child.delivered().isEmpty()) reason += ": " + child.delivered().size()
                                + " evidence excerpt(s) read; no validated findings returned. Retained read IDs: " + child.delivered().keySet();
                        return Mono.just(new SubtaskResult(child.taskId, List.of(), List.of(reason), List.of(), status));
                    }).doOnNext(result -> {
                        parent.check();
                        // 结果写入由订阅者完成，取消/超时后 callable 的迟到返回不会经过这里。
                        if (!parent.checkpoint(child, result.status().name(), result, json)) throw new CancellationException("RESEARCH_LEASE_SUPERSEDED");
                        parent.accept(result, child.delivered().keySet());
                    });
            }, properties.getMaxConcurrentWorkers(), 1).collectList().map(results -> (Object) results)
                .doFinally(signal -> release(sessions, bindings));
        } catch (RuntimeException error) {
            release(sessions, bindings);
            throw error;
        }
    }

    private static void release(List<ResearchSession> sessions, List<AutoCloseable> bindings) {
        sessions.forEach(s -> s.control.cancel());
        bindings.forEach(binding -> { try { binding.close(); } catch (Exception ignored) { } });
    }
    @Override public void close() { scheduler.dispose(); workers.shutdownNow(); }
}
