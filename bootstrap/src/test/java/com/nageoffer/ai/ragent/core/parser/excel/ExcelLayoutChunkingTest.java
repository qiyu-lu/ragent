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

import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版面切分接到解析与分块之后的整体行为：规整表开关开、关输出相同；区域是打包器的分节边界；表题随每一块
 */
class ExcelLayoutChunkingTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void cleanSheetsChunkIdenticallyWithLayoutOnAndOff() throws IOException {
        byte[] workbook;
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet small = book.createSheet("小表");
            put(small, 0, 0, "设备", "数量");
            put(small, 1, 0, "天平", "2台");
            Sheet big = book.createSheet("大表");
            put(big, 0, 0, "步骤", "参数", "说明");
            for (int r = 1; r <= 60; r++) {
                put(big, r, 0, "第" + r + "步", "参数" + r, "说明".repeat(10 + r % 7));
            }
            workbook = bytes(book);
        }
        for (ChunkBudget budget : List.of(ChunkBudget.defaults(), new ChunkBudget(300, 40, 5))) {
            List<Chunk> off = chunk(parse(workbook, false), budget);
            List<Chunk> on = chunk(parse(workbook, true), budget);
            assertEquals(texts(off), texts(on));
            assertEquals(off.stream().map(c -> c.metadata().outlinePath()).toList(),
                    on.stream().map(c -> c.metadata().outlinePath()).toList());
        }
    }

    @Test
    void regionsAreSectionBoundariesAndCaptionsTravelWithEveryChunk() throws IOException {
        byte[] workbook;
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("设备表");
            put(sheet, 0, 1, "一、生产型实验室");
            sheet.addMergedRegion(CellRangeAddress.valueOf("B1:D1"));
            put(sheet, 1, 1, "序号", "设备名称", "核心功能");
            for (int r = 2; r <= 40; r++) {
                put(sheet, r, 1, String.valueOf(r - 1), "生产设备" + r, "用于检测".repeat(12));
            }
            put(sheet, 42, 1, "二、研究型中心实验室");
            sheet.addMergedRegion(CellRangeAddress.valueOf("B43:D43"));
            put(sheet, 43, 1, "序号", "设备名称", "核心功能");
            for (int r = 44; r <= 80; r++) {
                put(sheet, r, 1, String.valueOf(r - 43), "研究仪器" + r, "用于研究".repeat(12));
            }
            workbook = bytes(book);
        }
        List<Chunk> chunks = chunk(parse(workbook, true), ChunkBudget.defaults());

        assertTrue(chunks.stream().allMatch(c -> c.content().length() <= 1024));
        assertFalse(chunks.stream().anyMatch(c -> c.content().isBlank()), "分节标记不能落成空块");
        for (Chunk chunk : chunks) {
            boolean production = chunk.content().contains("生产设备");
            boolean research = chunk.content().contains("研究仪器");
            assertFalse(production && research, "两张表不能装进同一块");
            if (production) {
                assertTrue(chunk.content().contains("一、生产型实验室") && chunk.embeddingText().contains("一、生产型实验室"));
            }
            if (research) {
                assertTrue(chunk.content().contains("二、研究型中心实验室") && chunk.embeddingText().contains("二、研究型中心实验室"));
                assertFalse(chunk.content().contains("一、生产型实验室"), "后一张表的标题不能并进前一张表的末块");
            }
        }
    }

    @Test
    void blankHeadingClosesTheSectionWithoutOpeningANewOne() {
        HeadingHandler handler = new HeadingHandler();
        Provenance provenance = Provenance.ofFile("x.xlsx");
        HeadingHandler.Outline outline = handler.update(HeadingHandler.Outline.EMPTY, new HeadingBlock(provenance, 1, "设备表"));
        outline = handler.update(outline, new HeadingBlock(provenance, 2, "一、生产型实验室"));
        outline = handler.update(outline, new HeadingBlock(provenance, 2, ""));

        assertEquals(List.of("设备表"), outline.path());
    }

    private static List<Block> parse(byte[] workbook, boolean layout) {
        ExcelLayoutProperties properties = new ExcelLayoutProperties();
        properties.setEnabled(layout);
        return new ExcelDocumentParser(null, new ImageParseProperties(), properties)
                .parseStructured(workbook, XLSX, Map.of(ExcelDocumentParser.OPT_SOURCE_FILE, "x.xlsx")).blocks();
    }

    private static List<Chunk> chunk(List<Block> blocks, ChunkBudget budget) {
        return new BlockAwareChunkerDispatcher(new HeadingHandler(), new ChunkPacker(),
                List.of(new HeadingChunker(), new ParagraphChunker(), new TableChunker())).dispatch(blocks, budget);
    }

    private static List<String> texts(List<Chunk> chunks) {
        return chunks.stream().map(c -> c.content() + "\u0000" + c.embeddingText()).toList();
    }

    private static void put(Sheet sheet, int row, int firstCol, String... values) {
        Row target = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
        for (int i = 0; i < values.length; i++) {
            target.createCell(firstCol + i).setCellValue(values[i]);
        }
    }

    private static byte[] bytes(XSSFWorkbook book) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        book.write(out);
        return out.toByteArray();
    }
}
