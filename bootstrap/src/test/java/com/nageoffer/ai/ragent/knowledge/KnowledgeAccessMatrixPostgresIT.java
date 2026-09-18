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

package com.nageoffer.ai.ragent.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseGrantRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.enums.KbGrantSubjectType;
import com.nageoffer.ai.ragent.knowledge.enums.KbPermission;
import com.nageoffer.ai.ragent.knowledge.enums.KbVisibility;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 越权矩阵：用户 A（所有者）、用户 B、管理员 × 三种可见性的知识库 × 知识库 / 文档 / 分块的每个按 ID 操作的接口，
 * 外加授权的授予与撤销、列表过滤、问答任务停止。
 * <p>
 * 判定走真实的 {@link KnowledgeAccessService} 与真实 PostgreSQL；下游业务服务是 mock，
 * 「拒绝」必须同时满足抛出 {@link ClientException} 且下游没有被调用——鉴权在业务逻辑之前。
 * 期望值由本类的 {@link #expected} 按规则独立算出，不读实现
 */
@EnabledIfEnvironmentVariable(named = "RESEARCH_P3_TEST_URL", matches = ".+")
class KnowledgeAccessMatrixPostgresIT {

    private JdbcTemplate jdbc;
    private KnowledgeAccessService access;
    private KnowledgeBaseService kbService;
    private KnowledgeDocumentService docService;
    private KnowledgeChunkService chunkService;
    private FileStorageService storage;
    private KnowledgeBaseController kbController;
    private KnowledgeDocumentController docController;
    private KnowledgeChunkController chunkController;

    private String userA, userB, userC, admin;
    private final Map<KbVisibility, String> kbs = new LinkedHashMap<>();
    private final Map<KbVisibility, String> docs = new LinkedHashMap<>();
    private final List<String> results = new ArrayList<>();

    private record Op(String name, KbPermission need, OpCall call) { }

    @FunctionalInterface
    private interface OpCall {
        void run(String kbId, String docId) throws Exception;
    }

    @BeforeEach
    void setup() {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only a random local research_p3_ database is allowed");
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD")));
        access = new KnowledgeAccessService(jdbc);
        kbService = mock(KnowledgeBaseService.class);
        docService = mock(KnowledgeDocumentService.class);
        chunkService = mock(KnowledgeChunkService.class);
        storage = mock(FileStorageService.class);
        kbController = new KnowledgeBaseController(kbService, access);
        docController = new KnowledgeDocumentController(docService, storage, mock(IngestionSpecSchemaProvider.class), access);
        chunkController = new KnowledgeChunkController(chunkService, access);

        String s = UUID.randomUUID().toString().substring(0, 8);
        userA = user("a" + s, "user");
        userB = user("b" + s, "user");
        userC = user("c" + s, "user");
        admin = user("m" + s, "admin");
        for (KbVisibility visibility : KbVisibility.values()) {
            String kb = "kb" + visibility.name().charAt(1) + s;
            jdbc.update("INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, owner_user_id, visibility) VALUES (?, ?, 'fixture', ?, ?, ?, ?)",
                    kb, kb, "cs_" + kb, userA, userA, visibility.name());
            String doc = "d" + visibility.name().charAt(1) + s;
            jdbc.update("INSERT INTO t_knowledge_document (id, kb_id, doc_name, document_key, file_url, file_type, created_by) VALUES (?, ?, 'doc.md', ?, ?, 'md', ?)",
                    doc, kb, doc, "fixture:" + doc, userA);
            kbs.put(visibility, kb);
            docs.put(visibility, doc);
        }
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        vo.setDocName("doc.md");
        vo.setFileType("md");
        vo.setFileUrl("fixture");
        when(docService.get(anyString())).thenReturn(vo);
        when(storage.openStream("fixture")).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        when(kbService.pageQuery(any(), any())).thenReturn(new Page<>());
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private String user(String id, String role) {
        jdbc.update("INSERT INTO t_user (id, username, password, role) VALUES (?, ?, 'fixture', ?)", id, id, role);
        return id;
    }

    private List<Op> operations() {
        return List.of(
                new Op("kb.get", KbPermission.READ, (kb, doc) -> kbController.queryKnowledgeBase(kb)),
                new Op("kb.rename", KbPermission.MANAGE, (kb, doc) -> kbController.renameKnowledgeBase(kb, new KnowledgeBaseUpdateRequest())),
                new Op("kb.delete", KbPermission.MANAGE, (kb, doc) -> kbController.deleteKnowledgeBase(kb)),
                new Op("kb.visibility", KbPermission.MANAGE, (kb, doc) -> kbController.updateVisibility(kb, visibilityOf(kb))),
                new Op("kb.grants.list", KbPermission.MANAGE, (kb, doc) -> kbController.listGrants(kb)),
                new Op("kb.grants.add", KbPermission.MANAGE, (kb, doc) -> kbController.grant(kb, grantRequest(KbGrantSubjectType.USER, userC, KbPermission.READ))),
                new Op("kb.grants.revoke", KbPermission.MANAGE, (kb, doc) -> kbController.revoke(kb, access.grant(kb, KbGrantSubjectType.USER, userC, KbPermission.READ))),
                new Op("doc.upload", KbPermission.MANAGE, (kb, doc) -> docController.upload(kb, null, new KnowledgeDocumentUploadRequest())),
                new Op("doc.page", KbPermission.READ, (kb, doc) -> docController.page(kb, new KnowledgeDocumentPageRequest())),
                new Op("doc.get", KbPermission.READ, (kb, doc) -> docController.get(doc)),
                new Op("doc.update", KbPermission.MANAGE, (kb, doc) -> docController.update(doc, new KnowledgeDocumentUpdateRequest())),
                new Op("doc.delete", KbPermission.MANAGE, (kb, doc) -> docController.delete(doc)),
                new Op("doc.enable", KbPermission.MANAGE, (kb, doc) -> docController.enable(doc, true)),
                new Op("doc.chunk", KbPermission.MANAGE, (kb, doc) -> docController.startChunk(doc)),
                new Op("doc.chunk-logs", KbPermission.MANAGE, (kb, doc) -> docController.getChunkLogs(doc, new Page<>())),
                new Op("doc.preview", KbPermission.READ, (kb, doc) -> docController.preview(doc)),
                new Op("doc.file", KbPermission.READ, (kb, doc) -> docController.file(doc, new MockHttpServletResponse())),
                new Op("chunk.page", KbPermission.READ, (kb, doc) -> chunkController.pageQuery(doc, new KnowledgeChunkPageRequest())),
                new Op("chunk.create", KbPermission.MANAGE, (kb, doc) -> chunkController.create(doc, new KnowledgeChunkCreateRequest())),
                new Op("chunk.update", KbPermission.MANAGE, (kb, doc) -> chunkController.update(doc, "chunk", new KnowledgeChunkUpdateRequest())),
                new Op("chunk.delete", KbPermission.MANAGE, (kb, doc) -> chunkController.delete(doc, "chunk")),
                new Op("chunk.enable", KbPermission.MANAGE, (kb, doc) -> chunkController.enable(doc, "chunk", true)),
                new Op("chunk.batch-enable", KbPermission.MANAGE, (kb, doc) -> chunkController.batchEnable(doc, true, new KnowledgeChunkBatchRequest())));
    }

    /**
     * 规则表：管理员与所有者全部允许；其他用户在 PUBLIC 上可读，在 RESTRICTED 上按授权，在 PRIVATE 上一律拒绝
     */
    private boolean expected(String actor, KbVisibility visibility, KbPermission need, KbPermission grantedToB) {
        if (actor.equals(admin) || actor.equals(userA)) return true;
        return switch (visibility) {
            case PUBLIC -> need == KbPermission.READ || grantedToB == KbPermission.MANAGE;
            case RESTRICTED -> grantedToB == KbPermission.MANAGE || (grantedToB == KbPermission.READ && need == KbPermission.READ);
            case PRIVATE -> false;
        };
    }

    @Test
    void crossUserAccessMatrix() throws Exception {
        int cases = 0;
        // 阶段一：无授权
        for (String actor : List.of(userA, userB, admin)) {
            for (KbVisibility visibility : KbVisibility.values()) {
                cases += runAll(actor, visibility, null);
            }
        }
        // 阶段二：B 在 RESTRICTED 上得到 READ，在 PRIVATE 上得到 MANAGE（PRIVATE 忽略授权）
        asUser(userA);
        String readGrant = access.grant(kbs.get(KbVisibility.RESTRICTED), KbGrantSubjectType.USER, userB, KbPermission.READ);
        access.grant(kbs.get(KbVisibility.PRIVATE), KbGrantSubjectType.USER, userB, KbPermission.MANAGE);
        cases += runAll(userB, KbVisibility.RESTRICTED, KbPermission.READ);
        cases += runAll(userB, KbVisibility.PRIVATE, KbPermission.MANAGE);
        // 阶段三：按角色授予 MANAGE，B 作为普通用户随之获得管理权限
        asUser(userA);
        String roleGrant = access.grant(kbs.get(KbVisibility.RESTRICTED), KbGrantSubjectType.ROLE, "user", KbPermission.MANAGE);
        cases += runAll(userB, KbVisibility.RESTRICTED, KbPermission.MANAGE);
        // 阶段四：所有者撤销两条授权，下一次请求立即失效
        asUser(userA);
        kbController.revoke(kbs.get(KbVisibility.RESTRICTED), readGrant);
        kbController.revoke(kbs.get(KbVisibility.RESTRICTED), roleGrant);
        cases += runAll(userB, KbVisibility.RESTRICTED, null);

        System.out.println("W4 access matrix: " + cases + " cases\n" + String.join("\n", results));
        assertEquals(operations().size() * (9 + 4), cases);
    }

    private int runAll(String actor, KbVisibility visibility, KbPermission grantedToB) throws Exception {
        int cases = 0;
        for (Op op : operations()) {
            boolean allow = expected(actor, visibility, op.need(), grantedToB);
            asUser(actor);
            clearInvocations(kbService, docService, chunkService, storage);
            boolean denied = false;
            try {
                op.call().run(kbs.get(visibility), docs.get(visibility));
            } catch (ClientException e) {
                denied = true;
            }
            String label = role(actor) + " " + visibility + (grantedToB == null ? "" : "+" + grantedToB) + " " + op.name();
            if (allow == denied) fail(label + (allow ? " should be allowed" : " should be denied"));
            if (denied && touched()) fail(label + " was denied after reaching the business service");
            results.add(label + " -> " + (allow ? "ALLOW" : "DENY"));
            cases++;
        }
        return cases;
    }

    @Test
    void listsOnlyContainReadableKnowledgeBases() {
        Set<String> fixture = Set.copyOf(kbs.values());
        Map<String, Set<String>> expected = Map.of(
                userA, fixture,
                userB, Set.of(kbs.get(KbVisibility.PUBLIC)),
                admin, fixture);
        for (var entry : expected.entrySet()) {
            asUser(entry.getKey());
            clearInvocations(kbService, docService);
            kbController.pageQuery(new KnowledgeBasePageRequest());
            docController.search("doc", 8);
            ArgumentCaptor<Collection<String>> pageScope = ArgumentCaptor.captor();
            ArgumentCaptor<Collection<String>> searchScope = ArgumentCaptor.captor();
            verify(kbService).pageQuery(any(), pageScope.capture());
            verify(docService).search(any(), any(Integer.class), searchScope.capture());
            assertEquals(entry.getValue(), fixtureOnly(pageScope.getValue(), fixture), role(entry.getKey()) + " kb page");
            assertEquals(entry.getValue(), fixtureOnly(searchScope.getValue(), fixture), role(entry.getKey()) + " doc search");
        }
    }

    @Test
    void missingOrDeletedIdentityHasNoAccess() {
        UserContext.clear();
        assertTrue(access.accessibleKbIds(access.current(), KbPermission.READ).isEmpty(), "no login user");
        assertTrue(access.accessibleKbIds(access.ofUser("missing-user"), KbPermission.READ).isEmpty(), "unknown user");
        jdbc.update("UPDATE t_user SET deleted = 1 WHERE id = ?", userB);
        assertTrue(access.accessibleKbIds(access.ofUser(userB), KbPermission.READ).isEmpty(), "deleted user");
        jdbc.update("UPDATE t_knowledge_base SET deleted = 1 WHERE id = ?", kbs.get(KbVisibility.PUBLIC));
        assertTrue(!access.accessibleKbIds(access.ofUser(admin), KbPermission.READ).contains(kbs.get(KbVisibility.PUBLIC)), "deleted kb");
    }

    @Test
    void stopRequiresTaskOwnership() {
        StreamTaskManager tasks = mock(StreamTaskManager.class);
        when(tasks.isOwnedBy("task-a", userA)).thenReturn(true);
        var chat = new RAGChatServiceImpl(null, null, null, null, tasks);
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put(userA + ":task-a", true);
        expected.put(userB + ":task-a", false);
        expected.put(admin + ":task-a", true);
        expected.put(userA + ":task-unknown", false);
        for (var entry : expected.entrySet()) {
            String[] parts = entry.getKey().split(":");
            asUser(parts[0]);
            clearInvocations(tasks);
            boolean denied = false;
            try { chat.stopTask(parts[1]); } catch (ClientException e) { denied = true; }
            assertEquals(entry.getValue(), !denied, role(parts[0]) + " stop " + parts[1]);
            if (denied) verify(tasks, org.mockito.Mockito.never()).cancel(anyString());
            else verify(tasks).cancel(parts[1]);
        }
    }

    private Set<String> fixtureOnly(Collection<String> scope, Set<String> fixture) {
        return scope.stream().filter(fixture::contains).collect(Collectors.toSet());
    }

    private boolean touched() {
        return !mockingDetails(kbService).getInvocations().isEmpty()
                || !mockingDetails(docService).getInvocations().isEmpty()
                || !mockingDetails(chunkService).getInvocations().isEmpty()
                || !mockingDetails(storage).getInvocations().isEmpty();
    }

    private KbVisibility visibilityOf(String kb) {
        return kbs.entrySet().stream().filter(e -> e.getValue().equals(kb)).findFirst().orElseThrow().getKey();
    }

    private KnowledgeBaseGrantRequest grantRequest(KbGrantSubjectType type, String subject, KbPermission permission) {
        KnowledgeBaseGrantRequest request = new KnowledgeBaseGrantRequest();
        request.setSubjectType(type);
        request.setSubjectId(subject);
        request.setPermission(permission);
        return request;
    }

    private void asUser(String id) {
        String role = id.equals(admin) ? "admin" : "user";
        UserContext.set(LoginUser.builder().userId(id).username(id).role(role).build());
    }

    private String role(String id) {
        return id.equals(admin) ? "admin" : id.equals(userA) ? "A(owner)" : "B";
    }
}
