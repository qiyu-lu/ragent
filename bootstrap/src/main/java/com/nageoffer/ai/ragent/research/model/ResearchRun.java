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

import java.time.Instant;
import java.util.Map;

/** API 不暴露执行租约，也不保存模型隐藏推理。 */
public record ResearchRun(String id, String conversationId, String clientRequestId,
                          ResearchBrief brief, Status status, long revision, long epoch,
                          Map<String, Object> state, Map<String, Object> artifact,
                          Map<String, Object> usage, String errorSummary,
                          Instant startedAt, Instant completedAt) {
    public enum Status {
        QUEUED, RUNNING, WAITING_INPUT, COMPLETED, PARTIAL, FAILED, CANCELLED, INTERRUPTED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING && this != WAITING_INPUT;
        }
    }
}
