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

package com.nageoffer.ai.ragent.framework.convention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievedChunkTest {

    @Test
    void rankingTextDoesNotReplaceVisibleText() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .text("原始正文")
                .rankingText("文档身份\n章节路径\n原始正文")
                .build();

        assertEquals("文档身份\n章节路径\n原始正文", chunk.textForRanking());
        assertEquals("原始正文", chunk.getText());
    }

    @Test
    void rankingFallsBackToVisibleTextForOldIndexesAndExternalChannels() {
        RetrievedChunk chunk = RetrievedChunk.builder().text("原始正文").build();

        assertEquals("原始正文", chunk.textForRanking());
    }
}
