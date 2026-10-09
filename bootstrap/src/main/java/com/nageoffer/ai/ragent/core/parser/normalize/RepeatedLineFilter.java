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
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 去掉页面家具（水印、页眉、页脚）的频次规则
 * <p>
 * 页面家具由 PDF 文字层定义：出现在多数页面的整行。解析结果里一行与某条家具行相同、是它的片段，
 * 或 OCR 识别出的近似写法（编辑距离相似度 ≥ 0.85），就算该家具行的一次出现；某条家具行在解析结果里
 * 出现满 {@code minOccurrences} 次才删，不满的保留。阈值取 3：MinerU 已删掉大部分页眉，封面上与页眉同文的
 * 标题在解析结果里只剩一次，那是正文；漏网的水印（"中国标准出版社授权……"）和购买方印章行（"东北大学"）
 * 在 16 页的文档里会留下四五次。标准的页眉就是本标准号，2026-10-09 的解析结果里它在硅含量、全铁两份里
 * 各出现 3 次：标准号形态的家具行保留第一次出现（封面），其余照删
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RepeatedLineFilter {

    private static final Pattern STANDARD_NUMBER_LINE = Pattern.compile("^(?:GB|YS|YB|HG|JB|SN)(?:/[TZ])?\\d+(?:\\.\\d+)*-");
    private static final int FUZZY_MIN_LENGTH = 8;
    private static final double FUZZY_MIN_SIMILARITY = 0.85;
    private static final double FRAGMENT_MIN_COVERAGE = 0.5;

    /**
     * @param blocks       过滤后的 Block
     * @param removedLines 删掉的行数
     */
    public record Result(List<Block> blocks, int removedLines) {
    }

    public static Result filter(List<Block> blocks, Collection<String> repeatedKeys, int minOccurrences) {
        if (blocks == null || blocks.isEmpty() || repeatedKeys == null || repeatedKeys.isEmpty()) {
            return new Result(blocks == null ? List.of() : blocks, 0);
        }
        List<String> keys = List.copyOf(repeatedKeys);
        Map<String, Integer> occurrences = new HashMap<>();
        for (Block block : blocks) {
            for (String line : lines(block)) {
                String matched = match(line, keys);
                if (matched != null) {
                    occurrences.merge(matched, 1, Integer::sum);
                }
            }
        }
        Set<String> removable = occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() >= minOccurrences)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (removable.isEmpty()) {
            return new Result(blocks, 0);
        }
        List<String> removableKeys = keys.stream().filter(removable::contains).toList();
        Set<String> keepFirst = removableKeys.stream()
                .filter(key -> STANDARD_NUMBER_LINE.matcher(key).find())
                .collect(Collectors.toCollection(HashSet::new));
        int[] removed = {0};
        List<Block> kept = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            Block filtered = filterBlock(block, removableKeys, keepFirst, removed);
            if (filtered != null) {
                kept.add(filtered);
            }
        }
        return new Result(kept, removed[0]);
    }

    /**
     * 这一行是否删掉：命中可删的家具行即删，标准号形态的家具行第一次命中时放过
     */
    private static boolean drop(String line, List<String> removableKeys, Set<String> keepFirst, int[] removed) {
        String matched = match(line, removableKeys);
        if (matched == null || keepFirst.remove(matched)) {
            return false;
        }
        removed[0]++;
        return true;
    }

    private static List<String> lines(Block block) {
        if (block instanceof HeadingBlock heading) {
            return List.of(nullToEmpty(heading.text()));
        }
        if (block instanceof ParagraphBlock paragraph) {
            return List.of(nullToEmpty(paragraph.text()).split("\n"));
        }
        if (block instanceof ListBlock list && list.items() != null) {
            return list.items();
        }
        return List.of();
    }

    private static Block filterBlock(Block block, List<String> removableKeys, Set<String> keepFirst, int[] removed) {
        if (block instanceof HeadingBlock heading) {
            return drop(nullToEmpty(heading.text()), removableKeys, keepFirst, removed) ? null : heading;
        }
        if (block instanceof ParagraphBlock paragraph) {
            List<String> kept = new ArrayList<>();
            for (String line : nullToEmpty(paragraph.text()).split("\n")) {
                if (!drop(line, removableKeys, keepFirst, removed)) {
                    kept.add(line);
                }
            }
            String text = String.join("\n", kept).strip();
            return text.isEmpty() ? null : new ParagraphBlock(paragraph.provenance(), text);
        }
        if (block instanceof ListBlock list && list.items() != null) {
            List<String> kept = new ArrayList<>();
            for (String item : list.items()) {
                if (!drop(item, removableKeys, keepFirst, removed)) {
                    kept.add(item);
                }
            }
            return kept.isEmpty() ? null : new ListBlock(list.provenance(), list.ordered(), kept);
        }
        return block;
    }

    /**
     * 该行对应的家具行；不是家具行返回 null
     */
    static String match(String line, List<String> keys) {
        String key = IngestionTextNormalizer.matchForm(line);
        if (key.isEmpty()) {
            return null;
        }
        for (String repeated : keys) {
            if (key.equals(repeated)) {
                return repeated;
            }
        }
        int length = key.codePointCount(0, key.length());
        if (length < FUZZY_MIN_LENGTH) {
            return null;
        }
        for (String repeated : keys) {
            int repeatedLength = repeated.codePointCount(0, repeated.length());
            if (repeated.contains(key) && length >= repeatedLength * FRAGMENT_MIN_COVERAGE) {
                return repeated;
            }
            if (repeatedLength >= FUZZY_MIN_LENGTH && similarity(key, repeated) >= FUZZY_MIN_SIMILARITY) {
                return repeated;
            }
        }
        return null;
    }

    /**
     * 1 − 编辑距离 / 较长串长度；长度差已超出容忍时直接判不相似，免得对长段落做平方级比较
     */
    static double similarity(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int longer = Math.max(x.length, y.length);
        if (longer == 0) {
            return 1D;
        }
        if (Math.abs(x.length - y.length) > longer * (1 - FUZZY_MIN_SIMILARITY)) {
            return 0D;
        }
        int[] previous = new int[y.length + 1];
        int[] current = new int[y.length + 1];
        for (int j = 0; j <= y.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= x.length; i++) {
            current[0] = i;
            for (int j = 1; j <= y.length; j++) {
                int cost = x[i - 1] == y[j - 1] ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1D - (double) previous[y.length] / longer;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
