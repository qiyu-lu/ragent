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

package com.nageoffer.ai.ragent.core.parser;

import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可以分两步解析的解析器：先向解析服务取回原始结果，再展开成 Block
 * <p>
 * 解析质量闸门要对同一份文件比较两次解析：候选结果只展开正文做审计，选定的那次才上传图片、调视觉模型生成描述。
 * 落选的那次不留下资产，也不多付图生文的费用；审计也不会把视觉模型的转写当成解析出来的正文
 */
public interface StagedDocumentParser extends DocumentParser {

    /**
     * 解析服务返回的原始结果
     *
     * @param payload    原始载荷（MinerU 为结果 zip）
     * @param sourceFile 来源文件名，写入 Provenance
     * @param documentId 文档 ID，资产 key 用
     * @param metadata   本次解析的元信息（实际参数、任务号等），并进展开后的 {@link ParsedDocument#metadata()}
     */
    record Fetched(byte[] payload, String sourceFile, String documentId, Map<String, Object> metadata) {

        public Fetched {
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    /**
     * 调解析服务取回原始结果，不上传图片、不调视觉模型
     */
    Fetched fetch(byte[] content, String mimeType, Map<String, Object> options);

    /**
     * 只展开正文：图片不上传、不生成描述，图片地址保留原始路径，供审计比较
     */
    ParsedDocument preview(Fetched fetched);

    /**
     * 展开成入库用的完整结果：上传图片并生成描述
     */
    ParsedDocument complete(Fetched fetched);
}
