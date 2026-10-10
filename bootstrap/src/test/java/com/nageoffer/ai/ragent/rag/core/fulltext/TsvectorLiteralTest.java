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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TsvectorLiteralTest {

    private static FullTextTokenizer.Token token(String term, int position) {
        return new FullTextTokenizer.Token(term, position);
    }

    @Test
    void writesQuotedTermsWithMergedPositions() {
        String literal = TsvectorLiteral.of(List.of(token("全铁", 1), token("400°c", 2), token("全铁", 5), token("全铁", 5)));
        assertEquals("'全铁':1,5 '400°c':2", literal);
        assertEquals(3, TsvectorLiteral.length(List.of(token("全铁", 1), token("400°c", 2), token("全铁", 5), token("全铁", 5))));
    }

    @Test
    void escapesQuotesAndBackslashesLikeTheTsvectorInputSyntax() {
        assertEquals("'it''s':1 'a\\\\b':2", TsvectorLiteral.of(List.of(token("it's", 1), token("a\\b", 2))));
        assertEquals("'a' | 'it''s'", TsvectorLiteral.orQuery(List.of("a", "it's")));
        assertNull(TsvectorLiteral.orQuery(List.of()));
    }

    @Test
    void capsPositionsAndPositionsPerTermAtThePostgresLimits() {
        List<FullTextTokenizer.Token> tokens = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            tokens.add(token("x", i));
        }
        tokens.add(token("y", 99_999));
        assertEquals(TsvectorLiteral.MAX_POSITIONS_PER_TERM + 1, TsvectorLiteral.length(tokens));
        assertEquals("'y':" + TsvectorLiteral.MAX_POSITION, TsvectorLiteral.of(List.of(token("y", 99_999))));
        assertEquals("", TsvectorLiteral.of(List.of()));
    }
}
