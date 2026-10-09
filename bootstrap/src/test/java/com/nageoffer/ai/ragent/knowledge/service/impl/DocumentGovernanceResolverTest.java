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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.core.ingest.metadata.DocumentMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentGovernanceResolverTest {

    private static DocumentMetadata standard(String base, int year, String... replaces) {
        return new DocumentMetadata(base + "-" + year, base, year, List.of(replaces), List.of("铁矿石"), List.of("硅"),
                List.of(), "extracted");
    }

    @Test
    void newerVersionOfTheSameStandardSupersedesOlderOnes() {
        DocumentMetadata v1986 = standard("GB/T 6730.10", 1986);
        DocumentMetadata v2014 = standard("GB/T 6730.10", 2014);
        DocumentMetadata v2025 = standard("GB/T 6730.10", 2025);
        List<DocumentGovernanceResolver.Sibling> siblings = List.of(
                new DocumentGovernanceResolver.Sibling("a", "kb", v1986),
                new DocumentGovernanceResolver.Sibling("b", "kb", v2014),
                new DocumentGovernanceResolver.Sibling("c", "kb", v2025),
                new DocumentGovernanceResolver.Sibling("x", "other-kb", standard("GB/T 6730.10", 2030)));

        assertEquals("GB/T 6730.10-2025", DocumentGovernanceResolver.supersededBy("a", "kb", v1986, siblings));
        assertEquals("GB/T 6730.10-2025", DocumentGovernanceResolver.supersededBy("b", "kb", v2014, siblings));
        assertNull(DocumentGovernanceResolver.supersededBy("c", "kb", v2025, siblings), "别的知识库里的新版本不算");
    }

    @Test
    void replacementListCoversRenumberedStandards() {
        DocumentMetadata old = standard("GB/T 1368", 1978);
        DocumentMetadata current = standard("GB/T 6730.10", 2014, "GB/T 1368-1978", "GB/T 6730.10-1986");
        List<DocumentGovernanceResolver.Sibling> siblings = List.of(
                new DocumentGovernanceResolver.Sibling("old", "kb", old),
                new DocumentGovernanceResolver.Sibling("cur", "kb", current));
        assertEquals("GB/T 6730.10-2014", DocumentGovernanceResolver.supersededBy("old", "kb", old, siblings));
        assertNull(DocumentGovernanceResolver.supersededBy("cur", "kb", current, siblings));
        assertNull(DocumentGovernanceResolver.supersededBy("n", "kb", null, siblings));
    }
}
