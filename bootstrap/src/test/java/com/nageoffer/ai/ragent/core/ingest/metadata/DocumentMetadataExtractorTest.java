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

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayer;
import com.nageoffer.ai.ragent.core.parser.quality.TextLayerClass;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentMetadataExtractorTest {

    private static final Provenance P = Provenance.ofFile("x.pdf");
    private static final TermDictionary TERMS = new TermDictionary(new DefaultResourceLoader(), "classpath:kq/terms.csv");
    private static final DocumentMetadataExtractor EXTRACTOR = new DocumentMetadataExtractor(TERMS);

    private static ParsedDocument cover(String... lines) {
        List<Block> blocks = new java.util.ArrayList<>();
        for (String line : lines) {
            blocks.add(line.startsWith("# ") ? new HeadingBlock(P, 1, line.substring(2)) : new ParagraphBlock(P, line));
        }
        return ParsedDocument.of(blocks);
    }

    private static PdfTextLayer layer(TextLayerClass layerClass, String head) {
        return new PdfTextLayer(16, layerClass, 500, 1000, 9000, 0D, List.of(), head);
    }

    @Test
    void dictionaryUsesLongestMatchAndSynonyms() {
        assertTrue(TERMS.size() > 50);
        assertEquals(List.of("钛铁矿精矿"), TERMS.canonicals("钛铁矿精矿化学分析方法", TermDictionary.OBJECT));
        assertEquals(List.of("硫铁矿", "硫精矿"), TERMS.canonicals("硫铁矿和硫精矿中硅含量的测定", TermDictionary.OBJECT));
        assertEquals(List.of("硅"), TERMS.canonicals("测 SiO₂ 的含量", TermDictionary.COMPONENT));
        assertEquals(List.of(), TERMS.canonicals("加入硫酸亚铁铵溶液", TermDictionary.COMPONENT));
        assertEquals(List.of("全铁"), TERMS.canonicals("铁矿的 tfe 怎么测", TermDictionary.COMPONENT));
        assertEquals(List.of("铁矿石"), TERMS.canonicals("铁矿的 tfe 怎么测", TermDictionary.OBJECT));
        assertEquals(List.of("重铬酸钾滴定法"), TERMS.canonicals("用重铬酸钾滴定法", TermDictionary.METHOD));
    }

    @Test
    void textLayerStandardNumberWinsOverALossyParse() {
        // 2014 版硅含量的默认解析把"代替"后面的号整段丢了，文字层完好
        DocumentMetadata metadata = EXTRACTOR.extract("铁矿石+硅含量的测定+重量法.pdf",
                layer(TextLayerClass.TEXT, "ICS 73.060.10\nGB/T6730.10—2014\n代替GB/T6730.10—1986\n2014-09-30 发布"),
                cover("# 中 华 人 民 共 和 国 国 家 标 准", "GB/T6730.10—2014", "代替 / —", "# 铁矿石 硅含量的测定 重量法"));
        assertEquals("GB/T 6730.10-2014", metadata.standardNo());
        assertEquals("GB/T 6730.10", metadata.standardBase());
        assertEquals(2014, metadata.publishYear());
        assertEquals(List.of("GB/T 6730.10-1986"), metadata.replaces());
        assertEquals(List.of("铁矿石"), metadata.objects());
        assertEquals(List.of("硅"), metadata.components());
        assertEquals(List.of("重量法"), metadata.methods());
        assertEquals(DocumentMetadata.SOURCE_EXTRACTED, metadata.source());
    }

    @Test
    void garbledHeaderFallsBackToTheParsedCover() {
        // 2007 版全铁的页眉在文字层里是"犌犅/犜"，文字层里能读的第一个号是"代替"后面的 1986 版，不能当成本标准号
        DocumentMetadata metadata = EXTRACTOR.extract("铁矿石+全铁含量的测定+三氯化钛还原法.pdf",
                layer(TextLayerClass.TEXT, "犌犅／犜６７３０．５—２００７\n代替 ＧＢ／Ｔ６７３０．５—１９８６"),
                cover("# 中华人民共和国国家标准", "GB/T 6730.5—2007", "代替 GB/T 6730.5—1986",
                        "# 铁矿石 全铁含量的测定三氯化钛还原法"));
        assertEquals("GB/T 6730.5-2007", metadata.standardNo());
        assertEquals(List.of("GB/T 6730.5-1986"), metadata.replaces());
        assertEquals(List.of("全铁"), metadata.components());
        assertEquals(List.of("三氯化钛还原法"), metadata.methods());
    }

    @Test
    void fullWidthIndustryStandardsAndNonStandards() {
        // 封面字距把年份排成"20 11"
        DocumentMetadata ys = EXTRACTOR.extract("钛铁矿精矿化学分析方法.第2部分_全铁量的测定.重铬酸钾滴定法.pdf",
                layer(TextLayerClass.TEXT, "YS／T 360．2—20 11\n代替ＹＳ／Ｔ ３６０－－１９９４"), cover());
        assertEquals("YS/T 360.2-2011", ys.standardNo());
        assertEquals(List.of("YS/T 360-1994"), ys.replaces());
        assertEquals(List.of("钛铁矿精矿"), ys.objects());
        assertEquals(List.of("全铁"), ys.components());

        DocumentMetadata survey = EXTRACTOR.extract("铁矿石人工检测流程调研V1.2.xlsx", null,
                cover("# 调研版本总览", "1.汇报摘要"));
        assertNull(survey.standardNo());
        assertEquals(List.of("铁矿石"), survey.objects());
        assertEquals(List.of("工序与设备"), survey.components());

        DocumentMetadata scan = EXTRACTOR.extract("硫铁矿和硫精矿中硅含量的测定+重量法.pdf",
                layer(TextLayerClass.SCANNED, "东北大学"), cover("# 中华人民共和国国家标准", "GB/T 16574—1996"));
        assertEquals("GB/T 16574-1996", scan.standardNo());
        assertEquals(List.of("硫铁矿", "硫精矿"), scan.objects());
    }

    @Test
    void standardNumbersParseFromAnySpelling() {
        assertEquals("GB/T 10322.1-2014", DocumentMetadataExtractor.parseStandardNo("GB/T10322.1—2014/ISO3082:2009").toString());
        assertEquals("YS/T 360.3-2011", DocumentMetadataExtractor.parseStandardNo("ＹＳ／Ｔ ３６０．３—２０１１").toString());
        assertNull(DocumentMetadataExtractor.parseStandardNo("ISO 3082:2009"));
    }

    @Test
    void metadataRoundTripsThroughAMap() {
        DocumentMetadata metadata = new DocumentMetadata("GB/T 6730.10-2014", "GB/T 6730.10", 2014,
                List.of("GB/T 6730.10-1986"), List.of("铁矿石"), List.of("硅"), List.of("重量法"), "confirmed");
        assertEquals(metadata, DocumentMetadata.fromMap(metadata.toMap()));
    }
}
