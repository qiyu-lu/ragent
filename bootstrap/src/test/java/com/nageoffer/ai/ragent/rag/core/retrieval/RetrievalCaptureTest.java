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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalCaptureTest {
    @Test
    void snapshotDoesNotChangeAfterReranking() {
        var capture = new RetrievalCapture();
        var chunk = RetrievedChunk.builder().id("a").text("original").score(0.2f).build();
        var input = new ArrayList<>(List.of(chunk));
        capture.record("q", "vector", input, 1, null);
        chunk.setText("changed");
        chunk.setScore(0.9f);
        input.clear();
        assertEquals("original", capture.stages().get(0).chunks().get(0).text());
        assertEquals(0.2f, capture.stages().get(0).chunks().get(0).score());
        assertThrows(UnsupportedOperationException.class, () -> capture.stages().clear());
        assertThrows(UnsupportedOperationException.class, () -> capture.stages().get(0).chunks().clear());
    }

    @Test
    void concurrentQuestionsRemainRequestOwned() {
        var capture = new RetrievalCapture();
        IntStream.range(0, 100).parallel().forEach(i ->
                capture.record("q" + i, "vector", List.of(), 1, "empty-or-failed-channel"));
        assertEquals(100, capture.stages().size());
        assertEquals(100, capture.stages().stream().map(RetrievalCapture.Stage::question).distinct().count());
        assertTrue(new RetrievalCapture().stages().isEmpty());
    }
}
