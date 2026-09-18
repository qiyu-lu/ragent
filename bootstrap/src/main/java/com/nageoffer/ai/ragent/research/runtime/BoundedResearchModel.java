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
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ToolChoice;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 在实际 HTTP 请求前分配额度，模型失败和取消也保留调用记录。 */
public class BoundedResearchModel implements Model {
    private final Model delegate;
    private final ResearchSession session;
    private final ResearchProperties properties;
    private final Semaphore quota;
    private final ObjectMapper json;
    private final TokenCounterService tokens;
    private final boolean finalization;

    public BoundedResearchModel(Model delegate, ResearchSession session, ResearchProperties properties,
                                 Semaphore quota, ObjectMapper json, TokenCounterService tokens) {
        this(delegate, session, properties, quota, json, tokens, false);
    }

    public BoundedResearchModel(Model delegate, ResearchSession session, ResearchProperties properties,
                                 Semaphore quota, ObjectMapper json, TokenCounterService tokens, boolean finalization) {
        this.delegate = delegate;
        this.session = session;
        this.properties = properties;
        this.quota = quota;
        this.json = json;
        this.tokens = tokens;
        this.finalization = finalization;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return attempt(messages, tools, options).retryWhen(reactor.util.retry.Retry.backoff(1, Duration.ofMillis(300))
                .jitter(0.2).filter(error -> !session.control.cancelled() && retryable(error))
                .doBeforeRetry(signal -> session.event("MODEL_RETRY", "模型传输失败，保留已完成步骤后重试",
                        Map.of("attempt", 2, "reason", signal.failure().getClass().getSimpleName()))))
                .takeUntilOther(session.control.signal());
    }

