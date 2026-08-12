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

package com.nageoffer.ai.ragent.knowledge.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIdentityResolverTest {

    @Test
    void groupsRealAndDemoVersionsUnderOneDocumentKey() {
        var v12 = DocumentIdentityResolver.resolve("铁矿石人工检测流程调研V1.2.xlsx");
        var demo = DocumentIdentityResolver.resolve("铁矿石人工检测流程调研V1.3-demo.xlsx");

        assertEquals("铁矿石人工检测流程调研", v12.documentKey());
        assertEquals(v12.documentKey(), demo.documentKey());
        assertEquals("V1.2", v12.documentVersion());
        assertEquals("V1.3-demo", demo.documentVersion());
        assertFalse(v12.demoData());
        assertTrue(demo.demoData());
    }
}
