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

package com.nageoffer.ai.ragent.research.eval;

import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.runtime.*;
import com.nageoffer.ai.ragent.research.service.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OneShotResearchRunnerTest {
    @Test void retrievesOnceAndUsesOnlyPinnedChunksWithoutAnExplorationModel() {
        var store = mock(ResearchRunStore.class);
        when(store.current(any())).thenReturn(true);
        var search = mock(KnowledgeSearchService.class);
        var reader = mock(SourceReader.class);
        var brief = new ResearchBrief("Question", ResearchBrief.OutputType.REPORT, List.of(), List.of("kb"), List.of("doc"));
        var run = new ResearchRun("run", "conversation", "request", brief, ResearchRun.Status.RUNNING,
                1, 1, Map.of(), null, Map.of(), null, Instant.now(), null);
        var session = new ResearchSession(store, new ResearchRunStore.Claim(run, "owner", "lease"),
                new ResearchBudget(new ResearchProperties(), Map.of()), new ResearchControl());
        var hit = new KnowledgeSearchHit("ev", "kb", "doc", "paper", "V1", "excerpt", false, Map.of(), EvidenceRecord.SourceExtent.CHUNK);
        when(search.search("run", "owner", "main", "Question", List.of(), List.of(), 10)).thenReturn(List.of(hit));
        var evidence = new EvidenceRecord("run", "ev", "kb", "doc", "paper", "V1", List.of("chunk"), "hash",
                "pinned body", Map.of("source_paragraph_id", "paragraph"), "main", false, true, EvidenceRecord.SourceExtent.CHUNK);
        when(reader.read("run", "owner", "ev")).thenReturn(new SourceReadResult(evidence, SourceReadResult.SourceState.CURRENT));
        var outcome = new OneShotResearchRunner(search, reader).run(session);
        assertEquals(Set.of("ev"), session.citableIds());
        assertEquals(0, session.budget.snapshot().get("modelCalls"));
        assertEquals(1, session.budget.snapshot().get("toolCalls"));
        assertEquals(SubtaskResult.Status.COMPLETED, outcome.result().status());
        verify(search, times(1)).search("run", "owner", "main", "Question", List.of(), List.of(), 10);
        verify(reader, times(1)).read("run", "owner", "ev");
        verifyNoMoreInteractions(search, reader);
    }
}
