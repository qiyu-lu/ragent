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

package com.nageoffer.ai.ragent.rag.dto;

import java.util.List;

/**
 * 请求级上下文选择诊断。
 *
 * @param fairRefillEnabled 公平去重回填是否开启
 * @param requestTopK 请求最终上下文额度
 * @param initialBudgets 各子问题初始配额
 * @param candidateCount 各子问题候选数之和（含跨题重复）
 * @param candidateUniqueCount 候选池全局唯一数
 * @param uniqueBeforeRefill 旧版按题截断后可用的唯一数
 * @param refillAdded 公平选择相对旧版固定前缀新增的唯一块数；包含配额内跳过重复和后续回填
 * @param finalUniqueCount 最终唯一块数
 * @param unfilledSlots 最终仍未填满的请求额度
 */
public record RetrievalSelectionDiagnostics(boolean fairRefillEnabled,
                                            int requestTopK,
                                            List<Integer> initialBudgets,
                                            int candidateCount,
                                            int candidateUniqueCount,
                                            int uniqueBeforeRefill,
                                            int refillAdded,
                                            int finalUniqueCount,
                                            int unfilledSlots) {

    public RetrievalSelectionDiagnostics {
        initialBudgets = initialBudgets == null ? List.of() : List.copyOf(initialBudgets);
    }
}
