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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 表格规范化器（简单 key-val 版）
 * <p>
 * 把一个 POI Sheet 转为单个干净的 (headers, rows) 二维结构，只处理「规整单表」的通用清洗：
 * <ul>
 *   <li>合并单元格展开：表头区域横向展开以保留层级，数据区域只按行携带一次值，避免同值跨列膨胀</li>
 *   <li>合并续行折叠：仅折叠原始为空、由纵向合并展开形成的连续同值行</li>
 *   <li>多行表头展平：前 N 行合并成单行表头，列名用分隔符拼接（如 "财务|收入"）</li>
 *   <li>超链接保留：cell 文字外包 {@code [text](url)}</li>
 *   <li>公式回退：通过 {@link ExcelValueFormatter}</li>
 *   <li>全空行 / 尾部全空列跳过</li>
 * </ul>
 * <p>
 * <b>不再做</b>多表格区域切分、文档/section 标题识别、横向重复列折叠等版面启发式
 * 这类复杂版面的 Excel 应由上层路由到 MinerU 解析，POI 只负责一 sheet 一张规整表
 */
public final class ExcelTableNormalizer {

    /**
     * 多行表头展平时使用的分隔符
     */
    public static final String HEADER_SEPARATOR = "|";

    /**
     * 划删除线 cell 的包裹标记（软删除约定：用 GFM 删除线 {@code ~~值~~} 包裹原值，保留文本并显式标注）
     */
    private static final String STRIKETHROUGH_WRAP = "~~";

    private ExcelTableNormalizer() {
    }

    /**
     * 规范化结果
     *
     * @param headers 已展平的列名（长度等于有效列数）
     * @param rows    数据行（与 headers 对齐，全空行已跳过）
     */
    public record NormalizedTable(
            List<String> headers,
            List<List<String>> rows,
            List<String> rowCellRanges
    ) {
        public boolean isEmpty() {
            return headers.isEmpty() && rows.isEmpty();
        }

        static NormalizedTable empty() {
            return new NormalizedTable(List.of(), List.of(), List.of());
        }
    }

    /**
     * 规范化 sheet 为单张表：前 headerRows 行为表头，其余为数据行
     *
     * @param sheet      POI sheet
     * @param formatter  DataFormatter 实例（线程不安全，调用方持有）
     * @param evaluator  公式求值器，可空
     * @param headerRows 表头占用的行数，{@code >= 1}
     * @return 规范化结果；空 sheet 返回空表
     */
    public static NormalizedTable normalize(Sheet sheet,
                                            DataFormatter formatter,
                                            FormulaEvaluator evaluator,
                                            int headerRows) {
        if (headerRows < 1) {
            throw new IllegalArgumentException("headerRows must be >= 1, got " + headerRows);
        }

        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) {
            return NormalizedTable.empty();
        }

        int maxCol = computeMaxColumns(sheet, lastRowNum);
        if (maxCol == 0) {
            return NormalizedTable.empty();
        }

        // 步骤 1: 读取 sheet 到二维 grid（已应用 hyperlink wrap 与公式回退）
        String[][] grid = readGrid(sheet, lastRowNum, maxCol, formatter, evaluator);

        // 后续折叠只允许作用于原始空行；先于合并展开记录，避免误删用户主动录入的重复记录
        boolean[] rowsWithSourceValues = rowsWithValues(grid);

        int effectiveHeaderRows = Math.min(headerRows, lastRowNum + 1);

        // 步骤 2: 结构感知展开合并单元格（grid 上原地填充）
        expandMergedRegions(grid, sheet.getMergedRegions(), lastRowNum, maxCol, effectiveHeaderRows);

        // 步骤 3: 丢弃全空列（表头与数据全程为空的列，含中间与尾部）
        int[] cols = selectNonEmptyColumns(grid, 0, lastRowNum, maxCol);
        if (cols.length == 0) {
            return NormalizedTable.empty();
        }

