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

import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析结果的审计度量：数字数、非空白字符数、严格空槽数
 * <p>
 * 统计前去掉图片引用和 HTML 标签：MinerU 的图片文件名是 64 位十六进制哈希、表格的 colspan/rowspan 也是数字，
 * 都不是正文，不去掉会让取样标准的坏解析显得"数字够多"。严格空槽与评测侧
 * {@code eval/kq/evalkit.STRICT_SLOT_PATTERNS} 同一组正则：数字丢掉后两侧文字直接相接留下的痕迹
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ParseTextMetrics {

    private static final Pattern IMAGE_REF = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    static final List<Pattern> STRICT_SLOT_PATTERNS = List.of(
            Pattern.compile("在\\s*[,，。.]"),
            Pattern.compile("[(（]\\s*见\\s*[)）]"),
            Pattern.compile("式\\s*[(（]\\s*[)）]"),
            Pattern.compile("(?:小于|大于|等于|高于|低于|不大于|不小于|不超过|超过)\\s*[时的,，(（]"),
            Pattern.compile("的\\s+个"),
            Pattern.compile("约\\s*[,，。.]"));

    /**
     * @param digits        Unicode 十进制数字个数
     * @param nonSpaceChars 非空白字符数
     * @param strictSlots   严格空槽个数
     */
    public record Measurement(int digits, int nonSpaceChars, int strictSlots) {

        public double strictSlotsPer1000() {
            return nonSpaceChars == 0 ? 0D : strictSlots * 1000.0 / nonSpaceChars;
        }
    }

    public static Measurement measure(ParsedDocument document) {
        return measure(BlockTextRenderer.render(document == null ? null : document.blocks()));
    }

    public static Measurement measure(String text) {
        String value = HTML_TAG.matcher(IMAGE_REF.matcher(text == null ? "" : text).replaceAll("")).replaceAll(" ");
        int slots = 0;
        for (Pattern pattern : STRICT_SLOT_PATTERNS) {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                slots++;
            }
        }
        return new Measurement(PdfTextLayerAnalyzer.countDigits(value), PdfTextLayerAnalyzer.countNonSpace(value), slots);
    }
}
