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

import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload.EvidenceItem;
import com.nageoffer.ai.ragent.ironore.model.TaskTemplatePayload.TaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskTemplateValidatorTest {

    private final TaskTemplateValidator validator = new TaskTemplateValidator();

    @Test
    void acceptsOnlyGroundedSequentialTaskAndOverridesVersion() {
        TaskTemplatePayload payload = new TaskTemplatePayload(
                "  浓度检测候选任务  ",
                "中心化验室浓度检测",
                "模型伪造版本",
                List.of(new EvidenceItem("准备采样桶", List.of("chunk-1"))),
                List.of(new TaskStep(1, "烘干样品", List.of("干燥箱"), List.of(), List.of("chunk-2"))),
                List.of(),
                List.of(),
                List.of());

        TaskTemplatePayload validated = validator.validate(payload, "V1.2", Set.of("chunk-1", "chunk-2"));

        assertEquals("浓度检测候选任务", validated.title());
        assertEquals("V1.2", validated.documentVersion());
    }

    @Test
    void rejectsEvidenceOutsideCurrentRetrieval() {
        TaskTemplatePayload payload = new TaskTemplatePayload(
                "任务",
                "流程",
                "V1.2",
                List.of(),
                List.of(new TaskStep(1, "执行", List.of(), List.of(), List.of("unknown"))),
                List.of(),
                List.of(),
                List.of());

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(payload, "V1.2", Set.of("chunk-1")));
    }
}
