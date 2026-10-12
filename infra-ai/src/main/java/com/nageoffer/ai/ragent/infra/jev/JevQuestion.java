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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发给 System One 的一道题。题是封闭的：调用方列出全部合法选项或等级，模型只在其中给概率
 */
public sealed interface JevQuestion {

    /**
     * 问什么；判断依据放在请求的 state 里，这里可以用反引号引用 state 的字段名
     */
    String instructions();

    /**
     * 是/否题，答案是"是"的概率
     *
     * @param whenTrue  "是"指什么，可空
     * @param whenFalse "否"指什么，可空
     */
    record Noul(String instructions, String whenTrue, String whenFalse) implements JevQuestion {
    }

    /**
     * 单选题，2 到 255 个选项；选项说明可空
     */
    record Choice(String instructions, Map<String, String> options) implements JevQuestion {

        public Choice {
            options = options == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
    }

    /**
     * 有序等级题，2 到 10 级，第 0 级在前；答案是按概率加权的等级分
     */
    record Score(String instructions, List<String> levels) implements JevQuestion {

        public Score {
            levels = levels == null ? null : List.copyOf(levels);
        }
    }
}
