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

import com.nageoffer.ai.ragent.core.parser.excel.ExcelLayoutAnalyzer.Item;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelLayoutAnalyzer.Region;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelLayoutAnalyzer.Table;
import com.nageoffer.ai.ragent.core.parser.excel.ExcelLayoutAnalyzer.Text;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelLayoutAnalyzerTest {

    @Test
    void cleanSheetIsOneTableWithFirstRowAsHeaderLikeTheNormalizer() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("台账");
            put(sheet, 0, 0, "设备", "数量", "说明");
            put(sheet, 1, 0, "天平", "2", "称样");
            put(sheet, 2, 0, "马弗炉", "1", "熔融");
            merge(sheet, "A4:A5");
            put(sheet, 3, 0, "烘箱", "1", "烘干");
            put(sheet, 4, 1, "2", "恒重");

            List<Table> tables = tables(analyze(sheet));

            assertEquals(1, tables.size());
            Table table = tables.get(0);
            assertEquals(0, table.headerRow());
            assertNull(table.title());
            assertEquals(List.of("设备", "数量", "说明"), table.headerCells());
            assertEquals(ExcelTableNormalizer.normalize(sheet, new DataFormatter(),
                    workbook.getCreationHelper().createFormulaEvaluator(), 1), table.table());
        }
    }

    @Test
    void titleRowAboveTheHeaderBecomesTheTitleAndLongLeadRowsStayParagraphs() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("需求");
            put(sheet, 0, 1, "自动化平台核心需求总览");
            merge(sheet, "B1:D1");
            put(sheet, 2, 1, "本表面向生产型实验室，列出平台分阶段落地时必须满足的核心需求与边界，供评审使用。");
            merge(sheet, "B3:D3");
            put(sheet, 4, 1, "序号", "模块", "内容");
            put(sheet, 5, 1, "1", "定位", "做什么");
            put(sheet, 6, 1, "2", "客户", "客户是谁");

            List<Region> regions = analyze(sheet);
            List<Table> tables = tables(regions);

            assertEquals(1, tables.size());
            assertEquals(4, tables.get(0).headerRow());
            assertEquals(List.of("序号", "模块", "内容"), tables.get(0).headerCells());
            assertNull(tables.get(0).title(), "表格上方紧挨着的是 40 多字的说明，不是标题");
            assertEquals(List.of("B1", "B3"), texts(regions).stream().map(Text::range).map(r -> r.split(":")[0]).toList());
        }
    }

    @Test
    void sectionRowsBetweenTablesBecomeTitlesAndEachTableKeepsItsOwnHeader() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("设备");
            put(sheet, 0, 1, "一、生产型实验室");
            merge(sheet, "B1:D1");
            put(sheet, 1, 1, "序号", "设备名称", "数量");
            put(sheet, 2, 1, "1", "天平", "2台");
            put(sheet, 3, 1, "2", "烘箱", "1台");
            put(sheet, 5, 1, "二、研究型中心实验室");
            merge(sheet, "B6:D6");
            put(sheet, 6, 1, "序号", "设备名称", "数量");
            put(sheet, 7, 1, "1", "马弗炉", "1台");

            List<Table> tables = tables(analyze(sheet));

            assertEquals(2, tables.size());
            assertEquals("一、生产型实验室", tables.get(0).title());
            assertEquals("二、研究型中心实验室", tables.get(1).title());
            assertEquals(6, tables.get(1).headerRow());
            assertEquals(List.of(List.of("1", "马弗炉", "1台")), tables.get(1).table().rows());
        }
    }

    @Test
    void sectionRowInsideOneRegionSplitsTheTableAndInheritsOrSkipsTheHeader() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("分组");
            put(sheet, 0, 0, "项目", "要求", "备注");
            put(sheet, 1, 0, "温度", "105℃", "恒温");
            put(sheet, 2, 0, "华东区");
            merge(sheet, "A3:C3");
            put(sheet, 3, 0, "时间", "2h", "计时");
            put(sheet, 4, 0, "华北区");
            merge(sheet, "A5:C5");
            put(sheet, 5, 0, "项目", "要求", "备注");
            put(sheet, 6, 0, "湿度", "40%", "记录");

            List<Table> tables = tables(analyze(sheet));

            assertEquals(3, tables.size());
            assertEquals("华东区", tables.get(1).title());
            assertEquals(0, tables.get(1).headerRow(), "分段后没有重复表头时沿用首段表头");
            assertEquals(List.of(List.of("时间", "2h", "计时")), tables.get(1).table().rows());
            assertEquals("华北区", tables.get(2).title());
            assertEquals(5, tables.get(2).headerRow(), "分段后紧跟的重复表头不当数据");
            assertEquals(List.of(List.of("湿度", "40%", "记录")), tables.get(2).table().rows());
        }
    }

    @Test
    void sideBySideTablesAreSeparateAndTheRightOneHasNoTitle() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("排班");
            put(sheet, 0, 0, "生产实验室排班");
            merge(sheet, "A1:B1");
            put(sheet, 0, 3, "维度", "生产", "中心");
            put(sheet, 1, 0, "时间", "样品");
            put(sheet, 1, 3, "模式", "倒班", "白班");
            put(sheet, 2, 0, "8:00", "原矿");
            put(sheet, 2, 3, "资质", "培训", "持证");
            put(sheet, 3, 0, "10:00", "浮精");

            List<Table> tables = tables(analyze(sheet));

            assertEquals(2, tables.size());
            assertEquals("生产实验室排班", tables.get(0).title());
            assertEquals(List.of("时间", "样品"), tables.get(0).headerCells());
            assertNull(tables.get(1).title());
            assertEquals(List.of("维度", "生产", "中心"), tables.get(1).headerCells());
            assertEquals(3, tables.get(1).firstCol());
        }
    }

    @Test
    void continuationRowsUnderVerticalMergesStayInsideTheTable() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("浓度");
            put(sheet, 0, 0, "场景", "步骤", "工具");
            put(sheet, 1, 0, "生产现场", "1.取样", "采样桶");
            merge(sheet, "A2:A4");
            merge(sheet, "C2:C4");
            put(sheet, 2, 1, "2.称重");
            put(sheet, 3, 1, "3.计算");

            List<Region> regions = analyze(sheet);
            List<Table> tables = tables(regions);

            assertEquals(1, tables.size());
            assertTrue(texts(regions).isEmpty(), "只有一格新值的续行不是单格行");
            assertEquals(3, tables.get(0).table().rows().size());
            assertEquals("生产现场", tables.get(0).table().rows().get(2).get(0));
        }
    }

    @Test
    void firstRowWithALongCellMakesTheTableHeaderless() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("词表");
            put(sheet, 0, 0, "矿样解释");
            merge(sheet, "A1:B1");
            put(sheet, 1, 0, "原矿溢流", "刚采出来的矿石经过第一次磨矿、分级之后流出来的初始矿浆，还没进入选别环节，是选别前的原料液。");
            put(sheet, 2, 0, "磁尾", "磁选后剩下的废料，含铁量低。");

            Table table = tables(analyze(sheet)).get(0);

            assertNull(table.headerRow());
            assertEquals("矿样解释", table.title());
            assertEquals(2, table.table().rows().size());
            assertEquals(List.of("", ""), table.table().headers());
        }
    }

    @Test
    void shortTextDirectlyAboveAnUntitledTableIsItsTitleButAFarCaptionIsNot() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("总览");
            put(sheet, 0, 1, "2.调研背景");
            put(sheet, 2, 1, "对比项", "现场", "中心");
            put(sheet, 3, 1, "频次", "1-2小时", "一周2次");
            put(sheet, 1, 8, "左：采样桶");
            put(sheet, 6, 1, "3.后续计划");
            put(sheet, 6, 8, "右：烘干炉");
            put(sheet, 8, 1, "阶段", "核心", "目标");
            put(sheet, 9, 1, "1.验证", "对接", "可行");

            List<Region> regions = analyze(sheet);
            List<Table> tables = tables(regions);

            assertEquals("2.调研背景", tables.get(0).title());
            assertEquals("3.后续计划", tables.get(1).title());
            assertEquals(List.of("I2", "I7"), texts(regions).stream().map(Text::range).toList(),
                    "列范围不与表重叠的图片说明只当段落");
        }
    }

    @Test
    void blankRowInsideATableDoesNotTurnTheNextDataRowIntoAHeader() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("续段");
            CellStyle bold = boldStyle(workbook);
            put(sheet, 0, 0, "设备", "数量", "用途");
            styleRow(sheet, 0, bold);
            put(sheet, 1, 0, "天平", "2台", "称样");
            put(sheet, 3, 0, "烘箱", "1台", "烘干");
            put(sheet, 4, 0, "马弗炉", "1台", "熔融");
            put(sheet, 6, 0, "试剂", "规格", "用量");
            styleRow(sheet, 6, bold);
            put(sheet, 7, 0, "盐酸", "分析纯", "25mL");

            List<Table> tables = tables(analyze(sheet));

            assertEquals(2, tables.size(), "空行后首行不加粗是续段；整行加粗的是新表头");
            assertEquals(3, tables.get(0).table().rows().size());
            assertEquals(List.of("设备", "数量", "用途"), tables.get(0).headerCells());
            assertEquals(6, tables.get(1).headerRow());
        }
    }

    @Test
    void isolatedCellsInsideASparseMatrixBelongToTheTable() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("矩阵");
            put(sheet, 0, 0, "时间", "样品");
            merge(sheet, "B1:F1");
            put(sheet, 1, 0, "8:00", "原矿", "混磁精");
            put(sheet, 2, 0, "10:00");
            put(sheet, 2, 5, "粒度");
            put(sheet, 3, 0, "12:00", "原矿");

            List<Region> regions = analyze(sheet);

            assertTrue(texts(regions).isEmpty(), "F3 与邻格不相邻，但落在表的范围里");
            Table table = tables(regions).get(0);
            assertEquals("粒度", table.table().rows().get(1).get(5));
        }
    }

    @Test
    void theJudgeDecidesTheFirstRowAndSeesThisSegmentAlignedByColumn() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("词表");
            put(sheet, 0, 0, "样品说明");
            merge(sheet, "A1:C1");
            put(sheet, 1, 0, "名称", "说明");
            merge(sheet, "B2:C2");
            put(sheet, 1, 4, "右：图片");
            put(sheet, 2, 0, "原矿", "刚采出", "未选别");
            put(sheet, 3, 0, "磁尾", "磁选后", "含铁低");
            List<HeaderJudge.Candidate> seen = new ArrayList<>();

            List<Region> regions = ExcelLayoutAnalyzer.analyze(sheet, new DataFormatter(),
                    workbook.getCreationHelper().createFormulaEvaluator(), 1, candidate -> {
                        seen.add(candidate);
                        return new HeaderJudge.Decision(false, HeaderJudge.Source.JEV, 0.1);
                    });
            Table table = tables(regions).get(0);

            assertNull(table.headerRow(), "判定说不是表头，就按无表头处理");
            assertEquals(1, table.firstDataRow());
            assertEquals("样品说明", table.title());
            assertEquals(1, seen.size(), "只有表格区域交给判定，右侧的单格说明不算");
            HeaderJudge.Candidate candidate = seen.get(0);
            assertEquals("词表", candidate.sheet());
            assertEquals("样品说明", candidate.title());
            assertEquals(List.of(1, 2, 3), candidate.rowIndexes());
            assertEquals(List.of("名称", "说明", ""), candidate.rows().get(0), "合并块只在首列出现一次");
            assertEquals(List.of("原矿", "刚采出", "未选别"), candidate.rows().get(1));
            assertEquals(List.of("名称", "说明"), candidate.firstRowCells());
        }
    }

    @Test
    void theRuleJudgeKeepsTheOldThresholds() {
        HeaderJudge rule = RuleHeaderJudge.INSTANCE;

        assertTrue(rule.judge(candidate(List.of("设备", "数量", "说明"))).header());
        assertFalse(rule.judge(candidate(List.of("天平", "2", "称样", "3"))).header(), "数字占一半就像数据");
        assertTrue(rule.judge(candidate(List.of("天平", "2", "称样", "备注"))).header(), "数字不到一半仍算表头");
        assertFalse(rule.judge(candidate(List.of("原矿", "x".repeat(41)))).header(), "超过 40 字像数据");
        assertTrue(rule.judge(candidate(List.of("原矿", "x".repeat(40)))).header());
        assertFalse(rule.judge(candidate(List.of("", ""))).header());
        assertEquals(HeaderJudge.Source.RULE, rule.judge(candidate(List.of("设备"))).source());
    }

    private static HeaderJudge.Candidate candidate(List<String> firstRow) {
        return new HeaderJudge.Candidate("s", null, List.of(0), List.of(firstRow));
    }

    private static List<Region> analyze(Sheet sheet) {
        return ExcelLayoutAnalyzer.analyze(sheet, new DataFormatter(),
                sheet.getWorkbook().getCreationHelper().createFormulaEvaluator(), 1);
    }

    private static List<Table> tables(List<Region> regions) {
        List<Table> tables = new ArrayList<>();
        for (Region region : regions) {
            for (Item item : region.items()) {
                if (item instanceof Table table) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private static List<Text> texts(List<Region> regions) {
        List<Text> texts = new ArrayList<>();
        for (Region region : regions) {
            for (Item item : region.items()) {
                if (item instanceof Text text) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    private static void put(Sheet sheet, int row, int firstCol, String... values) {
        Row target = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isEmpty()) {
                target.createCell(firstCol + i).setCellValue(values[i]);
            }
        }
    }

    private static void merge(Sheet sheet, String range) {
        sheet.addMergedRegion(CellRangeAddress.valueOf(range));
    }

    private static CellStyle boldStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static void styleRow(Sheet sheet, int row, CellStyle style) {
        sheet.getRow(row).forEach(cell -> cell.setCellStyle(style));
    }
}
