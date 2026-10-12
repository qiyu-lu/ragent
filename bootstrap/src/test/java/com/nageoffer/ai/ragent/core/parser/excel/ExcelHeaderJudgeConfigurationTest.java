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

import com.nageoffer.ai.ragent.infra.jev.JevClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelHeaderJudgeConfigurationTest {

    private final ExcelHeaderJudgeConfiguration configuration = new ExcelHeaderJudgeConfiguration();

    @Test
    void ruleIsTheDefault() {
        assertSame(RuleHeaderJudge.INSTANCE, configuration.excelHeaderJudge(new ExcelLayoutProperties(), provider(client(true))));
    }

    @Test
    void jevNeedsAKeyOtherwiseFallsBackToTheRule() {
        ExcelLayoutProperties properties = new ExcelLayoutProperties();
        properties.setHeaderJudge(ExcelLayoutProperties.HeaderJudgeMode.JEV);

        assertInstanceOf(JevHeaderJudge.class, configuration.excelHeaderJudge(properties, provider(client(true))));
        assertSame(RuleHeaderJudge.INSTANCE, configuration.excelHeaderJudge(properties, provider(client(false))));
        assertSame(RuleHeaderJudge.INSTANCE, configuration.excelHeaderJudge(properties, provider(null)));
    }

    private static JevClient client(boolean configured) {
        JevClient client = mock(JevClient.class);
        when(client.isConfigured()).thenReturn(configured);
        when(client.model()).thenReturn("jev-1.13.0");
        return client;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JevClient> provider(JevClient client) {
        ObjectProvider<JevClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
