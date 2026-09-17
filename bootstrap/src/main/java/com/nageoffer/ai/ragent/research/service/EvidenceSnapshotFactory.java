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

package com.nageoffer.ai.ragent.research.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.research.model.EvidenceRecord;
import com.nageoffer.ai.ragent.research.model.EvidenceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EvidenceSnapshotFactory {
    private final ObjectMapper objectMapper;

    public EvidenceSnapshot create(String runId, String taskId,
                                   List<ResearchSourceCatalog.SourceChunk> sources, String originEvidenceId) {
        if (sources.isEmpty() || sources.size() > 3) {
            throw new IllegalArgumentException("证据范围必须为 1—3 个可信块");
        }
        var first = sources.get(0);
        if (sources.stream().anyMatch(source -> !Objects.equals(first.chunk().getKbId(), source.chunk().getKbId())
                || !Objects.equals(first.document().getId(), source.document().getId())
                || !Objects.equals(first.document().getDocumentVersion(), source.document().getDocumentVersion()))) {
            throw new IllegalArgumentException("证据不能混合文档或版本");
        }
        String body = sources.stream().map(source -> source.chunk().getContent()).collect(Collectors.joining("\n\n"));
        List<String> chunkIds = sources.stream().map(source -> source.chunk().getId()).toList();
        Map<String, Object> location = sources.size() == 1 ? first.location()
                : Map.of("chunks", sources.stream().map(ResearchSourceCatalog.SourceChunk::location).toList());
        String hash = SecureUtil.sha256(body);
        String metadataHash = sources.size() == 1 ? first.metadataHash()
                : SecureUtil.sha256(json(sources.stream().map(ResearchSourceCatalog.SourceChunk::metadataHash).toList()));
        List<Object> identity = new ArrayList<>(List.of(runId, first.chunk().getKbId(), first.document().getId(),
                Objects.toString(first.document().getDocumentVersion(), ""),
                sources.size() == 1 ? chunkIds.get(0) : chunkIds, hash, metadataHash));
        if (originEvidenceId != null) {
            identity.add(originEvidenceId);
        }
        String id = "ev-" + SecureUtil.sha256(json(identity));
        String text = EvidenceText.preview(body, EvidenceText.SEARCH_MAX_CHARS);
        var evidence = new EvidenceRecord(runId, id, first.chunk().getKbId(), first.document().getId(),
                first.document().getDocName(), first.document().getDocumentVersion(), chunkIds,
                hash, text, location, taskId, text.length() < body.length(), false, first.extent());
        return new EvidenceSnapshot(evidence, body, metadataHash);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("证据身份序列化失败", e);
        }
    }
}
