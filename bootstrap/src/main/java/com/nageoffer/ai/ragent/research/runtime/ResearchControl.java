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

package com.nageoffer.ai.ragent.research.runtime;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reactor 取消信号会一直传播到 SDK 的 JDK HTTP future 和响应 socket。 */
public class ResearchControl {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Sinks.One<Boolean> signal = Sinks.one();
    private final CopyOnWriteArrayList<Runnable> interrupts = new CopyOnWriteArrayList<>();

    public AutoCloseable bindInterrupt(Runnable callback) {
        AtomicBoolean invoked = new AtomicBoolean();
        Runnable once = () -> { if (invoked.compareAndSet(false, true)) callback.run(); };
        interrupts.add(once);
        if (cancelled.get()) once.run();
        return () -> interrupts.remove(once);
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            signal.tryEmitValue(true);
            for (Runnable callback : interrupts) {
                try { callback.run(); } catch (RuntimeException ignored) { /* 继续取消其他 worker */ }
            }
            interrupts.clear();
        }
    }

    public boolean cancelled() { return cancelled.get(); }
    public Mono<Boolean> signal() { return signal.asMono(); }
    public void check() {
        if (cancelled()) throw new CancellationException("RESEARCH_CANCELLED");
    }
}
