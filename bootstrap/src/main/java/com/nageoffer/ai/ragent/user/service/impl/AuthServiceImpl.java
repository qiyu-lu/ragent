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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类。
 *
 * <p>负责处理用户登录和登出：
 * <ul>
 *   <li>登录：校验用户名和密码，通过 Sa-Token 创建会话，返回 Token 和用户基础信息。</li>
 *   <li>登出：通过 Sa-Token 销毁当前用户会话。</li>
 * </ul>
 * Sa-Token 是一个轻量级 Java 权限认证框架，由它维护 Token 的生成、存储和校验。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 用户未上传头像时使用的默认头像地址。 */
    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;

    /**
     * 用户登录。
     *
     * <p>校验流程：空参数检查 -> 查库匹配用户名 -> 密码比对 -> Sa-Token 登录。
     * 登录成功后返回 userId、角色、Token 和头像 URL。</p>
     *
     * @param requestParam 包含用户名和密码的登录请求
     * @return 包含 Token 及用户基础信息的登录响应
     * @throws ClientException 用户名或密码为空、不匹配、用户信息异常时抛出
     */
    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        if (user == null || !passwordMatches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        StpUtil.login(loginId);//Sa-Token 框架的登录操作
        //如果没有头像的话使用默认头像
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        //上面这一行等价于：
        //String avatar;
        //
        //if (StrUtil.isBlank(user.getAvatar())) {
        //    avatar = DEFAULT_AVATAR_URL;
        //} else {
        //    avatar = user.getAvatar();
        //}
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    /**
     * 用户登出，销毁当前 Sa-Token 会话。
     */
    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 按用户名查询未删除的用户记录。
     *
     * @param username 用户名
     * @return 用户 DO；不存在时返回 {@code null}
     */
    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
        //SELECT * FROM user WHERE username = 'admin' AND deleted = 0 LIMIT 1;
    }

    /**
     * 简单文本密码比对（当前为明文匹配，生产环境应替换为哈希比对）。
     *
     * @param input  用户输入的原始密码
     * @param stored 数据库中存储的密码
     * @return 两者一致时返回 {@code true}
     */
    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }
}
