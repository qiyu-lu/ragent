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

import com.nageoffer.ai.ragent.core.parser.normalize.IngestionTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用 PDFBox 逐页抽 PDF 文字层，给解析质量闸门做预判和审计分母
 * <p>
 * 与评测侧 {@code pdftotext -layout} 核对过：6 份标准的数字数完全一致（硅含量 1,252、取样 7,991），
 * 只有页眉的断行不同。统计前按页去掉"出现在多数页面的整行"和页码行：标准的页眉是自己的标准号，
 * 每页一次，不去掉会让 76 页的取样标准多出约 1,600 个数字
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfTextLayerAnalyzer {

    private static final Pattern PAGE_NUMBER = Pattern.compile("[0-9IVXivx\\-·]+");
    private static final int HEAD_MAX_CHARS = 4000;

    private final ParseQualityProperties properties;

    /**
     * 抽不出文字层（加密、损坏）时返回空，闸门随之放行
     */
    public Optional<PdfTextLayer> analyze(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return Optional.of(summarize(pages));
        } catch (Exception | LinkageError e) {
            log.warn("PDF 文字层抽取失败，解析质量闸门放行：{}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 由逐页文本得到统计；包内可见，测试直接喂页面文本
     */
    PdfTextLayer summarize(List<String> pages) {
        Set<String> repeated = repeatedLines(pages);
        int digits = 0;
        int nonSpace = 0;
        int rawNonSpace = 0;
        int garbled = 0;
        List<Integer> perPage = new ArrayList<>(pages.size());
        for (String page : pages) {
            rawNonSpace += countNonSpace(page);
            garbled += countGarbled(page);
            int pageChars = 0;
            for (String line : page.split("\n")) {
                String key = IngestionTextNormalizer.matchForm(line);
                if (key.isEmpty() || repeated.contains(key) || PAGE_NUMBER.matcher(key).matches()) {
                    continue;
                }
                digits += countDigits(line);
                pageChars += countNonSpace(line);
            }
            nonSpace += pageChars;
            perPage.add(pageChars);
        }
        int median = median(perPage);
        double garbledRatio = rawNonSpace == 0 ? 0D : (double) garbled / rawNonSpace;
        TextLayerClass layerClass = median < properties.getScannedMaxMedianCharsPerPage()
                ? TextLayerClass.SCANNED
                : garbledRatio >= properties.getGarbledMinRatio() ? TextLayerClass.GARBLED : TextLayerClass.TEXT;
        String head = String.join("\n", pages.subList(0, Math.min(2, pages.size())));
        head = head.length() > HEAD_MAX_CHARS ? head.substring(0, HEAD_MAX_CHARS) : head;
        return new PdfTextLayer(pages.size(), layerClass, median, digits, nonSpace, garbledRatio,
                List.copyOf(repeated), head);
    }

    /**
     * 出现在不少于 repeatedLineMinPageShare 比例页面上的整行（同一页内重复只算一次）；单页文档没有页眉可言
     */
    private Set<String> repeatedLines(List<String> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }
        Map<String, Integer> pageCounts = new HashMap<>();
        for (String page : pages) {
            Set<String> seen = new HashSet<>();
            for (String line : page.split("\n")) {
                String key = IngestionTextNormalizer.matchForm(line);
                if (key.codePointCount(0, key.length()) >= 2 && !PAGE_NUMBER.matcher(key).matches() && seen.add(key)) {
                    pageCounts.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pages.size() * properties.getRepeatedLineMinPageShare()));
        Set<String> repeated = new LinkedHashSet<>();
        pageCounts.forEach((key, count) -> {
            if (count >= threshold) {
                repeated.add(key);
            }
        });
        return repeated;
    }

    static int countDigits(String text) {
        return (int) text.codePoints().filter(cp -> Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER).count();
    }

    static int countNonSpace(String text) {
        return (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)).count();
    }

    private static int countGarbled(String text) {
        return (int) text.codePoints().filter(cp -> Character.getType(cp) == Character.PRIVATE_USE || cp == 0xFFFD).count();
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
