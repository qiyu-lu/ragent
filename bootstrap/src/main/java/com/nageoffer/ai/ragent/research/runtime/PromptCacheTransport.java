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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 百炼显式缓存的线路适配：SDK 把 cache_control 写成消息级字段，百炼只认内容块内的标记（消息级会被静默忽略）。
 * 同时从流式 usage 读取 SDK 不解析的缓存写入量，供调用台账按创建价计费。
 */
class PromptCacheTransport implements HttpTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpTransport delegate;
    private final AtomicReference<Usage> last = new AtomicReference<>();

    record Usage(Integer creationTokens, String type) { }

    PromptCacheTransport(HttpTransport delegate) { this.delegate = delegate; }

    /** 取走最近一次响应的缓存用量；一个 Agent 的模型调用串行，按模型实例保存最近一次即可。 */
    Usage take() { return last.getAndSet(null); }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(rewrite(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(rewrite(request)).doOnNext(this::observe);
    }

    /** 默认传输由 HttpTransportFactory 共享与关闭，这里不能关。 */
    @Override public void close() { }

    static HttpRequest rewrite(HttpRequest request) {
        JsonNode body;
        try { body = JSON.readTree(request.getBody()); }
        catch (Exception error) { return request; }
        boolean marked = false;
        for (JsonNode message : body.path("messages")) {
            if (!(message instanceof ObjectNode object) || !object.has("cache_control")) continue;
            JsonNode marker = object.remove("cache_control");
            JsonNode content = object.get("content");
            if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                ArrayNode parts = object.putArray("content");
                parts.addObject().put("type", "text").put("text", content.asText()).set("cache_control", marker);
                marked = true;
            } else if (content instanceof ArrayNode parts && !parts.isEmpty() && parts.get(parts.size() - 1) instanceof ObjectNode part) {
                part.set("cache_control", marker);
                marked = true;
            }
        }
        if (!marked) return request;
        try {
            var rewritten = HttpRequest.builder().url(request.getUrl()).method(request.getMethod()).body(JSON.writeValueAsString(body));
            if (request.getHeaders() != null) rewritten.headers(request.getHeaders());
            return rewritten.build();
        } catch (Exception error) { throw new IllegalStateException("缓存标记改写失败", error); }
    }

    private void observe(String payload) {
        if (payload == null || !payload.contains("prompt_tokens_details")) return;
        try {
            JsonNode details = JSON.readTree(payload.strip()).path("usage").path("prompt_tokens_details");
            if (details.isMissingNode()) return;
            last.set(new Usage(details.has("cache_creation_input_tokens") ? details.path("cache_creation_input_tokens").asInt() : null,
                    details.hasNonNull("cache_type") ? details.path("cache_type").asText() : null));
        } catch (Exception ignored) {
            // usage 解析失败只影响台账，不影响模型输出。
        }
    }

    /** 研究模型与它专属的缓存用量探针。 */
    record TrackedModel(Model delegate, PromptCacheTransport cache) implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return delegate.stream(messages, tools, options);
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
        @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
        @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
    }
}
