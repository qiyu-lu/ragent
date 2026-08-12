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

package com.nageoffer.ai.ragent.ironore.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ironore.model.WorkbookDiffView;
import com.nageoffer.ai.ragent.ironore.model.WorkbookDiffView.CellChange;
import com.nageoffer.ai.ragent.ironore.model.WorkbookDiffView.DocumentVersionRef;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * XLSX 版本差异使用 POI 做确定性单元格比较。LLM 只负责解释工具结果，不参与判断哪些单元格发生变化。
 */
@Service
@RequiredArgsConstructor
public class WorkbookDiffService {

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;

    public WorkbookDiffView compare(String documentKey, String baseVersion, String targetVersion) {
        if (StrUtil.hasBlank(documentKey, baseVersion, targetVersion)) {
            throw new ClientException("文档键、基准版本和目标版本均不能为空");
        }
        if (baseVersion.equalsIgnoreCase(targetVersion)) {
            throw new ClientException("基准版本和目标版本不能相同");
        }

        KnowledgeDocumentDO base = requireDocument(documentKey, baseVersion);
        KnowledgeDocumentDO target = requireDocument(documentKey, targetVersion);
        if (!Objects.equals(base.getKbId(), target.getKbId())) {
            throw new ClientException("两个文档版本不属于同一知识库");
        }

        WorkbookCells baseCells = readCells(base);
        WorkbookCells targetCells = readCells(target);
        LinkedHashSet<CellKey> orderedKeys = new LinkedHashSet<>(baseCells.order());
        orderedKeys.addAll(targetCells.order());

        List<CellChange> changes = new ArrayList<>();
        for (CellKey key : orderedKeys) {
            String before = baseCells.values().get(key);
            String after = targetCells.values().get(key);
            if (Objects.equals(before, after)) {
                continue;
            }
            String changeType = before == null ? "ADDED" : after == null ? "REMOVED" : "MODIFIED";
            changes.add(new CellChange(changeType, key.sheetName(), key.address(), before, after));
        }

        return new WorkbookDiffView(
                documentKey,
                toRef(base),
                toRef(target),
                changes.size(),
                List.copyOf(changes));
    }

    private KnowledgeDocumentDO requireDocument(String documentKey, String version) {
        List<KnowledgeDocumentDO> matches = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentDO>()
                        .eq(KnowledgeDocumentDO::getDocumentKey, documentKey)
                        .eq(KnowledgeDocumentDO::getDocumentVersion, version)
                        .eq(KnowledgeDocumentDO::getEnabled, 1));
        if (matches.isEmpty()) {
            throw new ClientException("未找到文档版本：" + documentKey + " " + version);
        }
        if (matches.size() > 1) {
            throw new ClientException("同一文档版本存在多份记录，请先清理重复上传：" + version);
        }
        KnowledgeDocumentDO document = matches.get(0);
        if (!"xlsx".equalsIgnoreCase(document.getFileType())) {
            throw new ClientException("当前版本差异工具仅支持 XLSX 文档");
        }
        return document;
    }

    private WorkbookCells readCells(KnowledgeDocumentDO document) {
        Map<CellKey, String> values = new LinkedHashMap<>();
        List<CellKey> order = new ArrayList<>();
        try (InputStream input = fileStorageService.openStream(document.getFileUrl());
                Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = displayValue(cell, formatter, evaluator);
                        if (StrUtil.isBlank(value)) {
                            continue;
                        }
                        CellKey key = new CellKey(
                                sheet.getSheetName(),
                                cell.getRowIndex(),
                                cell.getColumnIndex());
                        values.put(key, value);
                        order.add(key);
                    }
                }
            }
        } catch (Exception e) {
            throw new ClientException("读取 XLSX 版本失败：" + document.getDocName());
        }
        return new WorkbookCells(Map.copyOf(values), List.copyOf(order));
    }

    private String displayValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        String displayed;
        try {
            displayed = formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ignored) {
            displayed = formatter.formatCellValue(cell);
        }
        displayed = StrUtil.trim(StrUtil.nullToEmpty(displayed)).replace("\r\n", "\n");
        if (cell.getCellType() != CellType.FORMULA) {
            return displayed;
        }
        String formula = "=" + cell.getCellFormula();
        return StrUtil.isBlank(displayed) ? formula : formula + " => " + displayed;
    }

    private DocumentVersionRef toRef(KnowledgeDocumentDO document) {
        return new DocumentVersionRef(
                document.getId(),
                document.getDocName(),
                document.getDocumentVersion(),
                Integer.valueOf(1).equals(document.getDemoData()));
    }

    private record WorkbookCells(Map<CellKey, String> values, List<CellKey> order) {
    }

    private record CellKey(String sheetName, int rowIndex, int columnIndex) {

        private String address() {
            return new CellReference(rowIndex, columnIndex).formatAsString();
        }
    }
}
