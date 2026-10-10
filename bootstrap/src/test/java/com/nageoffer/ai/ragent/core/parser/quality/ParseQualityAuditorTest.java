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
import com.nageoffer.ai.ragent.core.parser.StagedDocumentParser;
import com.nageoffer.ai.ragent.core.parser.mineru.MinerUDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
     * 按 OCR 开关返回不同正文的假 MinerU 解析器：记下每次取结果用的开关，以及哪几次被展开成完整结果（上传图片、图生文）
     */
    private static final class FakeMinerU implements StagedDocumentParser {
        final List<Boolean> calls = new ArrayList<>();
        final List<Boolean> completed = new ArrayList<>();
        final String withoutOcr;
        final String withOcr;
        RuntimeException failOnSecondFetch;

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
            return complete(fetch(content, mimeType, options));
        }

        @Override
        public Fetched fetch(byte[] content, String mimeType, Map<String, Object> options) {
            boolean ocr = Boolean.TRUE.equals(options.get(MinerUDocumentParser.OPT_IS_OCR));
            calls.add(ocr);
            if (calls.size() == 2 && failOnSecondFetch != null) {
                throw failOnSecondFetch;
            }
            return new Fetched((ocr ? withOcr : withoutOcr).getBytes(StandardCharsets.UTF_8), "x.pdf", "doc-1",
                    Map.of(MinerUDocumentParser.META_PARAMS, Map.of("isOcr", ocr)));
        }

        @Override
        public ParsedDocument preview(Fetched fetched) {
            return document(fetched, null);
        }

        @Override
        public ParsedDocument complete(Fetched fetched) {
            completed.add(Boolean.TRUE.equals(((Map<?, ?>) fetched.metadata().get(MinerUDocumentParser.META_PARAMS)).get("isOcr")));
            return document(fetched, "图生文转写：温度 1000 ℃，时间 30 min，质量 0.5000 g");
        }

        private static ParsedDocument document(Fetched fetched, String imageDescription) {
            Provenance provenance = Provenance.ofFile(fetched.sourceFile());
            return ParsedDocument.of(List.of(new ParagraphBlock(provenance, new String(fetched.payload(), StandardCharsets.UTF_8)),
                            new ImageBlock(provenance, new AssetRef("images/a.jpg", "image/jpeg"), "图1", "图1", imageDescription)),
                    fetched.metadata());
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
        assertEquals(1.0, result.audit().chosen().digitRetention(), 1e-9, "审计用的是不带图生文描述的正文");
        assertEquals(List.of(true), parser.completed, "只有选中的那次上传图片、做图生文");
        assertTrue(((ImageBlock) result.document().blocks().get(1)).description().startsWith("图生文"));
    }

    @Test
    void passingFirstParseIsNotReparsed() {
        FakeMinerU parser = new FakeMinerU(GOOD, BAD);
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(),
                        layer(TextLayerClass.TEXT, ParseTextMetrics.measure(GOOD).digits()));

        assertEquals(List.of(false), parser.calls);
        assertEquals(List.of(false), parser.completed);
        assertEquals(ParseAudit.Verdict.PASSED, result.audit().verdict());
    }

    @Test
    void failedReparseKeepsTheFirstResultForReview() {
        FakeMinerU parser = new FakeMinerU(BAD, GOOD);
        parser.failOnSecondFetch = new ServiceException("MinerU 等待超时(包含调度缓冲)batchId=b-2");
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(),
                        layer(TextLayerClass.TEXT, ParseTextMetrics.measure(GOOD).digits()));

        assertEquals(List.of(false, true), parser.calls);
        assertEquals(ParseAudit.Verdict.NEEDS_REVIEW, result.audit().verdict());
        assertEquals(0, result.audit().chosenAttempt());
        assertEquals(BAD, ((ParagraphBlock) result.document().blocks().get(0)).text());
        assertEquals(List.of(false), parser.completed);
        ParseAudit.Attempt failed = result.audit().attempts().get(1);
        assertTrue(failed.failedToParse());
        assertEquals(Map.of("isOcr", true), failed.params());
        assertTrue(failed.parseError().contains("等待超时"));
        assertEquals(failed.parseError(), failed.toMap().get("parseError"));
        assertFalse(result.audit().attempts().get(0).toMap().containsKey("parseError"));
    }

    @Test
    void interruptedReparseIsNotSwallowed() {
        FakeMinerU parser = new FakeMinerU(BAD, GOOD);
        parser.failOnSecondFetch = new ServiceException("MinerU 获取解析许可被中断");
        Thread.currentThread().interrupt();
        try {
            assertThrows(ServiceException.class, () -> new ParseQualityAuditor(enabled())
                    .parse(parser, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.TEXT, 10)));
        } finally {
            Thread.interrupted();
        }
        assertTrue(parser.completed.isEmpty());
    }

    @Test
    void aSingleSlotDoesNotFailAShortDocument() {
        String oneSlot = "称取 0.5000 g 试样，加热 30 min，温度控制在 ,冷却后称量。";
        assertEquals(1, ParseTextMetrics.measure(oneSlot).strictSlots());
        FakeMinerU parser = new FakeMinerU(oneSlot, BAD);
        ParseQualityAuditor.AuditedParse result = new ParseQualityAuditor(enabled())
                .parse(parser, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.SCANNED, 5));
        assertEquals(ParseAudit.Verdict.PASSED, result.audit().verdict());
        assertTrue(result.audit().chosen().strictSlotsPer1000() > 0.3, "密度超了，但只有 1 处");
        assertEquals(List.of(false), parser.calls);
    }

    @Test
    void plainParsersAreNotGated() {
        DocumentParser plain = new DocumentParser() {
            @Override
            public String getParserType() {
                return ParserType.MINERU.getType();
            }

            @Override
            public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
                return ParsedDocument.of(List.of(new ParagraphBlock(Provenance.ofFile("x.pdf"), BAD)));
            }

            @Override
            public Map<ParseProfile, Set<String>> supportedMimeTypes() {
                return Map.of(ParseProfile.FAST, Set.of("application/pdf"));
            }
        };
        ParseQualityAuditor auditor = new ParseQualityAuditor(enabled());
        assertFalse(auditor.applies(plain, "application/pdf"));
        assertNull(auditor.parse(plain, new byte[]{1}, "application/pdf", Map.of(), layer(TextLayerClass.TEXT, 10)).audit());
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
        assertEquals(List.of(false, false), parser.completed, "闸门不作用时照常一次解析、上传图片");
    }

    @Test
    void metricsIgnoreImageHashesAndHtmlAttributes() {
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(
                "称取 0.5 g ![](images/7bbefdb15db7a398b850af5819e9032c.jpg) <td colspan=\"2\">10</td> 在 ,");
        assertEquals(4, measurement.digits());
        assertEquals(1, measurement.strictSlots());
    }

    @Test
    void lessThanSignsInTheTextAreNotTags() {
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(
                "<table><tr><td>< 15000</td><td><0.1 %</td><td rowspan=\"3\">>5 mm</td></tr></table><br/>");
        assertEquals(5 + 2 + 1, measurement.digits(), "小于号后面的数字不能被当成标签吞掉，rowspan 的 3 不算");
    }

    @Test
    void compoundWordsAreNotEmptySlots() {
        assertEquals(0, ParseTextMetrics.measure("结果按 GB/T 8170 修约。不存在，所在。现在，节约。").strictSlots());
        assertEquals(2, ParseTextMetrics.measure("称取约 ,温度控制在 。").strictSlots());
    }

    @Test
    void imageDescriptionsAreNotAuditText() {
        Provenance provenance = Provenance.ofFile("x.pdf");
        ParsedDocument document = ParsedDocument.of(List.of(new ParagraphBlock(provenance, "称取 0.5 g"),
                new ImageBlock(provenance, new AssetRef("images/a.jpg", "image/jpeg"), "图1", "图1", "流程图：在 ,加热 100 ℃")));
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(document);
        assertEquals(2, measurement.digits(), "描述里的数字不算");
        assertEquals(0, measurement.strictSlots(), "描述里的空槽也不算");
    }

    @Test
    void betterNeverPicksAFailedParseThenPrefersPassingThenFewerFailures() {
        ParseQualityAuditor auditor = new ParseQualityAuditor(enabled());
        ParseAudit.Attempt passing = new ParseAudit.Attempt(Map.of(), 10, 1000, 5, 5.0, 0.5, true, List.of());
        ParseAudit.Attempt failingClean = new ParseAudit.Attempt(Map.of(), 10, 1000, 0, 0.0, 1.0, false, List.of("x"));
        ParseAudit.Attempt failedParse = ParseAudit.Attempt.parseFailed(Map.of("isOcr", true), "timeout");
        assertTrue(auditor.better(passing, failingClean));
        assertFalse(auditor.better(failedParse, failingClean));
        assertTrue(auditor.better(failingClean, failedParse));

        ParseAudit.Attempt oneFailure = new ParseAudit.Attempt(Map.of(), 99, 1000, 4, 0.32, 0.99, false, List.of("slots"));
        ParseAudit.Attempt twoFailures = new ParseAudit.Attempt(Map.of(), 60, 1000, 3, 0.31, 0.60, false, List.of("slots", "retention"));
        assertTrue(auditor.better(oneFailure, twoFailures), "空槽密度只差 0.01，不能压过保留率 0.99 对 0.60");
        assertFalse(auditor.better(twoFailures, oneFailure));
    }

    @Test
    void betterComparesRetentionWhenOneSideIsShortOfIt() {
        ParseQualityAuditor auditor = new ParseQualityAuditor(enabled());
        ParseAudit.Attempt lowRetention = new ParseAudit.Attempt(Map.of(), 80, 1000, 0, 0.0, 0.80, false, List.of("retention"));
        ParseAudit.Attempt slotsOnly = new ParseAudit.Attempt(Map.of(), 99, 1000, 5, 5.0, 0.99, false, List.of("slots"));
        assertTrue(auditor.better(slotsOnly, lowRetention));
        assertFalse(auditor.better(lowRetention, slotsOnly));

        ParseAudit.Attempt fewerSlots = new ParseAudit.Attempt(Map.of(), 99, 1000, 5, 0.5, 0.99, false, List.of("slots"));
        ParseAudit.Attempt moreSlots = new ParseAudit.Attempt(Map.of(), 102, 1000, 20, 2.0, 1.02, false, List.of("slots"));
        assertTrue(auditor.better(fewerSlots, moreSlots), "保留率都达标时比空槽");
        assertFalse(auditor.better(moreSlots, fewerSlots));
        assertFalse(auditor.better(fewerSlots, fewerSlots), "打平留首次");
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
