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

package com.nageoffer.ai.ragent.research.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/** AgentScope 2.0.1 已在 DTO 转换前校验 schema；为其结束工具错误补充遗漏的字段路径。 */
final class ResearchToolkit extends Toolkit {
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final ObjectMapper json;

    ResearchToolkit(ObjectMapper json) {
        super(ToolkitConfig.builder().parallel(false).build());
        this.json = json;
    }

    @Override
    public Toolkit copy() {
        // ReActAgent 构建时会复制 Toolkit。研究工具仅使用静态 ungrouped 白名单。
        var copy = new ResearchToolkit(json);
        getToolNames().forEach(name -> copy.registerAgentTool(getTool(name)));
        return copy;
    }

    @Override
    public Mono<List<ToolResultBlock>> callTools(List<ToolUseBlock> calls, ExecutionConfig config,
                                                Agent agent, RuntimeContext context) {
        // 保留 SDK 的执行、取消、工具 ID 和原生错误；只补充已被 schema 拒绝的参数诊断。
        return super.callTools(calls, config, agent, context).map(results -> results.stream().map(result -> {
            if (result.getState() != ToolResultState.ERROR || !"finish_research".equals(result.getName())) return result;
            var call = calls.stream().filter(c -> c.getId().equals(result.getId())).findFirst().orElseThrow();
            try {
                var schema = SCHEMAS.getSchema(json.writeValueAsString(getTool(call.getName()).getParameters()));
                var errors = schema.validate(call.getContent(), InputFormat.JSON);
                if (errors.isEmpty()) return result; // 未读引用等业务校验仍返回原来的明确原因。
                String detail = errors.stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                        .collect(Collectors.joining("\n"));
                return new ToolResultBlock(call.getId(), call.getName(),
                        List.of(TextBlock.builder().text("Error: INVALID_TOOL_ARGUMENTS\n" + detail).build()),
                        result.getMetadata(), ToolResultState.ERROR);
            } catch (Exception error) {
                // 诊断不可覆盖 SDK 原有的安全拒绝。
                return result;
            }
        }).toList());
    }
}
