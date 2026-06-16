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

package com.nageoffer.ai.ragent.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.CurrentUserVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理控制器。
 *
 * <p>提供两类接口：
 * <ul>
 *   <li><b>当前用户</b>：{@code GET /user/me}（查自己信息）、{@code PUT /user/password}（改自己密码）</li>
 *   <li><b>管理员操作</b>：{@code GET/POST/PUT/DELETE /users/**}，均通过 {@code StpUtil.checkRole("admin")} 鉴权</li>
 * </ul>
 * </p>
 *
 * <p>所有接口均受 {@code SaInterceptor} 登录拦截；当前用户信息由 {@code UserContextInterceptor}
 * 在请求前置阶段写入 {@link UserContext}，控制器直接读取，无额外 DB 调用。</p>
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户的个人信息。
     *
     * <p>数据来源：{@link UserContext#requireUser()} — 由 {@code UserContextInterceptor}
     * 在每次请求前从 SaToken 中解析 loginId，再查询数据库填充到线程上下文中。
     * 本方法本身不访问数据库，属于纯内存读取。</p>
     *
     * @return 包含 {@link CurrentUserVO}（userId / username / role / avatar）的统一响应
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 若上下文中无登录用户（理论上已被拦截器过滤）
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        LoginUser user = UserContext.requireUser();
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getAvatar()
        ));
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前用户密码
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        userService.changePassword(requestParam);
        return Results.success();
    }
}
