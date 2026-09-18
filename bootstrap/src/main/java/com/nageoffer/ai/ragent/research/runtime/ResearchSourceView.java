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

package com.nageoffer.ai.ragent.research.runtime;

import com.nageoffer.ai.ragent.research.model.*;
import java.util.*;

/** Small model-facing projection; immutable full snapshots remain in the evidence store and audit events. */
public final class ResearchSourceView {
    private ResearchSourceView() { }
    public static Map<String, Object> candidate(KnowledgeSearchHit hit) {
        return content(hit.evidenceId(), hit.docId(), hit.documentName(), hit.documentVersion(), hit.text(),
                hit.truncated(), hit.sourceExtent(), hit.sourceLocation());
    }
    public static Map<String, Object> read(SourceReadResult result) {
        var e = result.evidence();
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("evidence", content(e.evidenceId(), e.docId(), e.documentName(), e.documentVersion(), e.text(),
                e.truncated(), e.sourceExtent(), e.sourceLocation()));
        view.put("sourceState", result.sourceState());
        view.put("expansionState", result.expansionState());
        view.put("requestedEvidenceId", result.requestedEvidenceId());
        if (result.note() != null) view.put("note", result.note());
        return view;
    }
    private static Map<String,Object> content(String id, String doc, String name, String version, String text,
                                               boolean truncated, EvidenceRecord.SourceExtent extent, Map<?,?> location) {
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("evidenceId", id); view.put("docId", doc); view.put("documentName", name);
        view.put("documentVersion", version); view.put("text", text); view.put("truncated", truncated);
        view.put("sourceExtent", extent); view.put("sourceContext", context(location));
        var warnings = warnings(text, location);
        if (!warnings.isEmpty()) view.put("readingWarnings", warnings);
        return view;
    }
    public static Map<String,Object> context(Map<?,?> location) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("section_path", "sectionPath", "sheet_name", "sheetName", "page_number", "pageNumber", "cellRange"))
            if (location.containsKey(key)) result.put(key, location.get(key));
        if (location.get("chunks") instanceof List<?> chunks) {
            var nested = chunks.stream().filter(Map.class::isInstance).map(Map.class::cast).map(ResearchSourceView::context)
                    .filter(m -> !m.isEmpty()).toList();
            if (!nested.isEmpty()) result.put("chunks", nested);
        }
        return result;
    }
    public static List<String> warnings(String text, Map<?,?> location) {
        List<String> result = new ArrayList<>();
        String scope = (text + " " + context(location)).toLowerCase(Locale.ROOT);
        if (scope.contains("table of contents") || scope.contains("目录"))
            result.add("Possible table of contents: headings locate material but do not establish its findings. Read the relevant body.");
        if (text.contains("INLINEFORM") || text.contains("DISPLAYFORM"))
            result.add("Extraction contains equation placeholders. Do not reconstruct missing symbols or values; inspect permitted neighbors if needed.");
        if (text.strip().length() < 160)
            result.add("Short excerpt: verify that it includes the conditions needed for this claim; neighboring text may help.");
        return List.copyOf(result);
    }
}
