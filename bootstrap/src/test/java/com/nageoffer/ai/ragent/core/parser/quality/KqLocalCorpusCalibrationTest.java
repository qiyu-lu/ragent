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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadataExtractor;
import com.nageoffer.ai.ragent.core.ingest.metadata.TermDictionary;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.normalize.RepeatedLineFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 在本机 {@code local-data/kq-eval/} 的 7 份资料与 2026-10-09 解析实验输出上复核闸门的标定（仓库里没有这些数据时跳过）
 * <p>
 * 断言的是计划 §5.1 第 4 条要求的标定结果：坏解析（硅含量、取样的默认参数）不合格，OCR 重解析与其余文档合格；
 * 硫铁矿判为扫描件。另外打印每份文档抽到的元数据草稿，供人工确认
 */
@EnabledIf("corpusPresent")
class KqLocalCorpusCalibrationTest {

    private static final Path ROOT = Stream.of(Path.of(".."), Path.of("."))
            .filter(path -> Files.isDirectory(path.resolve("local-data/kq-eval/mineru-raw")))
            .findFirst().orElse(Path.of(".."));
    private static final Path SOURCE = ROOT.resolve("local-data/source");
    private static final Path RAW = ROOT.resolve("local-data/kq-eval/mineru-raw");

    static boolean corpusPresent() {
        return Files.isDirectory(RAW) && Files.isDirectory(SOURCE);
    }

    private final PdfTextLayerAnalyzer analyzer = new PdfTextLayerAnalyzer(new ParseQualityProperties());
    private final ParseQualityAuditor auditor = new ParseQualityAuditor(new ParseQualityProperties());

    private static ParsedDocument markdown(String text) {
        List<Block> blocks = new ArrayList<>();
        Provenance provenance = Provenance.ofFile("calibration");
        for (String paragraph : text.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#") && !trimmed.contains("\n")) {
                blocks.add(new HeadingBlock(provenance, 1, trimmed.replaceFirst("^#+\\s*", "")));
            } else {
                blocks.add(new ParagraphBlock(provenance, trimmed));
            }
        }
        return ParsedDocument.of(blocks);
    }

    private static Path probe(String stem, String params) throws IOException {
        try (Stream<Path> runs = Files.list(RAW.resolve(stem).resolve(params))) {
            return runs.filter(dir -> Files.isRegularFile(dir.resolve("full.md"))).sorted().findFirst()
                    .orElseThrow().resolve("full.md");
        }
    }

    @Test
    void calibratedThresholdsSeparateBadParsesFromGoodOnes() throws IOException {
        record Case(String stem, String params, TextLayerClass layerClass, boolean passes) {
        }
        List<Case> cases = List.of(
                new Case("铁矿石+硅含量的测定+重量法", "pipeline_ocr-false_formula-true_table-true", TextLayerClass.TEXT, false),
                new Case("铁矿石+硅含量的测定+重量法", "pipeline_ocr-false_formula-false_table-true", TextLayerClass.TEXT, false),
                new Case("铁矿石+硅含量的测定+重量法", "vlm_ocr-false_formula-true_table-true", TextLayerClass.TEXT, false),
                new Case("铁矿石+硅含量的测定+重量法", "pipeline_ocr-true_formula-true_table-true", TextLayerClass.TEXT, true),
                new Case("铁矿石 取样和制样方法", "pipeline_ocr-false_formula-false_table-true", TextLayerClass.TEXT, false),
                new Case("铁矿石 取样和制样方法", "pipeline_ocr-true_formula-true_table-true", TextLayerClass.TEXT, true),
                new Case("铁矿石+全铁含量的测定+三氯化钛还原法", "pipeline_ocr-false_formula-true_table-true", TextLayerClass.TEXT, true),
                new Case("硫铁矿和硫精矿中硅含量的测定+重量法", "pipeline_ocr-true_formula-true_table-true", TextLayerClass.SCANNED, true));
        for (Case c : cases) {
            PdfTextLayer layer = analyzer.analyze(Files.readAllBytes(SOURCE.resolve(c.stem() + ".pdf"))).orElseThrow();
            ParseAudit.Attempt attempt = auditor.audit(markdown(Files.readString(probe(c.stem(), c.params()))), layer);
            System.out.printf("%-22s %-45s class=%s layerDigits=%d digits=%d retention=%s slots/1k=%.3f passed=%s%n",
                    c.stem(), c.params(), layer.textLayerClass(), layer.digits(), attempt.digits(),
                    attempt.digitRetention() == null ? "-" : String.format("%.2f", attempt.digitRetention()),
                    attempt.strictSlotsPer1000(), attempt.passed());
            assertEquals(c.layerClass(), layer.textLayerClass(), c.stem());
            assertEquals(c.passes(), attempt.passed(), c.stem() + " " + c.params() + " " + attempt.failures());
        }
    }

    @Test
    void furnitureRemovalAndMetadataDraft() throws IOException {
        TermDictionary terms = new TermDictionary(new DefaultResourceLoader(), "classpath:kq/terms.csv");
        DocumentMetadataExtractor extractor = new DocumentMetadataExtractor(terms);
        Map<String, String> parsedByStem = new LinkedHashMap<>();
        parsedByStem.put("铁矿石+硅含量的测定+重量法", "pipeline_ocr-true_formula-true_table-true");
        parsedByStem.put("铁矿石 取样和制样方法", "pipeline_ocr-true_formula-true_table-true");
        parsedByStem.put("铁矿石+全铁含量的测定+三氯化钛还原法", "pipeline_ocr-false_formula-true_table-true");
        parsedByStem.put("硫铁矿和硫精矿中硅含量的测定+重量法", "pipeline_ocr-true_formula-true_table-true");
        parsedByStem.put("钛铁矿精矿化学分析方法.第2部分_全铁量的测定.重铬酸钾滴定法", null);
        parsedByStem.put("钛铁矿精矿化学分析方法.第3部分_氧化亚铁量的测定.重铬酸钾滴定法", null);
        Map<String, Object> draft = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsedByStem.entrySet()) {
            PdfTextLayer layer = analyzer.analyze(Files.readAllBytes(SOURCE.resolve(entry.getKey() + ".pdf"))).orElseThrow();
            ParsedDocument parsed = entry.getValue() == null ? ParsedDocument.of(List.of())
                    : markdown(Files.readString(probe(entry.getKey(), entry.getValue())));
            int removed = RepeatedLineFilter.filter(parsed.blocks(), layer.repeatedLines(), 3).removedLines();
            DocumentMetadata metadata = extractor.extract(entry.getKey() + ".pdf", layer, parsed);
            Map<String, Object> row = new LinkedHashMap<>(metadata.toMap());
            row.put("repeatedLines", layer.repeatedLines());
            row.put("furnitureLinesRemoved", removed);
            draft.put(entry.getKey() + ".pdf", row);
        }
        draft.put("铁矿石人工检测流程调研V1.2.xlsx", extractor.extract("铁矿石人工检测流程调研V1.2.xlsx", null,
                ParsedDocument.of(List.of())).toMap());
        System.out.println("KQ-METADATA-DRAFT " + new ObjectMapper().writeValueAsString(draft));
    }
}