    static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.CancellationException || cause instanceof ResearchBudget.Exhausted) return false;
            if (cause instanceof io.agentscope.core.model.ModelHttpException http) return http.isRetryableHttpStatus();
            if (cause instanceof java.io.IOException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof IncompleteResponse) return true;
        }
        return false;
    }
    static final class IncompleteResponse extends RuntimeException {
        IncompleteResponse() { super("MODEL_STREAM_INCOMPLETE"); }
    }
    private Flux<ChatResponse> attempt(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            session.check();
            List<Msg> withBudget = new ArrayList<>(messages);
            int remainingCalls = session.remainingModelCalls(properties.getMaxWorkerModelCalls()) - 1;
            String reminder = "Server budget reminder: after this request "
                    + remainingCalls + " exploration model calls remain. When at most 2 remain, stop opening new searches, "
                    + "read only indispensable evidence and call finish_research with already-read findings and explicit gaps. "
                    + "Do not spend the finalization reserve. Missing source facts must remain gaps.";
            reminder += "\nExact evidence IDs currently permitted in findings[].evidenceIds: " + session.citableIds();
            if (session.main() && !session.results().isEmpty()) {
                try { reminder += "\nValidated compressed worker results (read proof checked by server): "
                        + json.writeValueAsString(session.results()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("子任务摘要序列化失败", error); }
            } else if (!session.main()) {
                reminder += "\nOnly these IDs have actually been read by this worker and may be cited: " + session.citableIds()
                        + ". Unread IDs from the latest search: " + session.unreadCandidates()
                        + ". Read immediately after a search, before opening another search. When forced to finish, use ONLY the read IDs;"
                        + " omit unsupported findings and describe gaps. Do not attach a read ID to a fact only seen in another candidate.";
            }
            reminder += "\nReading coverage: " + session.readingCoverage()
                    + ". Scope may contain distractors: read each TARGET document for comparisons, not every allowed document. "
                    + "Before another search, read a relevant pending candidate; seek only specific unresolved facts. "
                    + "A related-work mention is not proof a method was an experimental baseline. Check lists before claiming items are missing.";
            // 兼容端点通常只可靠处理开头的系统指令，不在工具结果后追加第二条 system。
            if (finalization) {
                // 生成输入已按证据裁剪，不混入要求调用研究工具的提示。
            } else if (!withBudget.isEmpty() && withBudget.get(0).getRole() == MsgRole.SYSTEM) {
                withBudget.set(0, Msg.builder().role(MsgRole.SYSTEM)
                        .textContent(withBudget.get(0).getTextContent() + "\n" + reminder).build());
            } else withBudget.add(0, Msg.builder().role(MsgRole.SYSTEM).textContent(reminder).build());
            List<Msg> trimmed = trim(withBudget, tools);
            try {
                long until = System.nanoTime() + Math.min((finalization ? session.budget.remaining() : session.budget.explorationRemaining()).toNanos(),
                        Duration.ofSeconds(properties.getModelCallTimeoutSeconds()).toNanos());
                while (!quota.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    session.check();
                    if (System.nanoTime() >= until) throw new ResearchBudget.Exhausted("MODEL_QUOTA_TIMEOUT");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new java.util.concurrent.CancellationException("MODEL_QUOTA_INTERRUPTED"));
            }
            try {
                session.check();
                if (finalization) session.budget.acquireModel(true);
                else session.acquireModel(properties.getMaxWorkerModelCalls());
            } catch (RuntimeException e) {
                quota.release();
                return Flux.error(e);
            }
            try {
                String id = UUID.randomUUID().toString();
                session.budget.startCall(id, getModelName(), estimate(trimmed, tools), finalization ? "finalization" : session.main() ? "main" : "worker", session.taskId);
                ToolChoice choice = session.finishingRepair() || session.remainingModelCalls(properties.getMaxWorkerModelCalls()) <= 1
                        ? new ToolChoice.Specific("finish_research") : new ToolChoice.Required();
                if (!session.finishingRepair() && session.remainingModelCalls(properties.getMaxWorkerModelCalls()) > 1
                        && session.requiresRead()) choice = new ToolChoice.Specific("read_source");
                GenerateOptions effective = tools == null || tools.isEmpty() ? options
                        : GenerateOptions.mergeOptions(GenerateOptions.builder().toolChoice(choice).build(), options);
                session.event("MODEL_STARTED", "正在调用研究模型", Map.of("callId", id, "model", getModelName(),
                        "requestedToolChoice", tools == null || tools.isEmpty() ? "none"
                                : choice instanceof ToolChoice.Specific specific
                                    ? Map.of("type", "function", "function", Map.of("name", specific.toolName())) : "required",
                        "toolSchemaSha256", schemaHash(tools), "finishRepair", !finalization && session.finishingRepair()));
                AtomicReference<ChatUsage> usage = new AtomicReference<>();
                AtomicReference<String> requestId = new AtomicReference<>();
                AtomicBoolean nativeSeen = new AtomicBoolean();
                AtomicBoolean textSeen = new AtomicBoolean();
                AtomicReference<String> finishReason = new AtomicReference<>("unknown");
                AtomicReference<String> errorType = new AtomicReference<>("none");
                AtomicBoolean recorded = new AtomicBoolean();
                java.util.function.Consumer<String> finish = status -> {
                    if (!recorded.compareAndSet(false, true)) return;
                    ChatUsage actual = usage.get();
                    session.budget.finishCall(id, status, requestId.get(), actual == null ? null : actual.getInputTokens(),
                            actual == null ? null : actual.getOutputTokens(), actual == null ? null : actual.getCachedTokens());
                    session.event("MODEL_ENDED", "研究模型请求已结束", Map.of("callId", id, "model", getModelName(), "status", status,
                            "nativeToolOutputSeen", nativeSeen.get(), "textOutputSeen", textSeen.get(), "finishReason", finishReason.get(),
                            "errorType", errorType.get(), "phase", finalization ? "finalization" : "research"));
                };
                Duration timeout = Duration.ofMillis(Math.min((finalization ? session.budget.remaining() : session.budget.explorationRemaining()).toMillis(),
                        properties.getModelCallTimeoutSeconds() * 1000L));
                return Flux.defer(() -> {
                            session.check();
                            return delegate.stream(trimmed, tools, effective);
                        }).doOnNext(response -> {
                            if (response.getUsage() != null) usage.set(response.getUsage());
                            requestId.set(response.getId());
                            if (response.getFinishReason() != null) finishReason.set(response.getFinishReason());
                            if (response.getContent() != null) for (var block : response.getContent()) {
                                if (block instanceof io.agentscope.core.message.ToolUseBlock) nativeSeen.set(true);
                                if (block instanceof io.agentscope.core.message.TextBlock) textSeen.set(true);
                            }
                        })
                        // 整次请求超时，不能靠持续发小数据包无限延长 idle timeout。
                        .collectList().timeout(timeout).flatMapMany(responses -> {
                            if (responses.isEmpty() || "unknown".equals(finishReason.get())) return Flux.error(new IncompleteResponse());
                            return Flux.fromIterable(responses);
                        })
                        .doOnComplete(() -> finish.accept("COMPLETED"))
                        .doOnError(error -> {
                            errorType.set(error.getClass().getSimpleName());
                            finish.accept(error instanceof java.util.concurrent.TimeoutException ? "TIMED_OUT" : "FAILED");
                        })
                        .doOnCancel(() -> finish.accept("CANCELLED"))
                        .doFinally(signal -> quota.release());
            } catch (RuntimeException error) {
                quota.release();
                return Flux.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String schemaHash(List<ToolSchema> tools) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(tools == null ? List.of() : tools).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("工具契约指纹生成失败", error); }
    }

    /** 整组删除最早 assistant/tool 往返，保留系统指令、用户目标和最新完整观察。 */
    List<Msg> trim(List<Msg> messages, List<ToolSchema> tools) {
        List<Msg> result = new ArrayList<>(messages);
        int removed = 0;
        while (estimate(result, tools) > properties.getMaxInputTokens()) {
            int start = -1, end = -1;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).getRole() == MsgRole.ASSISTANT) {
                    if (start < 0) start = i;
                    else { end = i; break; }
                }
            }
            if (start < 0 || end < 0) throw new ResearchBudget.Exhausted("MODEL_CONTEXT_BUDGET");
            removed += end - start;
            result.subList(start, end).clear();
        }
        if (removed > 0) session.event("CONTEXT_TRIMMED", "已裁剪最早的完整工具往返",
                Map.of("removedMessages", removed, "estimatedInputTokens", estimate(result, tools)));
        return result;
    }

    private int estimate(List<Msg> messages, List<ToolSchema> tools) {
        try {
            Integer count = tokens.countTokens(json.writeValueAsString(Map.of("messages", messages, "tools", tools == null ? List.of() : tools)));
            if (count == null) throw new IllegalStateException("研究上下文缺少 token 估算");
            return count;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("研究上下文估算失败", e);
        }
    }

    @Override public String getModelName() { return delegate.getModelName(); }
}
