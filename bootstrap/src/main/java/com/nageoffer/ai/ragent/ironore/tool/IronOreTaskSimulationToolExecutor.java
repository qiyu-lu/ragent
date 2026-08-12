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
import com.nageoffer.ai.ragent.ironore.model.TaskExecutionView;
import com.nageoffer.ai.ragent.ironore.service.IronOreTaskTemplateService;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class IronOreTaskSimulationToolExecutor implements McpToolExecutor {

    private final IronOreTaskTemplateService taskTemplateService;
    private final ObjectMapper objectMapper;

    @Override
    public Tool getToolDefinition() {
        Map<String, Object> properties = Map.of(
                "taskTemplateId", Map.of(
                        "type", "string",
                        "description", "已经人工批准的候选任务模板 ID"));
        return Tool.builder()
                .name("iron_ore_simulate_task")
                .title("模拟执行铁矿候选任务")
                .description("只生成模拟事件，不连接、不控制任何真实设备。任务必须先由用户批准。")
                .inputSchema(new JsonSchema(
                        "object", properties, List.of("taskTemplateId"), false, null, null))
                .build();
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        try {
            Object rawId = parameters == null ? null : parameters.get("taskTemplateId");
            if (rawId == null || rawId.toString().isBlank()) {
                throw new IllegalArgumentException("缺少参数 taskTemplateId");
            }
            TaskExecutionView result = taskTemplateService.simulate(rawId.toString().trim());
            return CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return CallToolResult.builder()
                    .addTextContent("候选任务模拟失败：" + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
