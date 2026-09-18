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

package com.nageoffer.ai.ragent.knowledge.enums;

/**
 * 知识库可见性
 * <p>
 * 所有者与管理员不受可见性影响；授权表只在 PUBLIC 与 RESTRICTED 下生效
 */
public enum KbVisibility {

    /**
     * 全员可读；管理权限仍只给所有者、管理员与 MANAGE 授权对象
     */
    PUBLIC,

    /**
     * 仅所有者与管理员，忽略授权表
     */
    PRIVATE,

    /**
     * 所有者、管理员与授权对象
     */
    RESTRICTED
}
