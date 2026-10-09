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

package com.nageoffer.ai.ragent.knowledge.controller.request;

import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadataExtractor;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 人工确认的文档元数据；标准号写法不限，落库前规整，发布年取自标准号
 */
@Data
public class DocumentMetadataConfirmRequest {

    /**
     * 标准号，非标准文档留空
     */
    private String standardNo;

    /**
     * 代替的旧标准号
     */
    private List<String> replaces;

    /**
     * 检测对象（术语表规范写法）
     */
    private List<String> objects;

    /**
     * 被测组分
     */
    private List<String> components;

    /**
     * 方法类型
     */
    private List<String> methods;

    public DocumentMetadata toMetadata() {
        DocumentMetadataExtractor.StandardNumber number = null;
        if (StringUtils.hasText(standardNo)) {
            number = DocumentMetadataExtractor.parseStandardNo(standardNo);
            if (number == null) {
                throw new ClientException("无法识别的标准号：" + standardNo);
            }
        }
        List<String> normalizedReplaces = new ArrayList<>();
        for (String replaced : replaces == null ? List.<String>of() : replaces) {
            DocumentMetadataExtractor.StandardNumber parsed = DocumentMetadataExtractor.parseStandardNo(replaced);
            if (parsed == null) {
                throw new ClientException("无法识别的被代替标准号：" + replaced);
            }
            normalizedReplaces.add(parsed.toString());
        }
        return new DocumentMetadata(number == null ? null : number.toString(), number == null ? null : number.base(),
                number == null ? null : number.year(), normalizedReplaces, trimmed(objects), trimmed(components),
                trimmed(methods), DocumentMetadata.SOURCE_CONFIRMED);
    }

    private static List<String> trimmed(List<String> values) {
        return values == null ? List.of() : values.stream().filter(StringUtils::hasText).map(String::trim).toList();
    }
}
