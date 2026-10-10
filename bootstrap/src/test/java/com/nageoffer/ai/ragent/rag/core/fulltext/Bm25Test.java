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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25Test {

    @Test
    void rareTermsWeighMoreThanCommonOnesAndIdfStaysPositive() {
        assertTrue(Bm25.idf(224, 1) > Bm25.idf(224, 100));
        assertTrue(Bm25.idf(224, 224) > 0);
        assertEquals(Math.log(1 + (224 - 1 + 0.5) / (1 + 0.5)), Bm25.idf(224, 1), 1e-12);
    }

    @Test
    void matchesTheTextbookFormula() {
        double idf = Math.log(1 + (10 - 2 + 0.5) / (2 + 0.5));
        double expected = idf * 3 * 2.2 / (3 + 1.2 * (1 - 0.75 + 0.75 * 40 / 20.0));
        double actual = Bm25.score(Map.of("焦硫酸钾", 3), 40, Map.of("焦硫酸钾", 2L), 10, 20, 1.2, 0.75);
        assertEquals(expected, actual, 1e-12);
    }

    @Test
    void termFrequencySaturatesAndLongChunksAreNormalized() {
        Map<String, Long> df = Map.of("全铁", 5L);
        double once = Bm25.score(Map.of("全铁", 1), 100, df, 100, 100, 1.2, 0.75);
        double tenTimes = Bm25.score(Map.of("全铁", 10), 100, df, 100, 100, 1.2, 0.75);
        assertTrue(tenTimes > once && tenTimes < 10 * once);
        double longChunk = Bm25.score(Map.of("全铁", 1), 400, df, 100, 100, 1.2, 0.75);
        assertTrue(longChunk < once);
    }

    @Test
    void noHitsOrNoCorpusScoresZero() {
        assertEquals(0D, Bm25.score(Map.of(), 10, Map.of(), 10, 10, 1.2, 0.75));
        assertEquals(0D, Bm25.score(Map.of("a", 1), 10, Map.of("a", 1L), 0, 10, 1.2, 0.75));
    }
}
