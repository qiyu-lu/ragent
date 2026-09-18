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
import com.fasterxml.jackson.annotation.JsonProperty;

/** 可核对的草稿；缺失参数保留 null，不包含审批或执行状态。 */
public record PlanDraft(@JsonProperty(required = true) List<Requirement> prerequisites, @JsonProperty(required = true) List<Step> steps,
                        @JsonProperty(required = true) List<Requirement> resources, @JsonProperty(required = true) List<Requirement> cautions,
                        @JsonProperty(required = true) List<String> pendingItems) {
    public record Requirement(@JsonProperty(required = true) String text, @JsonProperty(required = true) List<String> evidenceIds) { }
    public record Step(@JsonProperty(required = true) Integer order, @JsonProperty(required = true) String action, @JsonProperty(required = true) List<String> evidenceIds, @JsonProperty(required = true) List<Parameter> parameters) { }
    public record Parameter(@JsonProperty(required = true) String name, @JsonProperty(required = true) String value, @JsonProperty(required = true) String unit, @JsonProperty(required = true) List<String> evidenceIds) { }
}
