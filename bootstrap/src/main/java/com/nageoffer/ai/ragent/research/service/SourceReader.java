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

import cn.hutool.crypto.SecureUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.SourceReadResult;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.ReadMode;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.ExpansionState;
import com.nageoffer.ai.ragent.research.model.SourceReadResult.SourceState;
import com.nageoffer.ai.ragent.research.model.EvidenceSnapshot;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.List;

/**
 * 块级及受限邻接展开；优先返回本次研究的首次快照。
 */
@Service
@RequiredArgsConstructor
public class SourceReader {
    private final ResearchEvidenceStore evidenceStore;
    private final ResearchSourceCatalog sourceCatalog;
    private final EvidenceSnapshotFactory snapshotFactory;

    public SourceReadResult read(String runId, String ownerUserId, String evidenceId) {
        return read(runId, ownerUserId, evidenceId, ReadMode.CHUNK);
    }

    public SourceReadResult read(String runId, String ownerUserId, String evidenceId, ReadMode mode) {
        var brief = evidenceStore.requireBrief(runId, ownerUserId);
        if (mode == null) {
            throw new ClientException("原文展开模式不能为空");
        }
        var snapshot = evidenceStore.find(runId, ownerUserId, evidenceId);
        validateSnapshot(snapshot);
        if (mode == ReadMode.CHUNK || snapshot.evidence().chunkIds().size() > 1) {
            return readPinned(brief, ownerUserId, snapshot, evidenceId, null);
        }
        var expanded = evidenceStore.findExpansion(runId, ownerUserId, evidenceId);
        if (expanded.isPresent()) {
            return readPinned(brief, ownerUserId, expanded.get(), evidenceId, "返回首次保存的邻接快照");
        }
        var neighborhood = sourceCatalog.neighbors(snapshot.evidence().chunkIds().get(0),
                Set.copyOf(brief.allowedKbIds()), Set.copyOf(brief.allowedDocIds()));
        var seed = neighborhood.sources().stream()
                .filter(source -> source.chunk().getId().equals(snapshot.evidence().chunkIds().get(0)))
                .findFirst().orElseThrow(() -> new ClientException("邻接读取缺少原候选"));
        boolean changed = changed(snapshot, List.of(seed));
        if (changed || neighborhood.sources().size() == 1) {
            return new SourceReadResult(evidenceStore.markRead(ownerUserId, snapshot),
                    changed ? SourceState.CHANGED : SourceState.CURRENT, ExpansionState.BLOCK_ONLY,
                    evidenceId, changed ? "来源已变化，只返回已有块快照" : neighborhood.note());
        }
        var newSnapshot = snapshotFactory.create(runId, snapshot.evidence().retrievedByTaskId(),
                neighborhood.sources(), evidenceId);
        var stored = evidenceStore.saveExpansion(ownerUserId, evidenceId, newSnapshot);
        return readPinned(brief, ownerUserId, stored, evidenceId, null);
    }

    private void validateSnapshot(EvidenceSnapshot snapshot) {
        var e = snapshot.evidence();
        if (!SecureUtil.sha256(snapshot.sourceText()).equals(e.contentHash())) {
            throw new ClientException("已保存的证据快照与 hash 不一致");
        }
        if (e.chunkIds().isEmpty() || e.chunkIds().size() > 3) {
            throw new ClientException("证据展开范围无效");
        }
    }

    private SourceReadResult readPinned(ResearchBrief brief, String ownerUserId, EvidenceSnapshot snapshot,
                                        String requestedEvidenceId, String note) {
        validateSnapshot(snapshot);
        var sources = sourceCatalog.loadAll(snapshot.evidence().chunkIds(),
                Set.copyOf(brief.allowedKbIds()), Set.copyOf(brief.allowedDocIds()));
        boolean changed = changed(snapshot, sources);
        return new SourceReadResult(evidenceStore.markRead(ownerUserId, snapshot),
                changed ? SourceState.CHANGED : SourceState.CURRENT,
                snapshot.evidence().chunkIds().size() > 1 ? ExpansionState.NEIGHBORS : ExpansionState.CHUNK,
                requestedEvidenceId, note);
    }

    private boolean changed(EvidenceSnapshot snapshot, List<ResearchSourceCatalog.SourceChunk> sources) {
        var e = snapshot.evidence();
        if (sources.stream().anyMatch(source -> !Objects.equals(e.kbId(), source.chunk().getKbId())
                || !Objects.equals(e.docId(), source.document().getId()))) {
            throw new ClientException("来源块身份已变化");
        }
        var current = snapshotFactory.create(e.runId(), e.retrievedByTaskId(), sources, null);
        return !Objects.equals(e.documentVersion(), current.evidence().documentVersion())
                || !Objects.equals(e.documentName(), current.evidence().documentName())
                || !Objects.equals(e.contentHash(), current.evidence().contentHash())
                || !Objects.equals(snapshot.sourceMetadataHash(), current.sourceMetadataHash())
                || e.sourceExtent() != current.evidence().sourceExtent();
    }
}
