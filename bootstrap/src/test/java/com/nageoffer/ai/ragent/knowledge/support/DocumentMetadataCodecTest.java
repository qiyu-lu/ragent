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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.request.DocumentMetadataConfirmRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentMetadataCodecTest {

    private final DocumentMetadataCodec codec = new DocumentMetadataCodec(new ObjectMapper());

    private static Map<String, Object> ingested(String standardNo, String verdict) {
        Map<String, Object> map = new LinkedHashMap<>(new DocumentMetadata(standardNo, "GB/T 6730.10", 2014, List.of(),
                List.of("铁矿石"), List.of("硅"), List.of("重量法"), DocumentMetadata.SOURCE_EXTRACTED).toMap());
        map.put(DocumentMetadataCodec.KEY_PARSE_AUDIT, Map.of("verdict", verdict));
        map.put(DocumentMetadataCodec.KEY_NORMALIZATION, Map.of("applied", true, "repeatedLinesRemoved", 9));
        return map;
    }

    @Test
    void reingestReplacesExtractedFieldsButKeepsConfirmedOnes() {
        String extracted = codec.write(ingested("GB/T 6730.10-2014", "PASSED"));
        Map<String, Object> overwritten = codec.mergeOnIngest(extracted, ingested("GB/T 6730.10-2015", "RECOVERED"));
        assertEquals("GB/T 6730.10-2015", overwritten.get("standardNo"));

        DocumentMetadataConfirmRequest request = new DocumentMetadataConfirmRequest();
        request.setStandardNo("GB/T6730.10—2014");
        request.setReplaces(List.of("GB/T 6730.10—1986"));
        request.setObjects(List.of("铁矿石"));
        request.setComponents(List.of("硅"));
        Map<String, Object> confirmed = codec.confirm(extracted, request.toMetadata(), "reviewer");
        assertEquals("confirmed", confirmed.get("source"));
        assertEquals("reviewer", confirmed.get(DocumentMetadataCodec.KEY_CONFIRMED_BY));
        assertEquals(2014, confirmed.get("publishYear"));
        assertEquals(List.of("GB/T 6730.10-1986"), confirmed.get("replaces"));
        assertEquals("PASSED", DocumentMetadataCodec.parseVerdict(confirmed), "确认不动诊断");

        Map<String, Object> reingested = codec.mergeOnIngest(codec.write(confirmed), ingested("GB/T 9999-2020", "RECOVERED"));
        assertEquals("GB/T 6730.10-2014", reingested.get("standardNo"), "确认过的治理字段不被抽取结果覆盖");
        assertEquals("reviewer", reingested.get(DocumentMetadataCodec.KEY_CONFIRMED_BY));
        assertEquals("RECOVERED", DocumentMetadataCodec.parseVerdict(reingested), "诊断总用最新一次入库的");
    }

    @Test
    void unreadableJsonCountsAsNoMetadata() {
        assertTrue(codec.read("{not json").isEmpty());
        assertNull(codec.governance(null));
        assertNull(DocumentMetadataCodec.parseVerdict(Map.of()));
    }

    @Test
    void confirmRejectsUnrecognisedStandardNumbers() {
        DocumentMetadataConfirmRequest request = new DocumentMetadataConfirmRequest();
        request.setStandardNo("第 3 部分");
        assertThrows(ClientException.class, request::toMetadata);
    }
}
