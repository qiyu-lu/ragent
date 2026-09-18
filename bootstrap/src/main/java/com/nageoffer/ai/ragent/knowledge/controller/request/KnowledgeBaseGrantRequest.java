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

package com.nageoffer.ai.ragent.knowledge.controller.request;

import com.nageoffer.ai.ragent.knowledge.enums.KbGrantSubjectType;
import com.nageoffer.ai.ragent.knowledge.enums.KbPermission;
import lombok.Data;

/**
 * 知识库授权请求
 */
@Data
public class KnowledgeBaseGrantRequest {

    /**
     * 授权对象类型：USER / ROLE
     */
    private KbGrantSubjectType subjectType;

    /**
     * 用户 ID 或角色名
     */
    private String subjectId;

    /**
     * READ / MANAGE
     */
    private KbPermission permission;
}
