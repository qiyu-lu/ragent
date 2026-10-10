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

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.HtmlTableBlock;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析之后、分块之前的文本归一化与清洗：先按频次规则去页面家具，再逐 Block 做入库形态的归一化
 * （{@link IngestionTextNormalizer#normalizeForStorage}）
 * <p>
 * 代码块原样保留；图片只动说明文字，不碰资产地址
 */
@Component
@RequiredArgsConstructor
public class ParsedDocumentNormalizer {

    /**
     * 入库形态的版本，写进 {@code doc_metadata.normalization.version}：1 是 2026-10-09～10 评测用的整体 NFKC，
     * 2 是审查后改成的不改意思的换写
     */
    static final int STORAGE_FORM_VERSION = 2;

    private final TextNormalizeProperties properties;

    /**
     * @param document     归一化后的解析结果
     * @param removedLines 按频次规则删掉的行数
     * @param applied      本次是否做了归一化
     */
    public record Result(ParsedDocument document, int removedLines, boolean applied) {

        /**
         * 落库用的摘要，写进 {@code doc_metadata.normalization}
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("applied", applied);
            if (applied) {
                map.put("version", STORAGE_FORM_VERSION);
            }
            map.put("repeatedLinesRemoved", removedLines);
            return map;
        }
    }

    /**
     * @param unwrapMath 是否给数值型行内公式去壳：只有 MinerU 的 markdown 里 {@code $} 才是公式定界符
     */
    public Result normalize(ParsedDocument document, PdfTextLayer textLayer, boolean unwrapMath) {
        if (!properties.isEnabled() || document == null) {
            return new Result(document, 0, false);
        }
        List<Block> blocks = document.blocks();
        int removed = 0;
        if (properties.isDropRepeatedLines() && textLayer != null) {
            RepeatedLineFilter.Result filtered = RepeatedLineFilter.filter(blocks, textLayer.repeatedLines(),
                    properties.getRepeatedLineMinOccurrences());
            blocks = filtered.blocks();
            removed = filtered.removedLines();
        }
        List<Block> normalized = blocks.stream().map(block -> normalizeBlock(block, unwrapMath)).toList();
        return new Result(ParsedDocument.of(normalized, document.metadata()), removed, true);
    }

    static Block normalizeBlock(Block block, boolean unwrapMath) {
        if (block instanceof HeadingBlock heading) {
            return new HeadingBlock(heading.provenance(), heading.level(), normalize(heading.text(), unwrapMath));
        }
        if (block instanceof ParagraphBlock paragraph) {
            return new ParagraphBlock(paragraph.provenance(), normalize(paragraph.text(), unwrapMath));
        }
        if (block instanceof ListBlock list) {
            return new ListBlock(list.provenance(), list.ordered(), normalizeAll(list.items(), unwrapMath));
        }
        if (block instanceof TableBlock table) {
            return new TableBlock(table.provenance(), normalizeAll(table.headers(), unwrapMath),
                    table.rows() == null ? null : table.rows().stream().map(row -> normalizeAll(row, unwrapMath)).toList(),
                    table.rowCellRanges());
        }
        if (block instanceof HtmlTableBlock html) {
            return new HtmlTableBlock(html.provenance(), normalize(html.html(), unwrapMath));
        }
        if (block instanceof ImageBlock image) {
            return new ImageBlock(image.provenance(), image.asset(), normalizeNullable(image.caption(), unwrapMath),
                    normalizeNullable(image.altText(), unwrapMath), normalizeNullable(image.description(), unwrapMath));
        }
        // CodeBlock 原样保留
        return block;
    }

    private static String normalize(String value, boolean unwrapMath) {
        return IngestionTextNormalizer.normalizeForStorage(value, unwrapMath);
    }

    private static List<String> normalizeAll(List<String> values, boolean unwrapMath) {
        return values == null ? null : values.stream().map(value -> normalize(value, unwrapMath)).toList();
    }

    private static String normalizeNullable(String value, boolean unwrapMath) {
        return value == null ? null : normalize(value, unwrapMath);
    }
}
