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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 多子问题的请求级公平 Chunk 选择器。
 * <p>
 * 第一阶段按问题轮询，每题在自己的初始配额内一次取一个全局唯一候选；第二阶段继续按问题轮询，
 * 用各题候选池的剩余唯一项填满请求额度。不同子问题的相关性分数不做横向比较，避免把不同 query
 * 下不可比的 Rerank 分数混成一次全局排序。
 */
public final class RequestLevelChunkSelector {

    private RequestLevelChunkSelector() {
    }

    /**
     * @param candidatesByQuestion 每个子问题独立排序后的候选池
     * @param quotas               每个子问题的初始公平配额，长度必须与候选池列表一致
     * @param requestTopK          整个请求最终允许的唯一 Chunk 数量
     */
    public static SelectionResult select(List<List<RetrievedChunk>> candidatesByQuestion,
                                         List<Integer> quotas,
                                         int requestTopK) {
        Objects.requireNonNull(candidatesByQuestion, "candidatesByQuestion");
        Objects.requireNonNull(quotas, "quotas");
        if (candidatesByQuestion.size() != quotas.size()) {
            throw new IllegalArgumentException("候选池数量与初始配额数量必须一致");
        }
        if (quotas.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("初始配额不能为 null");
        }
        if (quotas.stream().anyMatch(quota -> quota < 0)) {
            throw new IllegalArgumentException("初始配额不能为负数");
        }

        CandidateStats candidateStats = countCandidates(candidatesByQuestion);
        int uniqueBeforeRefill = countUniqueInInitialPrefixes(candidatesByQuestion, quotas, requestTopK);
        List<List<RetrievedChunk>> selectedByQuestion = new ArrayList<>(candidatesByQuestion.size());
        for (int i = 0; i < candidatesByQuestion.size(); i++) {
            selectedByQuestion.add(new ArrayList<>());
        }
        if (requestTopK <= 0 || candidatesByQuestion.isEmpty()) {
            return result(selectedByQuestion, List.of(), candidateStats, uniqueBeforeRefill, requestTopK);
        }

        int[] cursors = new int[candidatesByQuestion.size()];
        Set<String> selectedKeys = new LinkedHashSet<>();
        List<RetrievedChunk> orderedChunks = new ArrayList<>(requestTopK);

        // 初始公平阶段：一轮内每题最多取得一个唯一候选，直到各自配额填满或候选耗尽。
        boolean progressed;
        do {
            progressed = false;
            for (int questionIndex = 0;
                 questionIndex < candidatesByQuestion.size() && orderedChunks.size() < requestTopK;
                 questionIndex++) {
                if (selectedByQuestion.get(questionIndex).size() >= quotas.get(questionIndex)) {
                    continue;
                }
                RetrievedChunk selected = nextUnique(
                        candidatesByQuestion.get(questionIndex), cursors, questionIndex, selectedKeys);
                if (selected != null) {
                    addSelected(selectedByQuestion, orderedChunks, selected, questionIndex);
                    progressed = true;
                }
            }
        } while (progressed && orderedChunks.size() < requestTopK && hasQuotaDeficit(selectedByQuestion, quotas));

        // 回填阶段：空余名额不按跨 query 分数竞争，而由仍有候选的题目逐轮各补一个。
        do {
            progressed = false;
            for (int questionIndex = 0;
                 questionIndex < candidatesByQuestion.size() && orderedChunks.size() < requestTopK;
                 questionIndex++) {
                RetrievedChunk selected = nextUnique(
                        candidatesByQuestion.get(questionIndex), cursors, questionIndex, selectedKeys);
                if (selected != null) {
                    addSelected(selectedByQuestion, orderedChunks, selected, questionIndex);
                    progressed = true;
                }
            }
        } while (progressed && orderedChunks.size() < requestTopK);

        return result(selectedByQuestion, orderedChunks, candidateStats, uniqueBeforeRefill, requestTopK);
    }

