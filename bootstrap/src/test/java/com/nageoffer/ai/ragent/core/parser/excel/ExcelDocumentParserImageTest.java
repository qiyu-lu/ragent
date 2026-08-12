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

package com.nageoffer.ai.ragent.core.parser.excel;

import com.nageoffer.ai.ragent.core.parser.image.ImageAssetProcessor;
import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelDocumentParserImageTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void sendsOnlyAllowlistedSheetPicturesToImageProcessor() throws Exception {
        ImageAssetProcessor processor = mock(ImageAssetProcessor.class);
        when(processor.process(any(), anyString(), anyString(), any(Provenance.class), anyString(), anyString()))
                .thenAnswer(invocation -> new ImageBlock(
                        invocation.getArgument(3),
                        new AssetRef("https://example.test/image.png", "image/png"),
                        invocation.getArgument(4),
                        invocation.getArgument(4),
                        "可见设备"));
        ImageParseProperties properties = new ImageParseProperties();
        properties.setExcelEmbeddedEnabled(true);
        properties.setExcelImageSheetAllowlist(List.of("浓度检测（双场景）"));

        ExcelDocumentParser parser = new ExcelDocumentParser(processor, properties);
        var parsed = parser.parseStructured(workbookBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Map.of("sourceFile", "demo.xlsx", "documentId", "doc-1"));

        assertEquals(2L, parsed.metadata().get("parsedImages"));
        assertEquals(2, parsed.blocks().stream().filter(ImageBlock.class::isInstance).count());
        assertEquals(List.of("A1:B2", "C3:D4"), parsed.blocks().stream()
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .map(block -> block.provenance().cellRange())
                .toList());
        verify(processor, times(2))
                .process(any(), anyString(), anyString(), any(Provenance.class), anyString(), anyString());
    }

    private static byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet allowed = workbook.createSheet("浓度检测（双场景）");
            allowed.createRow(0).createCell(0).setCellValue("流程");
            addPicture(workbook, allowed, 0, 0, 1, 1);
            addPicture(workbook, allowed, 2, 2, 3, 3);

            XSSFSheet ignored = workbook.createSheet("其他工作表");
            ignored.createRow(0).createCell(0).setCellValue("其他");
            addPicture(workbook, ignored, 0, 0, 1, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void addPicture(Workbook workbook,
                                   XSSFSheet sheet,
                                   int row1,
                                   int col1,
                                   int row2,
                                   int col2) {
        int pictureId = workbook.addPicture(ONE_PIXEL_PNG, Workbook.PICTURE_TYPE_PNG);
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            drawing = sheet.createDrawingPatriarch();
        }
        XSSFClientAnchor anchor = new XSSFClientAnchor();
        anchor.setRow1(row1);
        anchor.setCol1(col1);
        anchor.setRow2(row2);
        anchor.setCol2(col2);
        drawing.createPicture(anchor, pictureId);
    }
}
