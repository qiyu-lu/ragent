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

package com.nageoffer.ai.ragent.core.parser.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionTextNormalizerTest {


    private static JsonNode sharedCases() throws IOException {
        for (Path candidate : List.of(Path.of("..", "eval", "kq", "normalization_cases.json"),
                Path.of("eval", "kq", "normalization_cases.json"))) {
            if (Files.isRegularFile(candidate)) {
                return new ObjectMapper().readTree(candidate.toFile());
            }
        }
        throw new IllegalStateException("eval/kq/normalization_cases.json not found from " + Path.of("").toAbsolutePath());
    }

    @TestFactory
    Stream<DynamicTest> sharedCasesAgreeWithTheEvaluator() throws IOException {
        JsonNode root = sharedCases();
        List<DynamicTest> tests = new ArrayList<>();
        for (String section : List.of("cases", "latex_cases")) {
            for (JsonNode item : root.path(section)) {
                String input = item.path("input").asText();
                String expected = item.path("expected").asText();
                tests.add(DynamicTest.dynamicTest(section + ": " + item.path("name").asText(),
                        () -> assertEquals(expected, IngestionTextNormalizer.matchForm(input))));
            }
        }
        assertTrue(tests.size() >= 15, "shared normalization cases went missing");
        return tests.stream();
    }

    @Test
    void unwrapsNumericMathButKeepsStructuredFormulas() {
        assertEquals("温度控制在 400±20°C ,放置 1~2min",
                IngestionTextNormalizer.normalize("温度控制在 $400 \\pm 20 ^ { \\circ } \\mathrm { C }$ ，放置 $1 \\sim 2 \\mathrm { ~ m i n }$"));
        String fraction = "$w = \\frac { ( m _ { 1 } - m _ { 2 } ) \\times 100 } { m _ { 0 } }$";
        assertEquals(fraction, IngestionTextNormalizer.normalize(fraction));
        assertEquals("$\\mathrm { S i O } _ { 2 }$", IngestionTextNormalizer.normalize("$\\mathrm { S i O } _ { 2 }$"));
        assertNull(IngestionTextNormalizer.plainMath("10 ^ { -3 }"));
    }

    @Test
    void keepsLineBreaksAndImageLinksWhileCollapsingSpaces() {
        String text = "第一行   有  空格\n![图](http://127.0.0.1:9000/ragent-assets/assets/1/a-b.jpg)  \n第三行";
        assertEquals("第一行 有 空格\n![图](http://127.0.0.1:9000/ragent-assets/assets/1/a-b.jpg)\n第三行",
                IngestionTextNormalizer.normalize(text));
    }

    @Test
    void joinsDecimalGroupsOnly() {
        assertEquals("精确至 0.0001 g,0.0000001 g", IngestionTextNormalizer.normalize("精确至 0.000 1 g，0.000 000 1 g"));
        assertEquals("称取 0.200 g 试样", IngestionTextNormalizer.normalize("称取 0.200 g 试样"));
        assertEquals("", IngestionTextNormalizer.normalize(null));
    }
}
