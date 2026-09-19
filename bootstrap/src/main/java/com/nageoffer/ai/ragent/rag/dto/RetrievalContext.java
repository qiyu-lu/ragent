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

package com.nageoffer.ai.ragent.rag.dto;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 检索上下文（KB 结果的统一承载）
 */
@Data
@Builder
public class RetrievalContext {

    /**
     * KB 召回的上下文
     */
    private String kbContext;

    /**
     * 意图 ID -> 分片列表
     */
    private Map<String, List<RetrievedChunk>> intentChunks;

    /**
     * 最终进入请求上下文的 KB 分片，按请求级选择顺序保存。
     * <p>
     * 这是 Prompt、来源、grounding 和评测共同使用的 canonical 列表；intentChunks 只保留归因关系。
     */
    @Builder.Default
    private List<RetrievedChunk> kbChunks = List.of();

    /**
     * 请求级候选选择诊断，仅描述检索选择过程，不参与 Prompt。
     */
    private RetrievalSelectionDiagnostics retrievalDiagnostics;

    /**
     * 允许参与模板选择和规则注入的意图 ID
     */
    @Builder.Default
    private Set<String> eligibleIntentIds = Set.of();

    /**
     * 是否存在 KB 上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }

    /**
     * 是否无任何上下文
     */
    public boolean isEmpty() {
        return !hasKb();
    }

    /**
     * 兼容旧调用方手工构造的 RetrievalContext；新检索链始终直接填写 kbChunks。
     */
    public List<RetrievedChunk> effectiveKbChunks() {
        if (kbChunks != null && !kbChunks.isEmpty()) {
            return kbChunks;
        }
        if (intentChunks == null || intentChunks.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        intentChunks.values().stream()
                .filter(chunks -> chunks != null && !chunks.isEmpty())
                .flatMap(List::stream)
                .filter(chunk -> chunk != null)
                .forEach(chunk -> distinct.putIfAbsent(
                        com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey.of(chunk), chunk));
        return List.copyOf(distinct.values());
    }
}
