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

import com.nageoffer.ai.ragent.core.parser.excel.ExcelTableNormalizer.NormalizedTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Excel 版面分析：把一个 sheet 切成区域，区域里分出标题、段落和表格，逐张表识别表头（table-structure 计划 §2）
 * <p>
 * 只看单元格、合并与位置，结果确定：
 * <ol>
 *   <li>有内容的格子：有值，或被一个有值的合并单元格覆盖</li>
 *   <li>区域：有内容的格子按上下左右和斜角相邻连通，隔着整行或整列空白就是两个区域</li>
 *   <li>单格行：区域内整行只有一个单元格或一个合并块有内容。从上方纵向合并下来的格子也算内容，
 *       所以检测表里只有"操作步骤"一格有值的续行不是单格行</li>
 *   <li>区域开头的单格行是标题或说明，结尾的是段落，中间横跨一半以上宽度的是分段（从这里拆表），
 *       中间其他单格行是稀疏数据行；全是单格行的区域是文字区域</li>
 *   <li>每张表第一行当表头；它像数据（有格子超过 40 字，或一半以上非空格子是数字）就按无表头处理。
 *       分段后的表沿用首段表头，分段后紧跟着的重复表头不当数据</li>
 *   <li>不超过 30 字的区域开头行是这张表的标题；区域没有开头行时，往前找最近一个列范围与表重叠的区域，
 *       它是文字区域就取它的最后一行当标题（中间隔着的列不重叠的区域，如表右侧的图片说明，不算）</li>
 *   <li>上方（同样按列重叠找）是一张表、列布局与它的表头相同、首行又不像表头（不是整行加粗或带底色）的区域，
 *       是那张表的续段：表中间的空行不该把一张表拆成两张、让续段第一行冒充表头</li>
 *   <li>整个落在某张表范围内的区域属于这张表：稀疏矩阵（如排班表）里与邻格不相邻的格子会自成区域，
 *       它们已经在表格窗口里，不能再当段落输出一遍</li>
 * </ol>
 * 表格本身的清洗（合并展开、续行折叠、多行表头展平）交给 {@link ExcelTableNormalizer#normalizeWindow}
 */
public final class ExcelLayoutAnalyzer {

    /**
     * 标题最多这么多字，再长就是说明段落
     */
    static final int MAX_TITLE_CHARS = 30;

    /**
     * 表头单元格最多这么多字，再长就是数据
     */
    static final int MAX_HEADER_CELL_CHARS = 40;

    /**
     * 合并区域向右能把读取宽度撑到多远：防一个误设的整行合并把 grid 撑到上万列
     */
    private static final int MAX_MERGE_EXTENT_COLS = 1024;

    /**
     * 数字样子的单元格：整数、小数、千分位、可带正负号与百分号，只用于区分表头与数据
     */
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[$+\\-]?[\\d,]+(\\.\\d+)?%?$");

    private ExcelLayoutAnalyzer() {
    }

    /**
     * 区域里的一项产出
     */
    public sealed interface Item permits Text, Table {
    }

    /**
     * 表外文字：一个单元格或一个合并块
     *
     * @param range 单元格范围，如 {@code B24:I26}；行列从 0 起
     */
    public record Text(String range, int row, int col, String text) implements Item {
    }

    /**
     * 一张表，行列从 0 起
     *
     * @param title       标题，进章节路径；没有为 null
     * @param titleRange  标题所在的单元格范围
     * @param headerRow   表头行（多行表头取第一行），无表头为 null
     * @param headerCells 表头逐格文字：一个合并单元格算一格，空格子为空串；无表头为空列表
     */
    public record Table(String title, String titleRange, Integer headerRow, int firstDataRow, int lastDataRow,
                        int firstCol, int lastCol, List<String> headerCells, NormalizedTable table) implements Item {
    }

    /**
     * 一个区域的产出，按阅读顺序排列
     */
    public record Region(int firstRow, int firstCol, List<Item> items) {
    }

    /**
     * 分析一个 sheet；区域按（首行，首列）排序
     *
     * @param headerRows 每张表的表头行数，{@code >= 1}
     */
    public static List<Region> analyze(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                                       int headerRows) {
        if (headerRows < 1) {
            throw new IllegalArgumentException("headerRows must be >= 1, got " + headerRows);
        }
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) {
            return List.of();
        }
        int width = ExcelTableNormalizer.computeMaxColumns(sheet, lastRowNum);
        if (width == 0) {
            return List.of();
        }
        String[][] grid = ExcelTableNormalizer.readGrid(sheet, lastRowNum, width, formatter, evaluator);
        int extended = mergeExtent(sheet.getMergedRegions(), grid, width);
        if (extended > width) {
            width = extended;
            grid = ExcelTableNormalizer.readGrid(sheet, lastRowNum, width, formatter, evaluator);
        }
        return new Analysis(sheet, grid, width, headerRows).run();
    }

    /**
     * 有值的合并区域可能伸到最后一个实体单元格之外，读取宽度要盖住它，否则表格列范围会被截短
     */
    private static int mergeExtent(List<CellRangeAddress> merges, String[][] grid, int width) {
        int extent = width;
        for (CellRangeAddress merge : merges) {
            int row = merge.getFirstRow();
            int col = merge.getFirstColumn();
            if (row < 0 || row >= grid.length || col < 0 || col >= width || isBlank(grid[row][col])) {
                continue;
            }
            extent = Math.max(extent, Math.min(merge.getLastColumn() + 1, MAX_MERGE_EXTENT_COLS));
        }
        return extent;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 单元格或合并块：版面分析的最小单位
     */
    private record Owner(int firstRow, int firstCol, int lastRow, int lastCol, String text, boolean merged) {

        String range() {
            return merged
                    ? new CellRangeAddress(firstRow, lastRow, firstCol, lastCol).formatAsString()
                    : new CellReference(firstRow, firstCol).formatAsString();
        }
    }

    /**
     * 一张表的切分计划，行列从 0 起；headerRows 为空表示无表头
     */
    private static final class Segment {
        final List<Integer> sectionRows = new ArrayList<>();
        final List<Integer> logicalRows = new ArrayList<>();
        int[] headerRows = new int[0];
        int firstDataRow;
        int lastDataRow;
        int firstCol;
        int lastCol;
        String title;
        String titleRange;
    }

    /**
     * 一个区域的切分计划
     */
    private static final class Planned {
        final int id;
        final int firstRow;
        final int lastRow;
        final int firstCol;
        final int lastCol;
        final List<Integer> leading = new ArrayList<>();
        final List<Integer> trailing = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        final List<Integer> textRows = new ArrayList<>();
        boolean absorbed;

        Planned(int id, int firstRow, int lastRow, int firstCol, int lastCol) {
            this.id = id;
            this.firstRow = firstRow;
            this.lastRow = lastRow;
            this.firstCol = firstCol;
            this.lastCol = lastCol;
        }

        boolean textOnly() {
            return segments.isEmpty();
        }
    }

    private static final class Analysis {

        private final Sheet sheet;
        private final String[][] grid;
        private final int width;
        private final int headerRowCount;
        private final List<CellRangeAddress> merges;
        private final List<Owner> owners = new ArrayList<>();
        private final int[][] ownerOf;
        private final int[][] regionOf;

        Analysis(Sheet sheet, String[][] grid, int width, int headerRowCount) {
            this.sheet = sheet;
            this.grid = grid;
            this.width = width;
            this.headerRowCount = headerRowCount;
            this.merges = sheet.getMergedRegions();
            this.ownerOf = new int[grid.length][width];
            this.regionOf = new int[grid.length][width];
        }

        List<Region> run() {
            buildOwners();
            List<Planned> planned = new ArrayList<>();
            for (int[] box : findRegions()) {
                planned.add(plan(box));
            }
            planned.sort(Comparator.comparingInt((Planned p) -> p.firstRow).thenComparingInt(p -> p.firstCol));
            absorbInsideTables(planned);
            List<Planned> kept = new ArrayList<>();
            for (Planned current : planned) {
                if (current.absorbed) {
                    continue;
                }
                Planned above = nearestAbove(kept, current);
                if (absorbAsContinuation(above, current)) {
                    continue;
                }
                takeTitleFromTextAbove(above, current);
                kept.add(current);
            }
            List<Region> regions = new ArrayList<>();
            for (Planned p : planned) {
                if (p.absorbed) {
                    continue;
                }
                List<Item> items = emit(p);
                if (!items.isEmpty()) {
                    regions.add(new Region(p.firstRow, p.firstCol, List.copyOf(items)));
                }
            }
            return regions;
        }

        /**
         * 每个格子归属到一个单元格或合并块；只有有值的合并块才占格子
         */
        private void buildOwners() {
            for (int[] row : ownerOf) {
                Arrays.fill(row, -1);
            }
            for (CellRangeAddress merge : merges) {
                int firstRow = merge.getFirstRow();
                int firstCol = merge.getFirstColumn();
                if (firstRow < 0 || firstRow >= grid.length || firstCol < 0 || firstCol >= width
                        || isBlank(grid[firstRow][firstCol])) {
                    continue;
                }
                int lastRow = Math.min(merge.getLastRow(), grid.length - 1);
                int lastCol = Math.min(merge.getLastColumn(), width - 1);
                int id = owners.size();
                owners.add(new Owner(firstRow, firstCol, lastRow, lastCol, grid[firstRow][firstCol].strip(), true));
                for (int r = firstRow; r <= lastRow; r++) {
                    for (int c = firstCol; c <= lastCol; c++) {
                        ownerOf[r][c] = id;
                    }
                }
            }
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < width; c++) {
                    if (ownerOf[r][c] == -1 && !isBlank(grid[r][c])) {
                        ownerOf[r][c] = owners.size();
                        owners.add(new Owner(r, c, r, c, grid[r][c].strip(), false));
                    }
                }
            }
        }

        /**
         * 有内容的格子按八邻接连通成区域，返回每个区域的外框 {id, firstRow, lastRow, firstCol, lastCol}
         */
        private List<int[]> findRegions() {
            for (int[] row : regionOf) {
                Arrays.fill(row, -1);
            }
            List<int[]> boxes = new ArrayList<>();
            Deque<int[]> queue = new ArrayDeque<>();
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < width; c++) {
                    if (ownerOf[r][c] == -1 || regionOf[r][c] != -1) {
                        continue;
                    }
                    int id = boxes.size();
                    int[] box = {id, r, r, c, c};
                    regionOf[r][c] = id;
                    queue.add(new int[]{r, c});
                    while (!queue.isEmpty()) {
                        int[] cell = queue.poll();
                        box[1] = Math.min(box[1], cell[0]);
                        box[2] = Math.max(box[2], cell[0]);
                        box[3] = Math.min(box[3], cell[1]);
                        box[4] = Math.max(box[4], cell[1]);
                        for (int dr = -1; dr <= 1; dr++) {
                            for (int dc = -1; dc <= 1; dc++) {
                                int nr = cell[0] + dr;
                                int nc = cell[1] + dc;
                                if (nr < 0 || nr >= grid.length || nc < 0 || nc >= width
                                        || ownerOf[nr][nc] == -1 || regionOf[nr][nc] != -1) {
                                    continue;
                                }
                                regionOf[nr][nc] = id;
                                queue.add(new int[]{nr, nc});
                            }
                        }
                    }
                    boxes.add(box);
                }
            }
            return boxes;
        }

        /**
         * 这一行里属于本区域的格子主人，按列序
         */
        private List<Integer> rowOwners(Planned p, int row) {
            Set<Integer> ids = new LinkedHashSet<>();
            for (int c = p.firstCol; c <= p.lastCol; c++) {
                if (regionOf[row][c] == p.id) {
                    ids.add(ownerOf[row][c]);
                }
            }
            return new ArrayList<>(ids);
        }

        /**
         * 这一行有没有新内容：没有新单元格、只被上方的合并块覆盖的是续行，并进上一行
         */
        private boolean logical(Planned p, int row) {
            for (int id : rowOwners(p, row)) {
                if (owners.get(id).firstRow() == row) {
                    return true;
                }
            }
            return false;
        }

        private boolean single(Planned p, int row) {
            return rowOwners(p, row).size() == 1;
        }

        private Owner soleOwner(Planned p, int row) {
            return owners.get(rowOwners(p, row).get(0));
        }

        private boolean spanning(Planned p, int row) {
            if (!single(p, row)) {
                return false;
            }
            Owner owner = soleOwner(p, row);
            int span = Math.min(owner.lastCol(), p.lastCol) - Math.max(owner.firstCol(), p.firstCol) + 1;
            return span * 2 >= p.lastCol - p.firstCol + 1;
        }

        private Planned plan(int[] box) {
            Planned p = new Planned(box[0], box[1], box[2], box[3], box[4]);
            List<Integer> logicalRows = new ArrayList<>();
            for (int r = p.firstRow; r <= p.lastRow; r++) {
                if (logical(p, r)) {
                    logicalRows.add(r);
                }
            }
            int lead = 0;
            while (lead < logicalRows.size() && single(p, logicalRows.get(lead))) {
                lead++;
            }
            if (lead == logicalRows.size()) {
                p.textRows.addAll(logicalRows);
                return p;
            }
            int trail = logicalRows.size();
            while (trail > lead && single(p, logicalRows.get(trail - 1))) {
                trail--;
            }
            p.leading.addAll(logicalRows.subList(0, lead));
            p.trailing.addAll(logicalRows.subList(trail, logicalRows.size()));

            Segment current = new Segment();
            p.segments.add(current);
            for (int r : logicalRows.subList(lead, trail)) {
                if (spanning(p, r)) {
                    if (!current.logicalRows.isEmpty()) {
                        current = new Segment();
                        p.segments.add(current);
                    }
                    current.sectionRows.add(r);
                } else {
                    current.logicalRows.add(r);
                }
            }
            int end = p.trailing.isEmpty() ? p.lastRow : p.trailing.get(0) - 1;
            for (int i = p.segments.size() - 1; i >= 0; i--) {
                Segment segment = p.segments.get(i);
                segment.lastDataRow = end;
                end = (segment.sectionRows.isEmpty() ? segment.logicalRows.get(0) : segment.sectionRows.get(0)) - 1;
            }
            Segment first = p.segments.get(0);
            for (Segment segment : p.segments) {
                assignHeader(p, segment, segment == first ? null : first);
                assignColumns(p, segment);
            }
            if (!p.leading.isEmpty()) {
                Owner last = soleOwner(p, p.leading.get(p.leading.size() - 1));
                if (last.text().length() <= MAX_TITLE_CHARS) {
                    first.title = last.text();
                    first.titleRange = last.range();
                    p.leading.remove(p.leading.size() - 1);
                }
            }
            for (Segment segment : p.segments) {
                if (!segment.sectionRows.isEmpty()) {
                    Owner last = soleOwner(p, segment.sectionRows.get(segment.sectionRows.size() - 1));
                    if (last.text().length() <= MAX_TITLE_CHARS) {
                        segment.title = last.text();
                        segment.titleRange = last.range();
                        segment.sectionRows.remove(segment.sectionRows.size() - 1);
                    }
                }
            }
            return p;
        }

        /**
         * 首段：第一行像表头就当表头。后续段：首行与首段表头相同是重复表头，否则沿用首段表头
         */
        private void assignHeader(Planned p, Segment segment, Segment first) {
            int start = segment.logicalRows.get(0);
            if (first != null && first.headerRows.length > 0) {
                if (sameCells(p, start, first.headerRows[0])) {
                    takeHeaderRows(segment);
                } else {
                    segment.headerRows = first.headerRows;
                    segment.firstDataRow = start;
                }
                return;
            }
            if (headerLike(p, start)) {
                takeHeaderRows(segment);
            } else {
                segment.firstDataRow = start;
            }
        }

        private void takeHeaderRows(Segment segment) {
            int count = Math.min(headerRowCount, segment.logicalRows.size());
            segment.headerRows = new int[count];
            for (int i = 0; i < count; i++) {
                segment.headerRows[i] = segment.logicalRows.get(i);
            }
            segment.firstDataRow = segment.headerRows[count - 1] + 1;
        }

        private void assignColumns(Planned p, Segment segment) {
            segment.firstCol = Integer.MAX_VALUE;
            segment.lastCol = Integer.MIN_VALUE;
            List<Integer> rows = new ArrayList<>();
            for (int r : segment.headerRows) {
                rows.add(r);
            }
            for (int r = segment.firstDataRow; r <= segment.lastDataRow; r++) {
                rows.add(r);
            }
            for (int r : rows) {
                for (int id : rowOwners(p, r)) {
                    Owner owner = owners.get(id);
                    segment.firstCol = Math.min(segment.firstCol, Math.max(owner.firstCol(), p.firstCol));
                    segment.lastCol = Math.max(segment.lastCol, Math.min(owner.lastCol(), p.lastCol));
                }
            }
        }

        /**
         * 本区域这一行新出现的单元格（合并块算一个），按列序；同一行里别的区域（如表右侧的图片说明）不算
         */
        private List<Owner> anchoredOwners(Planned p, int row) {
            List<Owner> result = new ArrayList<>();
            for (int id : rowOwners(p, row)) {
                if (owners.get(id).firstRow() == row) {
                    result.add(owners.get(id));
                }
            }
            return result;
        }

        private boolean headerLike(Planned p, int row) {
            List<Owner> cells = anchoredOwners(p, row);
            if (cells.isEmpty()) {
                return false;
            }
            int numeric = 0;
            for (Owner cell : cells) {
                if (cell.text().length() > MAX_HEADER_CELL_CHARS) {
                    return false;
                }
                if (NUMERIC_CELL.matcher(cell.text()).matches()) {
                    numeric++;
                }
            }
            return numeric * 2 < cells.size();
        }

        private boolean sameCells(Planned p, int rowA, int rowB) {
            return layoutOf(p, rowA).equals(layoutOf(p, rowB)) && textsOf(p, rowA).equals(textsOf(p, rowB));
        }

        private List<List<Integer>> layoutOf(Planned p, int row) {
            List<List<Integer>> layout = new ArrayList<>();
            for (Owner owner : anchoredOwners(p, row)) {
                layout.add(List.of(owner.firstCol(), owner.lastCol()));
            }
            return layout;
        }

        private List<String> textsOf(Planned p, int row) {
            return anchoredOwners(p, row).stream().map(Owner::text).toList();
        }

        /**
         * 阅读顺序上在它之前、列范围与它重叠的最近一个区域；列不重叠的（如表右侧的图片说明）跳过
         */
        private static Planned nearestAbove(List<Planned> kept, Planned current) {
            for (int i = kept.size() - 1; i >= 0; i--) {
                Planned candidate = kept.get(i);
                if (candidate.lastCol >= current.firstCol && candidate.firstCol <= current.lastCol) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * 整个落在某张表范围（表头行到末行、表的列范围）内的其他区域并入这张表：它们的格子已在表格窗口里
         */
        private void absorbInsideTables(List<Planned> planned) {
            for (Planned owner : planned) {
                for (Segment segment : owner.segments) {
                    int top = segment.headerRows.length == 0 ? segment.firstDataRow : segment.headerRows[0];
                    for (Planned other : planned) {
                        if (other != owner && !other.absorbed
                                && other.firstRow >= top && other.lastRow <= segment.lastDataRow
                                && other.firstCol >= segment.firstCol && other.lastCol <= segment.lastCol) {
                            other.absorbed = true;
                        }
                    }
                }
            }
        }

        /**
         * 表中间的空行会把一张表拆成两个区域：后一个区域没有开头行、只有一段，列布局与上一张表的表头相同，
         * 首行又不像表头（整行加粗或带底色才算表头样式），就是上一张表的续段
         */
        private boolean absorbAsContinuation(Planned previous, Planned current) {
            if (previous == null || previous.textOnly() || current.textOnly() || !previous.trailing.isEmpty()
                    || !current.leading.isEmpty() || !current.trailing.isEmpty() || current.segments.size() != 1) {
                return false;
            }
            Segment table = previous.segments.get(previous.segments.size() - 1);
            Segment next = current.segments.get(0);
            if (table.headerRows.length == 0 || !next.sectionRows.isEmpty() || next.title != null) {
                return false;
            }
            int start = next.logicalRows.get(0);
            if (!layoutOf(current, start).equals(layoutOf(previous, table.headerRows[0]))
                    || headerStyled(current, start)) {
                return false;
            }
            table.lastDataRow = current.lastRow;
            table.firstCol = Math.min(table.firstCol, next.firstCol);
            table.lastCol = Math.max(table.lastCol, next.lastCol);
            current.absorbed = true;
            return true;
        }

        private boolean headerStyled(Planned p, int row) {
            Row sheetRow = sheet.getRow(row);
            if (sheetRow == null) {
                return false;
            }
            List<Owner> cells = anchoredOwners(p, row);
            for (Owner owner : cells) {
                Cell cell = sheetRow.getCell(owner.firstCol());
                if (cell == null) {
                    return false;
                }
                CellStyle style = cell.getCellStyle();
                boolean bold = sheet.getWorkbook().getFontAt(style.getFontIndex()).getBold();
                boolean filled = style.getFillPattern() != FillPatternType.NO_FILL;
                if (!bold && !filled) {
                    return false;
                }
            }
            return !cells.isEmpty();
        }

        /**
         * 表格区域没有自己的开头行时，上方最近的区域若是文字区域、列范围与表重叠，它的最后一行（不超过 30 字）是表的标题
         */
        private void takeTitleFromTextAbove(Planned previous, Planned current) {
            if (previous == null || !previous.textOnly() || previous.textRows.isEmpty()
                    || current.textOnly() || !current.leading.isEmpty()) {
                return;
            }
            Segment table = current.segments.get(0);
            if (table.title != null || !table.sectionRows.isEmpty()
                    || previous.lastCol < table.firstCol || previous.firstCol > table.lastCol) {
                return;
            }
            int row = previous.textRows.get(previous.textRows.size() - 1);
            Owner owner = soleOwner(previous, row);
            if (owner.text().length() > MAX_TITLE_CHARS) {
                return;
            }
            table.title = owner.text();
            table.titleRange = owner.range();
            previous.textRows.remove(previous.textRows.size() - 1);
        }

        private List<Item> emit(Planned p) {
            List<Item> items = new ArrayList<>();
            if (p.textOnly()) {
                p.textRows.forEach(row -> items.add(text(p, row)));
                return items;
            }
            p.leading.forEach(row -> items.add(text(p, row)));
            for (Segment segment : p.segments) {
                segment.sectionRows.forEach(row -> items.add(text(p, row)));
                items.add(table(segment));
            }
            p.trailing.forEach(row -> items.add(text(p, row)));
            return items;
        }

        private Text text(Planned p, int row) {
            Owner owner = soleOwner(p, row);
            return new Text(owner.range(), owner.firstRow(), owner.firstCol(), owner.text());
        }

        private Table table(Segment segment) {
            NormalizedTable normalized = ExcelTableNormalizer.normalizeWindow(grid, merges, segment.headerRows,
                    segment.firstDataRow, segment.lastDataRow, segment.firstCol, segment.lastCol);
            Integer headerRow = segment.headerRows.length == 0 ? null : segment.headerRows[0];
            return new Table(segment.title, segment.titleRange, headerRow, segment.firstDataRow,
                    segment.lastDataRow, segment.firstCol, segment.lastCol, headerCells(segment), normalized);
        }

        /**
         * 表头逐格文字：一个合并单元格只算一格，没有内容的格子记空串
         */
        private List<String> headerCells(Segment segment) {
            if (segment.headerRows.length == 0) {
                return List.of();
            }
            int row = segment.headerRows[0];
            List<String> cells = new ArrayList<>();
            for (int c = segment.firstCol; c <= segment.lastCol; c++) {
                int id = ownerOf[row][c];
                if (id == -1) {
                    cells.add("");
                    continue;
                }
                Owner owner = owners.get(id);
                if (c != Math.max(owner.firstCol(), segment.firstCol)) {
                    continue;
                }
                cells.add(owner.firstRow() == row ? owner.text() : "");
            }
            return cells;
        }
    }
}
