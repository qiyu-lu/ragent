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

/**
 * CHANGED 时仍返回本任务的原快照，来源位置和正文不会被新版本替换。
 */
public record SourceReadResult(EvidenceRecord evidence, SourceState sourceState,
                               ExpansionState expansionState, String requestedEvidenceId, String note) {
    public enum SourceState { CURRENT, CHANGED }
    public enum ReadMode { CHUNK, NEIGHBORS }
    public enum ExpansionState { CHUNK, NEIGHBORS, BLOCK_ONLY }

    public SourceReadResult(EvidenceRecord evidence, SourceState sourceState) {
        this(evidence, sourceState, ExpansionState.CHUNK, evidence.evidenceId(), null);
    }
}
