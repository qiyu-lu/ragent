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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 解析质量闸门配置（knowledge-quality 计划 §5.1）
 * <p>
 * 阈值在 2026-10-09 的解析实验上标定（6 份标准 PDF 都在调参集里）：坏解析的严格空槽每千字 1.3～9.2、每份几十处，
 * 数字保留率 0.40～0.69；好解析的空槽 0～1 处（那 1 处是"修约。"误报，2026-10-10 起排除），保留率 ≥ 0.98。
 * {@code KqLocalCorpusCalibrationTest} 在本机数据上复核这组判定。Java 字段默认关闭，{@code application.yaml}
 * 自 2026-10-10 起打开（过了阶段 2 门槛且测试集复现）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion.parse-quality")
public class ParseQualityProperties {

    /**
     * 总开关：只作用于 MinerU 解析的 PDF
     */
    private boolean enabled = false;

    /**
     * 每页字符数（去掉重复行与页码行后）的中位数低于它判扫描件；硫铁矿那份为 0，文字层可用的 ≥ 359
     */
    private int scannedMaxMedianCharsPerPage = 50;

    /**
     * 私用区与替换字符占非空白字符的比例达到它判坏字体；本语料最高 0.2%，未标定，取保守值
     */
    private double garbledMinRatio = 0.1;

    /**
     * 一行出现在不少于这个比例的页面上，算页眉、页脚或水印
     */
    private double repeatedLineMinPageShare = 0.5;

    /**
     * 严格空槽每千个非空白字符超过它、且不少于 {@link #minStrictSlots} 处即不合格
     */
    private double maxStrictSlotsPer1000 = 0.3;

    /**
     * 空槽至少这么多处才看密度：两三千字的短标准（本语料 YS/T 两份与硫铁矿）里一处误报就超过 0.3 处/千字；
     * 坏解析每份都有几十处
     */
    private int minStrictSlots = 3;

    /**
     * 数字保留率（解析文本数字 / 文字层数字）低于它即不合格；扫描件与坏字体不看这一项
     */
    private double minDigitRetention = 0.85;

    /**
     * 不合格时是否用另一种 OCR 设置重解析一次，保留得分高的
     */
    private boolean fallbackEnabled = true;
}
