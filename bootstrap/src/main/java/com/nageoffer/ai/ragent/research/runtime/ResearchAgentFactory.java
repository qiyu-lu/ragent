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
import io.agentscope.core.tool.ToolkitConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

@Component
public class ResearchAgentFactory implements ResearchRunner {
    public static final String PROMPT_VERSION = "research-main-v2";
    private final ResearchModelFactory models;
    private final ResearchProperties properties;
    private final KnowledgeSearchService search;
    private final SourceReader reader;
    private final ObjectMapper json;
    private final TokenCounterService tokens;
    private final Semaphore modelQuota;
    private final String prompt;

    public ResearchAgentFactory(ResearchModelFactory models, ResearchProperties properties,
                                 KnowledgeSearchService search, SourceReader reader, ObjectMapper json,
                                 TokenCounterService tokens) {
        properties.validate();
        this.models = models;
        this.properties = properties;
        this.search = search;
        this.reader = reader;
        this.json = json;
        this.tokens = tokens;
        this.modelQuota = new Semaphore(properties.getMaxConcurrentModelCalls(), true);
        try (var input = new ClassPathResource("prompts/" + PROMPT_VERSION + ".txt").getInputStream()) {
            this.prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("研究提示词读取失败", e); }
    }

    @Override
    public ResearchSession.Outcome run(ResearchSession session) {
        session.check();
        Toolkit tools = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        tools.registerTool(new ResearchTools(session, search, reader));
        var context = RuntimeContext.builder().userId(session.claim.owner())
                .sessionId(session.claim.run().id() + ":" + session.claim.run().epoch()).build();
        var model = new BoundedResearchModel(models.create(), session, properties, modelQuota, json, tokens);
        try (ReActAgent agent = ReActAgent.builder().name("research-main").sysPrompt(prompt)
                .model(model).toolkit(tools).enableMetaTool(false).enablePendingToolRecovery(false)
                .maxRetries(1).maxIters(properties.getMaxModelCalls())
                .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(properties.getMaxDurationSeconds())).build())
                .toolExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofSeconds(properties.getToolTimeoutSeconds())).build())
                .middleware(new ToolProgress(session)).build()) {
            session.control.bindInterrupt(() -> agent.interrupt(context));
            session.event("RESEARCH_STARTED", "正在按目标检索和阅读", Map.of("promptVersion", PROMPT_VERSION, "model", model.getModelName()));
            Map<String, Object> saved = new java.util.HashMap<>(session.claim.run().state());
            saved.remove("requestHash");
            String request = json.writeValueAsString(Map.of("brief", session.claim.run().brief(), "savedResearchState", saved));
            var message = Msg.builder().role(MsgRole.USER).textContent(request).build();
            agent.streamEvents(message, context)
                    // 不保存 text/thinking 事件；只留下可回放的工具参数、结果和实际 usage。
                    .takeUntil(event -> event instanceof ToolResultEndEvent && session.outcome() != null)
                    .takeUntilOther(session.control.signal())
                    .then().timeout(session.budget.remaining()).block();
            session.control.check();
            if (session.outcome() == null) throw new IllegalStateException("NATIVE_FINISH_REQUIRED");
            return session.outcome();
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("研究请求构建失败", e); }
    }

    private static class ToolProgress implements MiddlewareBase {
        private final ResearchSession session;
        ToolProgress(ResearchSession session) { this.session = session; }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                         Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.fromIterable(input.toolCalls()).concatMap(call -> Flux.defer(() -> {
                session.check();
                session.budget.acquireTool();
                session.event("TOOL_STARTED", "正在执行 " + call.getName(),
                        Map.of("toolCallId", call.getId(), "tool", call.getName(), "arguments", call.getInput()));
                StringBuilder output = new StringBuilder();
                return next.apply(new ActingInput(java.util.List.of(call))).doOnNext(event -> {
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
