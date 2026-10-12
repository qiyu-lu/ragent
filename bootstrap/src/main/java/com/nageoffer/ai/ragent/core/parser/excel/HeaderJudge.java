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

/**
 * 判断一张表的第一行是不是表头（jev-header 计划 §2）
 * <p>
 * 只回答这一个问题。区域、分段、标题、续段与重复表头仍由 {@link ExcelLayoutAnalyzer} 按规则处理；
 * 首段有表头时，后续段也不再问，直接沿用或识别重复表头
 */
@FunctionalInterface
public interface HeaderJudge {

    Decision judge(Candidate candidate);

    /**
     * 判定输入
     *
     * @param sheet      sheet 名
     * @param title      表题候选：首段取区域开头、后续段取分段行里最后一个不超过 30 字的单格行；没有为 null
     * @param rowIndexes 各行在 sheet 里的行号，从 0 起；第一个是待判定的行
     * @param rows       这一段从待判定行起的逻辑行（续行不算），按区域的列对齐；合并块只在首列出现一次，
     *                   空格子、被合并块覆盖的格子和别的区域的格子都是空串
     */
    record Candidate(String sheet, String title, List<Integer> rowIndexes, List<List<String>> rows) {

        public Candidate {
            rowIndexes = List.copyOf(rowIndexes);
            rows = rows.stream().map(List::copyOf).toList();
            if (rows.isEmpty() || rows.size() != rowIndexes.size()) {
                throw new IllegalArgumentException("rows 与 rowIndexes 要等长且不为空");
            }
        }

        public int firstRow() {
            return rowIndexes.get(0);
        }

        /**
         * 待判定行里有内容的格子，按列序
         */
        public List<String> firstRowCells() {
            return rows.get(0).stream().filter(cell -> !cell.isEmpty()).toList();
        }
    }

    /**
     * 判定结果
     *
     * @param probability Jev 给出的 P(表头)；规则判定或 Jev 调用失败时为 null
     */
    record Decision(boolean header, Source source, Double probability) {

        static Decision rule(boolean header) {
            return new Decision(header, Source.RULE, null);
        }
    }

    enum Source {

        /**
         * 规则判定
         */
        RULE,

        /**
         * Jev 有把握，采用 Jev 的结论
         */
        JEV,

        /**
         * Jev 没把握，按规则
         */
        JEV_UNSURE,

        /**
         * Jev 调用失败，按规则
         */
        JEV_FAILED
    }
}
