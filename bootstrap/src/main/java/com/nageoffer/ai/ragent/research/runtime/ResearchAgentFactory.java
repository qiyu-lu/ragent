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
import com.nageoffer.ai.ragent.research.model.ResearchModelRole;
import com.nageoffer.ai.ragent.research.service.KnowledgeSearchService;
import com.nageoffer.ai.ragent.research.service.SourceReader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

@Component
public class ResearchAgentFactory implements ResearchRunner, AutoCloseable {
    public static final String PROMPT_VERSION = "research-main-v4";
    private final ResearchModelFactory models;
    private final ResearchProperties properties;
    private final KnowledgeSearchService search;
    private final SourceReader reader;
    private final ObjectMapper json;
    private final TokenCounterService tokens;
    private final Semaphore modelQuota;
    private final String prompt;
    public static final String WORKER_PROMPT_VERSION = "research-worker-v3";
    private final String workerPrompt;
    private final ResearchWorkerCoordinator coordinator;
    private final boolean delegationEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public ResearchAgentFactory(ResearchModelFactory models, ResearchProperties properties,
                                 KnowledgeSearchService search, SourceReader reader, ObjectMapper json,
                                 TokenCounterService tokens) {
        this(models, properties, search, reader, json, tokens, true);
    }

    /** 离线对照可关闭委派，其他工具、预算和模型保持一致；在线入口默认启用。 */
    public ResearchAgentFactory(ResearchModelFactory models, ResearchProperties properties,
                                 KnowledgeSearchService search, SourceReader reader, ObjectMapper json,
                                 TokenCounterService tokens, boolean delegationEnabled) {
        properties.validate();
        this.delegationEnabled = delegationEnabled;
        this.models = models;
        this.properties = properties;
        this.search = search;
        this.reader = reader;
        this.json = json;
        this.tokens = tokens;
        this.modelQuota = models.quota();
        String mainPrompt = load(PROMPT_VERSION);
        this.prompt = delegationEnabled ? mainPrompt : mainPrompt.substring(0, mainPrompt.indexOf("You are the main coordinator."))
                + "\nYou are the single researcher. Only your own search/read/ask/finish tools are available. Research directly and sequentially; do not request delegation.\n";
        this.workerPrompt = load(WORKER_PROMPT_VERSION);
        this.coordinator = new ResearchWorkerCoordinator(properties, search, this::run, json);
    }

