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

package com.nageoffer.ai.ragent.core.parser.excel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 按字数与数字占比判断表头：第一行有格子超过 40 字，或一半以上非空格子是数字，就像数据、按无表头处理；
 * 否则当表头（table-structure 计划 §2 第 5 条）。默认判定，也是 {@link JevHeaderJudge} 的兜底
 */
public final class RuleHeaderJudge implements HeaderJudge {

    public static final RuleHeaderJudge INSTANCE = new RuleHeaderJudge();

    /**
     * 表头单元格最多这么多字，再长就是数据
     */
    static final int MAX_HEADER_CELL_CHARS = 40;

    /**
     * 数字样子的单元格：整数、小数、千分位、可带正负号与百分号，只用于区分表头与数据
     */
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[$+\\-]?[\\d,]+(\\.\\d+)?%?$");

    private RuleHeaderJudge() {
    }

    @Override
    public Decision judge(Candidate candidate) {
        return Decision.rule(headerLike(candidate.firstRowCells()));
    }

    private static boolean headerLike(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        int numeric = 0;
        for (String cell : cells) {
            if (cell.length() > MAX_HEADER_CELL_CHARS) {
                return false;
            }
            if (NUMERIC_CELL.matcher(cell).matches()) {
                numeric++;
            }
        }
        return numeric * 2 < cells.size();
    }
}
