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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 chunker：同时按 Markdown 展示文本与 key-value 向量文本的实际长度累加预算，每块都带完整表头；
 * 只有单个真实数据行本身已超预算时才保留原子行作为例外
 * <p>
 * {@code rowsPerChunk} 只作硬上限，兼顾宽表不超嵌入上限、窄表不过度碎片化；展示文本是完整
 * markdown 表格，向量文本改用 {@code 列名: 值}，因为 markdown 表格靠位置对齐列名与值、嵌入模型
 * 读不懂位置；表头不拼进向量文本，KV 正文已逐格自带列名，重复前缀会把同一张表各块的向量朝同一
 * 方向拉、压缩块间距离，表身份由 sheet 名经章节路径承载
 */
@Component
public class TableChunker implements BlockChunker<TableBlock> {

    @Override
    public Class<TableBlock> blockType() {
        return TableBlock.class;
    }

    @Override
    public List<ChunkDraft> chunk(TableBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        List<String> headers = block.headers() == null ? List.of() : block.headers();
        List<List<String>> rows = block.rows() == null ? List.of() : block.rows();
        List<String> rowCellRanges = block.rowCellRanges() == null ? List.of() : block.rowCellRanges();
        if (headers.isEmpty() && rows.isEmpty()) {
            return List.of();
        }

        int maxRows = Math.max(1, ctx.budget().rowsPerChunk());
        int budget = Math.max(1, ctx.budget().maxChars());

        List<ChunkDraft> result = new ArrayList<>();

        if (rows.isEmpty()) {
            result.add(buildDraft(block, ctx, headers, List.of(), List.of()));
            return result;
        }

        // 贪心累加：超硬上限或（非空且加入下一行会超预算）则先落块
        List<List<String>> group = new ArrayList<>();
        List<String> groupRanges = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            String rowRange = rowIndex < rowCellRanges.size() ? rowCellRanges.get(rowIndex) : null;
            List<RowPiece> rowPieces = splitWideRow(ctx, headers, row, budget);
            if (!rowPieces.get(0).fullWidth()) {
                if (!group.isEmpty()) {
                    result.add(buildDraft(block, ctx, headers, group, groupRanges));
                    group = new ArrayList<>();
                    groupRanges = new ArrayList<>();
                }
                for (RowPiece piece : rowPieces) {
                    result.add(buildDraft(block, ctx, piece.headers(), List.of(piece.values()),
                            rowRange == null ? List.of() : List.of(rowRange)));
                }
                continue;
            }
            boolean overCap = group.size() >= maxRows;
            boolean overBudget = !group.isEmpty() && exceedsBudget(ctx, headers, group, row, budget);
            if (overCap || overBudget) {
                result.add(buildDraft(block, ctx, headers, group, groupRanges));
                group = new ArrayList<>();
                groupRanges = new ArrayList<>();
            }
            group.add(row);
            if (rowRange != null) {
                groupRanges.add(rowRange);
            }
        }
        if (!group.isEmpty()) {
            result.add(buildDraft(block, ctx, headers, group, groupRanges));
        }
        return ChunkDraft.pieces(result);
    }

    /**
     * 稀疏宽行若仅因重复整张表头而超预算，按非空列分成若干窄表片段；每个键值对仍保持原子，来源范围
     * 仍指向同一原始行。单个键值对本身超预算时不截断业务文本，保留为唯一允许的超限例外。
     */
    private List<RowPiece> splitWideRow(ChunkContext ctx, List<String> headers, List<String> row, int budget) {
        if (fitsBudget(ctx, headers, List.of(row), budget)) {
            return List.of(new RowPiece(headers, row, true));
        }

        List<RowPiece> pieces = new ArrayList<>();
        List<String> pieceHeaders = new ArrayList<>();
        List<String> pieceValues = new ArrayList<>();
        for (int column = 0; column < row.size(); column++) {
            String value = row.get(column);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String header = column < headers.size() ? headers.get(column) : "";
            List<String> candidateHeaders = new ArrayList<>(pieceHeaders);
            List<String> candidateValues = new ArrayList<>(pieceValues);
            candidateHeaders.add(header);
            candidateValues.add(value);
            if (!pieceValues.isEmpty() && !fitsBudget(ctx, candidateHeaders, List.of(candidateValues), budget)) {
                pieces.add(new RowPiece(List.copyOf(pieceHeaders), List.copyOf(pieceValues), false));
                pieceHeaders = new ArrayList<>();
                pieceValues = new ArrayList<>();
            }
            pieceHeaders.add(header);
            pieceValues.add(value);
        }
        if (!pieceValues.isEmpty()) {
            pieces.add(new RowPiece(List.copyOf(pieceHeaders), List.copyOf(pieceValues), false));
        }
        return pieces.isEmpty() ? List.of(new RowPiece(headers, row, true)) : pieces;
    }

    /**
     * 预算同时覆盖两份真正落库的文本：前端/LLM 使用的 Markdown content，以及追加章节路径后的
     * embedding text。旧实现只累计 KV 行，宽表重复表头后会出现 content 已超限但预算仍判定可装入。
     */
    private boolean exceedsBudget(ChunkContext ctx, List<String> headers, List<List<String>> current,
                                  List<String> nextRow, int budget) {
        List<List<String>> candidate = new ArrayList<>(current.size() + 1);
        candidate.addAll(current);
        candidate.add(nextRow);
        return !fitsBudget(ctx, headers, candidate, budget);
    }

    private boolean fitsBudget(ChunkContext ctx, List<String> headers, List<List<String>> rows, int budget) {
        if (renderMarkdownTable(headers, rows).length() > budget) {
            return false;
        }
        String body = renderKeyValueRows(headers, rows);
        int embeddingLength = body.length();
        if (!ctx.outlinePath().isEmpty()) {
            embeddingLength += String.join(" / ", ctx.outlinePath()).length() + 1;
        }
        return embeddingLength <= budget;
    }

    private record RowPiece(List<String> headers, List<String> values, boolean fullWidth) {
    }

    private ChunkDraft buildDraft(TableBlock block, ChunkContext ctx, List<String> headers, List<List<String>> rows,
                                  List<String> rowRanges) {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(withCellRange(block.provenance(), rowRanges))
                .blockType("table")
                .build();
        // 章节路径由装配器统一拼进向量文本，此处只给 key-value 正文，避免重复前缀
        return ChunkDraft.of(renderMarkdownTable(headers, rows), renderKeyValueRows(headers, rows), metadata);
    }

    private Provenance withCellRange(Provenance provenance, List<String> rowRanges) {
        if (provenance == null || rowRanges == null || rowRanges.isEmpty()) {
            return provenance;
        }
        try {
            CellRangeAddress first = CellRangeAddress.valueOf(rowRanges.get(0));
            CellRangeAddress last = CellRangeAddress.valueOf(rowRanges.get(rowRanges.size() - 1));
            CellRangeAddress combined = new CellRangeAddress(
                    Math.min(first.getFirstRow(), last.getFirstRow()),
                    Math.max(first.getLastRow(), last.getLastRow()),
                    Math.min(first.getFirstColumn(), last.getFirstColumn()),
                    Math.max(first.getLastColumn(), last.getLastColumn()));
            return provenance.withCellRange(combined.formatAsString());
        } catch (IllegalArgumentException ignored) {
            return provenance.withCellRange(String.join(",", rowRanges));
        }
    }

    private String renderKeyValueRows(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            String line = renderKeyValueRow(headers, row);
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 单行渲染成 {@code 列名: 值}，"; " 拼接、跳过空值、整行空返回空串；同时用作预算切分的行体量度量
     */
    private String renderKeyValueRow(List<String> headers, List<String> row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String key = c < headers.size() ? headers.get(c) : "";
            if (!line.isEmpty()) {
                line.append("; ");
            }
            if (!key.isEmpty()) {
                line.append(oneLine(key)).append(": ");
            }
            line.append(oneLine(value));
        }
        return line.toString();
    }

    /**
     * 把 cell 内换行压成空格：key 与 value 之间夹一个断行会影响检索
     */
    private static String oneLine(String text) {
        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        appendSeparator(sb, headers.size());
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(sanitizeCell(cell)).append(" |");
        }
        sb.append('\n');
    }

    /**
     * 清洗 cell 以适配 markdown 表格语法
     * <p>
     * 单元格内换行（Excel Alt+Enter）转 {@code <br>}，裸换行会从中间截断表格行、使整块退化成普通段落；
     * 竖线转义，cell 内的字面 {@code |} 会被误判为列分隔
     */
    private String sanitizeCell(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        return cell.replace("|", "\\|").replaceAll("\\r\\n|\\r|\\n", "<br>");
    }

    private void appendSeparator(StringBuilder sb, int colCount) {
        sb.append('|');
        sb.append("---|".repeat(Math.max(0, colCount)));
        sb.append('\n');
    }

}
