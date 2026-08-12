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

package com.nageoffer.ai.ragent.ironore.service;

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkbookDiffServiceTest {

    @Test
    void reportsOnlyVisibleCellChangesInDeterministicOrder() throws Exception {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        KnowledgeDocumentDO base = document("base", "base-key", "V1.2", 0);
        KnowledgeDocumentDO target = document("target", "target-key", "V1.3-demo", 1);
        when(mapper.selectList(any())).thenReturn(List.of(base), List.of(target));

        byte[] baseBytes = workbook("旧值", null, "仅基准", "隐藏旧值");
        byte[] targetBytes = workbook("新值", "新增", null, "隐藏新值");
        when(storage.openStream("base-key")).thenReturn(new ByteArrayInputStream(baseBytes));
        when(storage.openStream("target-key")).thenReturn(new ByteArrayInputStream(targetBytes));

        var result = new WorkbookDiffService(mapper, storage)
                .compare("铁矿石人工检测流程调研", "V1.2", "V1.3-demo");

        assertEquals(3, result.changeCount());
        assertEquals(List.of("A1", "C3", "B2"),
                result.changes().stream().map(change -> change.cellRange()).toList());
        assertEquals(List.of("MODIFIED", "REMOVED", "ADDED"),
                result.changes().stream().map(change -> change.changeType()).toList());
        assertEquals("旧值", result.changes().get(0).beforeValue());
        assertEquals("新值", result.changes().get(0).afterValue());
        assertEquals("V1.3-demo", result.targetDocument().version());
    }

    private static KnowledgeDocumentDO document(String id, String key, String version, int demoData) {
        return KnowledgeDocumentDO.builder()
                .id(id)
                .kbId("kb-1")
                .docName("流程" + version + ".xlsx")
                .documentKey("铁矿石人工检测流程调研")
                .documentVersion(version)
                .demoData(demoData)
                .enabled(1)
                .fileType("xlsx")
                .fileUrl(key)
                .build();
    }

    private static byte[] workbook(String a1, String b2, String c3, String hiddenValue) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("流程");
            sheet.createRow(0).createCell(0).setCellValue(a1);
            if (b2 != null) {
                sheet.createRow(1).createCell(1).setCellValue(b2);
            }
            if (c3 != null) {
                sheet.createRow(2).createCell(2).setCellValue(c3);
            }
            var hidden = workbook.createSheet("隐藏表");
            hidden.createRow(0).createCell(0).setCellValue(hiddenValue);
            workbook.setSheetHidden(1, true);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
