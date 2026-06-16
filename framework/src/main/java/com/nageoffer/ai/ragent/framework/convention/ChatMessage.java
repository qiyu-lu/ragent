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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息实体
 *
 * <p>
 * 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容：
 * <ul>
 *   <li>{@link Role#SYSTEM}：系统提示词，用于为大模型设定行为、规则</li>
 *   <li>{@link Role#USER}：用户输入消息</li>
 *   <li>{@link Role#ASSISTANT}：大模型（助手）回复内容</li>
 * </ul>
 * 该结构适合在不同模型/厂商之间做一层通用抽象
 * </p>
 */
@Data //自动生成setter, getter
@NoArgsConstructor  //生成无参构造方法
@AllArgsConstructor //生成全参构造方法
public class ChatMessage {

    /**
     * 消息角色类型
     */
    public enum Role {//表示消息角色
        /**
         * 系统角色，一般用于设定对话规则、身份设定、风格约束等
         */
        SYSTEM,//系统消息一般用于设置模型行为,对模型的约束

        /**
         * 用户角色，表示真实用户的提问或输入内容
         */
        USER, //用户消息,表示真实用户输入的问题

        /**
         * 助手机器人角色，表示大模型返回的回复内容
         */
        ASSISTANT;//大模型之前返回的内容

        /**
         * 根据字符串值匹配对应的角色枚举
         *
         * @param value 角色字符串值，不区分大小写
         * @return 匹配到的 {@link Role} 枚举值
         * @throws IllegalArgumentException 当传入的字符串无法匹配任何角色时抛出异常
         */
        //根据字符串转换成对应的枚举角色
        public static Role fromString(String value) {
            for (Role role : Role.values()) {
                //equalsIgnoreCase(value) 不区分大小写
                // name ：枚举类继承的方法，返回这个枚举常量的名字字符串
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    /**
     * 当前消息的角色（系统 / 用户 / 助手）
     */
    private Role role;

    /**
     * 消息的具体文本内容
     */
    private String content;

    //三个静态工厂方法
    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}

