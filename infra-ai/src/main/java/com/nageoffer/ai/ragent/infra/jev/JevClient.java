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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * TypeSafe System One 客户端：把一段状态和一组类型化问题交给 Jev，拿回带概率的答案
 * <p>
 * 做法照 AgentScope-java 的 {@code agentscope-extensions-jev}：
 * <ul>
 *   <li>不实现聊天模型接口：Jev 不生成文本，只给是/否概率、选项分布或等级分</li>
 *   <li>按题核对应答：题号一一对应、类型一致、概率在 0~1、分布之和为 1（容许接口四位小数的舍入误差）</li>
 *   <li>429、529、5xx、网络错误与超时按指数退避重试，其余错误直接抛出</li>
 * </ul>
 * 失败一律抛 {@link ModelClientException}，退回什么由调用方决定
 */
@Slf4j
@Component
public class JevClient {

    private static final String SYSTEM_ONE_PATH = "/v1/systemone";

    /**
     * 接口把概率舍入到 4 位小数，每个值最多差 5e-5，n 个值之和最多偏离 1 达 n × 5e-5
     */
    private static final double SUM_TOLERANCE_PER_VALUE = 0.00005;

    private static final int MAX_CHOICE_OPTIONS = 255;

    private static final int MAX_SCORE_LEVELS = 10;

    /**
     * 错误响应写进异常消息时最多保留这么多字
     */
    private static final int MAX_ERROR_DETAIL_CHARS = 300;

    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;
    private final JevProperties properties;

