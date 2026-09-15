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

package com.nageoffer.ai.ragent.ironore.agent;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** H2 by default; an optional PostgreSQL URL uses a fresh schema for every test. */
final class TaskAgentTestDatabase implements AutoCloseable {
    final DataSource source;
    private final JdbcTemplate admin;
    private final String schema;

    TaskAgentTestDatabase() {
        String url = System.getProperty("taskAgentTest.postgresUrl", "");
        if (url.isBlank()) {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            source = h2;
            schema = null;
            admin = new JdbcTemplate(source);
        } else {
            String user = System.getProperty("taskAgentTest.postgresUser", "task_agent_test");
            String password = System.getProperty("taskAgentTest.postgresPassword", "task-agent-local-test");
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            schema = "task_agent_test_" + UUID.randomUUID().toString().replace("-", "");
            admin.execute("CREATE SCHEMA " + schema);
            source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        }
    }

    @Override
    public void close() {
        // Only the schema generated and created by this fixture can be removed.
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
        else admin.execute("SHUTDOWN");
    }
}
