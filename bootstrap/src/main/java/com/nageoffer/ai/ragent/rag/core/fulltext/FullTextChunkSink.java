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

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkSink;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全文索引落点：块表写完之后、同一事务里给这份文档的块写 {@code content_tsv}，提交后重算该库的词项统计
 * <p>
 * 顺序排在关系库落点之后（它先删后插块行），向量落点之前；通道关闭时什么也不做
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class FullTextChunkSink implements ChunkSink {

    private final FullTextIndexer indexer;

    @Override
    public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
        if (!indexer.enabled()) {
            return;
        }
        indexer.indexDocument(doc.docId());
        indexer.refreshStatsAfterCommit(doc.kbId());
    }

    @Override
    public void deleteDocument(VectorTarget target, DocumentRef doc) {
        if (indexer.enabled()) {
            indexer.refreshStatsAfterCommit(doc.kbId());
        }
    }
}
