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

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 召回前过滤的证明：最佳匹配位于不可读的库时，结果里没有来自该库的块，且 TopK=1 仍能拿到可读库的块。
 * 若过滤发生在召回之后，TopK=1 会先被不可读库的最佳匹配占掉、过滤后为空
 */
@EnabledIfEnvironmentVariable(named = "RESEARCH_P3_TEST_URL", matches = ".+")
class KnowledgeRetrievalIsolationPostgresIT {

    private static final int DIMENSION = 1536;

    private JdbcTemplate jdbc;
    private String secret, open, owner, reader, admin;

    @BeforeEach
    void setup() {
        String url = System.getenv("RESEARCH_P3_TEST_URL");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/research_p3_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only a random local research_p3_ database is allowed");
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("RESEARCH_TEST_PG_USER"), System.getenv("RESEARCH_TEST_PG_PASSWORD")));
        String s = UUID.randomUUID().toString().substring(0, 8);
        owner = "o" + s;
        reader = "r" + s;
        admin = "m" + s;
        for (String[] user : new String[][]{{owner, "user"}, {reader, "user"}, {admin, "admin"}}) {
            jdbc.update("INSERT INTO t_user (id, username, password, role) VALUES (?, ?, 'fixture', ?)", user[0], user[0], user[1]);
        }
        secret = "cs_secret_" + s;
        open = "cs_open_" + s;
        kb("ks" + s, secret, "PRIVATE");
        kb("kp" + s, open, "PUBLIC");
        // 查询向量为 e0：秘密库的块与它完全相同（相似度 1），公开库的块相似度 0.6
        vector("vs" + s, secret, unit(0, 1.0, 1, 0.0));
        vector("vp" + s, open, unit(0, 0.6, 1, 0.8));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void bestMatchInUnreadableKnowledgeBaseIsExcludedBeforeRecall() {
        var retriever = new PgVectorRetrieverService(jdbc, mock(EmbeddingService.class));
        float[] query = unit(0, 1.0, 1, 0.0);

        // 对照：所有者（或管理员）能读两库时，TopK=1 命中秘密库
        List<String> ownerScope = scopeFor(owner, "user").targetCollections();
        List<RetrievedChunk> ownerTop = retriever.retrieveByVector(query, request(ownerScope));
        assertEquals(List.of(secret), ownerTop.stream().map(RetrievedChunk::getCollectionName).toList());
        assertTrue(scopeFor(admin, "admin").targetCollections().containsAll(List.of(secret, open)));

        // 其他用户：作用域里没有秘密库，同一条 SQL 的 TopK=1 返回公开库的块
        RetrievalScope readerScope = scopeFor(reader, "user");
        assertFalse(readerScope.targetCollections().contains(secret));
        List<RetrievedChunk> readerTop = retriever.retrieveByVector(query, request(readerScope.targetCollections()));
        assertEquals(List.of(open), readerTop.stream().map(RetrievedChunk::getCollectionName).toList());

        // 没有登录身份时作用域为空，检索不发 SQL 也不返回任何块
        UserContext.clear();
        RetrievalScope anonymous = resolver().resolve();
        assertEquals(List.of(), fixtureOnly(anonymous.targetCollections()));
        System.out.printf("W4 retrieval isolation: owner top-1 %s (score %.3f); reader top-1 %s (score %.3f)%n",
                ownerTop.get(0).getCollectionName(), ownerTop.get(0).getScore(),
                readerTop.get(0).getCollectionName(), readerTop.get(0).getScore());
    }

    private RetrievalScope scopeFor(String userId, String role) {
        UserContext.set(LoginUser.builder().userId(userId).username(userId).role(role).build());
        return resolver().resolve();
    }

    private RetrievalScopeResolver resolver() {
        KbCollectionProvider active = mock(KbCollectionProvider.class);
        when(active.listActiveCollections()).thenReturn(List.of(secret, open));
        return new RetrievalScopeResolver(active, new KnowledgeAccessService(jdbc));
    }

    private List<String> fixtureOnly(List<String> collections) {
        return collections.stream().filter(c -> c.equals(secret) || c.equals(open)).toList();
    }

    private RetrieveRequest request(List<String> collections) {
        return RetrieveRequest.builder().query("q").collectionNames(fixtureOnly(collections)).topK(1).build();
    }

    private void kb(String id, String collection, String visibility) {
        jdbc.update("INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, owner_user_id, visibility) VALUES (?, ?, 'fixture', ?, ?, ?, ?)",
                id, id, collection, owner, owner, visibility);
    }

    private void vector(String id, String collection, float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) literal.append(i == 0 ? "" : ",").append(embedding[i]);
        jdbc.update("INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding) VALUES (?, ?, ?, jsonb_build_object('doc_id', ?::text), ?::vector)",
                id, collection, id, id, literal.append("]").toString());
    }

    private static float[] unit(int i, double x, int j, double y) {
        float[] v = new float[DIMENSION];
        v[i] = (float) x;
        v[j] += (float) y;
        return v;
    }
}
