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

package com.nageoffer.ai.ragent.infra.rerank;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Optional synchronous request observer; provider usage is recorded verbatim, never estimated as zero. */
public final class RerankUsageCapture implements AutoCloseable {
    private static final ThreadLocal<Consumer<Map<String, Object>>> CURRENT = new ThreadLocal<>();
    private final Consumer<Map<String, Object>> previous;
    public RerankUsageCapture(Consumer<Map<String, Object>> observer) {
        previous = CURRENT.get();
        CURRENT.set(observer);
    }
    static void record(Map<String, Object> request) {
        Consumer<Map<String, Object>> observer = CURRENT.get();
        if (observer != null) observer.accept(new LinkedHashMap<>(request));
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
