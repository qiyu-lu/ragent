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

package com.nageoffer.ai.ragent.infra.jev;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * TypeSafe System One（Jev 判定模型）配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.jev")
public class JevProperties {

    /**
     * 不配时依次读环境变量 {@code TYPESAFE_API_KEY}、{@code JEV_API_KEY}（与 AgentScope-java 相同）
     */
    private String apiKey;

    private String baseUrl = "https://api.typesafe.ai";

    /**
     * 钉具体版本：{@code jev-latest} 会随发版换模型，按概率写定的阈值就不再成立
     */
    private String model = "jev-1.13.0";

    /**
     * 单次请求超时，含排队与读取
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * 429、529、5xx、网络错误与超时的最多重试次数
     */
    private int maxRetries = 2;

    /**
     * 首次重试前的等待，之后每次翻倍，最多到 8 倍
     */
    private Duration initialBackoff = Duration.ofMillis(500);

    /**
     * 实际使用的密钥：配置优先，其次环境变量；都没有返回空串
     */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.strip();
        }
        for (String name : new String[]{"TYPESAFE_API_KEY", "JEV_API_KEY"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
