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

package com.nageoffer.ai.ragent.core.ingest.embed;

import cn.hutool.crypto.SecureUtil;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 向量化：模型与维度都取自 {@link VectorTarget}，没有可空入参可以回落到系统默认模型
 * <p>
 * 住在编排层而不是分块层：落点是编排层的概念，放在分块层会形成模态层反向依赖编排层的回边
 * <p>
 * 送上游前按（真实模型、维度、向量文本哈希）查 {@link EmbeddingCache}，只把未命中的文本去重后上送，
 * 返回后回写；重新入库时没变的块不再花钱。缓存故障只记日志并退回上游，不影响入库
 */
@Slf4j
@Service
public class ChunkEmbeddingService {

    private final EmbeddingService embeddingService;
    private final EmbeddingCache cache;
    private final AIModelProperties modelProperties;

    @Autowired
    public ChunkEmbeddingService(EmbeddingService embeddingService, EmbeddingCache cache,
                                 AIModelProperties modelProperties) {
        this.embeddingService = embeddingService;
        this.cache = cache;
        this.modelProperties = modelProperties;
    }

    /**
     * 不带缓存：离线导入等自带续跑机制的调用方使用
     */
    public ChunkEmbeddingService(EmbeddingService embeddingService) {
        this(embeddingService, EmbeddingCache.NONE, null);
    }

    /**
     * 为块列表计算向量，见 {@link #embedWithStats}
     */
    public List<EmbeddedChunk> embed(List<Chunk> chunks, VectorTarget target) {
        return embedWithStats(chunks, target).chunks();
    }

    /**
     * 为块列表计算向量，逐条校验维度：物理空间的列宽写死在建表语句里，不校验则错误漂到向量库类型转换才暴露
     *
     * @param chunks 待向量化的块，向量文本已由装配阶段保证非空
     * @param target 向量落点：提供模型与必须匹配的维度
     * @return 已向量化的块（与入参一一对应且顺序一致）及缓存计数
     */
    public ChunkEmbeddings embedWithStats(List<Chunk> chunks, VectorTarget target) {
        if (chunks == null || chunks.isEmpty()) {
            return ChunkEmbeddings.EMPTY;
        }
        List<String> hashes = chunks.stream().map(chunk -> SecureUtil.sha256(chunk.embeddingText())).toList();
        String cacheModel = cacheModelId(target);
        Map<String, float[]> vectors = new HashMap<>(lookup(cacheModel, target, hashes));
        int hits = (int) hashes.stream().filter(vectors::containsKey).count();

        // 未命中的按首次出现顺序去重：同一文档里重复的表头、页眉只上送一次
        Map<String, String> pending = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!vectors.containsKey(hashes.get(i))) {
                pending.putIfAbsent(hashes.get(i), chunks.get(i).embeddingText());
            }
        }
        if (!pending.isEmpty()) {
            Map<String, float[]> fresh = embedUpstream(pending, target);
            store(cacheModel, target, fresh);
            vectors.putAll(fresh);
        }

        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            result.add(new EmbeddedChunk(chunks.get(i), vectors.get(hashes.get(i))));
        }
        var stats = new ChunkEmbeddings.EmbeddingStats(chunks.size(), hits, pending.size());
        log.info("向量化完成 分区={} 模型={} 块数={} 缓存命中={} 上送文本={}",
                target.partition(), target.embeddingModel(), stats.chunks(), stats.cacheHits(), stats.upstreamTexts());
        return new ChunkEmbeddings(result, stats);
    }

    private Map<String, float[]> embedUpstream(Map<String, String> pending, VectorTarget target) {
        List<List<Float>> vectors = embeddingService.embedBatch(new ArrayList<>(pending.values()), target.embeddingModel());
        if (vectors == null || vectors.size() != pending.size()) {
            throw new ServiceException(String.format("向量结果条数与送出文本不符：期望 %d，实际 %s",
                    pending.size(), vectors == null ? "null" : String.valueOf(vectors.size())));
        }
        Map<String, float[]> fresh = new LinkedHashMap<>();
        int i = 0;
        for (String hash : pending.keySet()) {
            fresh.put(hash, toVector(vectors.get(i), i, target));
            i++;
        }
        return fresh;
    }

    private static float[] toVector(List<Float> row, int index, VectorTarget target) {
        if (row == null || row.isEmpty()) {
            throw new ServiceException("向量结果缺失，序号：" + index);
        }
        if (row.size() != target.dimension()) {
            throw new ServiceException(String.format(
                    "嵌入维度与部署级向量空间不符：模型 %s 输出 %d 维，物理空间要求 %d 维（分区 %s）"
                            + "——请改用同维度的嵌入模型，或调整部署级维度并重建向量空间",
                    target.embeddingModel(), row.size(), target.dimension(), target.partition()));
        }
        float[] vector = new float[row.size()];
        for (int j = 0; j < row.size(); j++) {
            vector[j] = row.get(j);
        }
        return vector;
    }

    /**
     * 缓存键里的模型取解析后的“供应商:模型名”而不是候选别名：别名改指向另一个模型时旧向量不能再用。
     * 候选未登记时返回 null，本次不查也不写缓存，由上游调用报出模型不可用
     */
    private String cacheModelId(VectorTarget target) {
        if (cache == EmbeddingCache.NONE || modelProperties == null) {
            return null;
        }
        return modelProperties.getEmbedding().getCandidates().stream()
                .filter(candidate -> target.embeddingModel().equals(candidate.getId()))
                .findFirst()
                .map(candidate -> candidate.getProvider() + ":" + candidate.getModel())
                .orElse(null);
    }

    private Map<String, float[]> lookup(String cacheModel, VectorTarget target, List<String> hashes) {
        if (cacheModel == null) {
            return Map.of();
        }
        try {
            Map<String, float[]> hits = new HashMap<>(cache.lookup(cacheModel, target.dimension(), new LinkedHashSet<>(hashes)));
            // 键已含维度，这里再核一遍：错维的向量写进物理空间会在类型转换处才报错
            hits.values().removeIf(vector -> vector.length != target.dimension());
            return hits;
        } catch (RuntimeException e) {
            log.warn("嵌入缓存读取失败，本批全部走上游：模型={} 原因={}", cacheModel, e.toString());
            return Map.of();
        }
    }

    private void store(String cacheModel, VectorTarget target, Map<String, float[]> fresh) {
        if (cacheModel == null) {
            return;
        }
        try {
            cache.store(cacheModel, target.dimension(), fresh);
        } catch (RuntimeException e) {
            log.warn("嵌入缓存写入失败，本批向量不复用：模型={} 条数={} 原因={}", cacheModel, fresh.size(), e.toString());
        }
    }
}
