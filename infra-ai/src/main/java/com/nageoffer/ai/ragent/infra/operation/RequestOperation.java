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

package com.nageoffer.ai.ragent.infra.operation;

import com.nageoffer.ai.ragent.infra.http.ModelClientException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in deadline/cancellation scope. Explicitly carried across executor boundaries; never inherited. */
public final class RequestOperation implements AutoCloseable {
    private static final ThreadLocal<RequestOperation> CURRENT = new ThreadLocal<>();
    private final long deadline;
    private final Map<String, Object> identity;
    private final Consumer<Map<String, Object>> observer;
    private final ConcurrentMap<String, List<List<Float>>> cache;
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean timedOut;

    public interface Binding extends AutoCloseable { @Override void close(); }
    public static final class Failure extends RuntimeException {
        public final String phase;
        public final String code;
        public final boolean retryable;
        public Failure(String phase, String code, boolean retryable, Throwable cause) {
            super(code + " at " + phase, cause);
            this.phase = phase; this.code = code; this.retryable = retryable;
        }
    }
    public RequestOperation(Duration duration, Map<String, Object> identity,
                            Consumer<Map<String, Object>> observer,
                            ConcurrentMap<String, List<List<Float>>> cache) {
        deadline = System.nanoTime() + duration.toNanos();
        this.identity = Map.copyOf(identity); this.observer = observer; this.cache = cache;
    }
    public static RequestOperation current() { return CURRENT.get(); }
    public Map<String, Object> identity() { return identity; }
    public Binding bind() {
        RequestOperation previous = CURRENT.get(); CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public Binding onCancel(Runnable action) {
        cancellations.add(action);
        if (cancelled.get() && cancellations.remove(action)) action.run();
        return () -> cancellations.remove(action);
    }
    public void cancel() {
        cancelled.set(true);
        for (Runnable action : cancellations) if (cancellations.remove(action)) {
            try { action.run(); } catch (RuntimeException ignored) { /* cancel remaining handles */ }
        }
    }
    public long remainingMillis() {
        if (timedOut) throw new Failure("deadline", "RETRIEVAL_DEADLINE", true, null);
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("RETRIEVAL_CANCELLED");
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            timedOut = true; cancel();
            throw new Failure("deadline", "RETRIEVAL_DEADLINE", true, null);
        }
        return remaining;
    }
    public void record(String phase, String status, long elapsedMillis, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>(identity);
        event.put("phase", phase); event.put("status", status); event.put("elapsedMs", elapsedMillis);
        event.putAll(details); observer.accept(event);
    }
    public <T> T measure(String phase, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            remainingMillis(); T result = action.get(); remainingMillis();
            record(phase, "COMPLETED", elapsed(start), Map.of()); return result;
        } catch (RuntimeException error) {
            RuntimeException failure = failure(phase, error);
            record(phase, failure instanceof CancellationException ? "CANCELLED" : "FAILED", elapsed(start),
                    failure instanceof Failure f ? Map.of("code", f.code, "retryable", f.retryable)
                            : Map.of("code", failure.getClass().getSimpleName(), "retryable", false));
            throw failure;
        }
    }
    public <T> T execute(Executor executor, String phase, Supplier<T> action) {
        long queued = System.nanoTime();
        FutureTask<T> task = new FutureTask<>(() -> {
            try (Binding ignored = bind()) {
                remainingMillis();
                record(phase + ".queue", "COMPLETED", elapsed(queued), Map.of());
                return measure(phase, action);
            }
        });
        try (Binding ignored = onCancel(() -> task.cancel(true))) {
            try {
                remainingMillis(); executor.execute(task);
                return task.get(remainingMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                timedOut = true; cancel();
                record(phase, "TIMED_OUT", elapsed(queued), Map.of("code", "RETRIEVAL_DEADLINE", "retryable", true));
                throw new Failure(phase, "RETRIEVAL_DEADLINE", true, error);
            } catch (InterruptedException error) {
                cancel(); Thread.currentThread().interrupt(); throw new CancellationException("RETRIEVAL_INTERRUPTED");
            } catch (ExecutionException error) { throw failure(phase, error.getCause()); }
            catch (RejectedExecutionException error) { throw new Failure(phase + ".queue", "RETRIEVAL_QUEUE_FULL", true, error); }
        } finally { if (!task.isDone()) task.cancel(true); }
    }
    public List<List<Float>> cached(String key) { remainingMillis(); return cache.get(key); }
    public void cache(String key, List<List<Float>> value) {
        remainingMillis();
        if (cache.size() < 128) cache.putIfAbsent(key, value.stream().map(List::copyOf).toList());
    }
    public void backoff(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            remainingMillis();
            try { Thread.sleep(Math.min(50, millis)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("RETRIEVAL_INTERRUPTED"); }
        }
    }
    public static RuntimeException failure(String phase, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException c) return c;
            if (cause instanceof Failure f) return f;
            if (cause instanceof ModelClientException m) return new Failure(phase, m.getErrorType().name(),
                    switch (m.getErrorType()) { case NETWORK_ERROR, RATE_LIMITED, SERVER_ERROR -> true; default -> false; }, error);
        }
        return new Failure(phase, error.getClass().getSimpleName(), false, error);
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    @Override public void close() { cancel(); }
}
