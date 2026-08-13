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

import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPackerProvenanceTest {

    @Test
    void headingMergedIntoFirstTableRowDoesNotEraseCellRange() {
        ChunkDraft heading = ChunkDraft.ofHeading(
                "浓度检测",
                "浓度检测",
                ChunkMetadata.builder()
                        .provenance(Provenance.ofExcelCell("demo.xlsx", "浓度检测"))
                        .build());
        ChunkDraft row = ChunkDraft.of(
                "|步骤|内容|",
                "步骤: 烘干",
                ChunkMetadata.builder()
                        .provenance(Provenance.ofExcelCell("demo.xlsx", "浓度检测", "B17:F17"))
                        .blockType("table")
                        .build());

        List<ChunkDraft> packed = new ChunkPacker().pack(List.of(heading, row), ChunkBudget.defaults());

        assertEquals(1, packed.size());
        assertEquals("B17:F17", packed.get(0).metadata().provenance().cellRange());
    }

    @Test
    void blockPiecesMustNotBeMergedBackWithinTolerance() {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(List.of("流程表"))
                .provenance(Provenance.ofExcelCell("demo.xlsx", "流程表"))
                .blockType("table")
                .build();
        ChunkDraft heading = ChunkDraft.ofHeading("# 流程表", "流程表", metadata);
        List<ChunkDraft> pieces = ChunkDraft.pieces(List.of(
                ChunkDraft.of("甲".repeat(600), metadata),
                ChunkDraft.of("乙".repeat(600), metadata)));

        List<ChunkDraft> packed = new ChunkPacker().pack(
                List.of(heading, pieces.get(0), pieces.get(1)), ChunkBudget.defaults());

        assertEquals(2, packed.size());
        assertTrue(packed.stream().allMatch(draft -> draft.content().length() <= 1024));
    }

    @Test
    void topLevelOutlinesMustRemainSeparateEvenWhenBothAreSmall() {
        ChunkDraft firstHeading = heading("甲表");
        ChunkDraft firstRow = row("甲表", "甲记录");
        ChunkDraft secondHeading = heading("乙表");
        ChunkDraft secondRow = row("乙表", "乙记录");

        List<ChunkDraft> packed = new ChunkPacker().pack(
                List.of(firstHeading, firstRow, secondHeading, secondRow), ChunkBudget.defaults());

        assertEquals(2, packed.size());
        assertEquals(List.of("甲表"), packed.get(0).metadata().outlinePath());
        assertEquals(List.of("乙表"), packed.get(1).metadata().outlinePath());
    }

    private static ChunkDraft heading(String sheet) {
        return ChunkDraft.ofHeading("# " + sheet, sheet, ChunkMetadata.builder()
                .outlinePath(List.of(sheet))
                .provenance(Provenance.ofExcelCell("demo.xlsx", sheet))
                .build());
    }

    private static ChunkDraft row(String sheet, String text) {
        return ChunkDraft.of(text, ChunkMetadata.builder()
                .outlinePath(List.of(sheet))
                .provenance(Provenance.ofExcelCell("demo.xlsx", sheet, "B2:C2"))
                .blockType("table")
                .build());
    }
}
