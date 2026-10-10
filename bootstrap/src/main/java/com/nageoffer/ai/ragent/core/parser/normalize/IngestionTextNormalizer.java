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
 * 文本归一化，分两种形态
 * <ul>
 *   <li>{@link #normalize} 比对形态：数值型行内公式去壳 → 度数符号预映射 → NFKC（全角转半角、℃ 拆成 °C）→
 *   破折号统一 → 删零宽字符 → 拼回国标的小数分组空格（0.000 1）→ 压缩行内空白。全文索引与查询、元数据抽取、
 *   页面家具比对用它。与评测侧 {@code eval/kq/evalkit.normalize} 的约定是「{@link #matchForm} 等于评测归一化的
 *   结果」，两边用同一份 {@code eval/kq/normalization_cases.json} 测试</li>
 *   <li>{@link #normalizeForStorage} 入库形态：解析之后、分块之前对每个 Block 做一次，展示、喂给模型、向量化
 *   都用这份文本，所以只做不改意思的换写，不做整体 NFKC（2026-10-10 审查后改）</li>
 * </ul>
 * 公式只去掉"数值 + 单位"这类简单行内公式的壳（{@code $400 \pm 20 ^ { \circ } \mathrm { C }$} → 400±20°C）；
 * 含上下标、分式、根号等结构的公式原样保留，去壳会丢结构。两个 {@code $} 之间跨行、夹着汉字、HTML 标签或
 * 英文单词、或者太长的，都不是 MinerU 的行内公式，而是货币、命令行变量之类的字面 {@code $}，不动
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IngestionTextNormalizer {

    private static final Pattern MATH_SEGMENT = Pattern.compile("\\$\\$([^$\\r\\n]+?)\\$\\$|\\$([^$\\r\\n]+?)\\$");
    /**
     * 不是行内公式的迹象：汉字或全角字符、HTML 标签、空格隔开的英文单词（MinerU 的公式里字母逐个隔开，"m i n"）
     */
    private static final Pattern NOT_MATH = Pattern.compile(
            "[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]|</?[A-Za-z]|(?:^|\\s)[A-Za-z]{3,}(?=\\s|$)");
    private static final int MAX_MATH_LENGTH = 120;
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
    /**
     * 入库形态里转半角的全角符号：只收数值与单位里用的，中文标点（，：；（）！？～）不动
     */
    private static final String FOLDED_FULLWIDTH_SYMBOLS = "％＋－．／＜＝＞";
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
     * 比对形态的归一化；null 视为空串
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
        value = ZERO_WIDTH.matcher(value).replaceAll("");
        value = joinDecimalGroups(value);
        value = INLINE_SPACES.matcher(value).replaceAll(" ");
        return TRAILING_SPACES.matcher(value).replaceAll("");
    }

    /**
     * 入库形态的归一化：只做不改意思的换写；null 视为空串
     * <p>
     * 中文标点、上下标（m²、10⁻³、Fe²⁺）、带圈数字、罗马数字、中文破折号与标准号里的"—"原样保留；
     * 全角数字、字母和 ％＋－．／＜＝＞ 转半角，㎎ 一类单位字符、微符号、拉丁连字、康熙部首与兼容汉字
     * （PDF 抽取常见，外观相同而码位不同）展开，减号与连字符统一成"-"，再删零宽字符、拼回小数分组、压缩行内空白
     *
     * @param unwrapMath 是否给数值型行内公式去壳：只对 MinerU 的 markdown 做，其他格式里的 {@code $} 是字面字符
     */
    public static String normalizeForStorage(String text, boolean unwrapMath) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = unwrapMath ? unwrapSimpleMath(text) : text;
        value = value.replace('º', '°').replace('˚', '°').replace("℃", "°C").replace("℉", "°F");
        value = foldCompatibility(value);
        value = value.replace('−', '-').replace('‐', '-').replace('‑', '-');
        value = ZERO_WIDTH.matcher(value).replaceAll("");
        value = joinDecimalGroups(value);
        value = INLINE_SPACES.matcher(value).replaceAll(" ");
        return TRAILING_SPACES.matcher(value).replaceAll("");
    }

    /**
     * 逐字符只展开不改意思的兼容字符，其余原样
     */
    private static String foldCompatibility(String value) {
        StringBuilder out = null;
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            String folded = foldable(cp) ? Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC) : null;
            // ㎡ ㎥ 一类展开后带数字（m2、m3）就等于把上标拍平，保留原字符
            if (folded != null && (cp < 0x3380 || cp > 0x33FF || folded.chars().noneMatch(Character::isDigit))) {
                if (out == null) {
                    out = new StringBuilder(value.length()).append(value, 0, i);
                }
                out.append(folded);
            } else if (out != null) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out == null ? value : out.toString();
    }

    private static boolean foldable(int cp) {
        return (cp >= 0xFF10 && cp <= 0xFF19)
                || (cp >= 0xFF21 && cp <= 0xFF3A)
                || (cp >= 0xFF41 && cp <= 0xFF5A)
                || (cp <= 0xFFFF && FOLDED_FULLWIDTH_SYMBOLS.indexOf(cp) >= 0)
                || cp == 0x3000
                || cp == 0x00B5
                || (cp >= 0x3380 && cp <= 0x33FF)
                || (cp >= 0xFB00 && cp <= 0xFB06)
                || (cp >= 0x2E80 && cp <= 0x2FDF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
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
     * 免得 {@code \circ C} 粘成一个不认识的命令。夹着汉字、全角字符、HTML 标签或空格隔开的英文单词，或者超过
     * 120 个字符的，不是行内公式（多半是两个货币符号之间的正文），也返回 null，删空格会把正文粘在一起
     */
    static String plainMath(String body) {
        if (body.length() > MAX_MATH_LENGTH || NOT_MATH.matcher(body).find()) {
            return null;
        }
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
