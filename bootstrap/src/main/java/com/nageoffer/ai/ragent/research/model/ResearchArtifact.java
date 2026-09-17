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

package com.nageoffer.ai.ragent.research.model;

import java.util.List;
import java.util.Map;

/** 引用编号和来源由服务端分配，用户约束独立保存为 user_input。 */
public record ResearchArtifact(ResearchBrief.OutputType outputType, String title, String goal,
                               List<UserConstraint> userConstraints, List<Section> sections,
                               PlanDraft plan, List<String> gaps, List<String> conflicts,
                               List<Citation> citations, String markdown, String promptVersion) {
    public record UserConstraint(String text, String source) { }
    public record Section(String heading, String text, List<String> evidenceIds) { }
    public record Citation(int index, String evidenceId, String docId, String docName,
                           String documentVersion, String contentHash, List<String> chunkIds,
                           String excerpt, Map<String, Object> sourceLocation,
                           boolean truncated, EvidenceRecord.SourceExtent sourceExtent) { }
    /** 模型只提交内容；不能自行提供用户约束、来源位置或展示编号。 */
    public record Payload(String title, List<Section> sections, PlanDraft plan, List<String> gaps) { }
}
