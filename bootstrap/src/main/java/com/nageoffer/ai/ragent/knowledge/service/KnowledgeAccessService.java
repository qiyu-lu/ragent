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

package com.nageoffer.ai.ragent.knowledge.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.enums.KbGrantSubjectType;
import com.nageoffer.ai.ragent.knowledge.enums.KbPermission;
import com.nageoffer.ai.ragent.knowledge.enums.KbVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库访问判定的唯一入口
 * <p>
 * 规则：管理员拥有全部权限；所有者拥有 MANAGE；PUBLIC 库全体登录用户 READ；PUBLIC 与 RESTRICTED 库上的授权按用户或角色生效，
 * PRIVATE 库忽略授权。MANAGE 蕴含 READ。已删除的库对任何人都不可访问。
 * <p>
 * 身份缺失（无登录用户、接管线程找不到用户）一律按无权处理，不回退为全局可见。
 * 结论不缓存：每次按库表与授权表现算，撤销授权对下一次请求立即生效
 */
@Service
@RequiredArgsConstructor
public class KnowledgeAccessService {

    public static final String ADMIN_ROLE = "admin";

    private static final String ACCESSIBLE = """
            SELECT kb.id, kb.collection_name FROM t_knowledge_base kb
            WHERE kb.deleted = 0 AND (
                ? OR kb.owner_user_id = ?
                OR (? AND kb.visibility = 'PUBLIC')
                OR (kb.visibility <> 'PRIVATE' AND EXISTS (
                    SELECT 1 FROM t_knowledge_base_grant g
                    WHERE g.kb_id = kb.id
                      AND ((g.subject_type = 'USER' AND g.subject_id = ?) OR (g.subject_type = 'ROLE' AND g.subject_id = ?))
                      AND (? OR g.permission = 'MANAGE'))))
            """;

    private final JdbcTemplate jdbc;

    /**
     * 访问主体。userId 与 role 均可为空，为空的一侧不匹配任何所有者或授权
     */
    public record Subject(String userId, String role) {

        public static final Subject NOBODY = new Subject(null, null);

        public boolean admin() {
            return ADMIN_ROLE.equals(role);
        }
    }

    public record Grant(String id, String kbId, KbGrantSubjectType subjectType, String subjectId,
                        KbPermission permission, String createdBy, Date createTime) {
    }

    /**
     * 当前请求的主体；没有登录用户时为 {@link Subject#NOBODY}
     */
    public Subject current() {
        LoginUser user = UserContext.get();
        return user == null ? Subject.NOBODY : new Subject(user.getUserId(), user.getRole());
    }

