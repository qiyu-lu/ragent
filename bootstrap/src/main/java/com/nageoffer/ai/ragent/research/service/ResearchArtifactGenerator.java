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

package com.nageoffer.ai.ragent.research.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.research.config.ResearchProperties;
import com.nageoffer.ai.ragent.research.model.*;
import com.nageoffer.ai.ragent.research.runtime.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 同一研究的末尾生成调用；结构/引用失败最多修复一次，不另起 Agent。 */
@Component
public class ResearchArtifactGenerator {
    public static final String PROMPT_VERSION = "research-artifact-v1";
    private final ResearchModelFactory models;
    private final ResearchProperties properties;
    private final ResearchEvidenceStore evidenceStore;
    private final PlanDraftValidator plans;
    private final ObjectMapper json;
    private final TokenCounterService tokens;
    private final String prompt;

    public ResearchArtifactGenerator(ResearchModelFactory models, ResearchProperties properties,
                                     ResearchEvidenceStore evidenceStore, PlanDraftValidator plans,
                                     ObjectMapper json, TokenCounterService tokens) {
        this.models = models;
        this.properties = properties;
        this.evidenceStore = evidenceStore;
        this.plans = plans;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.tokens = tokens;
        try (var input = new ClassPathResource("prompts/" + PROMPT_VERSION + ".txt").getInputStream()) {
            prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("产物提示词读取失败", error); }
    }

    public ResearchArtifact generate(ResearchSession session, SubtaskResult result) {
        session.check();
        Map<String, EvidenceRecord> evidence = new LinkedHashMap<>();
        // 确认本次运行、实际已读、服务端范围；worker 候选不能越过压缩结果的读证明。
        for (String id : session.citableIds().stream().sorted().toList()) {
            var item = evidenceStore.find(session.claim.run().id(), session.claim.owner(), id).evidence();
            if (!item.read() || !id.equals(item.evidenceId()) || !session.claim.run().id().equals(item.runId())
                    || !session.claim.run().brief().allowedKbIds().contains(item.kbId())
                    || !session.documents().isEmpty() && !session.documents().contains(item.docId())) {
                throw new IllegalArgumentException("ARTIFACT_EVIDENCE_OUT_OF_SCOPE_OR_UNREAD");
            }
            evidence.put(id, item);
        }
        List<Msg> messages = input(session, result, evidence);
        var model = new BoundedResearchModel(models.create(), session, properties, models.quota(), json, tokens, true);
        session.event("FINALIZATION_STARTED", "正在整理并校验研究产物", Map.of("promptVersion", PROMPT_VERSION, "outputType", session.claim.run().brief().outputType()));
        for (int attempt = 0; attempt < 2; attempt++) {
            session.check();
            StringBuilder raw = new StringBuilder();
            model.stream(messages, List.of(), null).takeUntilOther(session.control.signal())
                    .doOnNext(response -> {
                        for (var block : response.getContent()) if (block instanceof TextBlock text) raw.append(text.getText());
                        if (raw.length() > 100000) throw new IllegalArgumentException("ARTIFACT_OUTPUT_TOO_LARGE");
                    }).then().timeout(session.budget.remaining()).block();
            session.check();
            try {
                var payload = json.readValue(raw.toString(), ResearchArtifact.Payload.class);
                return validate(session.claim.run().brief(), payload, result, session.results(), evidence);
            } catch (Exception invalid) {
                String reason = invalid instanceof IllegalArgumentException ? invalid.getMessage() : "ARTIFACT_JSON_INVALID";
                session.event("FINALIZATION_VALIDATION_FAILED", "产物结构或引用校验失败", Map.of("attempt", attempt + 1, "reason", reason));
                if (attempt == 1) throw new IllegalStateException("ARTIFACT_VALIDATION_FAILED", invalid);
                List<Msg> repair = new ArrayList<>(messages);
                repair.add(Msg.builder().role(MsgRole.ASSISTANT).textContent(raw.toString()).build());
                repair.add(Msg.builder().role(MsgRole.USER).textContent("Return the corrected complete JSON only. Validation error: " + reason).build());
                // 必须完整保留目标、证据和修复对象；无法容纳时停止，不能裁掉证据后继续引用。
                if (estimate(repair) > properties.getMaxInputTokens()) throw new ResearchBudget.Exhausted("ARTIFACT_REPAIR_CONTEXT_BUDGET");
                messages = repair;
            }
        }
        throw new IllegalStateException("ARTIFACT_VALIDATION_FAILED");
    }

