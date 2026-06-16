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

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG Trace 上下文
 * 使用 TTL 在异步线程池中透传 traceId 与节点栈
 */
//链路追踪上下文工具类 用于在一次 RAG 任务执行过程中保存一些“当前线程上下文信息”
public final class RagTraceContext {

    //traceId：一次请求/链路的唯一 ID 表示一次完整请求的唯一标识
    //taskId：某个任务的 ID 表示当前 RAG 任务、智能体任务、工作流任务的 ID
    //nodeStack：当前执行到哪个节点，以及节点调用层级 这个栈用于记录当前正在执行的节点路径
    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>();

    private RagTraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    public static int depth() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    public static String currentNodeId() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    //先从 ThreadLocal 里取节点栈
    //如果没有栈，就创建一个新的 ArrayDeque
    //然后把 nodeId 压入栈顶
    public static void pushNode(String nodeId) {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        stack.push(nodeId);
    }

    public static void popNode() {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            NODE_STACK.remove();
        }
    }

    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
