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

package com.nageoffer.ai.ragent.knowledge.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code t_knowledge_document.doc_metadata} 的读写：单点
 * <p>
 * 一列里放两类东西：治理字段（标准号、检测对象……，入库抽取、人工确认）与入库诊断（parseAudit、normalization，
 * 每次入库重算）。重新分块时诊断总是覆盖；治理字段只在未确认时覆盖，人工确认过的值不被抽取结果冲掉
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMetadataCodec {

    public static final String KEY_PARSE_AUDIT = "parseAudit";
    public static final String KEY_NORMALIZATION = "normalization";
    public static final String KEY_CONFIRMED_BY = "confirmedBy";
    public static final String KEY_CONFIRMED_AT = "confirmedAt";

    private static final List<String> DIAGNOSTIC_KEYS = List.of(KEY_PARSE_AUDIT, KEY_NORMALIZATION);

    private final ObjectMapper objectMapper;

    public Map<String, Object> read(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("文档元数据解析失败，按无元数据处理：{}", json, e);
            return new LinkedHashMap<>();
        }
    }

    public String write(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文档元数据序列化失败", e);
        }
    }

    public DocumentMetadata governance(String json) {
        Map<String, Object> map = read(json);
        return map.isEmpty() ? null : DocumentMetadata.fromMap(map);
    }

    /**
     * 重新入库：诊断用新值；治理字段在已确认时保留旧值，否则用新抽取的
     */
    public Map<String, Object> mergeOnIngest(String existingJson, Map<String, Object> ingested) {
        Map<String, Object> existing = read(existingJson);
        boolean confirmed = DocumentMetadata.SOURCE_CONFIRMED.equals(existing.get("source"));
        Map<String, Object> merged = new LinkedHashMap<>(confirmed ? existing : ingested);
        for (String key : DIAGNOSTIC_KEYS) {
            if (ingested.containsKey(key)) {
                merged.put(key, ingested.get(key));
            } else {
                merged.remove(key);
            }
        }
        return merged;
    }

    /**
     * 人工确认：治理字段整体换成确认值，诊断保留
     */
    public Map<String, Object> confirm(String existingJson, DocumentMetadata confirmedValues, String confirmedBy) {
        Map<String, Object> existing = read(existingJson);
        DocumentMetadata confirmedMetadata = new DocumentMetadata(confirmedValues.standardNo(),
                confirmedValues.standardBase(), confirmedValues.publishYear(), confirmedValues.replaces(),
                confirmedValues.objects(), confirmedValues.components(), confirmedValues.methods(),
                DocumentMetadata.SOURCE_CONFIRMED);
        Map<String, Object> merged = new LinkedHashMap<>(confirmedMetadata.toMap());
        merged.put(KEY_CONFIRMED_BY, confirmedBy);
        merged.put(KEY_CONFIRMED_AT, Instant.now().toString());
        for (String key : DIAGNOSTIC_KEYS) {
            if (existing.containsKey(key)) {
                merged.put(key, existing.get(key));
            }
        }
        return merged;
    }

    /**
     * 解析审计结论（PASSED / RECOVERED / NEEDS_REVIEW），没有审计时为 null
     */
    public static String parseVerdict(Map<String, Object> metadata) {
        Object audit = metadata == null ? null : metadata.get(KEY_PARSE_AUDIT);
        if (audit instanceof Map<?, ?> map && map.get("verdict") != null) {
            return map.get("verdict").toString();
        }
        return null;
    }
}
