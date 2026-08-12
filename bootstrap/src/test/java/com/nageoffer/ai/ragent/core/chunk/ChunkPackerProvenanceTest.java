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
}
