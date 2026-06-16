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

package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /user/me} 接口的响应体。
 *
 * <p>字段值直接来源于 {@link com.nageoffer.ai.ragent.framework.context.UserContext} 中
 * 预存的 {@link com.nageoffer.ai.ragent.framework.context.LoginUser} 快照，
 * 由 {@code UserContextInterceptor} 在每次请求前从 SaToken + 数据库填充，不走额外 DB 查询。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserVO {

    /** 用户唯一 ID（雪花 ID 字符串）。 */
    private String userId;

    /** 登录用户名。 */
    private String username;

    /** 用户角色（如 {@code admin} / {@code user}），用于前端权限控制。 */
    private String role;

    /** 用户头像 URL；用户未配置时返回 GitHub 默认头像地址。 */
    private String avatar;
}
