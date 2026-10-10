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

package com.nageoffer.ai.ragent.core.parser.normalize;

import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.CodeBlock;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayer;
import com.nageoffer.ai.ragent.core.parser.quality.TextLayerClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedDocumentNormalizerTest {

    private static final Provenance P = Provenance.ofFile("si.pdf");
    private static final String WATERMARK = "中国标准出版社授权北京万方数据股份有限公司在中国境内(不含港澳台地区)推广使用";

    private static PdfTextLayer layerWithFurniture() {
        List<String> repeated = List.of(IngestionTextNormalizer.matchForm(WATERMARK),
                IngestionTextNormalizer.matchForm("东北大学"),
                IngestionTextNormalizer.matchForm("GB/T 6730.10—2014"));
        return new PdfTextLayer(16, TextLayerClass.TEXT, 500, 1000, 9000, 0D, repeated, "");
    }

    private static TextNormalizeProperties enabled() {
        TextNormalizeProperties properties = new TextNormalizeProperties();
        properties.setEnabled(true);
        return properties;
    }

    @Test
    void removesFurnitureSeenThreeTimesButKeepsTheCoverStandardNumber() {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new HeadingBlock(P, 1, "中华人民共和国国家标准"));
        blocks.add(new ParagraphBlock(P, "GB/T 6730.10—2014"));
        blocks.add(new ParagraphBlock(P, "前言正文。\n" + WATERMARK));
        blocks.add(new HeadingBlock(P, 2, "东北大学"));
        blocks.add(new ParagraphBlock(P, "中国标准出版社授权北京万方数据股份有限公司在中国培内(不含港澳台地区)推广使田"));
        blocks.add(new ParagraphBlock(P, "权北京万方数据股份有限公司在中国境内(不含港澳台地区)推广使用"));
        blocks.add(new ParagraphBlock(P, "东北大学"));
        blocks.add(new ParagraphBlock(P, "GB/T 6730.10—2014 规定了重量法。"));
        blocks.add(new ListBlock(P, false, List.of("东北大学", "盐酸")));

        ParsedDocumentNormalizer.Result result = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks, Map.of("parser", "MinerU")), layerWithFurniture(), true);

        List<String> texts = result.document().blocks().stream().map(ParsedDocumentNormalizerTest::text).toList();
        assertEquals(List.of("中华人民共和国国家标准", "GB/T 6730.10—2014", "前言正文。",
                "GB/T 6730.10—2014 规定了重量法。", "盐酸"), texts, "入库正文保留标准号里的破折号");
        assertEquals(6, result.removedLines(), "水印 3 次（含 OCR 变体与残片）+ 东北大学 3 次");
        assertEquals("MinerU", result.document().metadata().get("parser"));
        assertEquals(Map.of("applied", true, "version", 2, "repeatedLinesRemoved", 6), result.toMap());
    }

    @Test
    void runningHeaderKeepsItsCoverOccurrence() {
        List<Block> blocks = List.of(new ParagraphBlock(P, "GB/T 6730.10—2014"), new ParagraphBlock(P, "1 范围"),
                new ParagraphBlock(P, "GB/T 6730.10—2014"), new ParagraphBlock(P, "正文\nGB/T 6730.10-2014"));
        ParsedDocumentNormalizer.Result result = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks), layerWithFurniture(), true);
        assertEquals(List.of("GB/T 6730.10—2014", "1 范围", "正文"),
                result.document().blocks().stream().map(ParsedDocumentNormalizerTest::text).toList());
        assertEquals(2, result.removedLines());
    }

    @Test
    void furnitureBelowTheOccurrenceThresholdStays() {
        List<Block> blocks = List.of(new ParagraphBlock(P, WATERMARK), new ParagraphBlock(P, "正文"),
                new ParagraphBlock(P, WATERMARK));
        ParsedDocumentNormalizer.Result result = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks), layerWithFurniture(), true);
        assertEquals(3, result.document().blocks().size());
        assertEquals(0, result.removedLines());
    }

    @Test
    void normalizesTextButLeavesCodeAndImageAssetsAlone() {
        AssetRef asset = new AssetRef("http://127.0.0.1:9000/ragent-assets/assets/1/a.jpg", "image/jpeg");
        CodeBlock code = new CodeBlock(P, "text", "１００　℃");
        List<Block> blocks = List.of(
                new ParagraphBlock(P, "温度 $400 \\pm 20 ^ { \\circ } \\mathrm { C }$，质量分数１％"),
                new TableBlock(P, List.of("项目", "温度"), List.of(List.of("灼烧", "１０５０ ℃"))),
                code,
                new ImageBlock(P, asset, "图１", "图１"));
        ParsedDocument normalized = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks), null, true).document();

        assertEquals("温度 400±20°C，质量分数1%", text(normalized.blocks().get(0)));
        assertEquals(List.of("灼烧", "1050 °C"), ((TableBlock) normalized.blocks().get(1)).rows().get(0));
        assertSame(code, normalized.blocks().get(2));
        ImageBlock image = (ImageBlock) normalized.blocks().get(3);
        assertSame(asset, image.asset());
        assertEquals("图1", image.caption());

        ParsedDocument notMinerU = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks), null, false).document();
        assertEquals("温度 $400 \\pm 20 ^ { \\circ } \\mathrm { C }$，质量分数1%", text(notMinerU.blocks().get(0)),
                "不是 MinerU 的 markdown 时 $ 是字面字符，不去壳");
    }

    @Test
    void spreadsheetCellsKeepChinesePunctuationAndSuperscripts() {
        String cell = "铁矿石中以二价铁（Fe²+）形态存在的铁元素含量，占干矿总重量的百分比；核心功能：① 取样";
        List<Block> blocks = List.of(new TableBlock(P, List.of("指标", "说明"), List.of(List.of("亚铁品位", cell))));
        ParsedDocument normalized = new ParsedDocumentNormalizer(enabled())
                .normalize(ParsedDocument.of(blocks), null, false).document();
        assertEquals(List.of("亚铁品位", cell), ((TableBlock) normalized.blocks().get(0)).rows().get(0));
    }

    @Test
    void disabledNormalizerReturnsTheSameDocument() {
        ParsedDocument document = ParsedDocument.of(List.of(new ParagraphBlock(P, "１００ ℃")));
        ParsedDocumentNormalizer.Result result = new ParsedDocumentNormalizer(new TextNormalizeProperties())
                .normalize(document, layerWithFurniture(), true);
        assertSame(document, result.document());
        assertFalse(result.applied());
    }

    @Test
    void similarityToleratesOcrSlipsOnly() {
        assertTrue(RepeatedLineFilter.similarity("abcdefghij", "abcdefghiX") >= 0.85);
        assertTrue(RepeatedLineFilter.similarity("abcdefghij", "abcdeXXXXX") < 0.85);
        assertEquals(0D, RepeatedLineFilter.similarity("短", "一个长得多的句子"));
    }

    private static String text(Block block) {
        if (block instanceof HeadingBlock heading) {
            return heading.text();
        }
        if (block instanceof ParagraphBlock paragraph) {
            return paragraph.text();
        }
        if (block instanceof ListBlock list) {
            return String.join("|", list.items());
        }
        return block.getClass().getSimpleName();
    }
}
