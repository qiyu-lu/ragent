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

import java.util.List;

/**
 * PDFBox 抽出的文字层统计，按页去掉重复行与页码行之后计
 *
 * @param pages              页数
 * @param textLayerClass     预判类别
 * @param medianCharsPerPage 每页非空白字符数的中位数
 * @param digits             Unicode 十进制数字（含全角）个数，审计数字保留率的分母
 * @param nonSpaceChars      非空白字符数
 * @param garbledRatio       私用区与替换字符占比（按未去重复行的全文计）
 * @param repeatedLines      出现在多数页面的整行，已转成比对形态（{@code IngestionTextNormalizer.matchForm}）
 * @param headText           前两页原文（截断），供文档元数据抽取标准号与"代替"
 */
public record PdfTextLayer(int pages,
                           TextLayerClass textLayerClass,
                           int medianCharsPerPage,
                           int digits,
                           int nonSpaceChars,
                           double garbledRatio,
                           List<String> repeatedLines,
                           String headText) {

    public PdfTextLayer {
        repeatedLines = repeatedLines == null ? List.of() : List.copyOf(repeatedLines);
        headText = headText == null ? "" : headText;
    }
}