    /**
     * 按 t_user 重建主体，供不在请求线程里的执行者（如研究任务接管）使用；用户不存在或已删除时无任何权限
     */
    public Subject ofUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            return Subject.NOBODY;
        }
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM t_user WHERE id = ? AND COALESCE(deleted, 0) = 0", String.class, userId);
        return roles.isEmpty() ? Subject.NOBODY : new Subject(userId, roles.get(0));
    }

    /**
     * 主体在给定权限下可访问的知识库，键为库 ID，值为 collection 名
     */
    public Map<String, String> accessible(Subject subject, KbPermission permission) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(subject.userId())) {
            return result;
        }
        jdbc.query(ACCESSIBLE + " ORDER BY kb.id", rs -> {
            result.put(rs.getString(1), rs.getString(2));
        }, params(subject, permission));
        return result;
    }

    public Set<String> accessibleKbIds(Subject subject, KbPermission permission) {
        return Set.copyOf(accessible(subject, permission).keySet());
    }

    /**
     * 可读知识库的 collection，检索作用域在召回前与它求交
     */
    public List<String> readableCollections(Subject subject) {
        return accessible(subject, KbPermission.READ).values().stream()
                .filter(StrUtil::isNotBlank).distinct().toList();
    }

    public boolean can(Subject subject, String kbId, KbPermission permission) {
        if (StrUtil.isBlank(kbId) || StrUtil.isBlank(subject.userId())) {
            return false;
        }
        Object[] base = params(subject, permission);
        Object[] args = new Object[base.length + 1];
        System.arraycopy(base, 0, args, 0, base.length);
        args[base.length] = kbId;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (" + ACCESSIBLE + " AND kb.id = ?)", Boolean.class, args));
    }

    /**
     * 要求当前用户对知识库有给定权限。不可读时与不存在同一提示，不泄露库是否存在
     */
    public void requireKb(String kbId, KbPermission permission) {
        Subject subject = current();
        if (!can(subject, kbId, KbPermission.READ)) {
            throw new ClientException("知识库不存在");
        }
        if (permission == KbPermission.MANAGE && !can(subject, kbId, KbPermission.MANAGE)) {
            throw new ClientException("无权管理该知识库");
        }
    }

    /**
     * 要求当前用户对文档所在知识库有给定权限，返回库 ID
     */
    public String requireDocument(String docId, KbPermission permission) {
        List<String> kbIds = StrUtil.isBlank(docId) ? List.of() : jdbc.queryForList(
                "SELECT kb_id FROM t_knowledge_document WHERE id = ? AND deleted = 0", String.class, docId);
        Subject subject = current();
        if (kbIds.size() != 1 || !can(subject, kbIds.get(0), KbPermission.READ)) {
            throw new ClientException("文档不存在");
        }
        if (permission == KbPermission.MANAGE && !can(subject, kbIds.get(0), KbPermission.MANAGE)) {
            throw new ClientException("无权管理该文档");
        }
        return kbIds.get(0);
    }

    public void setVisibility(String kbId, KbVisibility visibility) {
        if (visibility == null) {
            throw new ClientException("可见性不能为空");
        }
        jdbc.update("UPDATE t_knowledge_base SET visibility = ?, update_time = CURRENT_TIMESTAMP WHERE id = ? AND deleted = 0",
                visibility.name(), kbId);
    }

    public List<Grant> listGrants(String kbId) {
        return jdbc.query("""
                        SELECT id, kb_id, subject_type, subject_id, permission, created_by, create_time
                        FROM t_knowledge_base_grant WHERE kb_id = ? ORDER BY create_time, id
                        """,
                (rs, row) -> new Grant(rs.getString(1), rs.getString(2),
                        KbGrantSubjectType.valueOf(rs.getString(3)), rs.getString(4),
                        KbPermission.valueOf(rs.getString(5)), rs.getString(6), rs.getTimestamp(7)),
                kbId);
    }

    /**
     * 授予或改写同一对象在该库上的权限；USER 对象必须是现存用户
     */
    public String grant(String kbId, KbGrantSubjectType subjectType, String subjectId, KbPermission permission) {
        if (subjectType == null || permission == null || StrUtil.isBlank(subjectId) || subjectId.length() > 64) {
            throw new ClientException("授权对象或权限无效");
        }
        if (subjectType == KbGrantSubjectType.USER && ofUser(subjectId) == Subject.NOBODY) {
            throw new ClientException("授权用户不存在");
        }
        return jdbc.queryForObject("""
                        INSERT INTO t_knowledge_base_grant (id, kb_id, subject_type, subject_id, permission, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (kb_id, subject_type, subject_id) DO UPDATE SET permission = EXCLUDED.permission
                        RETURNING id
                        """, String.class,
                IdUtil.getSnowflakeNextIdStr(), kbId, subjectType.name(), subjectId, permission.name(),
                UserContext.getUserId());
    }

    public void revoke(String kbId, String grantId) {
        if (jdbc.update("DELETE FROM t_knowledge_base_grant WHERE id = ? AND kb_id = ?", grantId, kbId) == 0) {
            throw new ClientException("授权不存在");
        }
    }

    private static Object[] params(Subject subject, KbPermission permission) {
        boolean read = permission == KbPermission.READ;
        return new Object[]{subject.admin(), subject.userId(), read, subject.userId(), subject.role(), read};
    }
}
