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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * read_source 首批只开放块级展开；缺少可靠章节/工作表 metadata 时不拼接邻块。
 */
@Service
@RequiredArgsConstructor
public class SourceReader {
    private final ResearchEvidenceStore evidenceStore;
    private final ResearchSourceCatalog sourceCatalog;

    public SourceReadResult read(String runId, String ownerUserId, String evidenceId) {
        var brief = evidenceStore.requireBrief(runId, ownerUserId);
        var snapshot = evidenceStore.find(runId, ownerUserId, evidenceId);
        var e = snapshot.evidence();
        if (!SecureUtil.sha256(snapshot.sourceText()).equals(e.contentHash())) {
            throw new ClientException("已保存的证据快照与 hash 不一致");
        }
        if (e.chunkIds().size() != 1) {
            throw new ClientException("当前读取器只支持可信的单块证据");
        }
        var current = sourceCatalog.load(e.chunkIds().get(0), Set.copyOf(brief.allowedKbIds()));
        if (!Objects.equals(e.kbId(), current.chunk().getKbId())
                || !Objects.equals(e.docId(), current.document().getId())) {
            throw new ClientException("来源块身份已变化");
        }
        boolean changed = !Objects.equals(e.documentVersion(), current.document().getDocumentVersion())
                || !Objects.equals(e.documentName(), current.document().getDocName())
                || !Objects.equals(e.contentHash(), current.contentHash())
                || !Objects.equals(snapshot.sourceMetadataHash(), current.metadataHash());
        return new SourceReadResult(evidenceStore.markRead(ownerUserId, snapshot), changed
                ? SourceReadResult.SourceState.CHANGED : SourceReadResult.SourceState.CURRENT);
    }
}
