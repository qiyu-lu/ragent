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
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkContext;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunkingProperties;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * table-structure 计划 §4.2 的离线检查：只读本地的两版调研表与标准答案，不调模型，结果写进
 * {@code local-data/kq-eval/runs/table-structure-offline-*.json}
 * <p>
 * 类名不以 Test 结尾，常规测试不跑；显式运行：
 * {@code ./mvnw -o -pl bootstrap -am test -Dtest=TableStructureOfflineReport -Dsurefire.failIfNoSpecifiedTests=false}。
 * 缺文件（local-data 不进 Git）就跳过。门槛：25 张表与 52 个文字格与标准答案一致、表内格子不当段落输出、
 * 按内容定界的不变式（改动之后第一个内容切点以后的块全部不变）100% 成立；失效块数、块数只报告
 */
class TableStructureOfflineReport {

    private static final Path REPO = locateRepo();
    private static final Path GOLDEN = REPO.resolve("local-data/kq-eval/table-layout/survey-layout-golden.json");
    private static final Path RUNS = REPO.resolve("local-data/kq-eval/runs");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final ChunkBudget BUDGET = ChunkBudget.defaults();

    @Test
    void writeReport() throws Exception {
        assumeTrue(Files.isRegularFile(GOLDEN), "缺标准答案，跳过：" + GOLDEN);
        JsonNode golden = new ObjectMapper().readTree(GOLDEN.toFile());
        List<Path> workbooks = new ArrayList<>();
        golden.get("workbooks").forEach(node -> workbooks.add(REPO.resolve(node.asText())));
        assumeTrue(workbooks.stream().allMatch(Files::isRegularFile), "缺调研表，跳过");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("kind", "table-structure-offline");
        report.put("created_at", LocalDateTime.now().toString());
        report.put("golden_sha256", sha256(Files.readAllBytes(GOLDEN)));
        report.put("budget", Map.of("maxChars", BUDGET.maxChars(), "rowsPerChunk", BUDGET.rowsPerChunk()));

        List<Map<String, Object>> structure = new ArrayList<>();
        List<Map<String, Object>> chunking = new ArrayList<>();
        for (Path workbook : workbooks) {
            structure.add(structureCheck(workbook, golden));
            chunking.add(chunkStats(workbook));
        }
        report.put("structure", structure);
        report.put("chunking", chunking);
        Map<String, Object> mutations = mutationExperiments(workbooks.get(workbooks.size() - 1));
        report.put("mutations", mutations);

        Files.createDirectories(RUNS);
        Path out = RUNS.resolve("table-structure-offline-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + ".json");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        System.out.println("offline report: " + out);

        for (Map<String, Object> book : structure) {
            System.out.printf("%s tables %s/%s text %s/%s extra-text %s%n", book.get("workbook"),
                    book.get("tables_matched"), book.get("tables_total"), book.get("text_cells_matched"),
                    book.get("text_cells_total"), ((List<?>) book.get("extra_text")).size());
            assertEquals(book.get("tables_total"), book.get("tables_matched"), "表格与标准答案不一致：" + book.get("table_mismatches"));
            assertEquals(book.get("text_cells_total"), book.get("text_cells_matched"), "文字格与标准答案不一致");
            assertTrue(((List<?>) book.get("extra_text")).isEmpty(), "表内格子被当段落输出：" + book.get("extra_text"));
        }
        System.out.println("mutations: " + mutations.get("summary"));
        assertEquals(0, ((Number) ((Map<?, ?>) mutations.get("summary")).get("invariant_violations")).intValue(),
                "按内容定界不变式不成立");
    }

    // ---------------------------------------------------------------- 结构：与标准答案对照

    private static Map<String, Object> structureCheck(Path workbook, JsonNode golden) throws Exception {
        int tablesTotal = 0;
        int tablesMatched = 0;
        int textTotal = 0;
        int textMatched = 0;
        List<String> mismatches = new ArrayList<>();
        List<String> extraText = new ArrayList<>();
        try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();
            for (JsonNode sheetNode : golden.get("sheets")) {
                String sheet = sheetNode.get("sheet").asText();
                List<ExcelLayoutAnalyzer.Table> tables = new ArrayList<>();
                Set<String> texts = new LinkedHashSet<>();
                for (ExcelLayoutAnalyzer.Region region
                        : ExcelLayoutAnalyzer.analyze(book.getSheet(sheet), formatter, evaluator, 1)) {
                    for (ExcelLayoutAnalyzer.Item item : region.items()) {
                        if (item instanceof ExcelLayoutAnalyzer.Table table) {
                            tables.add(table);
                            if (table.titleRange() != null) {
                                texts.add(topLeft(table.titleRange()));
                            }
                        } else if (item instanceof ExcelLayoutAnalyzer.Text text) {
                            texts.add(topLeft(text.range()));
                        }
                    }
                }
                for (JsonNode expected : sheetNode.get("tables")) {
                    tablesTotal++;
                    if (tables.stream().anyMatch(table -> matches(expected, table))) {
                        tablesMatched++;
                    } else {
                        mismatches.add(sheet + " " + expected);
                    }
                }
                Set<String> expectedText = new LinkedHashSet<>();
                sheetNode.get("text_cells").forEach(cell -> expectedText.add(cell.asText()));
                for (String cell : expectedText) {
                    textTotal++;
                    if (texts.contains(cell)) {
                        textMatched++;
                    }
                }
                texts.stream().filter(cell -> !expectedText.contains(cell)).forEach(cell -> extraText.add(sheet + "!" + cell));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workbook", workbook.getFileName().toString());
        result.put("tables_total", tablesTotal);
        result.put("tables_matched", tablesMatched);
        result.put("table_mismatches", mismatches);
        result.put("text_cells_total", textTotal);
        result.put("text_cells_matched", textMatched);
        result.put("extra_text", extraText);
        return result;
    }

    private static boolean matches(JsonNode expected, ExcelLayoutAnalyzer.Table table) {
        Integer headerRow = expected.get("header_row").isNull() ? null : expected.get("header_row").asInt();
        List<String> header = new ArrayList<>();
        if (!expected.get("header").isNull()) {
            expected.get("header").forEach(cell -> header.add(cell.asText()));
        }
        String title = expected.get("title").isNull() ? null : expected.get("title").asText();
        return Objects.equals(headerRow, table.headerRow() == null ? null : table.headerRow() + 1)
                && expected.get("first_data_row").asInt() == table.firstDataRow() + 1
                && expected.get("last_data_row").asInt() == table.lastDataRow() + 1
                && expected.get("first_col").asText().equals(CellReference.convertNumToColString(table.firstCol()))
                && expected.get("last_col").asText().equals(CellReference.convertNumToColString(table.lastCol()))
                && header.equals(table.headerCells())
                && Objects.equals(title, table.title());
    }

    private static String topLeft(String range) {
        return range.split(":")[0];
    }

    // ---------------------------------------------------------------- 块数统计

    private static Map<String, Object> chunkStats(Path workbook) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workbook", workbook.getFileName().toString());
        result.put("old", stats(chunk(parse(workbook, false), false)));
        result.put("layout", stats(chunk(parse(workbook, true), false)));
        result.put("layout_cdc", stats(chunk(parse(workbook, true), true)));
        return result;
    }

    private static Map<String, Object> stats(List<Chunk> chunks) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chunks", chunks.size());
        stats.put("mean_embedding_chars", Math.round(chunks.stream().mapToInt(c -> c.embeddingText().length()).average().orElse(0)));
        stats.put("max_content_chars", chunks.stream().mapToInt(c -> c.content().length()).max().orElse(0));
        stats.put("under_128_chars", chunks.stream().filter(c -> c.content().length() < 128).count());
        return stats;
    }

