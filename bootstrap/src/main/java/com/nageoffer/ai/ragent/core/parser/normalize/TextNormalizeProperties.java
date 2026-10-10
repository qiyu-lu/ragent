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

package com.nageoffer.ai.ragent.core.parser.normalize;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 入库文本归一化与清洗配置（knowledge-quality 计划 §5.2）。Java 字段默认关闭，{@code application.yaml}
 * 自 2026-10-10 起打开（与闸门同一臂过了阶段 2 门槛）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion.normalize")
public class TextNormalizeProperties {

    /**
     * 总开关：入库形态的归一化（全角数字字母转半角、单位字符、空白，MinerU 结果的数值型行内公式去壳），
     * 见 {@link IngestionTextNormalizer#normalizeForStorage}
     */
    private boolean enabled = false;

    /**
     * 是否按 PDF 文字层的频次规则删掉页面家具（水印、页眉、页脚）
     */
    private boolean dropRepeatedLines = true;

    /**
     * 一条家具行在解析结果里出现满这么多次才删
     */
    private int repeatedLineMinOccurrences = 3;
}
