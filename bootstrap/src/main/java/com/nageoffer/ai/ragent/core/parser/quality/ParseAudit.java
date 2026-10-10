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

package com.nageoffer.ai.ragent.core.parser.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次解析质量审计的结论，写进文档元数据 {@code doc_metadata.parseAudit}，来源面板据此提示
 *
 * @param verdict        {@link Verdict}
 * @param textLayerClass 预判类别
 * @param pages          页数
 * @param textLayerDigits 文字层数字数（去掉重复行与页码行后）
 * @param attempts       依次做过的解析尝试
 * @param chosenAttempt  入库用的是第几次尝试，从 0 开始
 */
public record ParseAudit(Verdict verdict,
                         TextLayerClass textLayerClass,
                         int pages,
                         int textLayerDigits,
                         List<Attempt> attempts,
                         int chosenAttempt) {

    public ParseAudit {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public enum Verdict {
        /**
         * 首次解析即合格
         */
        PASSED,
        /**
         * 首次不合格，换 OCR 设置重解析后合格
         */
        RECOVERED,
        /**
         * 所有尝试都不合格（或重解析本身失败），保留得分高的那次，需要人工复核
         */
        NEEDS_REVIEW
    }

    /**
     * @param params             发给 MinerU 的参数
     * @param digits             解析文本数字数
     * @param nonSpaceChars      解析文本非空白字符数
     * @param strictSlots        严格空槽数
     * @param strictSlotsPer1000 每千个非空白字符的严格空槽
     * @param digitRetention     数字保留率；扫描件与坏字体为 null
     * @param passed             是否合格
     * @param failures           不合格的原因
     * @param parseError         解析本身失败（超时、服务报错）时的原因；这样的尝试没有结果，不会被选中
     */
    public record Attempt(Map<String, Object> params,
                          int digits,
                          int nonSpaceChars,
                          int strictSlots,
                          double strictSlotsPer1000,
                          Double digitRetention,
                          boolean passed,
                          List<String> failures,
                          String parseError) {

        private static final int MAX_ERROR_LENGTH = 300;

        public Attempt {
            params = params == null ? Map.of() : Map.copyOf(params);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public Attempt(Map<String, Object> params, int digits, int nonSpaceChars, int strictSlots,
                       double strictSlotsPer1000, Double digitRetention, boolean passed, List<String> failures) {
            this(params, digits, nonSpaceChars, strictSlots, strictSlotsPer1000, digitRetention, passed, failures, null);
        }

        /**
         * 解析调用本身失败的一次尝试
         */
        static Attempt parseFailed(Map<String, Object> params, String error) {
            String message = error == null || error.isBlank() ? "unknown" : error.strip();
            if (message.length() > MAX_ERROR_LENGTH) {
                message = message.substring(0, MAX_ERROR_LENGTH);
            }
            return new Attempt(params, 0, 0, 0, 0D, null, false, List.of("parseError"), message);
        }

        public boolean failedToParse() {
            return parseError != null;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("params", new LinkedHashMap<>(params));
            map.put("digits", digits);
            map.put("nonSpaceChars", nonSpaceChars);
            map.put("strictSlots", strictSlots);
            map.put("strictSlotsPer1000", round(strictSlotsPer1000));
            map.put("digitRetention", digitRetention == null ? null : round(digitRetention));
            map.put("passed", passed);
            map.put("failures", failures);
            if (parseError != null) {
                map.put("parseError", parseError);
            }
            return map;
        }
    }

    public Attempt chosen() {
        return attempts.get(chosenAttempt);
    }

    /**
     * 落库用的 JSON 结构
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("verdict", verdict.name());
        map.put("textLayerClass", textLayerClass.name());
        map.put("pages", pages);
        map.put("textLayerDigits", textLayerDigits);
        map.put("chosenAttempt", chosenAttempt);
        map.put("attempts", attempts.stream().map(Attempt::toMap).toList());
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
