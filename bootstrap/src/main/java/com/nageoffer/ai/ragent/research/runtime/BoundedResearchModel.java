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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 在实际 HTTP 请求前分配额度，模型失败和取消也保留调用记录。 */
public class BoundedResearchModel implements Model {
    private static final int COMPACTION_TARGET_PERCENT = 60;
    private static final int STUB_MIN_CHARS = 600;
    private static final Pattern EVIDENCE_ID = Pattern.compile("\"evidenceId\"\\s*:\\s*\"([^\"]+)\"");
    private final Model delegate;
    private final ResearchSession session;
    private final ResearchProperties properties;
    private final Semaphore quota;
    private final ObjectMapper json;
    private final TokenCounterService tokens;
    private final boolean finalization;
    /** 压缩水位只增不减：最早的若干轮在之后每次调用里得到同样的存根或同样被移除。 */
    private int stubbedRounds;
    private int droppedRounds;

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
            if (cause instanceof io.agentscope.core.model.ModelHttpException http && http.getStatusCode() != null)
                return http.isRetryableHttpStatus();
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
            if (!finalization && session.retrievalBlocked() && !session.requiresRead()) session.requestFinishRepair();
            // 系统消息与历史逐字节不变，供应商前缀缓存才能命中；易变提醒只作为本次请求末尾的临时 user 消息，
            // 不写入 Agent 记忆（兼容端点对第二条 system 不可靠）。生成输入已按证据裁剪，不混入研究工具提示。
            Msg reminder = finalization ? null : Msg.builder().role(MsgRole.USER).textContent(reminder()).build();
            List<Msg> context = compact(messages, tools, reminder);
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
                session.budget.startCall(id, getModelName(), estimate(context, tools), finalization ? "finalization" : session.main() ? "main" : "worker", session.taskId);
                ToolChoice choice = session.finishingRepair() || session.remainingModelCalls(properties.getMaxWorkerModelCalls()) <= 1
                        ? new ToolChoice.Specific("finish_research") : new ToolChoice.Required();
                if (!session.finishingRepair() && session.remainingModelCalls(properties.getMaxWorkerModelCalls()) > 1
                        && session.requiresRead()) choice = new ToolChoice.Specific("read_source");
                GenerateOptions requested = tools == null || tools.isEmpty() ? options
                        : GenerateOptions.mergeOptions(GenerateOptions.builder().toolChoice(choice).build(), options);
                // No hidden SDK retries: each HTTP attempt gets its own budget/usage record here.
                var own = GenerateOptions.builder().executionConfig(io.agentscope.core.model.ExecutionConfig.builder().maxAttempts(1).build());
                // 显式缓存断点：系统消息与提醒之前的最新一条历史，见 PromptCacheFormatter。
                if (reminder != null && properties.isExplicitPromptCache()) own.cacheControl(true);
                GenerateOptions effective = GenerateOptions.mergeOptions(own.build(), requested);
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
                var cache = delegate instanceof PromptCacheTransport.TrackedModel tracked ? tracked.cache() : null;
                AtomicLong sent = new AtomicLong(Long.MIN_VALUE);
                AtomicLong firstChunk = new AtomicLong(Long.MIN_VALUE);
                java.util.function.Consumer<String> finish = status -> {
                    if (!recorded.compareAndSet(false, true)) return;
                    ChatUsage actual = usage.get();
                    long ended = System.nanoTime();
                    var written = cache == null ? null : cache.take();
                    var metrics = new ResearchBudget.CallMetrics(
                            sent.get() == Long.MIN_VALUE ? null : (ended - sent.get()) / 1_000_000,
                            firstChunk.get() == Long.MIN_VALUE ? null : (firstChunk.get() - sent.get()) / 1_000_000,
                            written == null ? null : written.creationTokens(), written == null ? null : written.type());
                    session.budget.finishCall(id, status, requestId.get(), actual == null ? null : actual.getInputTokens(),
                            actual == null ? null : actual.getOutputTokens(), actual == null ? null : actual.getCachedTokens(), metrics);
                    session.event("MODEL_ENDED", "研究模型请求已结束", Map.of("callId", id, "model", getModelName(), "status", status,
                            "nativeToolOutputSeen", nativeSeen.get(), "textOutputSeen", textSeen.get(), "finishReason", finishReason.get(),
                            "errorType", errorType.get(), "phase", finalization ? "finalization" : "research"));
                };
                Duration timeout = Duration.ofMillis(Math.min((finalization ? session.budget.remaining() : session.budget.explorationRemaining()).toMillis(),
                        properties.getModelCallTimeoutSeconds() * 1000L));
                return Flux.defer(() -> {
                            session.check();
                            if (cache != null) cache.take();
                            sent.set(System.nanoTime());
                            return delegate.stream(context, tools, effective);
                        }).doOnNext(response -> {
                            firstChunk.compareAndSet(Long.MIN_VALUE, System.nanoTime());
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

    private String reminder() {
        int remainingCalls = session.remainingModelCalls(properties.getMaxWorkerModelCalls()) - 1;
        String reminder = "Server budget reminder: after this request "
                + remainingCalls + " exploration model calls remain. When at most 2 remain, stop opening new searches, "
                + "read only indispensable evidence and call finish_research with already-read findings and explicit gaps. "
                + "Do not spend the finalization reserve. Missing source facts must remain gaps.";
        reminder += "\nExact evidence IDs currently permitted in findings[].evidenceIds: " + new TreeSet<>(session.citableIds());
        if (session.retrievalBlocked()) reminder += "\nRetrieval has reached its consecutive failure limit. "
                + "Do not search again or delegate more searches. Read any pending relevant candidates, then call finish_research "
                + "with already-read findings. Preserve execution issues; an unavailable service does not establish a source gap "
                + "and cannot be repaired by asking the user to provide source facts. If no supported finding or verified source gap "
                + "exists, empty findings/gaps/conflicts arrays are valid because the server preserves the execution issues.";
        if (session.main() && !session.results().isEmpty()) {
            try { reminder += "\nValidated compressed worker results (read proof checked by server): "
                    + json.writeValueAsString(session.results()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("子任务摘要序列化失败", error); }
        } else if (!session.main()) {
            reminder += "\nOnly these IDs have actually been read by this worker and may be cited: " + new TreeSet<>(session.citableIds())
                    + ". Unread IDs from the latest search: " + new TreeSet<>(session.unreadCandidates())
                    + ". Read immediately after a search, before opening another search. When forced to finish, use ONLY the read IDs;"
                    + " omit unsupported findings and describe gaps. Do not attach a read ID to a fact only seen in another candidate.";
        }
        return reminder + "\nReading coverage: " + session.readingCoverage()
                + ". Scope may contain distractors: read each TARGET document for comparisons, not every allowed document. "
                + "Before another search, read a relevant pending candidate; seek only specific unresolved facts. "
                + "A related-work mention is not proof a method was an experimental baseline. Check lists before claiming items are missing.";
    }

    /**
     * 超过上限时一次压到预算的约 60%：先把最早的工具结果换成可重读的存根（保留 tool_call 配对与证据 ID），
     * 仍不够再整轮移除；最新一轮观察始终完整。水位保存在本实例，之后每次调用得到同一前缀，直到再次越线。
     */
    synchronized List<Msg> compact(List<Msg> messages, List<ToolSchema> tools, Msg reminder) {
        List<Integer> rounds = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).getRole() == MsgRole.ASSISTANT) rounds.add(i);
        List<Msg> result = view(messages, rounds, reminder);
        int before = estimate(result, tools);
        if (before <= properties.getMaxInputTokens()) return result;
        long target = (long) properties.getMaxInputTokens() * COMPACTION_TARGET_PERCENT / 100;
        int after = before;
        while (after > target && droppedRounds < rounds.size() - 1) {
            if (stubbedRounds < rounds.size() - 1) stubbedRounds++;
            else droppedRounds++;
            result = view(messages, rounds, reminder);
            after = estimate(result, tools);
        }
        if (after > properties.getMaxInputTokens()) throw new ResearchBudget.Exhausted("MODEL_CONTEXT_BUDGET");
        session.event("CONTEXT_COMPACTED", "上下文超过上限，已一次性压缩最早的工具往返",
                Map.of("estimatedTokensBefore", before, "estimatedTokensAfter", after,
                        "stubbedRounds", stubbedRounds, "droppedRounds", droppedRounds));
        return result;
    }

    private List<Msg> view(List<Msg> messages, List<Integer> rounds, Msg reminder) {
        int first = rounds.isEmpty() ? messages.size() : rounds.get(0);
        int stubEnd = boundary(rounds, stubbedRounds, messages.size());
        int dropEnd = boundary(rounds, droppedRounds, messages.size());
        List<Msg> result = new ArrayList<>(messages.size() + 1);
        for (int i = 0; i < messages.size(); i++) {
            if (i >= first && i < dropEnd) continue;
            Msg message = messages.get(i);
            result.add(i >= first && i < stubEnd && message.getRole() == MsgRole.TOOL ? stub(message) : message);
        }
        if (reminder != null) result.add(reminder);
        return result;
    }

    /** 前 count 轮之后的第一个位置；每轮从一条 assistant 消息开始，到下一条 assistant 之前。 */
    private static int boundary(List<Integer> rounds, int count, int size) {
        if (count <= 0) return 0;
        return count < rounds.size() ? rounds.get(count) : size;
    }

    /** 可恢复的存根：保留调用 ID 与返回过的证据 ID，提示用 read_source 重读；短结果原样保留。 */
    private static Msg stub(Msg message) {
        List<ContentBlock> content = new ArrayList<>();
        boolean changed = false;
        for (ContentBlock block : message.getContent()) {
            StringBuilder text = new StringBuilder();
            if (block instanceof ToolResultBlock result) for (ContentBlock part : result.getOutput()) {
                if (part instanceof TextBlock output && output.getText() != null) text.append(output.getText());
            }
            if (!(block instanceof ToolResultBlock result) || text.length() <= STUB_MIN_CHARS) {
                content.add(block);
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (var matcher = EVIDENCE_ID.matcher(text); matcher.find(); ) ids.add(matcher.group(1));
            String stub = "[Compacted by the server to keep the context within budget: the full output of this earlier "
                    + result.getName() + " call was removed." + (ids.isEmpty() ? "" : " Evidence IDs it returned: " + String.join(", ", ids) + ".")
                    + " Call read_source with an evidence ID to see that text again; evidence already read stays citable.]";
            content.add(new ToolResultBlock(result.getId(), result.getName(), List.of(TextBlock.builder().text(stub).build()),
                    result.getMetadata(), result.getState()));
            changed = true;
        }
        return changed ? message.withContent(content) : message;
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
