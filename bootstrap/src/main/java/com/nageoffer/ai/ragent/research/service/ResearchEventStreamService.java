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

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.ResearchEvent;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 只读持久事件；连接/重连/断线均不触发调度或取消。 */
@Service
public class ResearchEventStreamService implements AutoCloseable {
    private final ResearchRunStore store;
    private final ScheduledExecutorService pollers = Executors.newScheduledThreadPool(2, action -> {
        Thread thread = new Thread(action, "research-events"); thread.setDaemon(true); return thread;
    });
    private final java.util.Set<Runnable> connections = ConcurrentHashMap.newKeySet();

    public ResearchEventStreamService(ResearchRunStore store) { this.store = store; }

    public SseEmitter subscribe(String id, long after) {
        if (after < 0) throw new ClientException("事件游标无效");
        String owner = UserContext.requireUser().getUserId();
        store.get(id, owner); // 在请求线程先鉴权；异步线程固定 owner，不依赖 ThreadLocal。
        synchronized (connections) {
            if (connections.size() >= 100) throw new ClientException("进度订阅过多，请稍后重试");
            SseEmitter emitter = new SseEmitter(120000L);
            AtomicBoolean closed = new AtomicBoolean();
            AtomicLong cursor = new AtomicLong(after);
            AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
            Runnable cleanup = () -> {
                if (closed.compareAndSet(false, true)) {
                    var pending = scheduled.get(); if (pending != null) pending.cancel(false);
                }
            };
            Runnable disconnect = () -> { cleanup.run(); emitter.complete(); };
            Runnable remove = () -> { cleanup.run(); connections.remove(disconnect); };
            connections.add(disconnect);
            emitter.onCompletion(remove); emitter.onTimeout(disconnect); emitter.onError(error -> remove.run());
            Runnable poll = () -> {
                if (closed.get()) return;
                try {
                    List<ResearchEvent> events = store.events(id, owner, cursor.get(), 100);
                    for (var event : events) {
                        emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                                .name("ARTIFACT".equals(event.type()) ? "artifact" : "progress").data(publicEvent(event)));
                        cursor.set(event.sequence());
                    }
                    var run = store.get(id, owner);
                    emitter.send(SseEmitter.event().name("snapshot").data(run));
                    // 终态检查与读取事件有竞争；再读取一次尾部，避免遗漏原子提交的 ARTIFACT。
                    if (run.status().terminal() || run.status() == ResearchRun.Status.WAITING_INPUT) {
                        List<ResearchEvent> tail;
                        do {
                            tail = store.events(id, owner, cursor.get(), 100);
                            for (var event : tail) {
                                emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                                        .name("ARTIFACT".equals(event.type()) ? "artifact" : "progress").data(publicEvent(event)));
                                cursor.set(event.sequence());
                            }
                        } while (tail.size() == 100);
                        disconnect.run(); remove.run();
                    }
                } catch (java.io.IOException disconnected) {
                    // Servlet 容器负责异步断线通知；只关闭订阅，不重复写入已断开的响应。
                    remove.run();
                } catch (Exception failure) {
                    remove.run(); emitter.completeWithError(failure);
                }
            };
            scheduled.set(pollers.scheduleWithFixedDelay(poll, 0, 750, TimeUnit.MILLISECONDS));
            if (closed.get()) scheduled.get().cancel(false);
            return emitter;
        }
    }

    static ResearchEvent publicEvent(ResearchEvent event) {
        Map<String, Object> payload;
        if (event.type().equals("ARTIFACT") || event.type().equals("SOURCE_READ") || event.type().startsWith("SUBTASK_")) payload = event.payload();
        else {
            var safe = new java.util.LinkedHashMap<String, Object>();
            for (String key : List.of("tool", "status", "outputType", "reason")) if (event.payload().containsKey(key)) safe.put(key, event.payload().get(key));
            payload = safe;
        }
        return new ResearchEvent(event.sequence(), event.taskId(), event.type(), event.summary(), payload, event.createdAt());
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { connections.forEach(Runnable::run); connections.clear(); pollers.shutdownNow(); }
}
