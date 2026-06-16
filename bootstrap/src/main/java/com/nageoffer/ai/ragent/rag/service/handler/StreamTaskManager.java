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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 流式聊天任务的注册、取消与清理管理器。
 *
 * <p>本地缓存保存当前节点上的 SSE 发送器、模型取消句柄和取消回调；
 * Redis Key 记录一段时间内的取消状态，Redis Topic 将取消指令广播到所有应用节点。
 * 两者配合，使取消请求能够找到实际执行模型流和持有 SSE 连接的节点。</p>
 *
 * <p>典型时序为：事件处理器先 {@link #register} SSE 信息，LLM 请求建立后再
 * {@link #bindHandle} 取消句柄；用户停止任务时调用 {@link #cancel}，实际执行节点通过
 * Topic 进入 {@link #cancelLocal}。实现必须同时覆盖“取消早于注册”和“取消早于句柄绑定”两类竞态。</p>
 */
@Slf4j
@Component
public class StreamTaskManager {

    /**
     * 取消广播主题、取消状态 Key 前缀，以及状态的最大保留时间。
     */
    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    /**
     * 当前节点持有的流式任务运行信息。
     *
     * <p>任务完成时会主动移除；TTL 和最大容量用于兜底，防止异常流程导致本地缓存无限增长。
     * {@code expireAfterWrite} 从缓存条目首次写入时计时，后续只是修改条目内部字段，
     * 不会自动刷新过期时间。</p>
     */
    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;

    /**
     * Redis Topic 监听器 ID，用于应用关闭时解除订阅。
     */
    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 应用启动后订阅跨节点取消事件。
     *
     * <p>所有节点都会收到广播，但只有本地缓存中存在该任务的节点才会执行实际取消。
     * 监听回调会在 Redisson 的消息处理线程中同步执行 {@link #cancelLocal}，
     * 因而其中的模型取消、消息落库和 SSE 收尾也发生在该回调链路上。</p>
     */
    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, taskId) -> {
            if (StrUtil.isBlank(taskId)) {
                return;
            }
            cancelLocal(taskId);
        });
    }

    /**
     * 应用关闭前移除 Redis Topic 监听器，避免遗留无效订阅。
     */
    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册任务的 SSE 连接和取消收尾逻辑。
     *
     * <p>注册时额外检查 Redis 取消标记，用于处理“取消请求先到、任务稍后才注册”
     * 或节点暂时错过 Topic 广播的情况。</p>
     *
     * @param taskId           一次流式请求的全局任务 ID
     * @param sender           当前任务对应的 SSE 发送器
     * @param onCancelSupplier 取消时生成消息 ID、标题等完成信息的回调
     */
    public void register(String taskId, SseEmitterSender sender, Supplier<CompletionPayload> onCancelSupplier) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);

        /*
         * taskInfo 可能已经由 bindHandle 提前创建。字段使用 volatile 保证监听线程可见，
         * 但 sender 与 onCancelSupplier 是两次独立赋值，并不是一个原子注册动作；
         * 极端情况下取消监听可能在两次赋值之间观察到不完整状态，这是现有实现需要注意的竞态。
         */
        taskInfo.sender = sender;
        taskInfo.onCancelSupplier = onCancelSupplier;

        /*
         * 若任务此前已被取消，立即执行取消收尾，不再等待新的广播消息。
         * 此分支只发送 SSE 收尾；按照当前正常调用顺序，底层 handle 此时尚未绑定。
         */
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(sender, payload);
            sender.complete();
        }
    }

    /**
     * 绑定底层模型请求的取消句柄。
     *
     * <p>模型调用建立和取消请求可能并发发生。如果绑定前任务已经被标记为取消，
     * 则在句柄到达后立即取消底层请求。</p>
     *
     * @param taskId 流式任务 ID
     * @param handle LLM 客户端返回的底层取消句柄
     */
    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.handle = handle;

        /*
         * 先发布 handle，再检查 cancelled，可覆盖 cancelLocal 已经把状态改为 true、
         * 但当时还没有 handle 的竞态。若取消恰好发生在赋值与检查之间，
         * handle.cancel() 可能被调用两次，因此底层取消实现应保持幂等。
         */
        if (taskInfo.cancelled.get() && handle != null) {
            handle.cancel();
        }
    }

    /**
     * 查询当前节点记录的任务取消状态。
     *
     * <p>流式内容回调调用频繁，因此这里只读取本地原子状态，不在每个 chunk 到达时访问 Redis。
     * Redis 状态同步由 Topic 监听和注册阶段检查负责。</p>
     *
     * @return 当前节点已知该任务被取消时返回 {@code true}
     */
    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 发起跨节点取消。
     *
     * <p>先写入带 TTL 的 Redis 标记，再广播任务 ID。持久化标记用于覆盖发布订阅
     * 不保留历史消息的特性，广播则让持有该任务的节点立即响应。</p>
     *
     * @param taskId 要停止的流式任务 ID
     */
    public void cancel(String taskId) {
        // 先设置 Redis 标记，确保订阅方或稍后注册的任务都能确认取消状态。
        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(Boolean.TRUE, CANCEL_TTL);

        // 包括发起取消的本地节点在内，统一通过 Topic 监听器进入 cancelLocal。
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId);
    }

    /**
     * 检查 Redis 中的取消标记，并在命中时同步到本地任务状态。
     *
     * <p>本地状态是后续 callback 快速判断的依据，因此 Redis 命中后必须写回
     * {@code taskInfo.cancelled}，而不能只返回查询结果。</p>
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        // 优先读取本地原子状态，避免不必要的 Redis 查询。
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        Boolean cancelled = bucket.get();
        if (Boolean.TRUE.equals(cancelled)) {
            taskInfo.cancelled.set(true);
            return true;
        }
        return false;
    }

    /**
     * 在实际持有任务的节点执行取消。
     *
     * <p>取消包括：停止底层模型请求、执行取消回调保存已生成内容、
     * 向客户端发送结束事件并关闭 SSE 连接。</p>
     *
     * <p>取消后不会立即从本地缓存移除任务：保留 {@code cancelled=true} 可以让随后到达的
     * 模型回调直接返回，也能让稍后绑定的 handle 立即取消。最终由缓存 TTL 兜底清理。</p>
     */
    private void cancelLocal(String taskId) {
        // 广播会到达所有节点；没有该任务本地状态的节点直接忽略。
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 同一取消消息可能被重复触发，CAS 保证模型取消和 SSE 收尾最多执行一次。
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        // 中止底层 HTTP/模型流，阻止后续内容继续生成。
        if (taskInfo.handle != null) {
            taskInfo.handle.cancel();
        }

        /*
         * 若 SSE 已注册，则由业务回调保存已累积的正式回答并构造 CompletionPayload，
         * 然后按 CANCEL -> DONE 的顺序通知前端。取消回调本身可能访问数据库。
         */
        if (taskInfo.sender != null) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(taskInfo.sender, payload);
            taskInfo.sender.complete();
        }
    }

    /**
     * 正常完成或异常结束后移除任务状态。
     *
     * <p>取消路径不会直接调用该方法，因为仍需保留 cancelled 状态拦截迟到的模型回调。</p>
     *
     * @param taskId 已结束的流式任务 ID
     */
    public void unregister(String taskId) {
        // 本地运行信息不再需要，立即释放相关对象引用。
        tasks.invalidate(taskId);

        /*
         * 异步删除 Redis 取消标记，不阻塞 SSE 完成线程。
         * 删除完成前存在一个很短的旧标记窗口，但 taskId 正常情况下不会复用。
         */
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
    }

    /**
     * 构造单个任务对应的 Redis 取消状态 Key。
     */
    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    /**
     * 按协议依次发送取消信息和 SSE 流结束标记。
     *
     * <p>业务载荷为空时创建字段均为 null 的默认载荷，保持 CANCEL 事件结构稳定；
     * DONE 则作为客户端停止读取事件流的最终标志。</p>
     */
    private void sendCancelAndDone(SseEmitterSender sender, CompletionPayload payload) {
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
    }

    /**
     * 获取本地任务信息；不存在时以原子方式创建。
     *
     * <p>Guava Cache 的 {@code get(key, loader)} 保证并发创建同一 taskId 时只发布一个缓存值。
     * 加载失败会抛出受检异常，{@link SneakyThrows} 仅用于省略包装代码。</p>
     */
    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    /**
     * 单个流式任务在当前节点上的运行时信息。
     *
     * <p>{@code cancelled} 用于保证取消幂等；其余字段使用 {@code volatile}，
     * 使注册线程、模型调用线程和 Redis 监听线程之间能够看到最新绑定结果。</p>
     *
     * <p>{@code handle} 负责停止底层模型/HTTP 请求，{@code sender} 负责结束客户端 SSE，
     * {@code onCancelSupplier} 则负责保存部分回答并生成取消事件载荷。</p>
     */
    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile StreamCancellationHandle handle;
        private volatile SseEmitterSender sender;
        private volatile Supplier<CompletionPayload> onCancelSupplier;
    }
}
