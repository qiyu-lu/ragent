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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.huaban.analysis.jieba.WordDictionary;
import com.nageoffer.ai.ragent.core.ingest.metadata.TermDictionary;
import com.nageoffer.ai.ragent.core.parser.normalize.IngestionTextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文全文通道的分词（knowledge-quality 计划 §6）：索引文本与查询走同一个函数
 * <ol>
 *   <li>{@link IngestionTextNormalizer#normalize}：NFKC、℃ → °C、破折号、国标小数分组空格，与入库归一化同一个函数；
 *       没去壳的结构型公式里的 LaTeX 命令名（{@code \mathrm}、{@code \circ}）不是内容，去掉</li>
 *   <li>先用正则整体切出标准号、化学式、带单位的数值：jieba 会把 {@code GB/T6730.10} 切成 gb / t6730 / 10，
 *       把 {@code 400°C}、{@code 100μm} 拆成数字和符号</li>
 *   <li>其余文本用 jieba 的 SEARCH 模式切，术语表里含汉字的写法加进用户词典：不加时"测全铁"切成"测全 / 铁用"。
 *       词典外的纯汉字词是 jieba 的 HMM 猜出来的（"测硅时""法测"），同一串在查询和正文里会因上下文切得不一样，
 *       所以再补出它的单字："测硅时"也产出"硅"</li>
 *   <li>去掉停用词、纯标点和单个 ASCII 字符；术语表的同义写法在原位置再补一个规范写法，
 *       "TFe""全铁量"都能和"全铁"对上，原写法也保留，"制样"和"取样"仍分得开</li>
 * </ol>
 * 词项一律小写。术语表或停用词改了之后，存量索引要重建（{@code POST /admin/full-text/rebuild}）
 */
@Slf4j
@Component
public class FullTextTokenizer {

    /**
     * 一个词项及其位置；同义写法补出的规范写法与原写法同位置
     */
    public record Token(String term, int position) {
    }

    /**
     * 用户词典的词频：远高于 jieba 词典里的普通词，保证术语整体切出（"钛铁矿精矿"不被切成"钛铁矿 / 精矿"）
     */
    static final int USER_WORD_FREQ = 100_000;

    /**
     * 超长的串（URL、长公式）不进索引：PostgreSQL 的词位上限是 2046 字节
     */
    static final int MAX_TERM_LENGTH = 64;

    private static final Pattern STANDARD_NO = Pattern.compile(
            "(?<![A-Za-z])(GB/T|GB/Z|GB|YS/T|YB/T|HG/T|JB/T|SN/T|JJG|ISO)\\s*(\\d+(?:\\.\\d+)*)(?:\\s*-\\s*(\\d{4}))?(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 化学式：两个及以上元素符号（可带下标），且含数字或两字母元素，避免把 ISO、YS 这类缩写认成化学式
     */
    private static final Pattern FORMULA = Pattern.compile(
            "(?<![A-Za-z])((?:(?:He|Li|Be|Ne|Na|Mg|Al|Si|Cl|Ar|Ca|Ti|Cr|Mn|Fe|Co|Ni|Cu|Zn|As|Se|Br|Sr|Zr|Mo|Ag|Cd|Sn|Sb|Ba|La|Ce|Pt|Au|Hg|Pb|Bi"
                    + "|H|B|C|N|O|F|P|S|K|V|W|I)\\d*){2,})(?![a-z])");

    private static final Pattern TWO_LETTER_ELEMENT = Pattern.compile("[A-Z][a-z]");

    private static final Pattern LATEX_COMMAND = Pattern.compile("\\\\[A-Za-z]+");

    /**
     * 带单位的数值：单位按长度倒序列出，"10min"不会被认成"10m"
     */
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s?(mol/L|g/mL|g/L|mg/L|r/min|kPa|MPa|min|mL|μL|kg|mg|μg|μm|mm|cm|nm|°C|Pa|ml|L|g|h|s|m|%)(?![A-Za-z])");

    private final TermDictionary terms;
    private final Set<String> stopwords;
    private final JiebaSegmenter segmenter;

    @Autowired
    public FullTextTokenizer(TermDictionary terms, ResourceLoader resourceLoader,
                             @Value("${rag.search.channels.full-text.stopwords-location:classpath:kq/stopwords.txt}")
                             String stopwordsLocation) {
        this(terms, loadStopwords(resourceLoader.getResource(stopwordsLocation)));
    }

    FullTextTokenizer(TermDictionary terms, Set<String> stopwords) {
        this.terms = terms;
        this.stopwords = Set.copyOf(stopwords);
        loadUserWords(terms);
        this.segmenter = new JiebaSegmenter();
    }

    /**
     * 分词，保留位置（索引用）
     */
    public List<Token> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String value = LATEX_COMMAND.matcher(IngestionTextNormalizer.normalize(text)).replaceAll(" ");
        List<Token> out = new ArrayList<>();
        int[] position = {0};
        int cursor = 0;
        for (Span span : protectedSpans(value)) {
            segment(value.substring(cursor, span.start()), out, position);
            position[0]++;
            emit(span.term(), position[0], out);
            if (span.extra() != null) {
                out.add(new Token(span.extra(), position[0]));
            }
            cursor = span.end();
        }
        segment(value.substring(cursor), out, position);
        return out;
    }

    /**
     * 查询词项：去重、保持出现顺序
     */
    public List<String> queryTerms(String text) {
        Set<String> distinct = new LinkedHashSet<>();
        tokenize(text).forEach(token -> distinct.add(token.term()));
        return List.copyOf(distinct);
    }

    private void segment(String text, List<Token> out, int[] position) {
        if (text.isBlank()) {
            return;
        }
        for (SegToken seg : segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)) {
            String word = seg.word.trim().toLowerCase(Locale.ROOT);
            if (!keep(word)) {
                continue;
            }
            int at = ++position[0];
            emit(word, at, out);
            if (isGuessedWord(word)) {
                word.codePoints().mapToObj(Character::toString).filter(this::keep)
                        .forEach(single -> emit(single, at, out));
            }
        }
    }

    /**
     * jieba 词典（含用户词典）里没有、两字以上的纯汉字词：HMM 新词发现的产物
     */
    private static boolean isGuessedWord(String word) {
        return word.length() >= 2
                && word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                && !WordDictionary.getInstance().containsWord(word);
    }

    private void emit(String term, int position, List<Token> out) {
        out.add(new Token(term, position));
        TermDictionary.Term known = terms.termOf(term);
        if (known != null) {
            String canonical = TermDictionary.canonicalForm(known);
            if (!canonical.isEmpty() && !canonical.equals(term)) {
                out.add(new Token(canonical, position));
            }
        }
    }

    private boolean keep(String word) {
        if (word.isEmpty() || word.length() > MAX_TERM_LENGTH || stopwords.contains(word)) {
            return false;
        }
        if (word.length() == 1 && word.charAt(0) < 128) {
            return false;
        }
        return word.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private record Span(int start, int end, String term, String extra) {
    }

    /**
     * 三类整体切出的串，按起点排序、互不重叠（同起点取长的）
     */
    static List<Span> protectedSpans(String value) {
        List<Span> found = new ArrayList<>();
        Matcher standard = STANDARD_NO.matcher(value);
        while (standard.find()) {
            String base = (standard.group(1) + standard.group(2)).toLowerCase(Locale.ROOT);
            String withYear = standard.group(3) == null ? null : base + "-" + standard.group(3);
            found.add(new Span(standard.start(), standard.end(), base, withYear));
        }
        Matcher formula = FORMULA.matcher(value);
        while (formula.find()) {
            String text = formula.group(1);
            if (text.chars().anyMatch(Character::isDigit) || TWO_LETTER_ELEMENT.matcher(text).find()) {
                found.add(new Span(formula.start(1), formula.end(1), text.toLowerCase(Locale.ROOT), null));
            }
        }
        Matcher number = NUMBER_WITH_UNIT.matcher(value);
        while (number.find()) {
            found.add(new Span(number.start(), number.end(),
                    (number.group(1) + number.group(2)).toLowerCase(Locale.ROOT), null));
        }
        found.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::end).reversed()));
        List<Span> spans = new ArrayList<>(found.size());
        int covered = 0;
        for (Span span : found) {
            if (span.start() >= covered) {
                spans.add(span);
                covered = span.end();
            }
        }
        return spans;
    }

    /**
     * 术语表里含汉字的写法加进 jieba 的用户词典；纯 ASCII 写法（SiO2、TFe）jieba 本来就整体切出
     * <p>
     * jieba 的词典是进程内单例，只能从文件加载：写一个临时文件，加载后删掉
     */
    private static synchronized void loadUserWords(TermDictionary terms) {
        List<String> lines = terms.forms().stream()
                .filter(form -> form.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
                .filter(form -> form.chars().noneMatch(Character::isWhitespace))
                .sorted()
                .map(form -> form + " " + USER_WORD_FREQ)
                .toList();
        if (lines.isEmpty()) {
            log.warn("术语表为空，全文分词只用 jieba 自带词典");
            return;
        }
        Path file = null;
        try {
            file = Files.createTempFile("kq-terms", ".dict");
            Files.write(file, lines, StandardCharsets.UTF_8);
            WordDictionary.getInstance().loadUserDict(file, StandardCharsets.UTF_8);
            log.info("全文分词用户词典：{} 个术语写法", lines.size());
        } catch (IOException e) {
            throw new IllegalStateException("写全文分词用户词典失败", e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响分词
                }
            }
        }
    }

    private static Set<String> loadStopwords(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("停用词表不存在，全文分词不去停用词：{}", resource);
            return Set.of();
        }
        try (InputStream in = resource.getInputStream()) {
            Set<String> words = new LinkedHashSet<>();
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(words::add);
            return words;
        } catch (IOException e) {
            throw new IllegalStateException("读取停用词表失败：" + resource, e);
        }
    }
}
