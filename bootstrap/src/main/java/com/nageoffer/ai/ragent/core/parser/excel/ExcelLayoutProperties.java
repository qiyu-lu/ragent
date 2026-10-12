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

package com.nageoffer.ai.ragent.core.parser.excel;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Excel 版面切分与表头识别配置（table-structure 计划 §2）。版面切分 Java 字段默认关闭，{@code application.yaml}
 * 自 2026-10-11 起打开（调参集、测试集都没有退化）；表头判定默认按规则
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion.excel-layout")
public class ExcelLayoutProperties {

    /**
     * 打开时由 {@link ExcelLayoutAnalyzer} 把每个 sheet 切成区域、逐张表识别表头，标题与分段进章节路径；
     * 关闭时整张 sheet 当一张表、第 1 行当表头
     */
    private boolean enabled = false;

    /**
     * 表的第一行是不是表头由谁判断（jev-header 计划）：{@code rule} 按字数与数字占比（{@link RuleHeaderJudge}）；
     * {@code jev} 交给 Jev（{@link JevHeaderJudge}），没把握或调用失败按规则，没有密钥时整个退回 {@code rule}
     */
    private HeaderJudgeMode headerJudge = HeaderJudgeMode.RULE;

    private Jev jev = new Jev();

    public enum HeaderJudgeMode {
        RULE, JEV
    }

    /**
     * Jev 表头判定的阈值与输入大小，计划写定后不改
     */
    @Data
    public static class Jev {

        /**
         * P(表头) 不低于它就判有表头
         */
        private double confidentHeader = 0.8;

        /**
         * P(表头) 不高于它就判无表头；介于两者之间按规则
         */
        private double confidentNoHeader = 0.2;

        /**
         * 只给 Jev 看前这么多行，含待判定行
         */
        private int maxRows = 6;

        /**
         * 单格超过这么多字截断，末尾加省略号
         */
        private int maxCellChars = 80;
    }
}