        // 步骤 4: 前 headerRows 行展平为表头，其余收集为数据行
        List<String> headers = flattenHeaders(grid, 0, effectiveHeaderRows, cols);
        List<NormalizedRow> normalizedRows = effectiveHeaderRows <= lastRowNum
                ? collectDataRows(grid, effectiveHeaderRows, lastRowNum, cols, rowsWithSourceValues)
                : List.of();
        return new NormalizedTable(
                headers,
                normalizedRows.stream().map(NormalizedRow::values).toList(),
                normalizedRows.stream()
                        .map(row -> row.cellRange(cols[0], cols[cols.length - 1]))
                        .toList());
    }

    /**
     * 计算 sheet 内最大列数（跨所有行）
     */
    private static int computeMaxColumns(Sheet sheet, int lastRowNum) {
        int maxCol = 0;
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int lastCellNum = row.getLastCellNum();
            if (lastCellNum > maxCol) {
                maxCol = lastCellNum;
            }
        }
        return maxCol;
    }

    /**
     * 选出非全空列：返回在 [startRow, endRow] 至少有一个非空 cell 的列索引（升序）
     * <p>
     * 全空列（表头与数据全程为空）不携带信息一律丢弃，既裁尾部空列，也裁夹在数据列中间的空列；
     * 仅表头空但有数据的列保留（数据本身有意义）
     */
    private static int[] selectNonEmptyColumns(String[][] grid, int startRow, int endRow, int maxCol) {
        List<Integer> kept = new ArrayList<>(maxCol);
        for (int c = 0; c < maxCol; c++) {
            boolean hasValue = false;
            for (int r = startRow; r <= endRow; r++) {
                String v = grid[r][c];
                if (v != null && !v.isEmpty()) {
                    hasValue = true;
                    break;
                }
            }
            if (hasValue) {
                kept.add(c);
            }
        }
        int[] cols = new int[kept.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = kept.get(i);
        }
        return cols;
    }

    /**
     * 读取 sheet 到二维数组，应用 hyperlink + 公式回退
     * <p>
     * 用 {@link Row.MissingCellPolicy#RETURN_NULL_AND_BLANK}：不存在的 cell 返回 null，
     * BLANK cell 仍返回 cell 实例（可携带 hyperlink）。这样空文字 + 超链接的场景能正确解析
     */
    private static String[][] readGrid(Sheet sheet, int lastRowNum, int maxCol,
                                       DataFormatter formatter, FormulaEvaluator evaluator) {
        String[][] grid = new String[lastRowNum + 1][maxCol];
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            for (int c = 0; c < maxCol; c++) {
                String value = "";
                if (row != null) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (cell != null) {
                        String formatted = ExcelValueFormatter.format(cell, formatter, evaluator);
                        value = ExcelHyperlinkResolver.wrap(formatted, cell);
                        // 软删除约定：非空且划删除线的 cell，用 ~~值~~ 包裹原值
                        if (!value.isEmpty() && ExcelValueFormatter.isStrikethrough(cell)) {
                            value = STRIKETHROUGH_WRAP + value + STRIKETHROUGH_WRAP;
                        }
                    }
                }
                grid[r][c] = value;
            }
        }
        return grid;
    }

    /**
     * 结构感知地展开合并区域。
     * <p>
     * 表头合并需要横向复制，下一行的子表头才能分别得到同一个父级；数据行则只把值放在合并区域的
     * 第一列，并在纵向范围内逐行携带。数据区横向复制会让一个长说明在每一列重复一次，既虚增块长度，
     * 也让向量文本被同一句话支配。
     */
    private static void expandMergedRegions(String[][] grid,
                                            List<CellRangeAddress> mergedRegions,
                                            int lastRowNum, int maxCol,
                                            int effectiveHeaderRows) {
        if (mergedRegions == null || mergedRegions.isEmpty()) {
            return;
        }
        for (CellRangeAddress region : mergedRegions) {
            int firstRow = region.getFirstRow();
            int firstCol = region.getFirstColumn();
            if (firstRow < 0 || firstRow > lastRowNum || firstCol < 0 || firstCol >= maxCol) {
                continue;
            }
            String value = grid[firstRow][firstCol];
            if (value == null || value.isEmpty()) {
                continue;
            }
            int rEnd = Math.min(region.getLastRow(), lastRowNum);
            int cEnd = Math.min(region.getLastColumn(), maxCol - 1);
            for (int r = firstRow; r <= rEnd; r++) {
                if (r < effectiveHeaderRows) {
                    for (int c = firstCol; c <= cEnd; c++) {
                        grid[r][c] = value;
                    }
                } else {
                    grid[r][firstCol] = value;
                }
            }
        }
    }

    private static boolean[] rowsWithValues(String[][] grid) {
        boolean[] result = new boolean[grid.length];
        for (int r = 0; r < grid.length; r++) {
            for (String value : grid[r]) {
                if (value != null && !value.isEmpty()) {
                    result[r] = true;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 展平前 N 行为单行表头：相邻相同值合并（避免合并单元格展开后的重复）
     */
    private static List<String> flattenHeaders(String[][] grid, int startRow, int headerRows, int[] cols) {
        List<String> headers = new ArrayList<>(cols.length);
        for (int c : cols) {
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (int r = startRow; r < startRow + headerRows; r++) {
                String v = grid[r][c];
                if (v == null || v.isEmpty()) {
                    continue;
                }
                if (v.equals(prev)) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(HEADER_SEPARATOR);
                }
                sb.append(v);
                prev = v;
            }
            headers.add(sb.toString());
        }
        return headers;
    }

    /**
     * 收集数据行（跳过全空），并折叠由纵向合并区域制造的连续重复续行。
     * <p>
     * 用户主动录入的相同行保留；只有源工作表中整行没有值、合并展开后又与上一行完全一致时才折叠，
     * 同时把来源范围扩展到完整纵向区域。
     */
    private static List<NormalizedRow> collectDataRows(String[][] grid, int startRow,
                                                       int endRow, int[] cols,
                                                       boolean[] rowsWithSourceValues) {
        List<NormalizedRow> rows = new ArrayList<>();
        int firstCol = cols[0];
        int lastCol = cols[cols.length - 1];
        for (int r = startRow; r <= endRow; r++) {
            List<String> rowValues = new ArrayList<>(cols.length);
            boolean allEmpty = true;
            for (int c : cols) {
                String v = grid[r][c];
                if (v != null && !v.isEmpty()) {
                    allEmpty = false;
                }
                rowValues.add(v == null ? "" : v);
            }
            if (!allEmpty) {
                if (!rowsWithSourceValues[r] && !rows.isEmpty()
                        && rows.get(rows.size() - 1).values().equals(rowValues)) {
                    NormalizedRow previous = rows.get(rows.size() - 1);
                    rows.set(rows.size() - 1, new NormalizedRow(previous.values(), previous.firstRow(), r));
                } else {
                    rows.add(new NormalizedRow(rowValues, r, r));
                }
            }
        }
        return rows;
    }

    private record NormalizedRow(List<String> values, int firstRow, int lastRow) {

        private String cellRange(int firstCol, int lastCol) {
            return new CellRangeAddress(firstRow, lastRow, firstCol, lastCol).formatAsString();
        }
    }
}
