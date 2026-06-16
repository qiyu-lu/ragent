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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;

    /**
     * 发起 SSE 流式对话
     */
    @IdempotentSubmit( //防止重复提交 作用是：同一个用户在一次请求还没处理完之前，不能重复发起新的请求。
            //使用的是 Spring Expression Language，SpEL。 含义是 调用 UserContext.getUserId()
            //即用当前登录用户的 userId 作为幂等锁的 key
            //如果用户 A 正在请求 /rag/v3/chat，还没有处理完成，那么用户 A 再次请求这个接口，就会被拦截。
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            //如果重复提交被拦截，就返回这个提示信息
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    //produces 表示这个接口返回的是 SSE 流式数据，不是普通 JSON,
    //服务端可以持续向前端推送数据。大模型回答经常用这种方式实现“一个字一个字输出”的效果。

    //question 必传参数，表示用户的问题。
    // conversationId 非必传参数，表示会话 ID,通常用于多轮对话，第一次提问没有这个id，
    // 后续追问时加入这个id，这样后端就知道这是同一个会话，可以加载上下文
    //deepThinking 非必传参数，默认是 false。是否开启“深度思考”模式。
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        SseEmitter emitter = new SseEmitter(0L);
        //SseEmitter是 Spring 提供的 SSE 推送对象,后端可以通过它不断发送数据给前端
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
