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

package com.nageoffer.ai.ragent.core.ingest;

import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadataExtractor;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.normalize.ParsedDocumentNormalizer;
import com.nageoffer.ai.ragent.core.parser.quality.ParseQualityAuditor;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayer;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayerAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 摄取第 ② 步的质量环节：PDF 文字层 → 解析质量闸门 → 归一化与去页面家具 → 文档元数据
 * <p>
 * 文字层只对 PDF 抽一次，闸门、清洗和元数据抽取共用；闸门与归一化各有开关（Java 默认关闭，{@code application.yaml}
 * 自 2026-10-10 起打开），关闭时解析结果与原先一致，只多出写进 {@code doc_metadata} 的元数据草稿
 */
@Component
public class ParseQualityStage {

    private final PdfTextLayerAnalyzer textLayerAnalyzer;
    private final ParseQualityAuditor parseQualityAuditor;
    private final ParsedDocumentNormalizer documentNormalizer;
    private final DocumentMetadataExtractor metadataExtractor;

    @Autowired
    public ParseQualityStage(PdfTextLayerAnalyzer textLayerAnalyzer,
                             ParseQualityAuditor parseQualityAuditor,
                             ParsedDocumentNormalizer documentNormalizer,
                             DocumentMetadataExtractor metadataExtractor) {
        this.textLayerAnalyzer = textLayerAnalyzer;
        this.parseQualityAuditor = parseQualityAuditor;
        this.documentNormalizer = documentNormalizer;
        this.metadataExtractor = metadataExtractor;
    }

    /**
     * 只解析、不做质量环节：离线复用实验（X5）沿用旧行为
     */
    public static ParseQualityStage passThrough() {
        return new ParseQualityStage(null, null, null, null);
    }

    /**
     * @param document         交给分块的解析结果
     * @param documentMetadata 写进 {@code doc_metadata} 的内容：元数据草稿、解析审计、归一化摘要；不做质量环节时为空
     */
    public record Output(ParsedDocument document, Map<String, Object> documentMetadata) {
    }

    public Output run(DocumentParser parser, byte[] bytes, String mimeType, Map<String, Object> options, String filename) {
        if (textLayerAnalyzer == null) {
            return new Output(parser.parseStructured(bytes, mimeType, options), Map.of());
        }
        PdfTextLayer textLayer = ParseQualityAuditor.isPdf(mimeType) ? textLayerAnalyzer.analyze(bytes).orElse(null) : null;
        ParseQualityAuditor.AuditedParse audited = parseQualityAuditor.parse(parser, bytes, mimeType, options, textLayer);
        boolean minerUMarkdown = ParserType.MINERU.getType().equals(parser.getParserType());
        ParsedDocumentNormalizer.Result normalized = documentNormalizer.normalize(audited.document(), textLayer, minerUMarkdown);

        Map<String, Object> metadata = new LinkedHashMap<>(
                metadataExtractor.extract(filename, textLayer, audited.document()).toMap());
        if (audited.audit() != null) {
            metadata.put("parseAudit", audited.audit().toMap());
        }
        metadata.put("normalization", normalized.toMap());
        return new Output(normalized.document(), Collections.unmodifiableMap(metadata));
    }
}