    @Override
    public ResearchSession.Outcome run(ResearchSession session) {
        session.check();
        Toolkit tools = new ResearchToolkit(json);
        if (session.main()) {
            session.restoreResults(json);
            tools.registerTool(new ResearchTools(session, search, reader));
            if (delegationEnabled) tools.registerTool(coordinator.tools(session));
        } else tools.registerTool(new ResearchWorkerTools(session, search, reader));
        var context = RuntimeContext.builder().userId(session.claim.owner())
                .sessionId(session.claim.run().id() + ":" + session.claim.run().epoch() + ":" + session.taskId).build();
        var role = session.main() ? ResearchModelRole.MAIN : ResearchModelRole.WORKER;
        var model = new BoundedResearchModel(models.create(role), session, properties, modelQuota, json, tokens);
        try (ReActAgent agent = ReActAgent.builder().name("research-" + session.taskId).sysPrompt(session.main() ? prompt : workerPrompt)
                .model(model).toolkit(tools).enableMetaTool(false).enablePendingToolRecovery(false)
                .maxRetries(1).maxIters(properties.getMaxModelCalls())
                .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(properties.getMaxDurationSeconds())).build())
                .toolExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(session.main() ? properties.getMaxDurationSeconds()
                                : properties.getToolTimeoutSeconds())).build())
                .middleware(new ToolProgress(session, properties)).build();
             var cancellation = session.control.bindInterrupt(() -> agent.interrupt(context))) {
            Map<String, Object> saved = new java.util.HashMap<>(session.claim.run().state());
            saved.remove("requestHash");
            // 按键排序：同一任务在任何进程里重建的首条消息逐字节一致，缓存前缀才能跨实例、跨续跑复用。
            var stable = json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            ResearchHistory history = session.main() ? session.history() : null;
            List<Msg> input;
            if (history == null) {
                String request = session.main()
                        ? stable.writeValueAsString(Map.of("brief", session.claim.run().brief(), "savedResearchState", saved))
                        : stable.writeValueAsString(Map.of("task", session.task, "outputType", session.claim.run().brief().outputType(),
                                "constraints", session.claim.run().brief().constraints(),
                                "allowedKbIds", session.claim.run().brief().allowedKbIds()));
                Map<String, Object> started = new java.util.LinkedHashMap<>();
                started.put("promptVersion", session.main() ? PROMPT_VERSION : WORKER_PROMPT_VERSION);
                started.put("model", model.getModelName());
                started.put("role", role.key());
                // 主 Agent 的原始首条请求随事件持久化，接管后据此重建逐字节相同的前缀。
                if (session.main()) started.put("request", request);
                session.event("RESEARCH_STARTED", "正在按目标检索和阅读", started);
                input = List.of(Msg.builder().role(MsgRole.USER).textContent(request).build());
            } else {
                // 接管：预载已完成的工具往返，从中断处继续；在途的那一步丢弃并由模型重做。
                var unreported = session.results().stream().map(r -> r.taskId())
                        .filter(id -> !history.reportedWorkers(json).contains(id)).toList();
                session.event("RESEARCH_RESUMED", "已从持久化的工具历史恢复研究",
                        Map.of("promptVersion", PROMPT_VERSION, "model", model.getModelName(), "role", role.key(),
                                "restoredToolCalls", history.steps().size(), "droppedToolCalls", history.droppedToolCalls(),
                                "restoredWorkerResults", unreported.size()));
                var concluded = history.outcome(json);
                if (concluded != null) {
                    session.conclude(concluded);
                    return concluded;
                }
                input = new java.util.ArrayList<>(history.messages("research-" + session.taskId));
                if (!unreported.isEmpty()) input.add(Msg.builder().role(MsgRole.USER).textContent(
                        "Execution resumed after an interruption. These worker results were completed and validated before it; "
                        + "use them instead of delegating the same goals again: "
                        + json.writeValueAsString(session.results().stream().filter(r -> unreported.contains(r.taskId())).toList())).build());
            }
            while (true) {
                agent.streamEvents(input, context)
                        // 不保存 text/thinking 事件；只留下可回放的工具参数、结果和实际 usage。
                        .takeUntil(event -> event instanceof ToolResultEndEvent && session.outcome() != null)
                        .takeUntilOther(session.control.signal())
                        .then().timeout(session.budget.remaining()).block();
                session.check();
                if (session.outcome() != null) return session.outcome();
                session.requestFinishRepair();
                session.event("NATIVE_FINISH_REPAIR", "正在修复研究结束协议",
                        Map.of("reason", "TEXT_WITHOUT_NATIVE_FINISH", "citableEvidenceCount", session.citableIds().size()));
                input = List.of(Msg.builder().role(MsgRole.USER).textContent(ResearchHistory.FINISH_REPAIR_MESSAGE).build());
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("研究请求构建失败", e); }
    }

    private static String load(String version) {
        try (var input = new ClassPathResource("prompts/" + version + ".txt").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("研究提示词读取失败", e); }
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { coordinator.close(); }

    private static class ToolProgress implements MiddlewareBase {
        private final ResearchSession session;
        private final ResearchProperties properties;
        ToolProgress(ResearchSession session, ResearchProperties properties) { this.session = session; this.properties = properties; }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                         Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.fromIterable(input.toolCalls()).concatMap(call -> Flux.defer(() -> {
                session.check();
                session.budget.acquireTool();
                session.activeToolCallId = call.getId();
                Map<String, Object> started = new java.util.LinkedHashMap<>();
                started.put("toolCallId", call.getId());
                started.put("tool", call.getName());
                started.put("arguments", call.getInput());
                // 接管重建历史时需要：同一轮模型输出的全部调用 ID，以及模型给出的原始参数文本。
                started.put("batch", input.toolCalls().stream().map(io.agentscope.core.message.ToolUseBlock::getId).toList());
                if (call.getContent() != null) started.put("rawArguments", call.getContent());
                session.event("TOOL_STARTED", "正在执行 " + call.getName(), started);
                StringBuilder output = new StringBuilder();
                var execution = next.apply(new ActingInput(java.util.List.of(call)));
                if (!"conduct_research".equals(call.getName())) {
                    execution = execution.timeout(Duration.ofSeconds(properties.getToolTimeoutSeconds()));
                }
                return execution.doOnNext(event -> {
                    if (event instanceof ToolResultTextDeltaEvent delta && output.length() < 40000) {
                        output.append(delta.getDelta(), 0, Math.min(delta.getDelta().length(), 40000 - output.length()));
                    }
                    if (event instanceof ToolResultEndEvent end) {
                        session.event("TOOL_ENDED", "工具执行已结束",
                                Map.of("toolCallId", end.getToolCallId(), "tool", end.getToolCallName(),
                                        "status", end.getState().name(), "output", output.toString()));
                    }
                }).doOnError(error -> session.event("TOOL_FAILED", "工具调用失败",
                        Map.of("toolCallId", call.getId(), "tool", call.getName(), "errorType", error.getClass().getSimpleName())));
            }));
        }
    }
}
