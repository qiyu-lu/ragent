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

package com.nageoffer.ai.ragent.ironore.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ironore.config.RobotGatewayProperties;
import com.nageoffer.ai.ragent.ironore.model.RobotMissionPayload;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RobotGatewayClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RobotGatewayProperties properties;

    public RobotGatewayClient(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                              ObjectMapper objectMapper,
                              RobotGatewayProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(250, properties.getTimeoutMs()));
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(timeout)
                .writeTimeout(timeout)
                .readTimeout(timeout)
                .callTimeout(timeout)
                .retryOnConnectionFailure(false)
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public RobotGatewaySnapshot dispatch(RobotMissionPayload mission) {
        requireEnabled();
        try {
            String json = objectMapper.writeValueAsString(mission);
            Request request = new Request.Builder()
                    .url(url("/missions"))
                    .post(RequestBody.create(json, JSON))
                    .build();
            return execute(request);
        } catch (RobotGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new RobotGatewayException("无法序列化 ROS1 机器人任务", e);
        }
    }

    public RobotGatewaySnapshot status(String missionId) {
        requireEnabled();
        return execute(new Request.Builder()
                .url(url("/missions/" + missionId))
                .get()
                .build());
    }

    public RobotGatewaySnapshot cancel(String missionId) {
        requireEnabled();
        return execute(new Request.Builder()
                .url(url("/missions/" + missionId + "/cancel"))
                .post(RequestBody.create("{}", JSON))
                .build());
    }

    private RobotGatewaySnapshot execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String detail = body.isBlank() ? response.message() : body;
                throw new RobotGatewayException("ROS1 网关请求失败（HTTP " + response.code() + "）：" + detail);
            }
            return objectMapper.readValue(body, RobotGatewaySnapshot.class);
        } catch (RobotGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new RobotGatewayException("无法连接 ROS1 网关：" + e.getMessage(), e);
        }
    }

    private String url(String path) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RobotGatewayException("未配置 ROS1 网关地址");
        }
        return baseUrl.replaceFirst("/+$", "") + path;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new RobotGatewayException("ROS1 机器人网关未启用");
        }
    }
}