    private static RetrievedChunk nextUnique(List<RetrievedChunk> candidates,
                                             int[] cursors,
                                             int questionIndex,
                                             Set<String> selectedKeys) {
        if (candidates == null) {
            return null;
        }
        while (cursors[questionIndex] < candidates.size()) {
            RetrievedChunk candidate = candidates.get(cursors[questionIndex]++);
            if (candidate != null && selectedKeys.add(RetrievedChunkKey.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static void addSelected(List<List<RetrievedChunk>> selectedByQuestion,
                                    List<RetrievedChunk> orderedChunks,
                                    RetrievedChunk selected,
                                    int questionIndex) {
        selectedByQuestion.get(questionIndex).add(selected);
        orderedChunks.add(selected);
    }

    private static boolean hasQuotaDeficit(List<List<RetrievedChunk>> selectedByQuestion,
                                           List<Integer> quotas) {
        for (int i = 0; i < quotas.size(); i++) {
            if (selectedByQuestion.get(i).size() < quotas.get(i)) {
                return true;
            }
        }
        return false;
    }

    private static CandidateStats countCandidates(List<List<RetrievedChunk>> candidatesByQuestion) {
        int candidateCount = 0;
        Set<String> uniqueKeys = new LinkedHashSet<>();
        for (List<RetrievedChunk> candidates : candidatesByQuestion) {
            if (candidates == null) {
                continue;
            }
            for (RetrievedChunk candidate : candidates) {
                if (candidate != null) {
                    candidateCount++;
                    uniqueKeys.add(RetrievedChunkKey.of(candidate));
                }
            }
        }
        return new CandidateStats(candidateCount, uniqueKeys.size());
    }

    /**
     * 旧行为基线：每题只看初始配额长度的固定前缀，最后才做全局去重，重复留下的空位不会向后补。
     */
    private static int countUniqueInInitialPrefixes(List<List<RetrievedChunk>> candidatesByQuestion,
                                                    List<Integer> quotas,
                                                    int requestTopK) {
        if (requestTopK <= 0) {
            return 0;
        }
        int consumedSlots = 0;
        Set<String> uniqueKeys = new LinkedHashSet<>();
        for (int questionIndex = 0;
             questionIndex < candidatesByQuestion.size() && consumedSlots < requestTopK;
             questionIndex++) {
            List<RetrievedChunk> candidates = candidatesByQuestion.get(questionIndex);
            if (candidates == null) {
                continue;
            }
            int prefixSize = Math.min(quotas.get(questionIndex), candidates.size());
            for (int index = 0; index < prefixSize && consumedSlots < requestTopK; index++) {
                RetrievedChunk candidate = candidates.get(index);
                consumedSlots++;
                if (candidate != null) {
                    uniqueKeys.add(RetrievedChunkKey.of(candidate));
                }
            }
        }
        return uniqueKeys.size();
    }

    private static SelectionResult result(List<List<RetrievedChunk>> selectedByQuestion,
                                          List<RetrievedChunk> orderedChunks,
                                          CandidateStats candidateStats,
                                          int uniqueBeforeRefill,
                                          int requestTopK) {
        int finalUniqueCount = orderedChunks.size();
        int refillAdded = finalUniqueCount - uniqueBeforeRefill;
        return new SelectionResult(
                selectedByQuestion,
                orderedChunks,
                candidateStats.candidateCount(),
                candidateStats.candidateUniqueCount(),
                uniqueBeforeRefill,
                refillAdded,
                finalUniqueCount,
                Math.max(0, requestTopK - finalUniqueCount));
    }

    private record CandidateStats(int candidateCount, int candidateUniqueCount) {
    }

    /**
     * @param selectedByQuestion  每题最终获得的唯一 Chunk，题内保持候选顺序
     * @param orderedChunks       按公平选择发生顺序排列的请求级唯一 Chunk
     * @param candidateCount      所有题候选条目总数（不含 null，含跨题重复）
     * @param candidateUniqueCount 所有题候选的全局唯一数
     * @param uniqueBeforeRefill  旧版按各题固定配额前缀截断后可用的唯一数
     * @param refillAdded         相对旧版固定前缀新增的唯一数；同时包含配额阶段跳过重复后向后扫描，
     *                            以及配额阶段结束后的剩余额度回填
     * @param finalUniqueCount    最终唯一数
     * @param unfilledSlots       候选耗尽后仍未填满的请求额度
     */
    public record SelectionResult(List<List<RetrievedChunk>> selectedByQuestion,
                                  List<RetrievedChunk> orderedChunks,
                                  int candidateCount,
                                  int candidateUniqueCount,
                                  int uniqueBeforeRefill,
                                  int refillAdded,
                                  int finalUniqueCount,
                                  int unfilledSlots) {

        public SelectionResult {
            selectedByQuestion = selectedByQuestion.stream()
                    .map(List::copyOf)
                    .toList();
            orderedChunks = List.copyOf(orderedChunks);
        }
    }
}
