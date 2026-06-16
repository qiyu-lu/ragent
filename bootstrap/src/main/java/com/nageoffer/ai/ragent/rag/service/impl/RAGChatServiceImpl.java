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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * RAG 流式对话的核心编排服务。
 *
 * <p>该类不直接实现查询改写、意图识别、数据检索或 SSE 事件发送，而是负责串联这些组件，
 * 根据中间结果选择“歧义引导、系统直答、RAG 检索问答”三条响应路径。</p>
 *
 * <p>
 * 主流程为：
 * 会话与任务初始化 -> 记忆加载并保存用户问题 -> 查询改写/拆分 -> 子问题意图解析
 * -> 歧义引导判断 -> MCP/知识库检索 -> Prompt 组装 -> LLM 流式输出。
 * 提前命中引导或系统意图时，会跳过后续不必要的检索阶段。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    /**
     * LLM 调用及 Prompt 构建相关组件。
     */
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 对话记忆与流式任务生命周期组件。
     */
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;

    /**
     * 执行一次完整的 SSE 流式问答。
     *
     * <p>方法本身只负责启动流式调用，不等待模型输出结束。后续内容、完成、异常和取消事件
     * 由 {@link StreamCallback} 与 {@link StreamTaskManager} 协同处理。</p>
     */
    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // 前端未传会话 ID 时创建新会话；已有会话则沿用原 ID，从而关联历史记忆。
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;

        /*
         * 优先复用链路追踪上下文中的 taskId，使日志追踪 ID、前端停止任务时使用的 ID
         * 和本地/Redis 中登记的流式任务 ID 保持一致；上下文缺失时才单独生成。
         */
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);

        // Boolean.TRUE.equals 可同时兼容 false 和 null，避免包装类型自动拆箱导致空指针。
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        /*
         * 创建处理器时会立即发送 META 事件，并把 SSE 连接及取消回调注册到 taskManager。
         * 因此前端拿到 taskId 后即可发起停止请求。
         * 创建一个流式回调处理器。后面大模型每生成一段内容，就会调用这个 callback。
         */
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        String userId = UserContext.getUserId();

        /*
         * loadAndAppend 的顺序是：先加载本轮之前的历史，再持久化当前用户问题。
         * 返回的 history 不包含本次 question，可直接作为查询改写和最终 Prompt 的历史上下文。
         */
        List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

        // 结合历史消解指代并改写当前问题；复合问题还会被拆成多个可独立识别、检索的子问题。
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);

        // 对每个子问题识别候选意图，后续据此决定系统直答、MCP 调用或知识库检索。
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        /*
         * 当多个业务系统意图分数接近且用户没有明确系统名称时，先让用户选择目标系统。
         * 该分支通过统一 callback 输出，因此引导文案同样会被保存为 assistant 消息并正常结束 SSE。
         */
        GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
        if (guidanceDecision.isPrompt()) {
            callback.onContent(guidanceDecision.getPrompt());
            callback.onComplete();
            return;
        }

        /*
         * 只有每个子问题都恰好命中单个 SYSTEM 意图时才走系统直答。
         * SYSTEM 意图用于一般对话或固定能力，不需要访问 MCP 和知识库。
         */
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            // 多个系统意图存在模板时，当前策略采用第一个非空模板作为本次 system prompt。
            String customPrompt = subIntents.stream()
                    .flatMap(si -> si.nodeScores().stream())
                    .map(ns -> ns.getNode().getPromptTemplate())
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .orElse(null);
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);

            /*
             * LLM 流建立后再绑定底层取消句柄。若停止请求先于句柄返回，
             * StreamTaskManager 会记录取消状态，并在绑定时立即取消该句柄。
             */
            taskManager.bindHandle(taskId, handle);
            return;
        }

        // 非纯系统意图进入统一检索引擎；引擎会按子问题并行执行 KB 检索和 MCP 工具调用。
        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K);
        if (ctx.isEmpty()) {
            /*
             * MCP 与知识库均未产生有效上下文时不让模型自由作答，避免生成缺乏依据的内容。
             * 仍通过 callback 完成响应，以复用消息落库、FINISH/DONE 事件和任务清理逻辑。
             */
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        /*
         * 检索以子问题为粒度执行，而 Prompt 规划需要当前请求的完整 MCP/KB 意图集合，
         * 因此在这里将各子问题意图按类型合并。
         */
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

        // 组装证据、历史和问题后启动真正的 RAG 流式生成。
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback
        );

        // 将模型客户端返回的取消能力绑定到前面已注册的 taskId。
        taskManager.bindHandle(taskId, handle);
    }

    /**
     * 请求停止指定流式任务。
     *
     * <p>任务管理器会写入 Redis 取消标记并广播，因此发起停止请求的节点
     * 不必与实际执行模型流的节点相同。</p>
     */
    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    /**
     * 启动不依赖外部检索证据的系统直答。
     *
     * @param question     改写后的当前问题
     * @param history      本轮问题写入前加载的对话历史
     * @param customPrompt SYSTEM 意图节点配置的专用提示词，可为空
     * @param callback     流式事件回调
     * @return 用于中止底层模型请求的句柄
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        // 意图节点配置了专用模板时优先使用，否则回退到通用聊天 system prompt。
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            /*
             * 注意：ConversationMemoryService.loadAndAppend 当前返回的是“追加当前问题之前”的历史，
             * history 中并不包含本轮 question。因此这里排除最后一条并非去除重复问题，
             * 按现有契约会实际少传一条既有历史消息；阅读或修改该逻辑时需要重点核对。
             */
            messages.addAll(history.subList(0, history.size() - 1));
        }

        // 使用改写后的问题作为本轮最终 user 消息，避免把原始问题和改写问题重复加入上下文。
        messages.add(ChatMessage.user(question));

        /*
         * 系统直答不启用深度思考，并使用相对较高的温度以保留一般对话的自然表达；
         * RAG 分支则会根据证据类型采用更保守的采样参数。
         */
        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    /**
     * 基于 MCP/知识库检索结果启动 RAG 流式生成。
     *
     * @param rewriteResult 改写后的主问题及拆分出的子问题
     * @param ctx           MCP、知识库证据及意图到文档分片的映射
     * @param intentGroup   合并后的 MCP/知识库意图
     * @param history       本轮之前的对话历史
     * @param deepThinking  是否允许模型输出思考流
     * @param callback      流式事件回调
     * @return 用于中止底层模型请求的句柄
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        /*
         * PromptContext 只承载 Prompt 规划所需的结构化数据：
         * 证据文本决定 KB_ONLY/MCP_ONLY/MIXED 场景，意图与分片映射决定是否采用节点专用模板。
         */
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        /*
         * 最终消息通常按 system prompt -> MCP/KB 证据 -> 历史消息 -> 当前问题排列。
         * 多子问题场景会把子问题显式编号，降低模型漏答其中某一项的概率。
         */
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()
        );

        /*
         * 纯知识库问答使用确定性更高的 temperature=0；MCP 返回的是动态数据，
         * 这里适当提高 temperature 并收紧 topP，在表达灵活性与答案稳定性之间折中。
         */
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        // 调用立即返回取消句柄；模型增量内容通过 callback 异步推送给前端。
        return llmService.streamChat(chatRequest, callback);
    }
}
