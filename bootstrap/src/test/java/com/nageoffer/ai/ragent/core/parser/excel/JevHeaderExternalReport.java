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

package com.nageoffer.ai.ragent.core.parser.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nageoffer.ai.ragent.infra.jev.JevClient;
import com.nageoffer.ai.ragent.infra.jev.JevProperties;
import com.nageoffer.ai.ragent.infra.jev.JevQuestion;
import com.nageoffer.ai.ragent.infra.jev.JevResult;
import okhttp3.OkHttpClient;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * jev-header 计划 §8 的集外评测：Strudel（英文，逐行人工标注）与 TableQA（中文，表头显式给出），
 * 结果写进 {@code local-data/kq-eval/runs/jev-header-external-*.json}
 * <p>
 * 类名不以 Test 结尾，常规测试不跑；显式运行（环境里要有 {@code TYPESAFE_API_KEY}）：
 * {@code ./mvnw -o -q -pl bootstrap -am test -Dtest=JevHeaderExternalReport -Dsurefire.failIfNoSpecifiedTests=false}。
 * 缺数据或缺密钥就跳过；数据哈希与 {@code eval/kq/manifests/jev-header-external.json} 不一致就失败。
 * 判定与 §2 完全相同，Jev 跑 3 次，8 路并发；报告给出 §8 的门槛是否通过，不改任何默认配置
 */
class JevHeaderExternalReport {

