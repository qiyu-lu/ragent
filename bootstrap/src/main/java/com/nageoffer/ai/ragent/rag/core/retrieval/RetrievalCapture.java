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

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Explicit request-owned capture, including work executed on retrieval executors. */
public final class RetrievalCapture {
    private final ConcurrentLinkedQueue<Stage> stages = new ConcurrentLinkedQueue<>();

    public void record(String question, String stage, List<RetrievedChunk> chunks, long elapsedMs, String failure) {
        stages.add(new Stage(question, stage, chunks.stream().map(Chunk::from).toList(), elapsedMs, failure));
    }

    public List<Stage> stages() {
        return List.copyOf(stages);
    }

    public record Stage(String question, String stage, List<Chunk> chunks, long elapsedMs, String failure) { }

    /** Copy scalar values immediately; later metadata/rerank mutation cannot rewrite history. */
    public record Chunk(String id, String text, Float score, String collectionName,
                        String docId, String docName, String rankingText) {
        static Chunk from(RetrievedChunk c) {
            return new Chunk(c.getId(), c.getText(), c.getScore(), c.getCollectionName(),
                    c.getDocId(), c.getDocName(), c.textForRanking());
        }
    }
}
