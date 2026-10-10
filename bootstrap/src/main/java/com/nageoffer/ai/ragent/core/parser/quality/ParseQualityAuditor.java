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
 *   <li>审计：解析结果对文字层比严格空槽与数字保留率（扫描件、坏字体只看空槽）</li>
 *   <li>回退：不合格就把 OCR 开关取反重解析一次，保留得分高的；两次都不合格记"需人工复核"</li>
 * </ol>
 * 2026-10-09 的实验定了这个形状：关公式、换 vlm 都救不回 2014 版两份 GB/T 丢掉的"数字 + 单位"，
 * 强开 OCR 能（硅含量事实 2→17/24、取样 3→17/21）；扫描件 MinerU 默认已自动 OCR，强开反而 14→11/15，
 * 所以 OCR 只作为不合格时的备选，不对所有文档一刀切
 * <p>
 * 候选结果只展开正文来审计（{@link StagedDocumentParser#preview}），选定的那次才上传图片、做图生文。
 * 重解析本身失败（超时、限流）时不拒收：保留首次结果，记下原因，文档标"需人工复核"；首次解析失败照旧抛出，
 * 与闸门关闭时一样
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
                && ParserType.MINERU.getType().equals(parser.getParserType())
                && parser instanceof StagedDocumentParser;
    }

    public AuditedParse parse(DocumentParser parser, byte[] bytes, String mimeType,
                              Map<String, Object> options, PdfTextLayer textLayer) {
        if (!applies(parser, mimeType) || textLayer == null) {
            return new AuditedParse(parser.parseStructured(bytes, mimeType, options), null);
        }
        StagedDocumentParser staged = (StagedDocumentParser) parser;
        boolean firstOcr = textLayer.textLayerClass() == TextLayerClass.GARBLED;
        List<StagedDocumentParser.Fetched> fetched = new ArrayList<>(2);
        List<ParseAudit.Attempt> attempts = new ArrayList<>(2);

        fetched.add(staged.fetch(bytes, mimeType, withOcr(options, firstOcr)));
        attempts.add(audit(staged.preview(fetched.get(0)), textLayer));
        if (!attempts.get(0).passed() && properties.isFallbackEnabled()) {
            log.info("解析质量审计不合格 {}，OCR 改为 {} 重解析一次", attempts.get(0).failures(), !firstOcr);
            try {
                StagedDocumentParser.Fetched second = staged.fetch(bytes, mimeType, withOcr(options, !firstOcr));
                ParseAudit.Attempt attempt = audit(staged.preview(second), textLayer);
                fetched.add(second);
                attempts.add(attempt);
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                log.warn("解析质量闸门重解析失败，保留首次结果并标记人工复核：{}", e.getMessage(), e);
                fetched.add(null);
                attempts.add(ParseAudit.Attempt.parseFailed(Map.of("isOcr", !firstOcr), e.getMessage()));
            }
        }

        int chosen = attempts.size() == 2 && better(attempts.get(1), attempts.get(0)) ? 1 : 0;
        ParseAudit.Verdict verdict = !attempts.get(chosen).passed() ? ParseAudit.Verdict.NEEDS_REVIEW
                : chosen == 0 ? ParseAudit.Verdict.PASSED : ParseAudit.Verdict.RECOVERED;
        ParseAudit audit = new ParseAudit(verdict, textLayer.textLayerClass(), textLayer.pages(),
                textLayer.digits(), attempts, chosen);
        log.info("解析质量审计 verdict={} 文字层={} 选用第 {} 次 {}", verdict, textLayer.textLayerClass(), chosen,
                attempts.get(chosen).params());
        return new AuditedParse(staged.complete(fetched.get(chosen)), audit);
    }

    /**
     * 对一份解析结果打分；包内可见供测试
     * <p>
     * 空槽要同时满足"密度超过阈值"和"至少 {@code minStrictSlots} 处"才算不合格：两三千字的短标准里，
     * 一处误报就会让密度超过 0.3 处/千字
     */
    ParseAudit.Attempt audit(ParsedDocument document, PdfTextLayer textLayer) {
        ParseTextMetrics.Measurement measurement = ParseTextMetrics.measure(document);
        List<String> failures = new ArrayList<>(2);
        double slotsPer1000 = measurement.strictSlotsPer1000();
        if (slotsPer1000 > properties.getMaxStrictSlotsPer1000()
                && measurement.strictSlots() >= properties.getMinStrictSlots()) {
            failures.add(String.format(Locale.ROOT, "strictSlotsPer1000 %.3f > %.3f (%d slots)",
                    slotsPer1000, properties.getMaxStrictSlotsPer1000(), measurement.strictSlots()));
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
     * candidate 是否比 incumbent 更好；比不出来时留 incumbent（首次解析用的是预期参数）。包内可见供测试
     * <ol>
     *   <li>解析失败的那次永远不选；合格优先；不合格项少的优先</li>
     *   <li>有一方保留率不达标时比保留率：它直接量出丢了多少数字，空槽只是丢数字留下的痕迹，还会误报</li>
     *   <li>最后比空槽密度</li>
     * </ol>
     */
    boolean better(ParseAudit.Attempt candidate, ParseAudit.Attempt incumbent) {
        if (candidate.failedToParse() || incumbent.failedToParse()) {
            return !candidate.failedToParse() && incumbent.failedToParse();
        }
        if (candidate.passed() != incumbent.passed()) {
            return candidate.passed();
        }
        if (candidate.failures().size() != incumbent.failures().size()) {
            return candidate.failures().size() < incumbent.failures().size();
        }
        Double candidateRetention = candidate.digitRetention();
        Double incumbentRetention = incumbent.digitRetention();
        if (candidateRetention != null && incumbentRetention != null
                && Math.min(candidateRetention, incumbentRetention) < properties.getMinDigitRetention()) {
            int byRetention = Double.compare(candidateRetention, incumbentRetention);
            if (byRetention != 0) {
                return byRetention > 0;
            }
        }
        return Double.compare(candidate.strictSlotsPer1000(), incumbent.strictSlotsPer1000()) < 0;
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
