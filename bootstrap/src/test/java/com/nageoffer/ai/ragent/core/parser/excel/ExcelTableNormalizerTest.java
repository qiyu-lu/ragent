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

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelTableNormalizerTest {

    @Test
    void shouldExpandHeadersButNotDuplicateMergedDataCells() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("流程");
            sheet.createRow(0).createCell(1).setCellValue("父级");
            sheet.getRow(0).createCell(3).setCellValue("说明");
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("B1:C1"));
            sheet.createRow(1).createCell(1).setCellValue("阶段");
            sheet.getRow(1).createCell(2).setCellValue("参数");
            sheet.getRow(1).createCell(3).setCellValue("备注");

            sheet.createRow(2).createCell(1).setCellValue("横向合并说明");
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("B3:D3"));

            sheet.createRow(3).createCell(1).setCellValue("纵向合并说明");
            sheet.createRow(4);
            sheet.createRow(5);
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("B4:D6"));

            sheet.createRow(6).createCell(1).setCellValue("共享阶段");
            sheet.getRow(6).createCell(2).setCellValue("条件一");
            sheet.createRow(7).createCell(2).setCellValue("条件二");
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("B7:B8"));

            sheet.createRow(8).createCell(1).setCellValue("主动重复");
            sheet.getRow(8).createCell(2).setCellValue("相同值");
            sheet.createRow(9).createCell(1).setCellValue("主动重复");
            sheet.getRow(9).createCell(2).setCellValue("相同值");

            var table = ExcelTableNormalizer.normalize(
                    sheet,
                    new DataFormatter(),
                    workbook.getCreationHelper().createFormulaEvaluator(),
                    2);

            assertEquals(List.of("父级|阶段", "父级|参数", "说明|备注"), table.headers());
            assertEquals(6, table.rows().size());
            assertEquals(List.of("横向合并说明", "", ""), table.rows().get(0));
            assertEquals(List.of("纵向合并说明", "", ""), table.rows().get(1));
            assertEquals("B4:D6", table.rowCellRanges().get(1));
            assertEquals(List.of("共享阶段", "条件一", ""), table.rows().get(2));
            assertEquals(List.of("共享阶段", "条件二", ""), table.rows().get(3));
            assertEquals(table.rows().get(4), table.rows().get(5), "用户主动录入的重复行必须保留");
        }
    }
}
