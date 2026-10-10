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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import java.util.Map;

/**
 * BM25（Robertson / Sparck Jones），IDF 取 Lucene 的非负形式 ln(1 + (N − df + 0.5) / (df + 0.5))
 * <p>
 * PostgreSQL 的 {@code ts_rank} / {@code ts_rank_cd} 只看词频与位置，没有 IDF：查询里"铁矿石"这种几乎每块都有的词
 * 和"焦硫酸钾"这种只在一处出现的词分量一样。文档频率与平均长度按知识库存在 {@code t_kq_term_stats} / {@code t_kq_kb_stats}
 */
public final class Bm25 {

    private Bm25() {
    }

    /**
     * @param chunkCount 语料块数 N
     * @param docFreq    含该词项的块数 df，统计缺失时按 0
     */
    public static double idf(long chunkCount, long docFreq) {
        long df = Math.max(0, Math.min(docFreq, chunkCount));
        return Math.log(1 + (chunkCount - df + 0.5) / (df + 0.5));
    }

    /**
     * 一块的 BM25 分：只对查询词项求和，每个查询词项计一次
     *
     * @param termFreqs 查询词项在该块里的词频
     * @param docLength 该块的词项位置总数
     * @param docFreqs  查询词项的文档频率
     */
    public static double score(Map<String, Integer> termFreqs, int docLength, Map<String, Long> docFreqs,
                               long chunkCount, double avgDocLength, double k1, double b) {
        if (termFreqs.isEmpty() || chunkCount <= 0) {
            return 0D;
        }
        double norm = avgDocLength > 0 ? docLength / avgDocLength : 1D;
        double score = 0D;
        for (Map.Entry<String, Integer> entry : termFreqs.entrySet()) {
            int tf = entry.getValue();
            if (tf <= 0) {
                continue;
            }
            double idf = idf(chunkCount, docFreqs.getOrDefault(entry.getKey(), 0L));
            score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * norm));
        }
        return score;
    }
}
