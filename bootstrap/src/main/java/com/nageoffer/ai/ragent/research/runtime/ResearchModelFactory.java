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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/** 研究模型只使用显式支持工具调用的注册项；不静默回退到普通问答模型。 */
@Component
@RequiredArgsConstructor
public class ResearchModelFactory {
    private final AIModelProperties models;
    private final ResearchProperties research;

    public Model create() {
        research.validate();
        var candidate = models.getChat().getCandidates().stream()
                .filter(c -> research.getModelId().equals(c.getId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("研究模型未注册"));
        if (!Boolean.TRUE.equals(candidate.getEnabled()) || !Boolean.TRUE.equals(candidate.getSupportsToolCalling())) {
            throw new IllegalStateException("研究模型必须启用并显式声明原生工具调用能力");
        }
        var provider = models.getProviders().get(candidate.getProvider());
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException("研究模型提供方或凭证缺失");
        }
        URI url = URI.create(ModelUrlResolver.resolveUrl(provider, candidate, ModelCapability.CHAT));
        if ((!"https".equals(url.getScheme()) && !"http".equals(url.getScheme()))
                || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null) {
            throw new IllegalStateException("研究模型端点无效");
        }
        return OpenAIChatModel.builder().apiKey(provider.getApiKey()).modelName(candidate.getModel())
                .baseUrl(url.getScheme() + "://" + url.getRawAuthority()).endpointPath(url.getRawPath())
                // SDK 流取消会关闭 HTTP；关闭 thinking，usage 独立记录供应商返回值。
                .stream(true).generateOptions(GenerateOptions.builder().temperature(0.0)
                        .maxTokens(research.getMaxOutputTokens()).parallelToolCalls(false)
                        .additionalBodyParams(Map.of("enable_thinking", false)).build()).build();
    }
}