    private List<Msg> input(ResearchSession session, SubtaskResult result, Map<String, EvidenceRecord> evidence) {
        List<Msg> messages;
        while (true) {
            try {
                messages = List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(prompt).build(),
                        Msg.builder().role(MsgRole.USER).textContent(json.writeValueAsString(Map.of(
                                "brief", session.claim.run().brief(), "findings", result,
                                "workerResults", session.results(), "evidence", evidence.values()))).build());
            } catch (Exception error) { throw new IllegalStateException("产物输入序列化失败", error); }
            if (estimate(messages) <= properties.getMaxInputTokens()) return messages;
            var longest = evidence.values().stream().filter(e -> e.text().length() > 256)
                    .max(Comparator.comparingInt(e -> e.text().length())).orElseThrow(() -> new ResearchBudget.Exhausted("ARTIFACT_CONTEXT_BUDGET"));
            String excerpt = EvidenceText.preview(longest.text(), Math.max(256, longest.text().length() / 2));
            evidence.put(longest.evidenceId(), longest.withReadText(excerpt, true));
        }
    }

    private int estimate(List<Msg> messages) {
        try { return tokens.countTokens(json.writeValueAsString(Map.of("messages", messages, "tools", List.of()))); }
        catch (Exception error) { throw new IllegalStateException("产物上下文估算失败", error); }
    }

    ResearchArtifact validate(ResearchBrief brief, ResearchArtifact.Payload payload, SubtaskResult main,
                              List<SubtaskResult> workers, Map<String, EvidenceRecord> evidence) {
        if (payload == null) throw new IllegalArgumentException("ARTIFACT_REQUIRED");
        PlanDraftValidator.text(payload.title());
        PlanDraftValidator.bounded(payload.sections(), 30);
        PlanDraftValidator.strings(payload.gaps(), 40);
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        if (brief.outputType() == ResearchBrief.OutputType.REPORT) {
            if (payload.plan() != null) throw new IllegalArgumentException("REPORT_MUST_NOT_CONTAIN_PLAN");
            for (var section : payload.sections()) {
                PlanDraftValidator.text(section.heading());
                PlanDraftValidator.text(section.text());
                PlanDraftValidator.references(section.evidenceIds(), evidence.keySet(), true);
                referenced.addAll(section.evidenceIds());
            }
        } else {
            if (!payload.sections().isEmpty()) throw new IllegalArgumentException("PLAN_MUST_NOT_CONTAIN_REPORT_SECTIONS");
            plans.validate(payload.plan(), evidence.keySet());
            payload.plan().prerequisites().forEach(r -> referenced.addAll(r.evidenceIds()));
            payload.plan().steps().forEach(s -> {
                referenced.addAll(s.evidenceIds());
                s.parameters().forEach(p -> referenced.addAll(p.evidenceIds()));
            });
            payload.plan().resources().forEach(r -> referenced.addAll(r.evidenceIds()));
            payload.plan().cautions().forEach(r -> referenced.addAll(r.evidenceIds()));
        }
        LinkedHashSet<String> gaps = new LinkedHashSet<>(main.gaps());
        workers.forEach(w -> gaps.addAll(w.gaps()));
        gaps.addAll(payload.gaps());
        LinkedHashSet<String> conflicts = new LinkedHashSet<>(main.conflicts());
        workers.forEach(w -> conflicts.addAll(w.conflicts()));
        PlanDraft plan = payload.plan();
        if (plan != null) {
            LinkedHashSet<String> pending = new LinkedHashSet<>(plan.pendingItems());
            plan.steps().forEach(s -> s.parameters().stream().filter(p -> p.value() == null || p.value().isBlank())
                    .forEach(p -> pending.add("步骤 " + s.order() + " 的参数“" + p.name() + "”未提供，待确认")));
            plan = new PlanDraft(plan.prerequisites(), plan.steps(), plan.resources(), plan.cautions(), List.copyOf(pending));
            if (plan.steps().isEmpty() && pending.isEmpty() && gaps.isEmpty()) throw new IllegalArgumentException("EMPTY_PLAN_MUST_EXPLAIN_GAPS");
        } else if (payload.sections().isEmpty() && gaps.isEmpty()) throw new IllegalArgumentException("EMPTY_REPORT_MUST_EXPLAIN_GAPS");
        List<ResearchArtifact.Citation> citations = new ArrayList<>();
        for (String id : referenced) {
            var e = evidence.get(id);
            citations.add(new ResearchArtifact.Citation(citations.size() + 1, id, e.docId(), e.documentName(), e.documentVersion(),
                    e.contentHash(), e.chunkIds(), e.text(), e.sourceLocation(), e.truncated(), e.sourceExtent()));
        }
        Map<String, Integer> indexes = new HashMap<>();
        citations.forEach(c -> indexes.put(c.evidenceId(), c.index()));
        StringBuilder markdown = new StringBuilder("# " + payload.title() + "\n\n");
        for (var section : payload.sections()) markdown.append("## ").append(section.heading()).append("\n\n")
                .append(section.text()).append(" ").append(section.evidenceIds().stream().map(id -> "[" + indexes.get(id) + "](#cite-" + indexes.get(id) + ")").reduce("", String::concat)).append("\n\n");
        if (!gaps.isEmpty()) markdown.append("## 资料缺口\n\n").append(String.join("\n\n", gaps)).append("\n\n");
        if (!conflicts.isEmpty()) markdown.append("## 资料冲突\n\n").append(String.join("\n\n", conflicts));
        return new ResearchArtifact(brief.outputType(), payload.title(), brief.goal(),
                brief.constraints().stream().map(c -> new ResearchArtifact.UserConstraint(c, "user_input")).toList(),
                payload.sections(), plan, List.copyOf(gaps), List.copyOf(conflicts), citations, markdown.toString(), PROMPT_VERSION);
    }
}