    public JevClient(@Qualifier("syncHttpClient") OkHttpClient httpClient, JevProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * 有密钥才能调用
     */
    public boolean isConfigured() {
        return !properties.resolveApiKey().isEmpty();
    }

    /**
     * 请求里写的模型名
     */
    public String model() {
        return properties.getModel();
    }

    /**
     * 提一组问题，答案按同样的题号返回
     *
     * @param state     判断依据：字符串，或能被 Gson 序列化的 Map / List
     * @param questions 题号到题目，至少一道
     */
    public JevResult systemOne(Object state, Map<String, JevQuestion> questions) {
        validateRequest(state, questions);
        String apiKey = properties.resolveApiKey();
        if (apiKey.isEmpty()) {
            throw new ModelClientException("Jev 密钥缺失：设置 TYPESAFE_API_KEY", ModelClientErrorType.UNAUTHORIZED, null);
        }
        String body = requestBody(state, questions).toString();
        long started = System.nanoTime();
        for (int attempt = 0; ; attempt++) {
            try {
                JevResult result = parseResult(post(body, apiKey), questions);
                log.debug("Jev 调用完成: model={}, inputTokens={}, attempts={}, elapsedMs={}", result.model(),
                        result.inputTokens(), attempt + 1, (System.nanoTime() - started) / 1_000_000);
                return result;
            } catch (ModelClientException e) {
                if (!retryable(e) || attempt >= properties.getMaxRetries()) {
                    throw e;
                }
                long wait = backoffMillis(attempt);
                log.warn("Jev 请求失败，{} ms 后第 {} 次重试: {}", wait, attempt + 1, e.getMessage());
                sleep(wait);
            }
        }
    }

    private String post(String body, String apiKey) {
        Request request = new Request.Builder()
                .url(systemOneUrl())
                .post(RequestBody.create(body, HttpMediaTypes.JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .build();
        Call call = httpClient.newCall(request);
        call.timeout().timeout(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            String text = HttpResponseHelper.readBody(response.body());
            if (!response.isSuccessful()) {
                throw new ModelClientException("Jev 请求失败: HTTP " + response.code() + errorDetail(text),
                        ModelClientErrorType.fromHttpStatus(response.code()), response.code());
            }
            return text;
        } catch (InterruptedIOException e) {
            throw new ModelClientException("Jev 请求超时: " + properties.getTimeout().toMillis() + " ms",
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        } catch (IOException e) {
            throw new ModelClientException("Jev 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    private String systemOneUrl() {
        String base = properties.getBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(SYSTEM_ONE_PATH)) {
            return base;
        }
        return base.endsWith("/v1") ? base + "/systemone" : base + SYSTEM_ONE_PATH;
    }

    private static boolean retryable(ModelClientException e) {
        ModelClientErrorType type = e.getErrorType();
        return type == ModelClientErrorType.RATE_LIMITED || type == ModelClientErrorType.SERVER_ERROR
                || type == ModelClientErrorType.NETWORK_ERROR;
    }

    private long backoffMillis(int attempt) {
        long initial = properties.getInitialBackoff().toMillis();
        long base = Math.min(initial << Math.min(attempt, 3), initial * 8);
        return Math.round(base * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Jev 重试等待被中断", ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    // ---------------------------------------------------------------- 请求

    private static void validateRequest(Object state, Map<String, JevQuestion> questions) {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("至少要有一道题");
        }
        questions.forEach((id, question) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("题号不能为空");
            }
            if (question == null || question.instructions() == null || question.instructions().isBlank()) {
                throw new IllegalArgumentException("题目 " + id + " 缺 instructions");
            }
            if (question instanceof JevQuestion.Choice choice && (choice.options() == null
                    || choice.options().size() < 2 || choice.options().size() > MAX_CHOICE_OPTIONS)) {
                throw new IllegalArgumentException("题目 " + id + " 的选项要有 2 到 " + MAX_CHOICE_OPTIONS + " 个");
            }
            if (question instanceof JevQuestion.Score score && (score.levels() == null
                    || score.levels().size() < 2 || score.levels().size() > MAX_SCORE_LEVELS)) {
                throw new IllegalArgumentException("题目 " + id + " 的等级要有 2 到 " + MAX_SCORE_LEVELS + " 级");
            }
        });
    }

    private JsonObject requestBody(Object state, Map<String, JevQuestion> questions) {
        JsonObject body = new JsonObject();
        body.addProperty("model", properties.getModel());
        body.add("state", GSON.toJsonTree(state));
        JsonObject items = new JsonObject();
        questions.forEach((id, question) -> items.add(id, questionJson(question)));
        body.add("questions", items);
        return body;
    }

    private static JsonObject questionJson(JevQuestion question) {
        JsonObject json = new JsonObject();
        if (question instanceof JevQuestion.Noul noul) {
            json.addProperty("type", "noul");
            json.addProperty("instructions", noul.instructions());
            JsonObject criteria = new JsonObject();
            if (noul.whenTrue() != null) {
                criteria.addProperty("true", noul.whenTrue());
            }
            if (noul.whenFalse() != null) {
                criteria.addProperty("false", noul.whenFalse());
            }
            if (criteria.size() > 0) {
                json.add("criteria", criteria);
            }
        } else if (question instanceof JevQuestion.Choice choice) {
            json.addProperty("type", "choice");
            json.addProperty("instructions", choice.instructions());
            JsonObject criteria = new JsonObject();
            choice.options().forEach(criteria::addProperty);
            json.add("criteria", criteria);
        } else if (question instanceof JevQuestion.Score score) {
            json.addProperty("type", "score");
            json.addProperty("instructions", score.instructions());
            JsonArray levels = new JsonArray();
            score.levels().forEach(levels::add);
            json.add("criteria", levels);
        }
        return json;
    }

    // ---------------------------------------------------------------- 应答

    private static JevResult parseResult(String text, Map<String, JevQuestion> questions) {
        JsonElement parsedRoot;
        try {
            parsedRoot = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw invalid("响应不是 JSON");
        }
        if (!parsedRoot.isJsonObject()) {
            throw invalid("响应不是 JSON 对象");
        }
        JsonObject root = parsedRoot.getAsJsonObject();
        String model = string(root, "model");
        if (model == null || model.isBlank()) {
            throw invalid("响应缺 model");
        }
        JsonObject answers = object(root, "answers");
        JsonObject usage = object(root, "usage");
        if (answers == null || usage == null) {
            throw invalid("响应缺 answers 或 usage");
        }
        if (!answers.keySet().equals(questions.keySet())) {
            throw invalid("答案的题号与请求不一致: " + answers.keySet());
        }
        Map<String, JevAnswer> parsed = new LinkedHashMap<>();
        questions.forEach((id, question) -> parsed.put(id, parseAnswer(id, question, answers.get(id))));
        return new JevResult(model, Collections.unmodifiableMap(parsed), tokens(usage, "input_tokens"),
                tokens(usage, "output_tokens"));
    }

    private static JevAnswer parseAnswer(String id, JevQuestion question, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw invalid("题目 " + id + " 没有答案");
        }
        JsonObject answer = element.getAsJsonObject();
        String type = string(answer, "type");
        if (question instanceof JevQuestion.Noul) {
            requireType(id, type, "noul");
            return new JevAnswer.Noul(probability(id, answer.get("noul")));
        }
        if (question instanceof JevQuestion.Choice choice) {
            requireType(id, type, "choice");
            String chosen = string(answer, "choice");
            if (chosen == null || !choice.options().containsKey(chosen)) {
                throw invalid("题目 " + id + " 选了不存在的选项: " + chosen);
            }
            return new JevAnswer.Choice(chosen,
                    distribution(id, object(answer, "probabilities"), choice.options().keySet()),
                    probability(id, answer.get("confidence")));
        }
        JevQuestion.Score score = (JevQuestion.Score) question;
        requireType(id, type, "score");
        int levels = score.levels().size();
        double value = finite(id, answer.get("score"));
        if (value < 0 || value > levels - 1) {
            throw invalid("题目 " + id + " 的等级分超出 0~" + (levels - 1) + ": " + value);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < levels; i++) {
            keys.add(Integer.toString(i));
        }
        JsonObject legend = object(answer, "legend");
        if (legend == null || !legend.keySet().equals(keys)) {
            throw invalid("题目 " + id + " 的等级说明与题目不一致");
        }
        Map<String, String> legendTexts = new LinkedHashMap<>();
        keys.forEach(key -> legendTexts.put(key, string(legend, key)));
        return new JevAnswer.Score(value, Collections.unmodifiableMap(legendTexts),
                distribution(id, object(answer, "probabilities"), keys), probability(id, answer.get("confidence")));
    }

    private static void requireType(String id, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw invalid("题目 " + id + " 的答案类型应为 " + expected + "，实际 " + actual);
        }
    }

    private static Map<String, Double> distribution(String id, JsonObject probabilities, Set<String> keys) {
        if (probabilities == null || !probabilities.keySet().equals(keys)) {
            throw invalid("题目 " + id + " 的概率分布与选项不一致");
        }
        Map<String, Double> values = new LinkedHashMap<>();
        double sum = 0;
        for (String key : keys) {
            double value = probability(id, probabilities.get(key));
            values.put(key, value);
            sum += value;
        }
        if (Math.abs(sum - 1) > SUM_TOLERANCE_PER_VALUE * keys.size()) {
            throw invalid("题目 " + id + " 的概率之和不是 1: " + sum);
        }
        return Collections.unmodifiableMap(values);
    }

    private static double probability(String id, JsonElement element) {
        double value = finite(id, element);
        if (value < 0 || value > 1) {
            throw invalid("题目 " + id + " 的概率超出 0~1: " + value);
        }
        return value;
    }

    private static double finite(String id, JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid("题目 " + id + " 缺数值");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw invalid("题目 " + id + " 的数值不是有限数");
        }
        return value;
    }

    private static long tokens(JsonObject usage, String key) {
        JsonElement element = usage.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong() : 0L;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static ModelClientException invalid(String message) {
        return new ModelClientException("Jev 应答无效: " + message, ModelClientErrorType.INVALID_RESPONSE, null);
    }

    /**
     * 错误响应里的 message / error / detail（422 会指出哪个字段不对），取不到就用原文；截断后接在消息末尾
     */
    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String detail = body.strip();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                for (String key : new String[]{"message", "error", "detail"}) {
                    JsonElement value = root.getAsJsonObject().get(key);
                    if (value != null && !value.isJsonNull()) {
                        detail = value.isJsonPrimitive() ? value.getAsString() : value.toString();
                        break;
                    }
                }
            }
        } catch (JsonParseException ignored) {
            // 非 JSON 的错误页，用原文
        }
        return ", " + (detail.length() > MAX_ERROR_DETAIL_CHARS ? detail.substring(0, MAX_ERROR_DETAIL_CHARS) + "…" : detail);
    }
}
