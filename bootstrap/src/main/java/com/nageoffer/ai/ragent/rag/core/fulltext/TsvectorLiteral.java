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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 应用侧分词结果与 PostgreSQL {@code tsvector} / {@code tsquery} 字面量之间的转换
 * <p>
 * 不用 {@code to_tsvector('simple', 分词后空格连接)}：它会再过一遍 PostgreSQL 的解析器，
 * 把 {@code 400°c} 拆成 400 和 c、把 {@code 1.00%} 的百分号丢掉、把 {@code 0.5000g} 拆成数字和 g，
 * 应用侧整体切出的词项就白切了。这里直接写带位置的字面量（{@code 'term':1,5}），词项原样进索引
 */
public final class TsvectorLiteral {

    /**
     * PostgreSQL 的位置上限，超出的记在最后一个位置
     */
    static final int MAX_POSITION = 16_383;

    /**
     * 每个词位最多记 256 个位置（PostgreSQL 的限制），超出的不再记，词频按 256 封顶
     */
    static final int MAX_POSITIONS_PER_TERM = 256;

    private TsvectorLiteral() {
    }

    /**
     * 带位置的 tsvector 字面量；没有词项时为空串（PostgreSQL 里是空 tsvector）
     */
    public static String of(List<FullTextTokenizer.Token> tokens) {
        return positions(tokens).entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + entry.getValue().stream()
                        .map(String::valueOf).collect(Collectors.joining(",")))
                .collect(Collectors.joining(" "));
    }

    /**
     * 词项的 OR 查询；词项为空时返回 null，调用方不发 SQL
     */
    public static String orQuery(Collection<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return null;
        }
        return terms.stream().map(TsvectorLiteral::quote).collect(Collectors.joining(" | "));
    }

    /**
     * 文档长度：位置个数，与库里 {@code sum(cardinality(positions))} 同口径
     */
    public static int length(List<FullTextTokenizer.Token> tokens) {
        return positions(tokens).values().stream().mapToInt(TreeSet::size).sum();
    }

    /**
     * 词项 → 位置集合，与 PostgreSQL 的规则一致：同一位置只记一次，位置封顶 16383，每个词项最多 256 个位置
     */
    private static Map<String, TreeSet<Integer>> positions(List<FullTextTokenizer.Token> tokens) {
        Map<String, TreeSet<Integer>> positions = new LinkedHashMap<>();
        for (FullTextTokenizer.Token token : tokens) {
            TreeSet<Integer> own = positions.computeIfAbsent(token.term(), k -> new TreeSet<>());
            if (own.size() < MAX_POSITIONS_PER_TERM) {
                own.add(Math.max(1, Math.min(MAX_POSITION, token.position())));
            }
        }
        return positions;
    }

    /**
     * 单引号包住词项，内部的单引号写两次、反斜杠转义：tsvector 与 tsquery 的输入语法相同
     */
    static String quote(String term) {
        return "'" + term.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
