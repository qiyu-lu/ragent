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

package com.nageoffer.ai.ragent.rag.core.guidance;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.constant.RAGConstant;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图歧义引导服务。
 *
 * <p>当问题只有一个子问题，但该子问题同时命中了多个置信度相近的知识库系统节点时，
 * 说明用户的意图不够明确，无法确定应该检索哪个系统。此时引导服务会生成一段
 * 让用户主动选择目标系统的提示文案（引导文案），由 RAGChatServiceImpl 通过 SSE 发送给前端。</p>
 *
 * <p>触发条件（全部满足时才触发引导）：
 * <ol>
 *   <li>功能开关 {@code guidance.enabled = true}</li>
 *   <li>仅有一个子问题（多子问题场景默认不触发）</li>
 *   <li>候选 KB 意图中至少存在两个得分相近的节点（比值 ≥ {@code ambiguityScoreRatio}）</li>
 *   <li>这些节点归属于不同的系统</li>
 *   <li>用户问题中没有已经明确提及某个系统的名称</li>
 * </ol>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    /** 歧义引导相关配置（是否启用、分数比阈值、最大选项数）。 */
    private final GuidanceProperties guidanceProperties;
    /** 意图节点注册表，用于按 ID 查询节点的名称、层级和父节点。 */
    private final IntentNodeRegistry intentNodeRegistry;
    /** Prompt 模板加载器，用于渲染歧义引导文案。 */
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 检测当前问题是否存在系统选择歧义，并在存在时返回引导提示。
     *
     * @param question   改写后的用户问题（用于判断是否已经明确提及系统名）
     * @param subIntents 每个子问题的意图评分列表
     * @return {@link GuidanceDecision#none()} 表示无歧义或功能关闭；
     *         {@link GuidanceDecision#prompt(String)} 表示存在歧义并携带引导文案
     */
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }

        AmbiguityGroup group = findAmbiguityGroup(subIntents);
        if (group == null || CollUtil.isEmpty(group.optionIds())) {
            return GuidanceDecision.none();
        }

        List<String> systemNames = resolveOptionNames(group.optionIds());
        if (shouldSkipGuidance(question, systemNames)) {
            return GuidanceDecision.none();
        }

        String prompt = buildPrompt(group.topicName(), group.optionIds());
        return GuidanceDecision.prompt(prompt);
    }

    /**
     * 在子问题意图列表中查找歧义组。
     *
     * <p>只支持单子问题（多子问题不触发引导）。
     * 在候选意图中，寻找同一"话题名"下得分最高且归属于不同系统的一组意图；
     * 若这组意图中第二高分与最高分的比值超过阈值，则认为存在歧义。</p>
     *
     * @return 找到歧义组时返回 {@link AmbiguityGroup}；否则返回 {@code null}
     */
    private AmbiguityGroup findAmbiguityGroup(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }

        List<NodeScore> candidates = filterCandidates(subIntents.get(0).nodeScores());
        if (candidates.size() < 2) {
            return null;
        }

        Map<String, List<NodeScore>> grouped = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getName()))
                .collect(Collectors.groupingBy(ns -> normalizeName(ns.getNode().getName())));

        Optional<Map.Entry<String, List<NodeScore>>> best = grouped.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), sortByScore(entry.getValue())))
                .filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> passScoreRatio(entry.getValue()))
                .filter(entry -> hasMultipleSystems(entry.getValue()))
                .max(Comparator.comparingDouble(entry -> entry.getValue().get(0).getScore()));

        if (best.isEmpty()) {
            return null;
        }

        List<NodeScore> groupScores = best.get().getValue();
        String topicName = Optional.ofNullable(groupScores.get(0).getNode().getName())
                .orElse(best.get().getKey());
        List<String> optionIds = collectSystemOptions(groupScores);
        if (optionIds.size() < 2) {
            return null;
        }
        return new AmbiguityGroup(topicName, trimOptions(optionIds));
    }

    /** 从意图评分列表中过滤出得分 ≥ 阈值且节点类型为 KB 的候选意图。 */
    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        return scores.stream()
                .filter(ns -> ns.getScore() >= RAGConstant.INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    /**
     * 从歧义评分组中收集各意图所属的系统节点 ID（去重，保留有序性）。
     *
     * <p>每个意图节点可能是系统的子节点（如二级分类），
     * 通过 {@link #resolveSystemNodeId} 向上溯源到系统级节点 ID。</p>
     */
    private List<String> collectSystemOptions(List<NodeScore> groupScores) {
        Set<String> ordered = new LinkedHashSet<>();
        for (NodeScore score : groupScores) {
            IntentNode node = score.getNode();
            String systemId = resolveSystemNodeId(node);
            if (StrUtil.isNotBlank(systemId)) {
                ordered.add(systemId);
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * 判断是否应跳过引导（用户在问题中已明确提及某个系统名称时跳过）。
     *
     * <p>对系统名进行归一化后，检查问题文本中是否包含该名称；
     * 若包含，说明用户已经指定了目标系统，不需要再引导。</p>
     */
    private boolean shouldSkipGuidance(String question, List<String> systemNames) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(systemNames)) {
            return false;
        }
        String normalizedQuestion = normalizeName(question);
        for (String name : systemNames) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            for (String alias : buildSystemAliases(name)) {
                if (alias.length() < 2) {
                    continue;
                }
                if (normalizedQuestion.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 根据系统节点 ID 列表从注册表中查询对应的系统名称，用于判断问题是否已包含系统名。 */
    private List<String> resolveOptionNames(List<String> optionIds) {
        if (CollUtil.isEmpty(optionIds)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String id : optionIds) {
            IntentNode node = intentNodeRegistry.getNodeById(id);
            if (node == null) {
                continue;
            }
            String name = StrUtil.blankToDefault(node.getName(), node.getId());
            names.add(name);
        }
        return names;
    }

    /** 构建某个系统名的归一化别名列表，用于匹配用户问题中的系统指称。 */
    private List<String> buildSystemAliases(String systemName) {
        if (StrUtil.isBlank(systemName)) {
            return List.of();
        }
        String normalized = normalizeName(systemName);
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(normalized)) {
            aliases.add(normalized);
        }
        return aliases;
    }

    /**
     * 判断评分组是否满足歧义阈值。
     *
     * <p>计算第二名与第一名的得分比值，若比值 ≥ {@code ambiguityScoreRatio} 则认为两者"难以区分"。</p>
     */
    private boolean passScoreRatio(List<NodeScore> group) {
        if (group.size() < 2) {
            return false;
        }
        double top = group.get(0).getScore();
        double second = group.get(1).getScore();
        if (top <= 0) {
            return false;
        }
        double ratio = second / top;
        return ratio >= Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.0D);
    }

    /** 判断评分组中的意图是否归属于多个不同的系统节点（歧义的必要条件）。 */
    private boolean hasMultipleSystems(List<NodeScore> group) {
        Set<String> systems = group.stream()
                .map(NodeScore::getNode)
                .map(this::resolveSystemNodeId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        return systems.size() > 1;
    }

    /**
     * 向上溯源，找到给定意图节点所属的"系统级"节点 ID。
     *
     * <p>规则：在节点树中沿 parentId 向上遍历，直到找到级别为 {@code CATEGORY}
     * 且其父节点为 {@code DOMAIN} 级（或已无父节点）的节点，该节点即为系统节点。</p>
     */
    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        for (; ; ) {
            IntentLevel level = current.getLevel();
            if (level == IntentLevel.CATEGORY && (parent == null || parent.getLevel() == IntentLevel.DOMAIN)) {
                return current.getId();
            }
            if (parent == null) {
                return current.getId();
            }
            current = parent;
            parent = fetchParent(current);
        }
    }

    /** 通过节点注册表查询指定节点的父节点，节点无父时返回 {@code null}。 */
    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    /** 按得分降序排序意图评分列表。 */
    private List<NodeScore> sortByScore(List<NodeScore> scores) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    /** 若候选系统数量超过配置的最大选项数，截取前 {@code maxOptions} 个。 */
    private List<String> trimOptions(List<String> optionIds) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(optionIds.size());
        if (optionIds.size() <= maxOptions) {
            return optionIds;
        }
        return optionIds.subList(0, maxOptions);
    }

    /**
     * 根据话题名和候选系统 ID 列表，渲染引导提示文案。
     *
     * <p>使用 {@link PromptTemplateLoader} 加载 {@code GUIDANCE_PROMPT_PATH} 模板，
     * 填充 {@code topic_name} 和 {@code options}（编号列表）后返回。</p>
     */
    private String buildPrompt(String topicName, List<String> optionIds) {
        String options = renderOptions(optionIds);
        return promptTemplateLoader.render(
                RAGConstant.GUIDANCE_PROMPT_PATH,
                Map.of(
                        "topic_name", StrUtil.blankToDefault(topicName, ""),
                        "options", options
                )
        );
    }

    /** 将系统 ID 列表渲染为"1) 系统A\n2) 系统B\n..."格式的选项文本。 */
    private String renderOptions(List<String> optionIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < optionIds.size(); i++) {
            String id = optionIds.get(i);
            IntentNode node = intentNodeRegistry.getNodeById(id);
            String name = node == null || StrUtil.isBlank(node.getName()) ? id : node.getName();
            sb.append(i + 1).append(") ").append(name).append("\n");
        }
        return sb.toString().trim();
    }

    /** 将名称转为小写并去除标点和空白，用于系统名的模糊匹配比对。 */
    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private record AmbiguityGroup(String topicName, List<String> optionIds) {
    }
}
