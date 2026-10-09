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
 * 解析之后、分块之前的文本归一化与清洗：先按频次规则去页面家具，再逐 Block 归一化文本
 * <p>
 * 代码块原样保留；图片只动说明文字，不碰资产地址
 */
@Component
@RequiredArgsConstructor
public class ParsedDocumentNormalizer {

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
            map.put("repeatedLinesRemoved", removedLines);
            return map;
        }
    }

    public Result normalize(ParsedDocument document, PdfTextLayer textLayer) {
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
        List<Block> normalized = blocks.stream().map(ParsedDocumentNormalizer::normalizeBlock).toList();
        return new Result(ParsedDocument.of(normalized, document.metadata()), removed, true);
    }

    static Block normalizeBlock(Block block) {
        if (block instanceof HeadingBlock heading) {
            return new HeadingBlock(heading.provenance(), heading.level(), IngestionTextNormalizer.normalize(heading.text()));
        }
        if (block instanceof ParagraphBlock paragraph) {
            return new ParagraphBlock(paragraph.provenance(), IngestionTextNormalizer.normalize(paragraph.text()));
        }
        if (block instanceof ListBlock list) {
            return new ListBlock(list.provenance(), list.ordered(), normalizeAll(list.items()));
        }
        if (block instanceof TableBlock table) {
            return new TableBlock(table.provenance(), normalizeAll(table.headers()),
                    table.rows() == null ? null : table.rows().stream().map(ParsedDocumentNormalizer::normalizeAll).toList(),
                    table.rowCellRanges());
        }
        if (block instanceof HtmlTableBlock html) {
            return new HtmlTableBlock(html.provenance(), IngestionTextNormalizer.normalize(html.html()));
        }
        if (block instanceof ImageBlock image) {
            return new ImageBlock(image.provenance(), image.asset(), normalizeNullable(image.caption()),
                    normalizeNullable(image.altText()), normalizeNullable(image.description()));
        }
        // CodeBlock 原样保留
        return block;
    }

    private static List<String> normalizeAll(List<String> values) {
        return values == null ? null : values.stream().map(IngestionTextNormalizer::normalize).toList();
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : IngestionTextNormalizer.normalize(value);
    }
}
