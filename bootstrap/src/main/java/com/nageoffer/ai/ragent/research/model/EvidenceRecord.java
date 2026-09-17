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

/**
 * 已提供给研究任务的文本与真实定位。contentHash 对应完整块快照，不代表未截断的全文。
 */
public record EvidenceRecord(String runId, String evidenceId, String kbId, String docId,
                             String documentName, String documentVersion, List<String> chunkIds,
                             String contentHash, String text, Map<String, Object> sourceLocation,
                             String retrievedByTaskId, boolean truncated, boolean read,
                             SourceExtent sourceExtent) {
    public enum SourceExtent { CHUNK, AVAILABLE_EXCERPT }

    public EvidenceRecord {
        chunkIds = List.copyOf(chunkIds);
        sourceLocation = Map.copyOf(sourceLocation);
    }

    public EvidenceRecord withReadText(String deliveredText, boolean stillTruncated) {
        return new EvidenceRecord(runId, evidenceId, kbId, docId, documentName, documentVersion,
                chunkIds, contentHash, deliveredText, sourceLocation, retrievedByTaskId,
                stillTruncated, true, sourceExtent);
    }
}
