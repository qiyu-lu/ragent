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
     * 最终进入请求上下文的 KB 分片，按请求级选择顺序保存。
     * <p>
     * 这是 Prompt、来源和 grounding 共同使用的 canonical 列表。
     */
    @Builder.Default
    private List<RetrievedChunk> kbChunks = List.of();

    /**
     * 请求级候选选择诊断，仅描述检索选择过程，不参与 Prompt。
     */
    private RetrievalSelectionDiagnostics retrievalDiagnostics;

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

    public List<RetrievedChunk> effectiveKbChunks() {
        return kbChunks == null ? List.of() : kbChunks;
    }
}
