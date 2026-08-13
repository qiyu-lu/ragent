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
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableChunkerTest {

    @Test
    void shouldBudgetBothMarkdownAndEmbeddingText() {
        ChunkBudget budget = new ChunkBudget(120, 10, 50);
        TableBlock block = new TableBlock(
                Provenance.ofExcelCell("fixture.xlsx", "检测流程"),
                List.of("检测阶段名称", "检测参数名称", "要求说明"),
                List.of(
                        List.of("阶段甲", "参数甲", "第一条要求需要保留结构"),
                        List.of("阶段乙", "参数乙", "第二条要求需要保留结构"),
                        List.of("阶段丙", "参数丙", "第三条要求需要保留结构")),
                List.of("B2:D2", "B3:D3", "B4:D4"));

        var drafts = new TableChunker().chunk(block, ChunkContext.of(List.of("检测流程"), budget));
        List<Chunk> chunks = ChunkAssembler.assembleAll(drafts);

        assertTrue(chunks.size() > 1, "重复表头后的 Markdown 超预算时必须拆块");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= budget.maxChars()));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.embeddingText().length() <= budget.maxChars()));
    }

    @Test
    void shouldKeepCombinedSourceRangeWhenRowsFitOneChunk() {
        ChunkBudget budget = new ChunkBudget(512, 32, 10);
        TableBlock block = new TableBlock(
                Provenance.ofExcelCell("fixture.xlsx", "检测流程"),
                List.of("阶段", "参数"),
                List.of(List.of("甲", "一"), List.of("乙", "二")),
                List.of("B2:C2", "B3:C3"));

        var drafts = new TableChunker().chunk(block, ChunkContext.of(List.of("检测流程"), budget));

        assertEquals(1, drafts.size());
        assertEquals("B2:C3", drafts.get(0).metadata().provenance().cellRange());
    }

    @Test
    void shouldSplitSparseWideRowByNonEmptyColumns() {
        ChunkBudget budget = new ChunkBudget(160, 16, 10);
        String stage = "阶段说明甲" + "甲".repeat(45);
        String parameter = "参数说明乙" + "乙".repeat(45);
        String requirement = "要求说明丙" + "丙".repeat(45);
        TableBlock block = new TableBlock(
                Provenance.ofExcelCell("fixture.xlsx", "需求表"),
                List.of("第一列很长的表头", "第二列很长的表头", "第三列很长的表头", "第四列很长的表头",
                        "第五列很长的表头", "第六列很长的表头", "第七列很长的表头", "第八列很长的表头"),
                List.of(List.of(stage, "", "", "", parameter, "", "", requirement)),
                List.of("B13:I13"));

        var drafts = new TableChunker().chunk(block, ChunkContext.of(List.of("需求表"), budget));

        assertTrue(drafts.size() > 1);
        assertTrue(drafts.stream().allMatch(draft -> draft.content().length() <= budget.maxChars()));
        assertTrue(drafts.stream().allMatch(draft -> draft.effectiveBody().length() + "需求表".length() + 1
                <= budget.maxChars()));
        String combined = drafts.stream().map(draft -> draft.content()).reduce("", String::concat);
        assertEquals(1, occurrences(combined, "阶段说明甲"));
        assertEquals(1, occurrences(combined, "参数说明乙"));
        assertEquals(1, occurrences(combined, "要求说明丙"));
    }

    private static int occurrences(String text, String target) {
        return text.split(java.util.regex.Pattern.quote(target), -1).length - 1;
    }
}
