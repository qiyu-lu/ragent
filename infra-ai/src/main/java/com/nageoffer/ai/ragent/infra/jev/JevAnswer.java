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

package com.nageoffer.ai.ragent.infra.jev;

import java.util.Map;

/**
 * 一道题的答案，类型与题目一一对应；{@link JevClient} 返回前已核对过取值范围
 */
public sealed interface JevAnswer {

    /**
     * 是/否题：答案为"是"的概率
     */
    record Noul(double probability) implements JevAnswer {
    }

    /**
     * 单选题：概率最高的选项、每个选项的概率（和为 1）与置信度
     */
    record Choice(String choice, Map<String, Double> probabilities, double confidence) implements JevAnswer {
    }

    /**
     * 等级题：按概率加权的等级分（可落在两级之间）、等级说明、每级的概率与置信度；键是等级下标 "0"、"1"…
     */
    record Score(double score, Map<String, String> legend, Map<String, Double> probabilities,
                 double confidence) implements JevAnswer {
    }
}
