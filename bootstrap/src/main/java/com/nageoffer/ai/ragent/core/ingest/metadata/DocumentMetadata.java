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

package com.nageoffer.ai.ragent.core.ingest.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档级元数据（knowledge-quality 计划 §5.3），落 {@code t_knowledge_document.doc_metadata}
 * <p>
 * 入库时由文字层与标题抽取（source = extracted），人工确认后改为 confirmed；
 * 解析审计与归一化摘要与它同列存放，但不属于这里的字段
 *
 * @param standardNo   标准号，如 GB/T 6730.10-2014
 * @param standardBase 去掉年份的标准号，同一标准不同版本共用，用于时效性判断
 * @param publishYear  发布年（取自标准号）
 * @param replaces     代替的旧标准号
 * @param objects      检测对象（术语表规范写法）
 * @param components   被测组分
 * @param methods      方法类型
 * @param source       extracted / confirmed
 */
public record DocumentMetadata(String standardNo,
                               String standardBase,
                               Integer publishYear,
                               List<String> replaces,
                               List<String> objects,
                               List<String> components,
                               List<String> methods,
                               String source) {

    public static final String SOURCE_EXTRACTED = "extracted";
    public static final String SOURCE_CONFIRMED = "confirmed";

    public DocumentMetadata {
        replaces = copy(replaces);
        objects = copy(objects);
        components = copy(components);
        methods = copy(methods);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("standardNo", standardNo);
        map.put("standardBase", standardBase);
        map.put("publishYear", publishYear);
        map.put("replaces", replaces);
        map.put("objects", objects);
        map.put("components", components);
        map.put("methods", methods);
        map.put("source", source);
        return map;
    }

    /**
     * 从 {@code doc_metadata} 的 JSON 对象还原；缺字段按空处理
     */
    public static DocumentMetadata fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Object year = map.get("publishYear");
        return new DocumentMetadata(text(map.get("standardNo")), text(map.get("standardBase")),
                year instanceof Number number ? number.intValue() : null,
                strings(map.get("replaces")), strings(map.get("objects")), strings(map.get("components")),
                strings(map.get("methods")), text(map.get("source")));
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString().trim());
            }
        }
        return result;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
