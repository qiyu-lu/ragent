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

package com.nageoffer.ai.ragent.ironore.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ironore.model.WorkbookDiffView;
import com.nageoffer.ai.ragent.ironore.service.WorkbookDiffService;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class IronOreVersionDiffToolExecutor implements McpToolExecutor {

    private final WorkbookDiffService workbookDiffService;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("documentKey", stringProperty(
                "跨版本稳定文档键，例如：铁矿石人工检测流程调研", "铁矿石人工检测流程调研"));
        properties.put("baseVersion", stringProperty("基准版本，例如：V1.2", "V1.2"));
        properties.put("targetVersion", stringProperty("目标版本，例如：V1.3-demo", "V1.3-demo"));
        return Tool.builder()
                .name("iron_ore_compare_versions")
                .title("铁矿 XLSX 版本差异")
                .description("确定性比较同一 XLSX 文档两个版本的单元格变化，不使用模型猜测差异。")
                .inputSchema(new JsonSchema(
                        "object", properties, List.of("documentKey", "baseVersion", "targetVersion"), false, null, null))
                .build();
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        try {
            WorkbookDiffView result = workbookDiffService.compare(
                    required(parameters, "documentKey"),
                    required(parameters, "baseVersion"),
                    required(parameters, "targetVersion"));
            return CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return CallToolResult.builder()
                    .addTextContent("版本差异比较失败：" + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private Map<String, Object> stringProperty(String description, String defaultValue) {
        return Map.of("type", "string", "description", description, "default", defaultValue);
    }

    private String required(Map<String, Object> parameters, String name) {
        Object value = parameters == null ? null : parameters.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少参数 " + name);
        }
        return value.toString().trim();
    }
}
