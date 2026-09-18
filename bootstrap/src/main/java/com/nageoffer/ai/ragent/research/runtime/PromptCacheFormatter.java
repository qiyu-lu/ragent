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

import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import java.util.List;
import java.util.Map;

/**
 * 研究调用的缓存断点：系统消息（含工具定义）与最后一条稳定历史消息。
 * SDK 默认标记最后一条消息，而研究调用的最后一条是每次都变的服务端提醒，标在那里永远无法复用。
 */
class PromptCacheFormatter extends OpenAIChatFormatter {
    static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    /** 仅在 {@link BoundedResearchModel} 追加了末尾提醒的请求上启用。 */
    @Override
    public void applyCacheControl(List<OpenAIMessage> messages) {
        if (messages == null || messages.size() < 2) return;
        if ("system".equals(messages.get(0).getRole())) messages.get(0).setCacheControl(EPHEMERAL);
        messages.get(messages.size() - 2).setCacheControl(EPHEMERAL);
    }
}
