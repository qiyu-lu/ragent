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

import com.nageoffer.ai.ragent.infra.jev.JevAnswer;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevQuestion;
import com.nageoffer.ai.ragent.infra.jev.JevResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让 Jev 判断表的第一行是不是表头，有把握才采用，没把握或调用失败按规则（jev-header 计划 §2）
 * <p>
 * 只发这张表的前几行：状态越长 Jev 越不准，也越贵。提示用英文（Jev 的主要训练语言），不放任何语料里的词
 */
@Slf4j
public final class JevHeaderJudge implements HeaderJudge {

    static final String QUESTION_ID = "first_row_is_header";

    static final JevQuestion.Noul QUESTION = new JevQuestion.Noul(
            "`first_row` is the top row of a table cut out of a spreadsheet and `rows_below` are the rows under it, "
                    + "cells in column order; empty strings are blank cells and long cells are cut short with …. "
                    + "`sheet` is the worksheet name and `title`, when present, is the caption written above the table. "
                    + "Is `first_row` the table's header row, giving the names of the columns?",
            "`first_row` holds column names: each non-empty cell is a short label for the kind of value found in its "
                    + "column of `rows_below`, not a value itself.",
            "`first_row` is a data record of the same kind as `rows_below`: its cells are values (names, items, numbers, "
                    + "times, descriptions or definitions) that a header would label, so the table has no header row.");

    private final JevClient client;
    private final ExcelLayoutProperties.Jev settings;
    private final HeaderJudge fallback;

    public JevHeaderJudge(JevClient client, ExcelLayoutProperties.Jev settings, HeaderJudge fallback) {
        this.client = client;
        this.settings = settings;
        this.fallback = fallback;
    }

    @Override
    public Decision judge(Candidate candidate) {
        boolean ruled = fallback.judge(candidate).header();
        double probability;
        try {
            JevResult result = client.systemOne(state(candidate), Map.of(QUESTION_ID, QUESTION));
            probability = ((JevAnswer.Noul) result.answers().get(QUESTION_ID)).probability();
        } catch (RuntimeException e) {
            log.warn("Jev 表头判定失败，按规则: sheet={}, row={}, reason={}", candidate.sheet(), candidate.firstRow() + 1,
                    e.getMessage());
            return new Decision(ruled, Source.JEV_FAILED, null);
        }
        Decision decision;
        if (probability >= settings.getConfidentHeader()) {
            decision = new Decision(true, Source.JEV, probability);
        } else if (probability <= settings.getConfidentNoHeader()) {
            decision = new Decision(false, Source.JEV, probability);
        } else {
            decision = new Decision(ruled, Source.JEV_UNSURE, probability);
        }
        log.debug("Jev 表头判定: sheet={}, row={}, p={}, rule={}, decision={}, source={}", candidate.sheet(),
                candidate.firstRow() + 1, probability, ruled, decision.header(), decision.source());
        return decision;
    }

    /**
     * 发给 Jev 的状态：sheet 名、表题候选、待判定行与其下几行；只看前 {@code maxRows} 行，单格截断，
     * 这几行里全空的列去掉
     */
    Map<String, Object> state(Candidate candidate) {
        List<List<String>> rows = candidate.rows().subList(0, Math.min(settings.getMaxRows(), candidate.rows().size()));
        List<Integer> columns = new ArrayList<>();
        for (int c = 0; c < rows.get(0).size(); c++) {
            final int column = c;
            if (rows.stream().anyMatch(row -> column < row.size() && !row.get(column).isEmpty())) {
                columns.add(c);
            }
        }
        List<List<String>> shown = rows.stream()
                .map(row -> columns.stream().map(c -> cut(c < row.size() ? row.get(c) : "")).toList())
                .toList();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sheet", candidate.sheet());
        if (candidate.title() != null) {
            state.put("title", cut(candidate.title()));
        }
        state.put("first_row", shown.get(0));
        state.put("rows_below", shown.subList(1, shown.size()));
        return state;
    }

    private String cut(String value) {
        int limit = settings.getMaxCellChars();
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
    }
}
