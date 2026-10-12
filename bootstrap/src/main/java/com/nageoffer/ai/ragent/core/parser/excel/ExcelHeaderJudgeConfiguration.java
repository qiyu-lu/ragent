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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code rag.ingestion.excel-layout.header-judge} 选表头判定；要 Jev 但没有密钥时退回规则，启动时告警一次
 */
@Slf4j
@Configuration
public class ExcelHeaderJudgeConfiguration {

    @Bean
    public HeaderJudge excelHeaderJudge(ExcelLayoutProperties properties, ObjectProvider<JevClient> jevClient) {
        if (properties.getHeaderJudge() != ExcelLayoutProperties.HeaderJudgeMode.JEV) {
            return RuleHeaderJudge.INSTANCE;
        }
        JevClient client = jevClient.getIfAvailable();
        if (client == null || !client.isConfigured()) {
            log.warn("rag.ingestion.excel-layout.header-judge=jev，但没有 Jev 密钥（TYPESAFE_API_KEY），表头按规则判断");
            return RuleHeaderJudge.INSTANCE;
        }
        log.info("Excel 表头判定使用 Jev: model={}", client.model());
        return new JevHeaderJudge(client, properties.getJev(), RuleHeaderJudge.INSTANCE);
    }
}
