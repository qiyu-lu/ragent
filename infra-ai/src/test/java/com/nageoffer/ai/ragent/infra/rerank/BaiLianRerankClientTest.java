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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaiLianRerankClientTest {

    @Test
    void stillReranksWhenCandidateCountDoesNotExceedTopN() throws Exception {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);
        when(response.body()).thenReturn(ResponseBody.create(
                "{\"output\":{\"results\":["
                        + "{\"index\":1,\"relevance_score\":0.9},"
                        + "{\"index\":0,\"relevance_score\":0.4}]}}",
                MediaType.get("application/json")));

        BaiLianRerankClient client = new BaiLianRerankClient(httpClient);
        RetrievedChunk first = RetrievedChunk.builder().id("first").text("第一块").build();
        RetrievedChunk second = RetrievedChunk.builder().id("second").text("第二块").build();

        List<RetrievedChunk> result = client.rerank(
                "问题", List.of(first, second), 10, target());

        assertEquals(List.of("second", "first"), result.stream().map(RetrievedChunk::getId).toList());
        verify(httpClient).newCall(any());
    }

    @Test
    void fallbackKeepsDifferentIdlessChunksByCanonicalKey() throws Exception {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);
        when(response.body()).thenReturn(ResponseBody.create(
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}",
                MediaType.get("application/json")));

        BaiLianRerankClient client = new BaiLianRerankClient(httpClient);
        RetrievedChunk first = RetrievedChunk.builder().text("无 ID 正文一").build();
        RetrievedChunk second = RetrievedChunk.builder().text("无 ID 正文二").build();

        List<RetrievedChunk> result = client.rerank(
                "问题", List.of(first, second), 2, target());

        assertEquals(List.of("无 ID 正文一", "无 ID 正文二"),
                result.stream().map(RetrievedChunk::getText).toList());
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1");
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("rerank", "/rerank"));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("test-rerank");
        return new ModelTarget("test", candidate, provider, null);
    }
}