    private static final Path MANIFEST = TableStructureOfflineReport.REPO.resolve("eval/kq/manifests/jev-header-external.json");
    private static final int RUNS = 3;
    private static final int THREADS = 8;
    private static final int TABLEQA_DATA_ROWS = 20;
    private static final List<String> STRUDEL_DATASETS = List.of("troy", "saus", "cius", "deex");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writeReport() throws Exception {
        assumeTrue(Files.isRegularFile(MANIFEST), "缺清单，跳过");
        JsonNode manifest = JSON.readTree(MANIFEST.toFile());
        Map<String, Path> files = new LinkedHashMap<>();
        for (JsonNode dataset : manifest.get("datasets")) {
            for (JsonNode file : dataset.get("files")) {
                Path path = TableStructureOfflineReport.REPO.resolve(file.get("path").asText());
                assumeTrue(Files.isRegularFile(path), "缺数据，跳过：" + path);
                assertEquals(file.get("sha256").asText(), TableStructureOfflineReport.sha256(Files.readAllBytes(path)),
                        "数据与清单的哈希不一致：" + path);
                files.put(path.getFileName().toString(), path);
            }
        }
        JevProperties properties = new JevProperties();
        // 只在用本地桩自测报告代码时设；正式运行用默认的 https://api.typesafe.ai
        properties.setBaseUrl(System.getProperty("jev.base-url", properties.getBaseUrl()));
        MeteredJevClient client = new MeteredJevClient(properties);
        assumeTrue(client.isConfigured(), "缺 TYPESAFE_API_KEY，跳过");
        ExcelLayoutProperties.Jev settings = new ExcelLayoutProperties.Jev();
        JevHeaderJudge jev = new JevHeaderJudge(client, settings, RuleHeaderJudge.INSTANCE);

        List<Map<String, Object>> unmatched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Probe> probes = new ArrayList<>();
        for (String dataset : STRUDEL_DATASETS) {
            probes.addAll(collectStrudel(dataset, files.get(dataset + ".jl.gz"), jev, seen, unmatched));
        }
        probes.addAll(collectTableQa(files.get("val.tables.json"), jev, seen, unmatched));
        System.out.printf("probes: %d (strudel %d, tableqa %d), unmatched %d%n", probes.size(),
                probes.stream().filter(p -> p.batch().equals("strudel")).count(),
                probes.stream().filter(p -> p.batch().equals("tableqa")).count(), unmatched.size());

        // 密钥无效、服务不可达时在这里失败，不写一份全是"调用失败"的报告
        client.systemOne(jev.state(probes.get(0).candidate()),
                Map.of(JevHeaderJudge.QUESTION_ID, JevHeaderJudge.QUESTION));
        client.reset();

        List<Map<String, HeaderJudge.Decision>> decisionsByRun = new ArrayList<>();
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) {
            long started = System.nanoTime();
            Map<String, HeaderJudge.Decision> decisions = judgeAll(probes, jev, run);
            decisionsByRun.add(decisions);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run", run);
            result.put("wall_seconds", (System.nanoTime() - started) / 1_000_000_000L);
            Map<String, Object> batches = new LinkedHashMap<>();
            for (String batch : List.of("strudel", "tableqa")) {
                batches.put(batch, metrics(probes.stream().filter(p -> p.batch().equals(batch)).toList(), decisions));
            }
            result.put("batches", batches);
            Map<String, Object> datasets = new LinkedHashMap<>();
            for (String dataset : probes.stream().map(Probe::dataset).distinct().toList()) {
                datasets.put(dataset, metrics(probes.stream().filter(p -> p.dataset().equals(dataset)).toList(), decisions));
            }
            result.put("datasets", datasets);
            result.put("jev_calls", client.snapshot());
            client.reset();
            runs.add(result);
            System.out.printf("run %d: %s%n", run, batches);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("kind", "jev-header-external");
        report.put("plan", "docs/iron-ore-rag/jev-header-plan-2026-10-12.md §8");
        report.put("created_at", LocalDateTime.now().toString());
        report.put("manifest_sha256", TableStructureOfflineReport.sha256(Files.readAllBytes(MANIFEST)));
        report.put("model_requested", client.model());
        report.put("models_answered", client.modelsAnswered());
        report.put("settings", Map.of("confident_header", settings.getConfidentHeader(),
                "confident_no_header", settings.getConfidentNoHeader(), "max_rows", settings.getMaxRows(),
                "max_cell_chars", settings.getMaxCellChars(), "threads", THREADS));
        report.put("probes", probeCounts(probes));
        report.put("unmatched", unmatched);
        report.put("gates", gates(runs));
        report.put("runs", runs);
        report.put("stability", stability(probes, decisionsByRun));
        report.put("probe_details", details(probes, decisionsByRun));

        Files.createDirectories(TableStructureOfflineReport.RUNS);
        Path out = TableStructureOfflineReport.RUNS.resolve("jev-header-external-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + ".json");
        JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        System.out.println("jev header external report: " + out);
        System.out.println("gates: " + report.get("gates"));
        assertEquals(RUNS, runs.size());
    }

    // ---------------------------------------------------------------- 候选

    /**
     * 一个待判定的行
     *
     * @param batch   "strudel" 或 "tableqa"
     * @param dataset troy / saus / cius / deex / tableqa-with-header / tableqa-without-header
     * @param key     批次加发给 Jev 的状态，同一批里相同的输入只算一次
     * @param row     待判定行，从 1 起
     */
    private record Probe(String batch, String dataset, String key, String source, int row, boolean label,
                         HeaderJudge.Candidate candidate) {
    }

    private static List<Probe> collectStrudel(String dataset, Path file, JevHeaderJudge jev, Set<String> seen,
                                              List<Map<String, Object>> unmatched) throws Exception {
        List<Probe> probes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode record = JSON.readTree(line);
                String source = record.get("file_name").asText() + "#" + record.get("table_id").asText();
                JsonNode labels = record.get("line_annotation");
                try (XSSFWorkbook book = new XSSFWorkbook()) {
                    Sheet sheet = book.createSheet(WorkbookUtil.createSafeSheetName(record.get("table_id").asText()));
                    JsonNode rows = record.get("table_array");
                    for (int r = 0; r < rows.size(); r++) {
                        List<Object> values = new ArrayList<>();
                        rows.get(r).forEach(value -> values.add(value.isNull() ? "" : value.asText()));
                        put(sheet, r, values);
                    }
                    for (HeaderJudge.Candidate candidate : ask(sheet)) {
                        JsonNode label = labels.get(candidate.firstRow());
                        if (label == null || label.isNull()) {
                            unmatched.add(Map.of("dataset", dataset, "source", source, "row", candidate.firstRow() + 1));
                            continue;
                        }
                        String key = "strudel\n" + key(jev, candidate);
                        if (seen.add(key)) {
                            probes.add(new Probe("strudel", dataset, key, source, candidate.firstRow() + 1,
                                    "header".equals(label.asText()), candidate));
                        }
                    }
                }
            }
        }
        return probes;
    }

    /**
     * 每张表写两个 sheet：标题（非空时）+ 表头 + 前 20 行数据，判定行应是表头；去掉表头的同一张表，判定行应是首个数据行
     */
    private static List<Probe> collectTableQa(Path file, JevHeaderJudge jev, Set<String> seen,
                                              List<Map<String, Object>> unmatched) throws Exception {
        List<Probe> probes = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode table = JSON.readTree(line);
            JsonNode header = table.get("header");
            if (header.size() < 2) {
                continue;
            }
            String title = table.get("title").asText().strip();
            List<List<Object>> data = new ArrayList<>();
            for (JsonNode row : table.get("rows")) {
                if (data.size() == TABLEQA_DATA_ROWS) {
                    break;
                }
                List<Object> values = new ArrayList<>();
                row.forEach(value -> values.add(value.isNumber() ? (Object) value.asDouble()
                        : value.isNull() ? "" : value.asText()));
                data.add(values);
            }
            if (data.isEmpty()) {
                continue;
            }
            for (boolean withHeader : new boolean[]{true, false}) {
                try (XSSFWorkbook book = new XSSFWorkbook()) {
                    Sheet sheet = book.createSheet("Sheet1");
                    int r = 0;
                    if (!title.isEmpty()) {
                        put(sheet, r++, List.of(title));
                    }
                    int expected = r;
                    if (withHeader) {
                        List<Object> names = new ArrayList<>();
                        header.forEach(name -> names.add(name.asText()));
                        put(sheet, r++, names);
                    }
                    for (List<Object> values : data) {
                        put(sheet, r++, values);
                    }
                    String dataset = withHeader ? "tableqa-with-header" : "tableqa-without-header";
                    String source = table.get("id").asText();
                    boolean found = false;
                    for (HeaderJudge.Candidate candidate : ask(sheet)) {
                        if (candidate.firstRow() != expected) {
                            unmatched.add(Map.of("dataset", dataset, "source", source, "row", candidate.firstRow() + 1));
                            continue;
                        }
                        found = true;
                        String key = "tableqa\n" + key(jev, candidate);
                        if (seen.add(key)) {
                            probes.add(new Probe("tableqa", dataset, key, source, expected + 1, withHeader, candidate));
                        }
                    }
                    if (!found) {
                        unmatched.add(Map.of("dataset", dataset, "source", source, "row", expected + 1,
                                "reason", "版面分析没有就这一行判定"));
                    }
                }
            }
        }
        return probes;
    }

    /**
     * 用规则判定把 sheet 过一遍版面分析，记下实际交给判定的行
     */
    private static List<HeaderJudge.Candidate> ask(Sheet sheet) {
        List<HeaderJudge.Candidate> asked = new ArrayList<>();
        ExcelLayoutAnalyzer.analyze(sheet, new DataFormatter(),
                sheet.getWorkbook().getCreationHelper().createFormulaEvaluator(), 1, candidate -> {
                    asked.add(candidate);
                    return RuleHeaderJudge.INSTANCE.judge(candidate);
                });
        return asked;
    }

    private static void put(Sheet sheet, int rowIndex, List<?> values) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.size(); c++) {
            Object value = values.get(c);
            if (value instanceof Double number) {
                row.createCell(c).setCellValue(number);
            } else if (value != null && !value.toString().isEmpty()) {
                row.createCell(c).setCellValue(value.toString());
            }
        }
    }

    private static String key(JevHeaderJudge jev, HeaderJudge.Candidate candidate) {
        try {
            return JSON.writeValueAsString(jev.state(candidate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 判定

    private static Map<String, HeaderJudge.Decision> judgeAll(List<Probe> probes, JevHeaderJudge jev, int run)
            throws Exception {
        Map<String, HeaderJudge.Decision> decisions = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Probe probe : probes) {
                futures.add(pool.submit(() -> {
                    decisions.put(probe.key(), jev.judge(probe.candidate()));
                    int count = done.incrementAndGet();
                    if (count % 500 == 0) {
                        System.out.printf("run %d: %d/%d%n", run, count, probes.size());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        return decisions;
    }

    // ---------------------------------------------------------------- 统计

    private static Map<String, Object> metrics(List<Probe> probes, Map<String, HeaderJudge.Decision> decisions) {
        int rule = 0;
        int jevOnly = 0;
        int hybrid = 0;
        int confident = 0;
        int confidentCorrect = 0;
        int unsure = 0;
        int failed = 0;
        for (Probe probe : probes) {
            boolean ruled = RuleHeaderJudge.INSTANCE.judge(probe.candidate()).header();
            HeaderJudge.Decision decision = decisions.get(probe.key());
            rule += ruled == probe.label() ? 1 : 0;
            boolean alone = decision.probability() == null ? ruled : decision.probability() >= 0.5;
            jevOnly += alone == probe.label() ? 1 : 0;
            hybrid += decision.header() == probe.label() ? 1 : 0;
            switch (decision.source()) {
                case JEV -> {
                    confident++;
                    confidentCorrect += decision.header() == probe.label() ? 1 : 0;
                }
                case JEV_UNSURE -> unsure++;
                case JEV_FAILED -> failed++;
                default -> {
                }
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("n", probes.size());
        metrics.put("with_header", probes.stream().filter(Probe::label).count());
        metrics.put("rule_correct", rule);
        metrics.put("jev_only_correct", jevOnly);
        metrics.put("hybrid_correct", hybrid);
        metrics.put("rule_accuracy", ratio(rule, probes.size()));
        metrics.put("jev_only_accuracy", ratio(jevOnly, probes.size()));
        metrics.put("hybrid_accuracy", ratio(hybrid, probes.size()));
        metrics.put("confident", confident);
        metrics.put("confident_accuracy", ratio(confidentCorrect, confident));
        metrics.put("unsure", unsure);
        metrics.put("failed", failed);
        return metrics;
    }

    /**
     * 计划 §8：两批各自，混合判定 3 次都不低于规则，才保持默认 jev
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> gates(List<Map<String, Object>> runs) {
        Map<String, Object> gates = new LinkedHashMap<>();
        boolean keep = true;
        for (String batch : List.of("strudel", "tableqa")) {
            List<Integer> hybrid = new ArrayList<>();
            int rule = 0;
            for (Map<String, Object> run : runs) {
                Map<String, Object> metrics = (Map<String, Object>) ((Map<String, Object>) run.get("batches")).get(batch);
                hybrid.add((Integer) metrics.get("hybrid_correct"));
                rule = (Integer) metrics.get("rule_correct");
            }
            final int ruleCorrect = rule;
            boolean neverBelow = hybrid.stream().allMatch(correct -> correct >= ruleCorrect);
            keep &= neverBelow;
            gates.put(batch, Map.of("rule_correct", rule, "hybrid_correct_by_run", hybrid, "never_below_rule", neverBelow));
        }
        gates.put("keep_jev_default", keep);
        return gates;
    }

    private static Map<String, Object> probeCounts(List<Probe> probes) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String dataset : probes.stream().map(Probe::dataset).distinct().toList()) {
            List<Probe> subset = probes.stream().filter(p -> p.dataset().equals(dataset)).toList();
            counts.put(dataset, Map.of("n", subset.size(), "with_header", subset.stream().filter(Probe::label).count()));
        }
        return counts;
    }

    private static Map<String, Object> stability(List<Probe> probes, List<Map<String, HeaderJudge.Decision>> runs) {
        int flipped = 0;
        double maxSpread = 0;
        for (Probe probe : probes) {
            Set<Boolean> decisions = new LinkedHashSet<>();
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Map<String, HeaderJudge.Decision> run : runs) {
                HeaderJudge.Decision decision = run.get(probe.key());
                decisions.add(decision.header());
                if (decision.probability() != null) {
                    min = Math.min(min, decision.probability());
                    max = Math.max(max, decision.probability());
                }
            }
            flipped += decisions.size() > 1 ? 1 : 0;
            if (max >= min) {
                maxSpread = Math.max(maxSpread, max - min);
            }
        }
        return Map.of("probes_with_flipped_hybrid_decision", flipped,
                "max_probability_spread", Math.round(maxSpread * 10000) / 10000.0);
    }

    private static List<Map<String, Object>> details(List<Probe> probes, List<Map<String, HeaderJudge.Decision>> runs) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (Probe probe : probes) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("dataset", probe.dataset());
            detail.put("source", probe.source());
            detail.put("row", probe.row());
            detail.put("first_row", preview(probe.candidate()));
            detail.put("label_header", probe.label());
            detail.put("rule_header", RuleHeaderJudge.INSTANCE.judge(probe.candidate()).header());
            detail.put("p_header", runs.stream().map(run -> run.get(probe.key()).probability()).toList());
            detail.put("hybrid_header", runs.stream().map(run -> run.get(probe.key()).header()).toList());
            details.add(detail);
        }
        return details;
    }

    private static String preview(HeaderJudge.Candidate candidate) {
        String text = String.join(" | ", candidate.firstRowCells());
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 10000.0 / total) / 10000.0;
    }

    /**
     * 并发安全地记下调用次数、失败、输入 token、耗时与实际作答的模型版本
     */
    private static final class MeteredJevClient extends JevClient {

        private final ConcurrentLinkedQueue<Long> elapsedMillis = new ConcurrentLinkedQueue<>();
        private final Set<String> modelsAnswered = ConcurrentHashMap.newKeySet();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicInteger failures = new AtomicInteger();

        MeteredJevClient(JevProperties properties) {
            super(new OkHttpClient(), properties);
        }

        @Override
        public JevResult systemOne(Object state, Map<String, JevQuestion> questions) {
            long started = System.nanoTime();
            try {
                JevResult result = super.systemOne(state, questions);
                inputTokens.addAndGet(result.inputTokens());
                modelsAnswered.add(result.model());
                return result;
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                throw e;
            } finally {
                elapsedMillis.add((System.nanoTime() - started) / 1_000_000);
            }
        }

        Map<String, Object> snapshot() {
            List<Long> sorted = elapsedMillis.stream().sorted().toList();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("calls", sorted.size());
            snapshot.put("failures", failures.get());
            snapshot.put("input_tokens", inputTokens.get());
            snapshot.put("elapsed_ms_p50", sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2));
            snapshot.put("elapsed_ms_p95", sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95)));
            snapshot.put("elapsed_ms_max", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
            return snapshot;
        }

        List<String> modelsAnswered() {
            return List.copyOf(modelsAnswered);
        }

        void reset() {
            elapsedMillis.clear();
            inputTokens.set(0);
            failures.set(0);
        }
    }
}
