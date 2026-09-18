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
import com.nageoffer.ai.ragent.research.model.ResearchEvent;
import com.nageoffer.ai.ragent.research.model.SubtaskResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由持久事件重建主 Agent 当前阶段（最近一次补充条件之后）的对话：成对的 TOOL_STARTED / TOOL_ENDED 还原为
 * assistant 的 tool_call 与工具结果，同一轮的多个调用归入同一条 assistant 消息。没有配对完成的调用丢弃，
 * 由模型重新决定——语义是至少一次；检索与阅读只读，证据按稳定 ID 幂等保存，所以重做安全。
 */
public final class ResearchHistory {
    public static final String FINISH_REPAIR_MESSAGE = "Your previous response did not finish through the native tool. Call finish_research now. "
            + "Use only findings supported by already-read evidence, with exact evidenceIds from the server reminder. "
            + "If unsupported, return empty findings and an explicit gap. Do not search again, output plain text, "
            + "or imitate a tool call in JSON. Preserve the existing research and user constraints.";

    /** 一次已完成的工具调用；readEvidenceIds 是该调用期间主 Agent 读到的证据。 */
    public record Step(String toolCallId, String tool, Map<String, Object> arguments, String rawArguments,
                       String output, String status, List<String> readEvidenceIds) {
        public boolean succeeded() { return ToolResultState.SUCCESS.name().equals(status); }
    }
    private record Turn(List<Step> steps) { }
    private record Repair() { }

    private final String request;
    private final List<Object> entries;
    private final int dropped;
    private final Map<String, Object> concluded;

    private ResearchHistory(String request, List<Object> entries, int dropped, Map<String, Object> concluded) {
        this.request = request;
        this.entries = List.copyOf(entries);
        this.dropped = dropped;
        this.concluded = concluded;
    }

    /** 事件须按序号排列且属于同一任务；没有找到原始请求（旧事件或尚未开始研究）时返回 null，调用方从头执行。 */
    @SuppressWarnings("unchecked")
    public static ResearchHistory from(List<ResearchEvent> events) {
        int start = 0;
        for (int i = 0; i < events.size(); i++) if ("INPUT_RECEIVED".equals(events.get(i).type())) start = i + 1;
        String request = null;
        List<Object> entries = new ArrayList<>();
        Batch pending = null;
        int dropped = 0;
        Map<String, Object> concluded = null;
        for (var event : events.subList(start, events.size())) {
            if (!"main".equals(event.taskId())) continue;
            var payload = event.payload();
            switch (event.type()) {
                case "RESEARCH_STARTED" -> { if (request == null && payload.get("request") instanceof String text) request = text; }
                case "RUN_STARTED", "NATIVE_FINISH_REPAIR" -> {
                    if (pending != null) dropped += pending.flushInto(entries);
                    pending = null;
                    if (event.type().equals("NATIVE_FINISH_REPAIR") && request != null) entries.add(new Repair());
                }
                case "TOOL_STARTED" -> {
                    String id = String.valueOf(payload.get("toolCallId"));
                    if (pending == null || !pending.ids.contains(id) || pending.calls.containsKey(id)) {
                        if (pending != null) dropped += pending.flushInto(entries);
                        pending = new Batch(payload.get("batch") instanceof List<?> ids ? ids.stream().map(String::valueOf).toList() : List.of(id));
                    }
                    Map<String, Object> arguments = payload.get("arguments") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
                    pending.calls.put(id, new Open(id, String.valueOf(payload.get("tool")), arguments,
                            payload.get("rawArguments") instanceof String raw ? raw : null));
                    pending.open = pending.calls.get(id);
                }
                case "SOURCE_READ" -> { if (pending != null && pending.open != null) pending.open.reads.add(String.valueOf(payload.get("evidenceId"))); }
                case "TOOL_ENDED" -> {
                    Open call = pending == null ? null : pending.calls.get(String.valueOf(payload.get("toolCallId")));
                    if (call != null) {
                        call.output = String.valueOf(payload.getOrDefault("output", ""));
                        call.status = String.valueOf(payload.get("status"));
                    }
                    if (pending != null) pending.open = null;
                }
                case "TOOL_FAILED" -> { if (pending != null) pending.open = null; }
                case "RESEARCH_CONCLUDED" -> {
                    concluded = payload;
                    // 结束调用本身不会有 TOOL_ENDED；它已生效，不计入丢弃。
                    if (pending != null && pending.open != null) { pending.calls.remove(pending.open.id); pending.ids.remove(pending.open.id); pending.open = null; }
                }
                default -> { }
            }
        }
        if (pending != null) dropped += pending.flushInto(entries);
        return request == null ? null : new ResearchHistory(request, entries, dropped, concluded);
    }