    // ---------------------------------------------------------------- 插、删、改一行

    private static Map<String, Object> mutationExperiments(Path workbook) throws Exception {
        List<Block> blocks = parse(workbook, true);
        List<Map<String, Object>> runs = new ArrayList<>();
        int violations = 0;
        int invariantChecks = 0;
        long[] missesGreedy = new long[1];
        long[] missesCdc = new long[1];
        int singleRowEdits = 0;
        long maxCdc = 0;
        List<Chunk> baseGreedy = chunk(blocks, false);
        List<Chunk> baseCdc = chunk(blocks, true);
        String sheet = null;
        int tableInSheet = 0;
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            if (block instanceof HeadingBlock heading && heading.level() == 1) {
                sheet = heading.text();
                tableInSheet = 0;
            }
            if (!(block instanceof TableBlock table)) {
                continue;
            }
            tableInSheet++;
            List<String> outline = outlineFor(blocks, index);
            ChunkContext ctx = ChunkContext.of(outline, BUDGET);
            if (cdcChunker().chunk(table, ctx).size() < 3) {
                continue;
            }
            int n = table.rows().size();
            Map<String, UnaryOperator<List<List<String>>>> cases = new LinkedHashMap<>();
            for (int position : new int[]{n / 4, n / 2, 3 * n / 4}) {
                cases.put("insert@" + position, rows -> insertCopy(rows, position));
            }
            for (int position : new int[]{n / 4, n / 2, 3 * n / 4}) {
                cases.put("delete@" + position, rows -> delete(rows, position));
            }
            cases.put("edit+150@" + n / 2, rows -> editLonger(rows, n / 2));
            cases.put("append", rows -> insertCopy(rows, rows.size()));
            for (Map.Entry<String, UnaryOperator<List<List<String>>>> mutation : cases.entrySet()) {
                TableBlock mutated = mutate(table, mutation.getValue());
                List<Block> changed = new ArrayList<>(blocks);
                changed.set(index, mutated);
                long greedy = misses(baseGreedy, chunk(changed, false));
                long cdc = misses(baseCdc, chunk(changed, true));
                Boolean invariant = invariantHolds(table, mutated, mutation.getKey(), ctx);
                if (invariant != null) {
                    invariantChecks++;
                    if (!invariant) {
                        violations++;
                    }
                }
                if (!mutation.getKey().equals("append")) {
                    singleRowEdits++;
                    missesGreedy[0] += greedy;
                    missesCdc[0] += cdc;
                    maxCdc = Math.max(maxCdc, cdc);
                }
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("sheet", sheet);
                run.put("table", tableInSheet);
                run.put("rows", n);
                run.put("mutation", mutation.getKey());
                run.put("misses_greedy", greedy);
                run.put("misses_cdc", cdc);
                run.put("invariant", invariant);
                runs.add(run);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workbook", workbook.getFileName().toString());
        summary.put("single_row_edits", singleRowEdits);
        summary.put("mean_misses_greedy", singleRowEdits == 0 ? null : (double) missesGreedy[0] / singleRowEdits);
        summary.put("mean_misses_cdc", singleRowEdits == 0 ? null : (double) missesCdc[0] / singleRowEdits);
        summary.put("max_misses_cdc", maxCdc);
        summary.put("invariant_checks", invariantChecks);
        summary.put("invariant_violations", violations);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("runs", runs);
        return result;
    }

    /**
     * 不变式：改动之后、两版共有的行里第一个内容切点所在的块之后，按内容定界切出的块逐个相同；改动之后没有切点时不检查
     */
    private static Boolean invariantHolds(TableBlock before, TableBlock after, String mutation, ChunkContext ctx) {
        int position = Integer.parseInt(mutation.contains("@") ? mutation.substring(mutation.indexOf('@') + 1) : "-1");
        if (position < 0) {
            return null;
        }
        // 两版共有、改动之后的第一行在原表里的下标
        int firstShared = mutation.startsWith("insert") ? position : position + 1;
        String cutLine = null;
        for (int r = firstShared; r < before.rows().size(); r++) {
            String line = TableChunker.renderKeyValueRow(before.headers(), before.rows().get(r));
            if (TableChunker.isContentCut(line, ctx.budget().maxChars())) {
                cutLine = line;
                break;
            }
        }
        if (cutLine == null) {
            return null;
        }
        List<String> beforeBodies = bodies(cdcChunker().chunk(before, ctx));
        List<String> afterBodies = bodies(cdcChunker().chunk(after, ctx));
        int i = endsWith(beforeBodies, cutLine);
        int j = endsWith(afterBodies, cutLine);
        if (i < 0 || j < 0) {
            return false;
        }
        return beforeBodies.subList(i + 1, beforeBodies.size()).equals(afterBodies.subList(j + 1, afterBodies.size()));
    }

    private static int endsWith(List<String> bodies, String line) {
        for (int k = 0; k < bodies.size(); k++) {
            if (bodies.get(k).endsWith(line)) {
                return k;
            }
        }
        return -1;
    }

    private static List<String> bodies(List<ChunkDraft> drafts) {
        return drafts.stream().map(ChunkDraft::effectiveBody).toList();
    }

    private static List<String> outlineFor(List<Block> blocks, int index) {
        HeadingHandler handler = new HeadingHandler();
        HeadingHandler.Outline outline = HeadingHandler.Outline.EMPTY;
        for (int i = 0; i < index; i++) {
            if (blocks.get(i) instanceof HeadingBlock heading) {
                outline = handler.update(outline, heading);
            }
        }
        return outline.path();
    }

    private static TableBlock mutate(TableBlock table, UnaryOperator<List<List<String>>> mutation) {
        List<List<String>> rows = new ArrayList<>();
        table.rows().forEach(row -> rows.add(new ArrayList<>(row)));
        List<List<String>> changed = mutation.apply(rows);
        List<String> ranges = new ArrayList<>();
        for (int i = 0; i < changed.size(); i++) {
            ranges.add(table.rowCellRanges().get(Math.min(i, table.rowCellRanges().size() - 1)));
        }
        return new TableBlock(table.provenance(), table.headers(), changed, ranges, table.caption());
    }

    private static List<List<String>> insertCopy(List<List<String>> rows, int position) {
        List<String> copy = new ArrayList<>(rows.get(Math.min(Math.max(0, position - 1), rows.size() - 1)));
        for (int c = 0; c < copy.size(); c++) {
            if (!copy.get(c).isEmpty()) {
                copy.set(c, copy.get(c) + "（新增）");
                break;
            }
        }
        rows.add(position, copy);
        return rows;
    }

    private static List<List<String>> delete(List<List<String>> rows, int position) {
        rows.remove(position);
        return rows;
    }

    private static List<List<String>> editLonger(List<List<String>> rows, int position) {
        List<String> row = rows.get(position);
        for (int c = row.size() - 1; c >= 0; c--) {
            if (!row.get(c).isEmpty()) {
                row.set(c, row.get(c) + "补".repeat(150));
                break;
            }
        }
        return rows;
    }

    private static long misses(List<Chunk> before, List<Chunk> after) {
        Set<String> old = new LinkedHashSet<>();
        before.forEach(chunk -> old.add(sha256(chunk.embeddingText().getBytes(StandardCharsets.UTF_8))));
        return after.stream()
                .filter(chunk -> !old.contains(sha256(chunk.embeddingText().getBytes(StandardCharsets.UTF_8))))
                .count();
    }

    // ---------------------------------------------------------------- 解析与分块

    private static List<Block> parse(Path workbook, boolean layout) throws Exception {
        ExcelLayoutProperties properties = new ExcelLayoutProperties();
        properties.setEnabled(layout);
        ExcelDocumentParser parser = new ExcelDocumentParser(null, new ImageParseProperties(), properties);
        return parser.parseStructured(Files.readAllBytes(workbook), XLSX,
                Map.of(ExcelDocumentParser.OPT_SOURCE_FILE, workbook.getFileName().toString())).blocks();
    }

    private static List<Chunk> chunk(List<Block> blocks, boolean contentDefined) {
        TableChunkingProperties properties = new TableChunkingProperties();
        properties.setContentDefined(contentDefined);
        BlockAwareChunkerDispatcher dispatcher = new BlockAwareChunkerDispatcher(new HeadingHandler(),
                new ChunkPacker(), List.of(new HeadingChunker(), new ParagraphChunker(), new TableChunker(properties)));
        return dispatcher.dispatch(blocks, BUDGET);
    }

    private static TableChunker cdcChunker() {
        TableChunkingProperties properties = new TableChunkingProperties();
        properties.setContentDefined(true);
        return new TableChunker(properties);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path locateRepo() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("local-data"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }
}
