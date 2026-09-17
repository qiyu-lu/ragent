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

/** 可核对的草稿；缺失参数保留 null，不包含审批或执行状态。 */
public record PlanDraft(List<Requirement> prerequisites, List<Step> steps,
                        List<Requirement> resources, List<Requirement> cautions,
                        List<String> pendingItems) {
    public record Requirement(String text, List<String> evidenceIds) { }
    public record Step(Integer order, String action, List<String> evidenceIds, List<Parameter> parameters) { }
    public record Parameter(String name, String value, String unit, List<String> evidenceIds) { }
}
