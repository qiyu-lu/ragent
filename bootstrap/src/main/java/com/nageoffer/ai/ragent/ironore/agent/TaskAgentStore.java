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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

@Repository
public class TaskAgentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TaskAgentStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    public Run create(String owner, State state) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        return transaction(() -> {
            jdbc.update("INSERT INTO t_task_agent_run(id, owner_user_id, status, state_json, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    id, owner, Status.READY.name(), write(state), now, now);
            event(id, 0, "CREATED", "任务已建立", state.getGoal());
            return get(id, owner);
        });
    }

    public Run get(String id, String owner) {
        return read(id, owner, false);
    }

    public Run lock(String id, String owner) {
        return read(id, owner, true);
    }

    private Run read(String id, String owner, boolean lock) {
        List<Run> rows = jdbc.query("SELECT * FROM t_task_agent_run WHERE id=? AND owner_user_id=?"
                + (lock ? " FOR UPDATE" : ""), this::mapRun, id, owner);
        if (rows.isEmpty()) throw new ClientException("任务不存在或无权访问");
        return rows.get(0);
    }

    public List<Summary> list(String owner) {
        return jdbc.query("SELECT * FROM t_task_agent_run WHERE owner_user_id=? ORDER BY created_at DESC LIMIT 30",
                this::mapRun, owner).stream().map(run -> new Summary(run.id(), run.state().getGoal(),
                        run.state().getSampleId(), run.status(), run.updatedAt())).toList();
    }

    /** Claims only read/planning work. Mutating business tools run under a separate short transaction. */
    public Run claim(String id, String owner, int maxTurns, long leaseMillis) {
        return transaction(() -> {
            Run run = lock(id, owner);
            long now = System.currentTimeMillis();
            if (run.status() != Status.READY && !(run.status() == Status.RUNNING && run.leaseUntil() < now)) {
                return null;
            }
            if (run.state().getTurns() >= maxTurns) {
                save(run, Status.FAILED, "LIMIT", "已达到本任务的模型调用上限，请检查执行记录后新建任务", null);
                return null;
            }
            run.state().setTurns(run.state().getTurns() + 1);
            String token = UUID.randomUUID().toString();
            jdbc.update("UPDATE t_task_agent_run SET status=?, revision=revision+1, state_json=?, lease_token=?, lease_until=?, updated_at=? WHERE id=?",
                    Status.RUNNING.name(), write(run.state()), token, now + leaseMillis, now, id);
            event(id, run.revision() + 1, "STARTED", "正在选择并执行下一步", run.state().getTurns());
            return get(id, owner);
        });
    }

    public boolean ownsLease(Run claimed, Run current) {
        return current.status() == Status.RUNNING && claimed.leaseToken().equals(current.leaseToken());
    }

    /** Must be called inside transaction after lock; stale model results must pass ownsLease first. */
    public Run save(Run run, Status status, String eventType, String message, Object detail) {
        run.state().setMessage(message);
        jdbc.update("UPDATE t_task_agent_run SET status=?, revision=revision+1, state_json=?, lease_token=NULL, lease_until=0, updated_at=? WHERE id=? AND owner_user_id=?",
                status.name(), write(run.state()), System.currentTimeMillis(), run.id(), run.ownerUserId());
        event(run.id(), run.revision() + 1, eventType, message, detail);
        return get(run.id(), run.ownerUserId());
    }

    public List<Event> events(String id) {
        return jdbc.query("SELECT * FROM t_task_agent_event WHERE run_id=? ORDER BY sequence_no",
                (rs, n) -> new Event(rs.getLong("sequence_no"), rs.getString("event_type"),
                        rs.getString("message"), tree(rs.getString("detail_json")), rs.getLong("created_at")), id);
    }

    private void event(String id, long sequence, String type, String message, Object detail) {
        jdbc.update("INSERT INTO t_task_agent_event(run_id,sequence_no,event_type,message,detail_json,created_at) VALUES (?,?,?,?,?,?)",
                id, sequence, type, message, write(detail), System.currentTimeMillis());
    }

    private Run mapRun(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getString("id"), rs.getString("owner_user_id"), Status.valueOf(rs.getString("status")),
                rs.getLong("revision"), readState(rs.getString("state_json")), rs.getString("lease_token"),
                rs.getLong("lease_until"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    public String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务数据无法序列化", e); }
    }

    public JsonNode tree(Object value) { return json.valueToTree(value); }

    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务事件无法读取", e); }
    }

    private State readState(String value) {
        try { return json.readValue(value, State.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务状态无法读取", e); }
    }
}
