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

package com.nageoffer.ai.ragent.core.parser.quality;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseQualityAuditorTest {

    private static final String GOOD = "温度控制在 400 ℃±20 ℃，放置 1 min。硅含量高于 10%（质量分数）。12 个国家的 28 个实验室。";
    private static final String BAD = "温度控制在 ,放置 。硅含量高于 (质量分数)。个国家的 个实验室。";

    private static ParseQualityProperties enabled() {
        ParseQualityProperties properties = new ParseQualityProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static PdfTextLayer layer(TextLayerClass layerClass, int digits) {
        return new PdfTextLayer(16, layerClass, 500, digits, 9000, 0D, List.of(), "");
    }

    /**
     * 按 OCR 开关返回不同正文的假 MinerU 解析器，并记下每次调用的开关
     */
    private static final class FakeMinerU implements DocumentParser {
        final List<Boolean> calls = new ArrayList<>();
        final String withoutOcr;
        final String withOcr;

        FakeMinerU(String withoutOcr, String withOcr) {
            this.withoutOcr = withoutOcr;
            this.withOcr = withOcr;
        }

        @Override
        public String getParserType() {
            return ParserType.MINERU.getType();
        }

        @Override
        public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
            boolean ocr = Boolean.TRUE.equals(options.get(MinerUDocumentParser.OPT_IS_OCR));
            calls.add(ocr);
            return ParsedDocument.of(List.of(new ParagraphBlock(Provenance.ofFile("x.pdf"), ocr ? withOcr : withoutOcr)),
                    Map.of(MinerUDocumentParser.META_PARAMS, Map.of("isOcr", ocr)));
        }

        @Override
        public Map<ParseProfile, Set<String>> supportedMimeTypes() {
            return Map.of(ParseProfile.FAST, Set.of("application/pdf"));
        }
    }

    @Test
    void failingFirstParseIsRecoveredByOcr() {
        FakeMinerU parser = new FakeMinerU(BAD, GOOD);
        int digits = ParseTextMetrics.measure(GOOD).digits();
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.TEXT, digits));

        assertEquals(List.of(false, true), parser.calls);
        assertEquals(ParseAudit.Verdict.RECOVERED, result.audit().verdict());
        assertEquals(1, result.audit().chosenAttempt());
        assertEquals(GOOD, ((ParagraphBlock) result.document().blocks().get(0)).text());
        assertFalse(result.audit().attempts().get(0).passed());
        assertTrue(result.audit().attempts().get(0).failures().get(0).startsWith("strictSlotsPer1000"));
        assertEquals(1.0, result.audit().chosen().digitRetention(), 1e-9);
    }

    @Test
    void passingFirstParseIsNotReparsed() {
        FakeMinerU parser = new FakeMinerU(GOOD, BAD);
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(),
                        layer(TextLayerClass.TEXT, ParseTextMetrics.measure(GOOD).digits()));

        assertEquals(List.of(false), parser.calls);
        assertEquals(ParseAudit.Verdict.PASSED, result.audit().verdict());
    }

    @Test
    void lowDigitRetentionAloneFailsTheAudit() {
        String fewDigits = "温度控制在规定范围内，放置一段时间后称量。";
        FakeMinerU parser = new FakeMinerU(fewDigits, fewDigits);
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.TEXT, 100));

        assertEquals(ParseAudit.Verdict.NEEDS_REVIEW, result.audit().verdict());
        assertTrue(result.audit().attempts().get(0).failures().get(0).startsWith("digitRetention"));
        assertEquals(0, result.audit().chosenAttempt(), "两次一样差时留首次");
    }

    @Test
    void scannedDocumentsAreJudgedBySlotsOnlyAndGarbledOnesStartWithOcr() {
        FakeMinerU scanned = new FakeMinerU(GOOD, BAD);
        ParseQualityAuditor.AuditedParse scan = new ParseQualityAuditor(enabled())
                .parse(scanned, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.SCANNED, 5));
        assertEquals(List.of(false), scanned.calls);
        assertNull(scan.audit().chosen().digitRetention());
        assertEquals(ParseAudit.Verdict.PASSED, scan.audit().verdict());

        FakeMinerU garbled = new FakeMinerU(GOOD, GOOD);
        new ParseQualityAuditor(enabled())
                .parse(garbled, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.GARBLED, 5));
        assertEquals(List.of(true), garbled.calls);
    }

    @Test
    void gateIsInertWhenDisabledOrNotApplicable() {
        FakeMinerU parser = new FakeMinerU(BAD, GOOD);
        ParseQualityAuditor.AuditedParse disabled = new ParseQualityAuditor(new ParseQualityProperties())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of("k", "v"), layer(TextLayerClass.TEXT, 10));
        assertNull(disabled.audit());
        assertEquals(List.of(false), parser.calls);

        ParseQualityAuditor.AuditedParse word = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/msword", Map.of(), layer(TextLayerClass.TEXT, 10));
        assertNull(word.audit());
    }

    @Test
    void metricsIgnoreImageHashesAndHtmlAttributes() {
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(
                "称取 0.5 g ![](images/7bbefdb15db7a398b850af5819e9032c.jpg) <td colspan=\"2\">10</td> 在 ,");
        assertEquals(4, measurement.digits());
        assertEquals(1, measurement.strictSlots());
    }

    @Test
    void betterPrefersPassingThenFewerSlotsThenHigherRetention() {
        ParseAudit.Attempt passing = new ParseAudit.Attempt(Map.of(), 10, 1000, 5, 5.0, 0.5, true, List.of());
        ParseAudit.Attempt failingClean = new ParseAudit.Attempt(Map.of(), 10, 1000, 0, 0.0, 1.0, false, List.of("x"));
        assertTrue(ParseQualityAuditor.better(passing, failingClean));
        ParseAudit.Attempt fewerSlots = new ParseAudit.Attempt(Map.of(), 10, 1000, 1, 1.0, 0.4, false, List.of("x"));
        ParseAudit.Attempt moreSlots = new ParseAudit.Attempt(Map.of(), 10, 1000, 9, 9.0, 0.9, false, List.of("x"));
        assertTrue(ParseQualityAuditor.better(fewerSlots, moreSlots));
        assertFalse(ParseQualityAuditor.better(moreSlots, fewerSlots));
    }

    @Test
    void textLayerDropsRunningHeadersAndPageNumbersAndClassifiesScans() {
        PdfTextLayerAnalyzer analyzer = new PdfTextLayerAnalyzer(new ParseQualityProperties());
        List<String> pages = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pages.add("GB/T 6730.10—2014\n中国标准出版社授权北京万方数据股份有限公司在中国境内(不含港澳台地区)推广使用\n"
                    + "第" + i + "页正文：温度控制在 400 ℃±20 ℃，放置 1 min，称取试样后加入盐酸并加热溶解，然后过滤洗涤沉淀并灼烧至恒重。\n"
                    + i + "\n");
        }
        PdfTextLayer text = analyzer.summarize(pages);
        assertEquals(TextLayerClass.TEXT, text.textLayerClass());
        assertTrue(text.repeatedLines().contains("GB/T6730.10-2014"));
        assertEquals(4 * 7, text.digits(), "页眉与页码行不计，每页正文 7 个数字");
        assertTrue(text.headText().startsWith("GB/T 6730.10"));

        PdfTextLayer scan = analyzer.summarize(List.of("东北大学\n", "东北大学\n", "东北大学\n", "附录\n东北大学\n"));
        assertEquals(TextLayerClass.SCANNED, scan.textLayerClass());

        PdfTextLayer garbled = analyzer.summarize(List.of("".repeat(40) + "正文".repeat(30)));
        assertEquals(TextLayerClass.GARBLED, garbled.textLayerClass());
    }

    @Test
    void analyzerReadsARealPdfPageByPage() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= 3; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText("ISO 3082 running header");
                    stream.newLineAtOffset(0, -20);
                    stream.showText("Body text of page number " + i + " with temperature 400 and 20 more words for length");
                    stream.endText();
                }
            }
            document.save(out);
            pdf = out.toByteArray();
        }
        PdfTextLayer layer = new PdfTextLayerAnalyzer(new ParseQualityProperties()).analyze(pdf).orElseThrow();
        assertEquals(3, layer.pages());
        assertEquals(List.of("iso3082runningheader"), layer.repeatedLines().stream().map(String::toLowerCase).toList());
        assertEquals(3 * 6, layer.digits());
        assertSame(TextLayerClass.TEXT, layer.textLayerClass());
        assertTrue(new PdfTextLayerAnalyzer(new ParseQualityProperties()).analyze(new byte[]{1, 2, 3}).isEmpty());
    }
}
