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
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * jev-header 计划 §3 的评测：只读本地两版调研表与冻结的标准答案，调 Jev，结果写进
 * {@code local-data/kq-eval/runs/jev-header-*.json}
 * <p>
 * 类名不以 Test 结尾，常规测试不跑；显式运行（环境里要有 {@code TYPESAFE_API_KEY}）：
 * {@code ./mvnw -o -pl bootstrap -am test -Dtest=JevHeaderJudgeReport -Dsurefire.failIfNoSpecifiedTests=false}。
 * 缺文件或缺密钥就跳过；密钥无效时第一次调用就失败，不写报告。
 * <ul>
 *   <li>原表组 G：版面分析在两版调研表上实际交给判定的行，按标准答案打标签；两版判定输入相同的只算一次</li>
 *   <li>去表头组 R：G 里有表头的候选去掉第一行，原来的首个数据行成为待判定行，标签"无表头"</li>
 *   <li>Jev 跑 3 次：每次用混合判定把两版完整过一遍版面分析、与标准答案对照，再判 R</li>
 * </ul>
 * 报告给出计划 §4 两条门槛是否通过，不改任何默认配置
 */
class JevHeaderJudgeReport {

    private static final int RUNS = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writeReport() throws Exception {
        assumeTrue(Files.isRegularFile(TableStructureOfflineReport.GOLDEN), "缺标准答案，跳过");
        JsonNode golden = JSON.readTree(TableStructureOfflineReport.GOLDEN.toFile());
        List<Path> workbooks = new ArrayList<>();
        golden.get("workbooks").forEach(node -> workbooks.add(TableStructureOfflineReport.REPO.resolve(node.asText())));
        assumeTrue(workbooks.stream().allMatch(Files::isRegularFile), "缺调研表，跳过");
        JevProperties properties = new JevProperties();
        // 只在用本地桩自测报告代码时设；正式运行用默认的 https://api.typesafe.ai
        properties.setBaseUrl(System.getProperty("jev.base-url", properties.getBaseUrl()));
        MeteredJevClient client = new MeteredJevClient(properties);
        assumeTrue(client.isConfigured(), "缺 TYPESAFE_API_KEY，跳过");

        ExcelLayoutProperties.Jev settings = new ExcelLayoutProperties.Jev();
        JevHeaderJudge jev = new JevHeaderJudge(client, settings, RuleHeaderJudge.INSTANCE);
        List<Map<String, Object>> unmatched = new ArrayList<>();
        List<Probe> g = collectG(workbooks, golden, jev, unmatched);
        List<Probe> r = deriveR(g, jev);
        List<Probe> all = new ArrayList<>(g);
        all.addAll(r);

        // 密钥无效、服务不可达时在这里失败，不写一份全是"调用失败"的报告
        client.systemOne(jev.state(g.get(0).candidate()), Map.of(JevHeaderJudge.QUESTION_ID, JevHeaderJudge.QUESTION));
        client.reset();

        List<Map<String, HeaderJudge.Decision>> decisionsByRun = new ArrayList<>();
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) {
            Map<String, HeaderJudge.Decision> decisions = new LinkedHashMap<>();
            List<Map<String, Object>> structure = new ArrayList<>();
            for (Path workbook : workbooks) {
                HeaderJudge recording = candidate -> {
                    HeaderJudge.Decision decision = jev.judge(candidate);
                    decisions.putIfAbsent(key(jev, candidate), decision);
                    return decision;
                };
                structure.add(TableStructureOfflineReport.structureCheck(workbook, golden, recording));
            }
            for (Probe probe : r) {
                decisions.put(probe.key(), jev.judge(probe.candidate()));
            }
            for (Probe probe : all) {
                if (!decisions.containsKey(probe.key())) {
                    throw new IllegalStateException("G 候选在完整版面分析里没有被判定: " + probe.sheet() + " 第 " + probe.row() + " 行");
                }
            }
            decisionsByRun.add(decisions);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run", run);
            result.put("structure_ok", structureOk(structure));
            result.put("structure", structure);
            result.put("G", metrics(g, decisions));
            result.put("R", metrics(r, decisions));
            result.put("G+R", metrics(all, decisions));
            result.put("jev_calls", client.snapshot());
            client.reset();
            runs.add(result);
            System.out.printf("run %d: structure %s, G+R %s%n", run, result.get("structure_ok"), result.get("G+R"));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("kind", "jev-header");
        report.put("plan", "docs/iron-ore-rag/jev-header-plan-2026-10-12.md");
        report.put("created_at", LocalDateTime.now().toString());
        report.put("golden_sha256", TableStructureOfflineReport.sha256(Files.readAllBytes(TableStructureOfflineReport.GOLDEN)));
        report.put("model_requested", client.model());
        report.put("models_answered", client.modelsAnswered());
        report.put("settings", Map.of("confident_header", settings.getConfidentHeader(),
                "confident_no_header", settings.getConfidentNoHeader(), "max_rows", settings.getMaxRows(),
                "max_cell_chars", settings.getMaxCellChars()));
        report.put("question", Map.of("instructions", JevHeaderJudge.QUESTION.instructions(),
                "true", JevHeaderJudge.QUESTION.whenTrue(), "false", JevHeaderJudge.QUESTION.whenFalse()));
        report.put("probes", Map.of("G", g.size(), "G_without_header", g.stream().filter(p -> !p.label()).count(),
                "R", r.size(), "unmatched", unmatched));
        report.put("gates", gates(runs, all));
        report.put("runs", runs);
        report.put("stability", stability(all, decisionsByRun));
        report.put("probe_details", details(all, decisionsByRun));

        Files.createDirectories(TableStructureOfflineReport.RUNS);
        Path out = TableStructureOfflineReport.RUNS.resolve("jev-header-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + ".json");
        JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        System.out.println("jev header report: " + out);
        System.out.println("gates: " + report.get("gates"));
        assertEquals(RUNS, runs.size());
    }

    // ---------------------------------------------------------------- 候选

    /**
     * 一个待判定的行
     *
     * @param group "G" 或 "R"
     * @param key   sheet 名加发给 Jev 的状态，两版相同的输入只算一次
     * @param row   待判定行，Excel 行号（从 1 起）
     */
    private record Probe(String group, String key, String workbook, String sheet, int row, boolean label,
                         HeaderJudge.Candidate candidate) {
    }

    private static List<Probe> collectG(List<Path> workbooks, JsonNode golden, JevHeaderJudge jev,
                                        List<Map<String, Object>> unmatched) throws Exception {
        List<Probe> probes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path workbook : workbooks) {
            try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
                DataFormatter formatter = new DataFormatter();
                FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();
                for (JsonNode sheetNode : golden.get("sheets")) {
                    String sheet = sheetNode.get("sheet").asText();
                    List<HeaderJudge.Candidate> asked = new ArrayList<>();
                    ExcelLayoutAnalyzer.analyze(book.getSheet(sheet), formatter, evaluator, 1, candidate -> {
                        asked.add(candidate);
                        return RuleHeaderJudge.INSTANCE.judge(candidate);
                    });
                    for (HeaderJudge.Candidate candidate : asked) {
                        Boolean label = label(sheetNode, candidate.firstRow() + 1);
                        String key = key(jev, candidate);
                        if (label == null) {
                            unmatched.add(Map.of("workbook", workbook.getFileName().toString(), "sheet", sheet,
                                    "row", candidate.firstRow() + 1, "first_row", preview(candidate)));
                        } else if (seen.add(key)) {
                            probes.add(new Probe("G", key, workbook.getFileName().toString(), sheet,
                                    candidate.firstRow() + 1, label, candidate));
                        }
                    }
                }
            }
        }
        return probes;
    }

    /**
     * 标准答案里这一行是某张表的表头为 true，是无表头表的首个数据行为 false，都不是为 null
     */
    private static Boolean label(JsonNode sheetNode, int excelRow) {
        for (JsonNode table : sheetNode.get("tables")) {
            JsonNode header = table.get("header_row");
            if (!header.isNull() && header.asInt() == excelRow) {
                return true;
            }
            if (header.isNull() && table.get("first_data_row").asInt() == excelRow) {
                return false;
            }
        }
        return null;
    }

    private static List<Probe> deriveR(List<Probe> g, JevHeaderJudge jev) {
        List<Probe> probes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Probe probe : g) {
            HeaderJudge.Candidate candidate = probe.candidate();
            if (!probe.label() || candidate.rows().size() < 2) {
                continue;
            }
            HeaderJudge.Candidate dropped = new HeaderJudge.Candidate(candidate.sheet(), candidate.title(),
                    candidate.rowIndexes().subList(1, candidate.rowIndexes().size()),
                    candidate.rows().subList(1, candidate.rows().size()));
            String key = key(jev, dropped);
            if (seen.add(key)) {
                probes.add(new Probe("R", key, probe.workbook(), probe.sheet(), dropped.firstRow() + 1, false, dropped));
            }
        }
        return probes;
    }

    private static String key(JevHeaderJudge jev, HeaderJudge.Candidate candidate) {
        try {
            return JSON.writeValueAsString(jev.state(candidate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String preview(HeaderJudge.Candidate candidate) {
        String text = String.join(" | ", candidate.firstRowCells());
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    // ---------------------------------------------------------------- 统计

    /**
     * Jev 单独判：P ≥ 0.5 即表头；调用失败时没有答案，按规则（失败次数另报）
     */
    private static boolean jevOnly(HeaderJudge.Decision decision, boolean rule) {
        return decision.probability() == null ? rule : decision.probability() >= 0.5;
    }

    private static Map<String, Object> metrics(List<Probe> probes, Map<String, HeaderJudge.Decision> decisions) {
        int rule = 0;
        int jevOnly = 0;
        int hybrid = 0;
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (HeaderJudge.Source source : HeaderJudge.Source.values()) {
            sources.put(source.name(), 0);
        }
        for (Probe probe : probes) {
            boolean ruled = RuleHeaderJudge.INSTANCE.judge(probe.candidate()).header();
            HeaderJudge.Decision decision = decisions.get(probe.key());
            rule += ruled == probe.label() ? 1 : 0;
            jevOnly += jevOnly(decision, ruled) == probe.label() ? 1 : 0;
            hybrid += decision.header() == probe.label() ? 1 : 0;
            sources.merge(decision.source().name(), 1, Integer::sum);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("n", probes.size());
        metrics.put("rule_correct", rule);
        metrics.put("jev_only_correct", jevOnly);
        metrics.put("hybrid_correct", hybrid);
        metrics.put("rule_accuracy", ratio(rule, probes.size()));
        metrics.put("jev_only_accuracy", ratio(jevOnly, probes.size()));
        metrics.put("hybrid_accuracy", ratio(hybrid, probes.size()));
        metrics.put("sources", sources);
        return metrics;
    }

    private static boolean structureOk(List<Map<String, Object>> structure) {
        return structure.stream().allMatch(book -> book.get("tables_total").equals(book.get("tables_matched"))
                && book.get("text_cells_total").equals(book.get("text_cells_matched"))
                && ((List<?>) book.get("extra_text")).isEmpty());
    }

    /**
     * 计划 §4：不退化（3 次版面都与标准答案一致）且有提升（混合 3 次都不低于规则、平均严格高于规则）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> gates(List<Map<String, Object>> runs, List<Probe> all) {
        boolean noRegression = runs.stream().allMatch(run -> Boolean.TRUE.equals(run.get("structure_ok")));
        List<Integer> hybrid = runs.stream()
                .map(run -> (Integer) ((Map<String, Object>) run.get("G+R")).get("hybrid_correct")).toList();
        int rule = (Integer) ((Map<String, Object>) runs.get(0).get("G+R")).get("rule_correct");
        boolean neverBelow = hybrid.stream().allMatch(correct -> correct >= rule);
        double mean = hybrid.stream().mapToInt(Integer::intValue).average().orElse(0);
        boolean improved = neverBelow && mean > rule;
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("no_regression", noRegression);
        gates.put("improved", improved);
        gates.put("rule_correct", rule);
        gates.put("hybrid_correct_by_run", hybrid);
        gates.put("hybrid_correct_mean", mean);
        gates.put("n", all.size());
        gates.put("adopt_jev_by_default", noRegression && improved);
        return gates;
    }

    private static Map<String, Object> stability(List<Probe> probes, List<Map<String, HeaderJudge.Decision>> runs) {
        int flipped = 0;
        double maxSpread = 0;
        List<String> flips = new ArrayList<>();
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
            if (decisions.size() > 1) {
                flipped++;
                flips.add(probe.group() + " " + probe.sheet() + " 第 " + probe.row() + " 行");
            }
            if (max >= min) {
                maxSpread = Math.max(maxSpread, max - min);
            }
        }
        Map<String, Object> stability = new LinkedHashMap<>();
        stability.put("probes_with_flipped_hybrid_decision", flipped);
        stability.put("flipped", flips);
        stability.put("max_probability_spread", Math.round(maxSpread * 10000) / 10000.0);
        return stability;
    }

    private static List<Map<String, Object>> details(List<Probe> probes, List<Map<String, HeaderJudge.Decision>> runs) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (Probe probe : probes) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("group", probe.group());
            detail.put("sheet", probe.sheet());
            detail.put("row", probe.row());
            detail.put("title", probe.candidate().title());
            detail.put("first_row", preview(probe.candidate()));
            detail.put("label_header", probe.label());
            detail.put("rule_header", RuleHeaderJudge.INSTANCE.judge(probe.candidate()).header());
            detail.put("p_header", runs.stream().map(run -> run.get(probe.key()).probability()).toList());
            detail.put("hybrid_header", runs.stream().map(run -> run.get(probe.key()).header()).toList());
            detail.put("source", runs.stream().map(run -> run.get(probe.key()).source().name()).toList());
            details.add(detail);
        }
        return details;
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 10000.0 / total) / 10000.0;
    }

    /**
     * 记下每次调用的耗时、输入 token、失败与实际作答的模型版本
     */
    private static final class MeteredJevClient extends JevClient {

        private final List<Long> elapsedMillis = new ArrayList<>();
        private final Set<String> modelsAnswered = new LinkedHashSet<>();
        private long inputTokens;
        private int failures;

        MeteredJevClient(JevProperties properties) {
            super(new OkHttpClient(), properties);
        }

        @Override
        public JevResult systemOne(Object state, Map<String, JevQuestion> questions) {
            long started = System.nanoTime();
            try {
                JevResult result = super.systemOne(state, questions);
                inputTokens += result.inputTokens();
                modelsAnswered.add(result.model());
                return result;
            } catch (RuntimeException e) {
                failures++;
                throw e;
            } finally {
                elapsedMillis.add((System.nanoTime() - started) / 1_000_000);
            }
        }

        Map<String, Object> snapshot() {
            List<Long> sorted = elapsedMillis.stream().sorted().toList();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("calls", sorted.size());
            snapshot.put("failures", failures);
            snapshot.put("input_tokens", inputTokens);
            snapshot.put("elapsed_ms_p50", sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2));
            snapshot.put("elapsed_ms_max", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
            snapshot.put("elapsed_ms_total", sorted.stream().mapToLong(Long::longValue).sum());
            return snapshot;
        }

        List<String> modelsAnswered() {
            return List.copyOf(modelsAnswered);
        }

        void reset() {
            elapsedMillis.clear();
            inputTokens = 0;
            failures = 0;
        }
    }
}
