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

import java.util.Map;

/**
 * search_knowledge 的候选摘要；必须调用 read_source 才标记为已读证据。
 */
public record KnowledgeSearchHit(String evidenceId, String kbId, String docId,
                                 String documentName, String documentVersion, String text,
                                 boolean truncated, Map<String, Object> sourceLocation,
                                 EvidenceRecord.SourceExtent sourceExtent) {
}
