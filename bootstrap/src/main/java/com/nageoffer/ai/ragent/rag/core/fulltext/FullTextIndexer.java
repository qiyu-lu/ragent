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

import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全文索引的维护：写 {@code content_tsv}、重算词项统计、重建存量
 * <p>
 * 索引文本 = 文档名（去扩展名）+ 向量文本（章节路径 + 正文），与 Rerank 看到的精排文本同源。
 * {@code content_tsv} 跟着块写，在调用方的事务里；统计按库整体重算，放在事务提交之后、另开事务，
 * 重算失败只告警（统计略旧只影响 IDF，不影响能否命中），下次入库或重建时纠正
 */
@Slf4j
@Component
public class FullTextIndexer {

    private final FullTextStore store;
    private final FullTextTokenizer tokenizer;
    private final SearchChannelProperties properties;
    private final TransactionTemplate newTransaction;

    public FullTextIndexer(FullTextStore store, FullTextTokenizer tokenizer, SearchChannelProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.store = store;
        this.tokenizer = tokenizer;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param knowledgeBases 重建的库数
     * @param documents      重建的文档数
     * @param chunks         写入 content_tsv 的块数
     * @param elapsedMs      耗时
     */
    public record RebuildSummary(int knowledgeBases, int documents, int chunks, long elapsedMs) {
    }

    /**
     * 通道与索引维护共用一个开关
     */
    public boolean enabled() {
        return properties.getChannels().getFullText().isEnabled();
    }

    /**
     * 写一个文档全部未删除块的 content_tsv（入库时在块落库的同一事务里调用）
     */
    public int indexDocument(String docId) {
        return write(store.sourcesOfDocument(docId));
    }

    /**
     * 写指定块的 content_tsv（单块新增、编辑后调用）
     */
    public int indexChunks(Collection<String> chunkIds) {
        return write(store.sourcesOfChunks(chunkIds));
    }

    /**
     * 当前事务提交后重算该库的统计；不在事务里时立即重算
     */
    public void refreshStatsAfterCommit(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshQuietly(kbId);
                }
            });
        } else {
            refreshQuietly(kbId);
        }
    }

    /**
     * 重建存量：指定库或全部未删除的库，逐个文档写 content_tsv，最后重算统计
     */
    public RebuildSummary rebuild(String kbId) {
        long started = System.currentTimeMillis();
        List<String> kbIds = kbId == null || kbId.isBlank() ? store.liveKbIds() : List.of(kbId);
        int documents = 0;
        int chunks = 0;
        for (String id : kbIds) {
            for (String docId : store.documentIdsOfKb(id)) {
                Integer written = newTransaction.execute(status -> indexDocument(docId));
                chunks += written == null ? 0 : written;
                documents++;
            }
            newTransaction.executeWithoutResult(status -> store.refreshStats(id));
        }
        RebuildSummary summary = new RebuildSummary(kbIds.size(), documents, chunks, System.currentTimeMillis() - started);
        log.info("全文索引重建完成 {}", summary);
        return summary;
    }

    static String indexText(String docName, String body) {
        String title = stripExtension(docName == null ? "" : docName.trim());
        String text = body == null ? "" : body;
        return title.isEmpty() ? text : title + "\n" + text;
    }

    private int write(List<FullTextStore.IndexSource> sources) {
        Map<String, String> literals = new LinkedHashMap<>();
        for (FullTextStore.IndexSource source : sources) {
            literals.put(source.chunkId(), TsvectorLiteral.of(tokenizer.tokenize(indexText(source.docName(), source.body()))));
        }
        store.writeTsvectors(literals);
        return literals.size();
    }

    private void refreshQuietly(String kbId) {
        try {
            newTransaction.executeWithoutResult(status -> store.refreshStats(kbId));
        } catch (RuntimeException e) {
            log.warn("全文索引统计重算失败，下次入库或重建时纠正 kbId={}", kbId, e);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(0, dot) : name;
    }
}
