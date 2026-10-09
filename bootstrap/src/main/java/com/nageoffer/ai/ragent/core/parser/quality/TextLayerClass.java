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

/**
 * PDF 文字层预判结果，决定首次解析是否强开 OCR，以及审计看不看数字保留率
 */
public enum TextLayerClass {

    /**
     * 文字层可用：首次按默认参数解析，审计看空槽与数字保留率
     */
    TEXT,

    /**
     * 扫描件：首次按默认参数解析（MinerU 会对无文字页自动 OCR，强开反而更差），审计只看空槽
     */
    SCANNED,

    /**
     * 坏字体：文字层多为私用区或替换字符，首次就强开 OCR，审计只看空槽
     */
    GARBLED
}
