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

package com.nageoffer.ai.ragent.research.runtime;

import com.nageoffer.ai.ragent.research.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchSourceViewTest {
    @Test void projectionPreservesTextIdentityAndSectionButRemovesStorageMetadata() {
        var location = Map.<String,Object>of("sectionPath", "Methods", "raw_metadata", "irrelevant storage payload".repeat(500));
        var evidence = new EvidenceRecord("run", "expanded-id", "kb", "doc", "Paper", "v1", List.of("chunk-a", "chunk-b"),
                "full-hash", "The method uses 7 ms.", location, "main", false, true, EvidenceRecord.SourceExtent.CHUNK);
        var view = ResearchSourceView.read(new SourceReadResult(evidence, SourceReadResult.SourceState.CURRENT,
                SourceReadResult.ExpansionState.NEIGHBORS, "seed-id", null));
        var source = (Map<?,?>) view.get("evidence");
        assertEquals("expanded-id", source.get("evidenceId"));
        assertEquals("seed-id", view.get("requestedEvidenceId"));
        assertEquals(evidence.text(), source.get("text"));
        assertEquals(Map.of("sectionPath", "Methods"), source.get("sourceContext"));
        assertFalse(view.toString().contains("raw_metadata"));
        assertTrue(evidence.sourceLocation().containsKey("raw_metadata"));
    }
    @Test void contentsAndEquationFragmentsAreWarningsNotFabricatedEvidence() {
        var warnings = ResearchSourceView.warnings("Table of contents: Method DISPLAYFORM", Map.of());
        assertEquals(3, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("placeholders")));
        assertTrue(ResearchSourceView.warnings("Supported paragraph. ".repeat(30), Map.of()).isEmpty());
    }
}
