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

package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkContext;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunkingProperties;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableChunkerContentDefinedTest {

    private static final ChunkBudget BUDGET = new ChunkBudget(1024, 128, 50);
    private static final List<String> HEADERS = List.of("步骤", "参数", "说明");
    private static final ChunkContext CTX = ChunkContext.of(List.of("检测"), BUDGET);

    @Test
    void offKeepsTheGreedyGroupingOfTheDefaultConstructor() {
        TableBlock table = table(rows(new Random(7), 120));
        assertEquals(bodies(new TableChunker().chunk(table, CTX)), bodies(chunker(false).chunk(table, CTX)));
    }

    @Test
    void afterTheFirstContentCutFollowingAnEditEveryChunkIsUnchanged() {
        Random random = new Random(42);
        int checked = 0;
        for (int trial = 0; trial < 200; trial++) {
            List<List<String>> rows = rows(random, 60 + random.nextInt(120));
            int position = random.nextInt(rows.size());
            int kind = random.nextInt(3);
            List<List<String>> changed = new ArrayList<>(rows);
            if (kind == 0) {
                changed.add(position, row(random, "新增"));
            } else if (kind == 1) {
                changed.remove(position);
            } else {
                changed.set(position, row(random, "改动"));
            }
            // 两版共有的、改动之后的第一行在原表里的下标
            int firstShared = kind == 0 ? position : position + 1;
            String cut = null;
            for (int r = firstShared; r < rows.size(); r++) {
                String line = TableChunker.renderKeyValueRow(HEADERS, rows.get(r));
                if (TableChunker.isContentCut(line, BUDGET.maxChars())) {
                    cut = line;
                    break;
                }
            }
            if (cut == null) {
                continue;
            }
            List<String> before = bodies(chunker(true).chunk(table(rows), CTX));
            List<String> after = bodies(chunker(true).chunk(table(changed), CTX));
            int i = lastEndingWith(before, cut);
            int j = lastEndingWith(after, cut);
            assertTrue(i >= 0 && j >= 0, "切点行必然是某块的最后一行");
            assertEquals(before.subList(i + 1, before.size()), after.subList(j + 1, after.size()),
                    "trial " + trial + "：切点之后的块必须逐个相同");
            checked++;
        }
        assertTrue(checked > 100, "大多数改动之后都有内容切点，否则用例没有检到不变式");
    }

    @Test
    void contentDefinedChunksStillRespectTheBudget() {
        List<ChunkDraft> drafts = chunker(true).chunk(table(rows(new Random(3), 200)), CTX);
        assertTrue(drafts.size() > 1);
        assertTrue(drafts.stream().allMatch(d -> d.content().length() <= BUDGET.maxChars()));
        assertTrue(drafts.stream().allMatch(d -> d.effectiveBody().length() + "检测".length() + 1 <= BUDGET.maxChars()));
    }

    @Test
    void cutProbabilityFollowsRowLength() {
        Random random = new Random(11);
        int cuts = 0;
        double expected = 0;
        int samples = 20000;
        for (int i = 0; i < samples; i++) {
            String line = "参数: " + random.nextLong() + "x".repeat(random.nextInt(400));
            expected += (double) line.length() / (2 * 1024) / samples;
            if (TableChunker.isContentCut(line, 1024)) {
                cuts++;
            }
        }
        assertEquals(expected, (double) cuts / samples, 0.01);
        assertTrue(TableChunker.isContentCut("x".repeat(5000), 1024), "行长超过两倍块长时必然是切点");
    }

    @Test
    void captionLeadsEveryChunkAndEntersTheOutline() {
        TableBlock table = new TableBlock(Provenance.ofExcelCell("x.xlsx", "设备表"), HEADERS, rows(new Random(5), 80),
                List.of(), "二、研究型中心实验室");
        List<ChunkDraft> drafts = chunker(true).chunk(table, ChunkContext.of(List.of("设备表"), BUDGET));

        assertTrue(drafts.size() > 1);
        for (ChunkDraft draft : drafts) {
            assertTrue(draft.content().startsWith("二、研究型中心实验室\n\n|"));
            assertTrue(draft.effectiveBody().startsWith("二、研究型中心实验室\n"));
            assertEquals(List.of("设备表", "二、研究型中心实验室"), draft.metadata().outlinePath());
            assertTrue(draft.content().length() <= BUDGET.maxChars());
        }
    }

    private static TableChunker chunker(boolean contentDefined) {
        TableChunkingProperties properties = new TableChunkingProperties();
        properties.setContentDefined(contentDefined);
        return new TableChunker(properties);
    }

    private static TableBlock table(List<List<String>> rows) {
        return new TableBlock(Provenance.ofExcelCell("x.xlsx", "检测"), HEADERS, rows);
    }

    private static List<List<String>> rows(Random random, int count) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row(random, "第" + i + "步"));
        }
        return rows;
    }

    private static List<String> row(Random random, String step) {
        return List.of(step, "参数" + random.nextInt(1000), "说".repeat(20 + random.nextInt(260)));
    }

    private static List<String> bodies(List<ChunkDraft> drafts) {
        return drafts.stream().map(ChunkDraft::effectiveBody).toList();
    }

    private static int lastEndingWith(List<String> bodies, String line) {
        for (int k = bodies.size() - 1; k >= 0; k--) {
            if (bodies.get(k).endsWith(line)) {
                return k;
            }
        }
        return -1;
    }
}
