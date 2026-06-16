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
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

/**
 * 将大模型的流式回调转换为前端可消费的 SSE 事件。
 *
 * <p>处理器在创建时发送会话元数据并注册流式任务；生成过程中分别转发思考内容和回答内容，
 * 同时累计完整回答；正常完成或用户取消时，将已经生成的回答写入对话记忆并结束 SSE 连接。</p>
 *
 * <p>该对象对应一次流式任务，内部保存了本次任务的 conversationId、taskId、userId 和标题策略。
 * 回调可能发生在请求线程之外，因此需要尽量使用构造阶段捕获的稳定上下文，而不是依赖后续线程现场。</p>
 */
//流式聊天处理器
public class StreamChatEventHandler implements StreamCallback {

    /**
     * MESSAGE 事件中的内容类型，前端据此区分思考过程与正式回答。
     */
    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    /**
     * 单个 SSE MESSAGE 事件最多携带的 Unicode 字符数量。
     *
     * <p>该限制作用于每一次模型回调传入的 chunk，不会跨多次回调合并缓冲。</p>
     */
    private final int messageChunkSize;

    /**
     * SSE 输出、会话归属、记忆存储和任务生命周期所需的固定依赖。
     */
    private final SseEmitterSender sender; //对 SseEmitter 的进一步封装
    private final String conversationId; //当前会话 ID。
    private final ConversationMemoryService memoryService; //保存对话消息
    private final ConversationGroupService conversationGroupService; //用于查询会话信息，比如标题
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager; //用于管理流式任务，比如注册任务、取消任务、判断任务是否已取消、注销任务
    private final boolean sendTitleOnComplete;

    /**
     * 仅累计正式回答，不包含思考内容；用于完成或取消时持久化 assistant 消息。
     */
    private final StringBuilder answer = new StringBuilder();

