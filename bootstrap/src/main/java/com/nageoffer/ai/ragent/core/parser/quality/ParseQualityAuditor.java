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
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析质量闸门（knowledge-quality 计划 §5.1）：PDF 经 MinerU 解析之后、分块之前
 * <ol>
 *   <li>预判：由 PDFBox 文字层分出扫描件 / 坏字体 / 文字层可用。坏字体首次就强开 OCR，其余按默认参数</li>
 *   <li>审计：解析结果对文字层比严格空槽密度与数字保留率（扫描件、坏字体只看空槽）</li>
 *   <li>回退：不合格就把 OCR 开关取反重解析一次，保留得分高的；两次都不合格记"需人工复核"</li>
 * </ol>
 * 2026-10-09 的实验定了这个形状：关公式、换 vlm 都救不回 2014 版两份 GB/T 丢掉的"数字 + 单位"，
 * 强开 OCR 能（硅含量事实 2→17/24、取样 3→17/21）；扫描件 MinerU 默认已自动 OCR，强开反而 14→11/15，
 * 所以 OCR 只作为不合格时的备选，不对所有文档一刀切
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseQualityAuditor {

    private final ParseQualityProperties properties;

    /**
     * @param document 入库用的解析结果
     * @param audit    审计结论；闸门关闭、非 MinerU PDF 或拿不到文字层时为 null
     */
    public record AuditedParse(ParsedDocument document, ParseAudit audit) {
    }

    /**
     * 闸门是否作用于这次解析
     */
    public boolean applies(DocumentParser parser, String mimeType) {
        return properties.isEnabled() && isPdf(mimeType)
                && ParserType.MINERU.getType().equals(parser.getParserType());
    }

    public AuditedParse parse(DocumentParser parser, byte[] bytes, String mimeType,
                              Map<String, Object> options, PdfTextLayer textLayer) {
        if (!applies(parser, mimeType) || textLayer == null) {
            return new AuditedParse(parser.parseStructured(bytes, mimeType, options), null);
        }
        boolean firstOcr = textLayer.textLayerClass() == TextLayerClass.GARBLED;
        List<ParsedDocument> documents = new ArrayList<>(2);
        List<ParseAudit.Attempt> attempts = new ArrayList<>(2);

        documents.add(parser.parseStructured(bytes, mimeType, withOcr(options, firstOcr)));
        attempts.add(audit(documents.get(0), textLayer));
        if (!attempts.get(0).passed() && properties.isFallbackEnabled()) {
            log.info("解析质量审计不合格 {}，OCR 改为 {} 重解析一次", attempts.get(0).failures(), !firstOcr);
            documents.add(parser.parseStructured(bytes, mimeType, withOcr(options, !firstOcr)));
            attempts.add(audit(documents.get(1), textLayer));
        }

        int chosen = attempts.size() == 2 && better(attempts.get(1), attempts.get(0)) ? 1 : 0;
        ParseAudit.Verdict verdict = !attempts.get(chosen).passed() ? ParseAudit.Verdict.NEEDS_REVIEW
                : chosen == 0 ? ParseAudit.Verdict.PASSED : ParseAudit.Verdict.RECOVERED;
        ParseAudit audit = new ParseAudit(verdict, textLayer.textLayerClass(), textLayer.pages(),
                textLayer.digits(), attempts, chosen);
        log.info("解析质量审计 verdict={} 文字层={} 选用第 {} 次 {}", verdict, textLayer.textLayerClass(), chosen,
                attempts.get(chosen).params());
        return new AuditedParse(documents.get(chosen), audit);
    }

    /**
     * 对一份解析结果打分；包内可见供测试
     */
    ParseAudit.Attempt audit(ParsedDocument document, PdfTextLayer textLayer) {
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(document);
        List<String> failures = new ArrayList<>(2);
        double slotsPer1000 = measurement.strictSlotsPer1000();
        if (slotsPer1000 > properties.getMaxStrictSlotsPer1000()) {
            failures.add(String.format(Locale.ROOT, "strictSlotsPer1000 %.3f > %.3f",
                    slotsPer1000, properties.getMaxStrictSlotsPer1000()));
        }
        Double retention = null;
        if (textLayer.textLayerClass() == TextLayerClass.TEXT && textLayer.digits() > 0) {
            retention = (double) measurement.digits() / textLayer.digits();
            if (retention < properties.getMinDigitRetention()) {
                failures.add(String.format(Locale.ROOT, "digitRetention %.3f < %.3f",
                        retention, properties.getMinDigitRetention()));
            }
        }
        Object params = document.metadata() == null ? null : document.metadata().get(MinerUDocumentParser.META_PARAMS);
        @SuppressWarnings("unchecked")
        Map<String, Object> paramMap = params instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        return new ParseAudit.Attempt(paramMap, measurement.digits(), measurement.nonSpaceChars(),
                measurement.strictSlots(), slotsPer1000, retention, failures.isEmpty(), failures);
    }

    /**
     * 合格优先；同为合格或同为不合格时，空槽少的优先，再看数字保留率高的
     */
    static boolean better(ParseAudit.Attempt candidate, ParseAudit.Attempt incumbent) {
        if (candidate.passed() != incumbent.passed()) {
            return candidate.passed();
        }
        int bySlots = Double.compare(incumbent.strictSlotsPer1000(), candidate.strictSlotsPer1000());
        if (bySlots != 0) {
            return bySlots > 0;
        }
        double candidateRetention = candidate.digitRetention() == null ? 0D : candidate.digitRetention();
        double incumbentRetention = incumbent.digitRetention() == null ? 0D : incumbent.digitRetention();
        return candidateRetention > incumbentRetention;
    }

    private static Map<String, Object> withOcr(Map<String, Object> options, boolean ocr) {
        Map<String, Object> copy = new HashMap<>(options == null ? Map.of() : options);
        copy.put(MinerUDocumentParser.OPT_IS_OCR, ocr);
        return copy;
    }

    public static boolean isPdf(String mimeType) {
        return mimeType != null && (mimeType.equalsIgnoreCase("application/pdf")
                || mimeType.equalsIgnoreCase("application/x-pdf"));
    }
}
