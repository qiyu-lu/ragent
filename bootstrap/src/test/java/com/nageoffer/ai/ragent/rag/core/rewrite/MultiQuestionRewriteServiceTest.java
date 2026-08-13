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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MultiQuestionRewriteServiceTest {

    private final MultiQuestionRewriteService service = new MultiQuestionRewriteService(
            mock(LLMService.class),
            mock(RAGConfigProperties.class),
            mock(QueryTermMappingService.class),
            mock(PromptTemplateLoader.class));

    @Test
    void honorsShouldSplitFalseEvenIfModelReturnsExtraArrayItems() {
        RewriteResult result = service.parseRewriteAndSplit("""
                {"rewrite":"标准的适用范围","should_split":false,
                 "sub_questions":["标准的对象","标准的范围"]}
                """);

        assertEquals(List.of("标准的适用范围"), result.subQuestions());
    }

    @Test
    void deduplicatesSplitFanOutWithoutDroppingDistinctQuestions() {
        RewriteResult result = service.parseRewriteAndSplit("""
                {"rewrite":"四项要求","should_split":true,
                 "sub_questions":["要求一","要求一","要求二","要求三","要求四"]}
                """);

        assertEquals(List.of("要求一", "要求二", "要求三", "要求四"), result.subQuestions());
    }
}