    /**
     * 创建一次流式聊天事件处理器。
     *
     * <p>构造过程不仅赋值，还会查询标题状态、发送 META 事件并注册任务，
     * 因此创建该对象本身已经产生外部副作用。</p>
     *
     * @param params SSE、会话标识、模型配置和依赖服务等构建参数
     */
    //初始化一次流式任务
    //1. 获取当前 userId
    //2. 确定 SSE 分片大小
    //3. 判断结束时是否需要发送标题
    //4. 调用 initialize() 发送 META 并注册任务
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 固化本次流式任务使用的分片大小和标题返回策略，避免生成过程中配置或会话状态变化。
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 构造完成后立即向前端发送任务标识，并使该任务具备被取消的能力。
        initialize();
    }

    /**
     * 发送会话 ID、任务 ID，并向任务管理器注册取消时的收尾回调。
     *
     * <p>META 先发送，使前端尽早拿到停止任务所需的 taskId；随后才注册本地任务。
     * 如果取消请求恰好发生在两者之间，{@link StreamTaskManager#register} 会读取 Redis
     * 中已经写入的取消标记，并立即执行取消收尾。</p>
     */
    private void initialize() {
        //把conversationId ， taskId 告诉前端
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        //把当前任务注册到任务管理器中，使它具备被取消的能力
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析 SSE 消息分片大小，未配置时默认每片 5 个 Unicode 字符，且最小值为 1。
     *
     * <p>{@code Math.max(1, ...)} 防止配置为 0 或负数后分片条件永远无法按预期工作。</p>
     */
    //确定每次 SSE 发送多少字符
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断本次流结束时是否需要携带标题。
     *
     * <p>该判断在任务创建阶段只执行一次并保存为 {@code sendTitleOnComplete}：
     * 新对话或当前标题为空时，结束阶段会再查一次标题；已有标题的对话不重复下发。</p>
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消事件载荷。
     *
     * <p>用户可能在模型输出一部分内容后取消，因此先把已累计的回答作为 assistant 消息落库，
     * 再返回消息 ID 和当前标题，由 {@link StreamTaskManager} 发送 CANCEL 与 DONE 事件。</p>
     *
     * <p>只有正式回答非空时才落库，思考内容不会保存。注意当前代码使用
     * {@code String.valueOf(messageId)}：当没有可保存内容或存储返回 {@code null} 时，
     * 载荷中的 messageId 会是字符串 {@code "null"}，而不是真正的 {@code null}。</p>
     */
    //在用户取消任务时调用的，在模型输出了一部分后，用户点击了停止
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            //如果 answer 非空，就保存已经生成的部分回答 然后构造 CompletionPayload
            messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content));
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    /**
     * 接收一段模型正式回答。
     *
     * <p>有效内容会先追加到完整答案，再按配置大小拆分为 response 类型的 MESSAGE 事件。</p>
     *
     * @param chunk 模型本次回调产生的回答片段
     */
    //当模型返回正式回答片段时，会进入这里
    @Override
    public void onContent(String chunk) {
        // 取消后的模型回调可能仍会到达，此时不再累计、发送或重复落库。
        //1. 如果任务已取消，直接丢弃
        if (taskManager.isCancelled(taskId)) {
            return;
        }

        /*
         * StrUtil.isBlank 会把只包含空格、换行或制表符的 chunk 也视为无效。
         * 如果模型供应商把排版空白单独作为一个 chunk 返回，这些字符会被当前逻辑丢弃。
         */
        //2. 如果 chunk 是空白，直接丢弃
        if (StrUtil.isBlank(chunk)) {
            return;
        }

        // 正式回答既要实时推送，也要累计为完整消息供最终持久化。
        //3. 把 chunk 追加到 answer 中
        answer.append(chunk);
        //4. 按分片大小发送 response 类型 MESSAGE 事件
        sendChunked(TYPE_RESPONSE, chunk);
    }

    /**
     * 接收一段模型思考内容。
     *
     * <p>思考内容只以 think 类型实时展示，不加入最终 answer，
     * 因而不会进入正常完成或取消时保存的 assistant 消息。</p>
     *
     * @param chunk 模型本次回调产生的思考片段
     */
    //和 onContent() 类似，但有一个关键区别：onThinking 不会 answer.append(chunk)
    //思考过程：只给前端看，不落库,正式回答：给前端看，并最终保存
    @Override
    public void onThinking(String chunk) {
        // 思考内容只实时展示，不加入 answer，也不会作为最终 assistant 消息保存。
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        sendChunked(TYPE_THINK, chunk);
    }

    /**
     * 处理模型正常完成事件。
     *
     * <p>收尾顺序为：保存完整回答 -> 解析标题 -> 发送 FINISH -> 发送 DONE
     * -> 注销任务 -> 正常关闭 SSE。FINISH 携带业务完成信息，DONE 是流协议终止标记。</p>
     */
    @Override
    public void onComplete() {
        // 取消流程已由 StreamTaskManager 完成 SSE 收尾，避免正常完成逻辑再次执行。
        //1. 如果任务已经取消，直接返回
        if (taskManager.isCancelled(taskId)) {
            return;
        }

        /*
         * 这里重新从 UserContext 获取 userId，而取消分支使用构造时捕获的 userId。
         * 回调运行在线程池时依赖 TransmittableThreadLocal 正确传播上下文；若传播失败，
         * 正常完成和取消可能表现不一致。这是当前实现行为，本次不修改。
         */
        //2. 保存完整 assistant 回答
        String messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()));
        //3. 查询需要返回的标题
        String title = resolveTitleForEvent();

        // 正常完成分支会把 null 或空消息 ID 统一转换为真正的 null，与取消分支行为不同。
        String messageIdText = StrUtil.isBlank(messageId)? null : messageId;
        //4. 发送 FINISH 事件 业务完成事件，携带业务数据：messageId：保存后的消息 ID title：会话标题
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        //5. 发送 DONE 事件 协议结束标记
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");

        // 先注销任务并清除 Redis 取消标记，再以正常完成方式关闭连接。
        //6. 注销任务
        taskManager.unregister(taskId);
        //7. 正常关闭 SSE 连接
        sender.complete();
    }

    /**
     * 处理模型调用或回调链路异常。
     *
     * <p>异常路径只注销任务并关闭 SSE，不会像用户取消那样保存已经累计的部分回答。</p>
     *
     * @param t 导致流式任务失败的异常
     */
    @Override
    public void onError(Throwable t) {
        // 已取消任务的连接已经关闭，不再将随后到达的异常重复发送给客户端。
        //1. 如果任务已取消，不重复处理
        if (taskManager.isCancelled(taskId)) {
            return;
        }

        // 异常结束不执行正常完成落库，仅清理任务并通过 SSE 通知客户端失败。
        //2. 注销任务
        taskManager.unregister(taskId);
        //3. 通过 SSE 通知前端失败并关闭连接
        sender.fail(t);
    }

    /**
     * 按配置大小拆分一次模型回调内容，并逐片发送 MESSAGE 事件。
     *
     * <p>使用 Unicode code point 遍历，而不是直接按 {@code char} 截取，
     * 避免将 emoji、部分生僻字等由代理对表示的字符拆成两个无效片段。
     * {@code idx} 记录 UTF-16 char 偏移量，{@code count} 记录实际 Unicode 字符数量。</p>
     */
    //按 Unicode 字符安全分片
    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            // 读取完整 code point，并按其实际占用的 1 或 2 个 char 推进索引。
            //按 Unicode code point 遍历，能避免拆坏 emoji
            //Java 的 char 是 UTF-16，有些字符，比如 emoji、生僻字，可能由两个 char 组成。如果直接按 char 截取，可能把一个完整字符拆坏
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;

            // 达到配置字符数后立即发送一片，并复用同一个 buffer 处理后续字符。
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }

        // 输入长度不是分片大小整数倍时，发送最后不足一片的剩余内容。
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    /**
     * 获取完成事件中需要返回的标题。
     *
     * <p>仅当任务开始时判断为需要标题才重新查询，以便拿到对话处理期间异步生成的标题；
     * 若此时会话记录尚未创建或标题仍为空，则向前端返回“新对话”作为展示兜底。</p>
     */
    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            //如果本次不需要发送标题，返回 null
            // 已有标题的会话不在每次回答结束时重复传输标题。
            return null;
        }
        //如果需要发送标题，就查询会话
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        //如果查到标题，返回标题
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        //否则返回“新对话”
        return "新对话";
    }
}
