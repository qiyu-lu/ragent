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

package com.nageoffer.ai.ragent.ironore.model;

import java.util.Date;
import java.util.List;

/**
 * 确定性模拟执行结果；不表示任何真实设备动作。
 */
public record TaskExecutionView(
        String id,
        String taskTemplateId,
        String status,
        List<TaskSimulationEvent> events,
        Date startTime,
        Date endTime
) {

    public record TaskSimulationEvent(Integer sequence, String type, String message, List<String> evidenceChunkIds) {
        public TaskSimulationEvent {
            evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
        }
    }
}