    public String request() { return request; }
    public int droppedToolCalls() { return dropped; }
    public List<Step> steps() {
        return entries.stream().filter(Turn.class::isInstance).flatMap(e -> ((Turn) e).steps().stream()).toList();
    }
    public int repairs() { return (int) entries.stream().filter(Repair.class::isInstance).count(); }

    /** 预载进 Agent 的消息：原始首条请求在前，逐字节与首次执行相同，缓存前缀因此跨实例可复用。 */
    public List<Msg> messages(String agentName) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().role(MsgRole.USER).textContent(request).build());
        for (Object entry : entries) {
            if (entry instanceof Repair) {
                messages.add(Msg.builder().role(MsgRole.USER).textContent(FINISH_REPAIR_MESSAGE).build());
                continue;
            }
            List<ContentBlock> uses = new ArrayList<>();
            for (Step step : ((Turn) entry).steps()) {
                uses.add(new ToolUseBlock(step.toolCallId(), step.tool(), step.arguments(), step.rawArguments(), Map.of()));
            }
            // SDK 把 Agent 名写进 assistant 消息的 name 字段；缺了它请求体就不再逐字节相同。
            messages.add(Msg.builder().role(MsgRole.ASSISTANT).name(agentName).content(uses).build());
            for (Step step : ((Turn) entry).steps()) {
                messages.add(Msg.builder().role(MsgRole.TOOL).content(new ToolResultBlock(step.toolCallId(), step.tool(),
                        List.of(TextBlock.builder().text(step.output()).build()), Map.of(), ToolResultState.valueOf(step.status()))).build());
            }
        }
        return messages;
    }

    /** 本阶段已得出结论（finish_research / ask_user）时，接管后直接沿用，不再调用模型。 */
    public ResearchSession.Outcome outcome(ObjectMapper json) {
        if (concluded == null) return null;
        if (concluded.get("question") instanceof String question) return new ResearchSession.Outcome(question, null);
        return new ResearchSession.Outcome(null, json.convertValue(concluded.get("result"), SubtaskResult.class));
    }

    /** 已完成的 conduct_research 输出里出现过的 worker；其余已恢复的 worker 结果需要另行告知模型。 */
    public Set<String> reportedWorkers(ObjectMapper json) {
        Set<String> ids = new LinkedHashSet<>();
        for (Step step : steps()) {
            if (!"conduct_research".equals(step.tool()) || !step.succeeded()) continue;
            try { json.readTree(step.output()).findValues("taskId").forEach(id -> ids.add(id.asText())); }
            catch (Exception ignored) { }
        }
        return ids;
    }

    private static final class Open {
        final String id, tool, raw;
        final Map<String, Object> arguments;
        final List<String> reads = new ArrayList<>();
        String output, status;
        Open(String id, String tool, Map<String, Object> arguments, String raw) {
            this.id = id; this.tool = tool; this.arguments = arguments; this.raw = raw;
        }
    }

    private static final class Batch {
        final List<String> ids;
        final Map<String, Open> calls = new LinkedHashMap<>();
        Open open;
        Batch(List<String> ids) { this.ids = new ArrayList<>(ids); }

        /** 只保留有结果的调用；返回丢弃的调用数（已开始未结束的，加上同一轮里尚未开始的）。 */
        int flushInto(List<Object> entries) {
            List<Step> done = calls.values().stream().filter(c -> c.status != null)
                    .map(c -> new Step(c.id, c.tool, c.arguments, c.raw, c.output, c.status, List.copyOf(c.reads))).toList();
            if (!done.isEmpty()) entries.add(new Turn(done));
            return ids.size() - done.size();
        }
    }
}
