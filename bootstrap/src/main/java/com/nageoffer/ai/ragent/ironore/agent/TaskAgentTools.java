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

package com.nageoffer.ai.ragent.ironore.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@Component
@RequiredArgsConstructor
public class TaskAgentTools {
    private final TaskKnowledge knowledge;
    private final InspectionBusinessService business;
    private final ObjectMapper json;

    public List<ToolSpec> specifications() {
        return List.of(
                new ToolSpec("search_procedure", "在本任务选定规程内检索，返回带来源和版本的证据", "{query: string}"),
                new ToolSpec("inspect_sample", "查询本任务样品的检测项目、标签核对和交接资料状态", "{}"),
                new ToolSpec("list_stations", "查询工位能力、可用性和现有预约", "{}"),
                new ToolSpec("propose_submission", "提出送检草稿并等待用户确认，不执行预约", "{title: string, stationId: string, requirements: [{text: string, evidenceIds: string[]}]}"),
                new ToolSpec("ask_user", "等待用户补充资料、澄清目标或处理业务阻塞", "{question: string}"));
    }

    public Outcome execute(Run run, Decision decision) {
        JsonNode args = decision.arguments();
        if (args == null || !args.isObject()) throw new ClientException("工具参数必须为对象");
        return switch (decision.tool()) {
            case "search_procedure" -> search(run, text(args, "query", 500));
            case "inspect_sample" -> {
                var sample = business.sample(run.ownerUserId(), run.state().getSampleId());
                run.state().setSampleInspected(true);
                yield new Outcome(Status.READY, "已核对样品资料", sample);
            }
            case "list_stations" -> {
                var stations = business.stations(run.ownerUserId());
                run.state().setStationsListed(true);
                yield new Outcome(Status.READY, "已查询工位能力和预约状态", stations);
            }
            case "propose_submission" -> propose(run, args);
            case "ask_user" -> new Outcome(Status.WAITING_INPUT, text(args, "question", 1500), Map.of());
            default -> throw new ClientException("未知工具：" + decision.tool());
        };
    }

    private Outcome search(Run run, String query) {
        List<Evidence> found = knowledge.search(run.state().getDocument(), query);
        LinkedHashMap<String, Evidence> merged = new LinkedHashMap<>();
        // New search results remain available even when the evidence window is full.
        found.forEach(item -> merged.put(item.id(), item));
        run.state().getEvidence().forEach(item -> merged.putIfAbsent(item.id(), item));
        run.state().setEvidence(merged.values().stream().limit(12).toList());
        return new Outcome(Status.READY, found.isEmpty() ? "本次查询没有命中规程证据" : "已检索到规程证据", found);
    }

    private Outcome propose(Run run, JsonNode args) {
        if (!run.state().isSampleInspected() || !run.state().isStationsListed()) {
            throw new ClientException("请先查询样品资料和工位状态");
        }
        Proposal proposal;
        try { proposal = json.treeToValue(args, Proposal.class); }
        catch (Exception invalid) { throw new ClientException("草稿参数不符合工具协议"); }
        validateProposal(run.state(), proposal);
        business.validate(run, proposal);
        run.state().setProposal(proposal);
        return new Outcome(Status.WAITING_APPROVAL, "草稿已准备好，请核对规程要求后确认预约与登记", proposal);
    }

    public void validateProposal(State state, Proposal proposal) {
        if (proposal == null || proposal.title() == null || proposal.title().isBlank() || proposal.title().length() > 200
                || proposal.stationId() == null || proposal.stationId().isBlank()
                || proposal.requirements() == null || proposal.requirements().isEmpty() || proposal.requirements().size() > 12) {
            throw new ClientException("草稿需要标题、工位和 1 至 12 项有依据的办理要求");
        }
        Set<String> ids = state.getEvidence().stream().map(Evidence::id).collect(Collectors.toSet());
        for (Requirement requirement : proposal.requirements()) {
            if (requirement == null || requirement.text() == null || requirement.text().isBlank()
                    || requirement.text().length() > 1000 || requirement.evidenceIds() == null
                    || requirement.evidenceIds().isEmpty() || !ids.containsAll(requirement.evidenceIds())) {
                throw new ClientException("每项办理要求都必须引用本任务检索到的规程证据");
            }
        }
    }

    public void validateEvidence(State state) {
        Set<String> cited = state.getProposal().requirements().stream().flatMap(r -> r.evidenceIds().stream()).collect(Collectors.toSet());
        knowledge.validate(state.getDocument(), state.getEvidence().stream().filter(e -> cited.contains(e.id())).toList());
    }

    private String text(JsonNode args, String field, int max) {
        JsonNode value = args.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            throw new ClientException(field + " 必须是非空文本，长度不超过 " + max);
        }
        return value.asText().trim();
    }
}
