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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入库文本归一化：解析之后、分块之前对每个 Block 的文本做一次，查询侧将来用同一个函数
 * <p>
 * 依次为：数值型行内公式去壳 → 度数符号预映射 → NFKC（全角转半角、℃ 拆成 °C）→ 破折号统一 → 删零宽字符
 * → 拼回国标的小数分组空格（0.000 1）→ 压缩行内空白。与评测侧 {@code eval/kq/evalkit.normalize} 的约定是
 * 「本函数输出再去掉空白和 Markdown 记号，等于评测归一化的结果」，两边用同一份
 * {@code eval/kq/normalization_cases.json} 测试
 * <p>
 * 公式只去掉"数值 + 单位"这类简单行内公式的壳（{@code $400 \pm 20 ^ { \circ } \mathrm { C }$} → 400±20°C）；
 * 含上下标、分式、根号等结构的公式原样保留，去壳会丢结构
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IngestionTextNormalizer {

    private static final Pattern MATH_SEGMENT = Pattern.compile("\\$\\$(.+?)\\$\\$|\\$(.+?)\\$", Pattern.DOTALL);
    private static final Pattern SPACING_COMMAND = Pattern.compile("\\\\[,;:!]|\\\\q?quad(?![A-Za-z])|\\\\hspace\\s*\\{[^{}]*}");
    private static final Pattern SYMBOL_COMMAND = Pattern.compile(
            "\\\\(pm|times|sim|circ|cdot|approx|leq|le|geq|ge|div|mu|rho|sigma|beta|alpha|%)(?![A-Za-z])");
    private static final Pattern DEGREE_SUPERSCRIPT = Pattern.compile("\\^\\s*\\{\\s*°\\s*}|\\^\\s*°");
    private static final Pattern TEXT_WRAPPER = Pattern.compile(
            "\\\\(?:mathrm|mathit|mathbf|text|textrm|operatorname|mathsf|mathtt)\\{([^{}]*)}");
    private static final Pattern STRUCTURE_LEFT = Pattern.compile("[\\\\_^{}&]");
    private static final Pattern DECIMAL_GROUP_SPACE = Pattern.compile("(?<=\\.(?:\\d{3}){1,5}) (?=\\d{1,3}(?![\\d.]))");
    private static final Pattern MATCH_STRIP = Pattern.compile("[\\s*#`_]+");
    private static final Pattern INLINE_SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+(?=\\n|$)");

    /**
     * 归一化一段文本；null 视为空串
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = unwrapSimpleMath(text);
        // NFKC 会把序数符号 º 变成字母 o，度数含义要在它之前保住
        value = value.replace('º', '°').replace('˚', '°').replace("℃", "°C").replace("℉", "°F");
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = value.replace('–', '-').replace('—', '-').replace('−', '-').replace('―', '-').replace('‐', '-');
        value = value.replace("​", "").replace("‌", "").replace("‍", "")
                .replace("⁠", "").replace("﻿", "");
        value = joinDecimalGroups(value);
        value = INLINE_SPACES.matcher(value).replaceAll(" ");
        return TRAILING_SPACES.matcher(value).replaceAll("");
    }

    /**
     * 比对形态：归一化后再删掉全部空白与 Markdown 记号，与评测侧 {@code evalkit.normalize} 同形；
     * 用来判断两行是否"同一行"（页眉、水印的频次规则），不用于入库正文
     */
    public static String matchForm(String text) {
        return MATCH_STRIP.matcher(normalize(text)).replaceAll("");
    }

    /**
     * 把简单行内公式换成正文写法，结构型公式保持原样（含两侧 $）
     */
    static String unwrapSimpleMath(String text) {
        if (text.indexOf('$') < 0) {
            return text;
        }
        Matcher matcher = MATH_SEGMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String body = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String plain = plainMath(body);
            matcher.appendReplacement(out, Matcher.quoteReplacement(plain == null ? matcher.group() : plain));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 简单公式的正文写法；留下反斜杠命令、上下标、花括号或对齐符时返回 null
     * <p>
     * 数学模式里空格只是分隔记号、{@code ~} 是不换行空格，都不是内容；先换掉符号命令再删空格，
     * 免得 {@code \circ C} 粘成一个不认识的命令
     */
    static String plainMath(String body) {
        String value = body.replace("~", "");
        value = SPACING_COMMAND.matcher(value).replaceAll(" ");
        value = SYMBOL_COMMAND.matcher(value).replaceAll(match -> Matcher.quoteReplacement(symbol(match.group(1))));
        value = DEGREE_SUPERSCRIPT.matcher(value).replaceAll("°");
        value = value.replaceAll("\\s+", "");
        for (int i = 0; i < 3 && value.contains("\\"); i++) {
            value = TEXT_WRAPPER.matcher(value).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        }
        value = DEGREE_SUPERSCRIPT.matcher(value).replaceAll("°");
        if (value.isEmpty() || STRUCTURE_LEFT.matcher(value).find()) {
            return null;
        }
        return value;
    }

    private static String symbol(String command) {
        return switch (command) {
            case "pm" -> "±";
            case "times" -> "×";
            case "sim" -> "~";
            case "circ" -> "°";
            case "cdot" -> "·";
            case "approx" -> "≈";
            case "leq", "le" -> "≤";
            case "geq", "ge" -> "≥";
            case "div" -> "÷";
            case "mu" -> "μ";
            case "rho" -> "ρ";
            case "sigma" -> "σ";
            case "beta" -> "β";
            case "alpha" -> "α";
            case "%" -> "%";
            default -> command;
        };
    }

    /**
     * 国标把长小数按三位一组用空格隔开（0.000 1 g），检索和分词都需要它是一个数
     */
    private static String joinDecimalGroups(String value) {
        String current = value;
        for (int i = 0; i < 5; i++) {
            String next = DECIMAL_GROUP_SPACE.matcher(current).replaceAll("");
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }
}
