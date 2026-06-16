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

package com.nageoffer.ai.ragent.rag.dto;

/**
 * SSE META 事件的数据载体。
 *
 * <p>在每次流式任务建立后的第一个 SSE 事件中发送给前端，
 * 让前端提前获知：
 * <ul>
 *   <li>{@code conversationId}：本轮对话 ID，首次对话时由后端生成并通过此事件告知前端；
 *       后续追问时前端需把这个 ID 带上，以便加载历史记忆。</li>
 *   <li>{@code taskId}：本次流式任务 ID，前端可在需要时用它调用停止接口（/rag/v3/stop）</li>
 * </ul>
 * </p>
 *
 * <p>record 是 Java 16 正式引入的一种语法，用来快速定义不可变数据对象，
 * 字段自动生成 getter、equals、hashCode 和 toString。</p>
 */
public record MetaPayload(String conversationId, String taskId) {
}
