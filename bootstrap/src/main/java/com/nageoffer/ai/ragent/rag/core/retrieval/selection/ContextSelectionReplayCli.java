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

package com.nageoffer.ai.ragent.rag.core.retrieval.selection;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local JSONL replay entrypoint. Candidate snapshots contain no gold fields. */
public final class ContextSelectionReplayCli {

    private ContextSelectionReplayCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path inputPath = requiredPath(options, "input");
        Path outputPath = requiredPath(options, "output");
        if (Files.exists(outputPath)) {
            throw new IllegalArgumentException("refusing to overwrite existing output: " + outputPath);
        }
        ContextSelectionStrategy strategy = ContextSelectionStrategy.fromLabel(required(options, "strategy"));
        int tokenBudget = Integer.parseInt(options.getOrDefault("token-budget", "1024"));
        int maxChunks = Integer.parseInt(options.getOrDefault("max-chunks", "10"));
        double lambda = Double.parseDouble(options.getOrDefault("lambda", "1.0"));
        double mu = Double.parseDouble(options.getOrDefault("mu", "0.3"));
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ContextSelector selector = new DeterministicContextSelector();
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(
                     outputPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                CandidateSnapshot snapshot = mapper.readValue(line, CandidateSnapshot.class);
                ContextSelectionInput input = new ContextSelectionInput(
                        snapshot.exampleId(), snapshot.originalQuestion(), snapshot.predictedAspects(),
                        snapshot.candidates(), strategy, tokenBudget, maxChunks, lambda, mu);
                writer.write(mapper.writeValueAsString(selector.select(input)));
                writer.newLine();
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("arguments must be --name value pairs");
            }
            options.put(args[index].substring(2), args[index + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        return Path.of(required(options, name));
    }

    private record CandidateSnapshot(
            String exampleId,
            String originalQuestion,
            List<String> predictedAspects,
            List<ContextSelectionCandidate> candidates) {
    }
}
