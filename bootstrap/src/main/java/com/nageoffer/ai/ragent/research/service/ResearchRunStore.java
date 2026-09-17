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

package com.nageoffer.ai.ragent.research.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.research.model.ResearchBrief;
import com.nageoffer.ai.ragent.research.model.ResearchEvent;
import com.nageoffer.ai.ragent.research.model.ResearchRun;
import com.nageoffer.ai.ragent.research.model.ResearchRun.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 领取、状态变更、事件分配各自使用短事务；模型和工具在事务外执行。 */
@Repository
public class ResearchRunStore {
    public record Claim(ResearchRun run, String owner, String leaseToken) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ResearchRunStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public Optional<ResearchRun> findRequest(String owner, String requestId) {
        identity(owner);
        return jdbc.query("SELECT * FROM t_research_run WHERE owner_user_id = ? AND client_request_id = ?",
                (rs, row) -> read(rs), owner, requestId).stream().findFirst();
    }

    public ResearchRun create(String owner, String conversationId, String requestId, ResearchBrief brief) {
        identity(owner);
        String requestHash = SecureUtil.sha256(conversationId + "\n" + encode(brief));
        return transactions.execute(tx -> {
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.update("""
                    INSERT INTO t_research_run (id, owner_user_id, conversation_id, client_request_id, output_type, brief, state)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    ON CONFLICT (owner_user_id, client_request_id) DO NOTHING
                    """, id, owner, conversationId, requestId, brief.outputType().name(), encode(brief),
                    encode(Map.of("requestHash", requestHash)));
            ResearchRun run = findRequest(owner, requestId).orElseThrow();
            if (!requestHash.equals(run.state().get("requestHash"))) {
                throw new ClientException("同一 clientRequestId 不能提交不同的研究请求");
            }
            if (inserted == 1) appendLocked(run.id(), "main", "QUEUED", "研究任务已排队", Map.of());
            return get(run.id(), owner);
        });
    }

    public ResearchRun get(String id, String owner) {
        identity(owner);
        return rows(id, owner, false).stream().findFirst()
                .orElseThrow(() -> new ClientException("研究任务不存在或无权访问"));
    }

    public Optional<Claim> claim(String id, String owner, Duration leaseDuration) {
        return transactions.execute(tx -> {
            ResearchRun run = lock(id, owner);
            if (run.status() != Status.QUEUED && run.status() != Status.RUNNING) return Optional.empty();
            if (run.status() == Status.RUNNING && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT lease_until > CURRENT_TIMESTAMP FROM t_research_run WHERE id = ?", Boolean.class, id))) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE t_research_run SET status = 'RUNNING', epoch = epoch + 1, revision = revision + 1,
                        lease_token = ?, lease_until = ?, started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                        update_time = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?
                    """, token, Timestamp.from(Instant.now().plus(leaseDuration)), id, owner);
            appendLocked(id, "main", "RUN_STARTED", "研究执行已开始", Map.of("epoch", run.epoch() + 1));
            return Optional.of(new Claim(get(id, owner), owner, token));
        });
    }

    public boolean current(Claim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM t_research_run WHERE id = ? AND owner_user_id = ?
                    AND status = 'RUNNING' AND epoch = ? AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP)
                """, Boolean.class, claim.run().id(), claim.owner(), claim.run().epoch(), claim.leaseToken()));
    }

    public boolean event(Claim claim, String type, String summary, Map<String, Object> payload, Map<String, Object> usage) {
        return event(claim, "main", type, summary, payload, usage);
    }

    public boolean event(Claim claim, String taskId, String type, String summary, Map<String, Object> payload, Map<String, Object> usage) {
        return transactions.execute(tx -> {
            lock(claim.run().id(), claim.owner());
            if (!current(claim)) return false;
            if (usage != null) jdbc.update("UPDATE t_research_run SET usage = ?::jsonb WHERE id = ?",
                    encode(usage), claim.run().id());
            appendLocked(claim.run().id(), taskId, type, summary, payload);
            return true;
        });
    }

    /** 子任务快照和事件同一短事务提交；仅父领取仍有效时接收。 */
    public boolean subtask(Claim claim, String taskId, Map<String, Object> checkpoint,
                           String type, Map<String, Object> usage) {
        return transactions.execute(tx -> {
            lock(claim.run().id(), claim.owner());
            if (!current(claim)) return false;
            String previous = jdbc.queryForObject("SELECT state -> 'subtasks' -> ? ->> 'status' FROM t_research_run WHERE id = ?",
                    String.class, taskId, claim.run().id());
            if (previous != null && !previous.equals("QUEUED") && !previous.equals("RUNNING")) return false;
            jdbc.update("""
                    UPDATE t_research_run SET state = jsonb_set(state, '{subtasks}',
                        COALESCE(state -> 'subtasks', '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb)),
                        usage = ?::jsonb WHERE id = ?
                    """, taskId, encode(checkpoint), encode(usage), claim.run().id());
            appendLocked(claim.run().id(), taskId, type, "子任务状态已更新", checkpoint);
            return true;
        });
    }

    public boolean finish(Claim claim, Status status, Map<String, Object> state,
                           Map<String, Object> usage, String error) {
        if (status != Status.WAITING_INPUT && !status.terminal()) throw new IllegalArgumentException("无效的研究结束状态");
        return transactions.execute(tx -> {
            ResearchRun run = lock(claim.run().id(), claim.owner());
            if (!current(claim)) return false;
            Map<String, Object> finishedState = new java.util.LinkedHashMap<>(state);
            finishedState.putAll(closeSubtasks(run, "INTERRUPTED"));
            jdbc.update("""
                    UPDATE t_research_run SET status = ?, state = state || ?::jsonb, usage = ?::jsonb,
                        error_summary = ?, revision = revision + 1, lease_token = NULL, lease_until = NULL,
                        completed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        update_time = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?
                    """, status.name(), encode(finishedState), encode(usage), error, status.terminal(),
                    claim.run().id(), claim.owner());
            appendLocked(claim.run().id(), "main", status.name(),
                    status == Status.WAITING_INPUT ? "研究等待补充条件" : "研究执行已结束",
                    Map.of("status", status.name()));
            return true;
        });
    }

    public ResearchRun input(String id, String owner, long revision, String answer) {
        if (answer == null || answer.isBlank() || answer.length() > 10000) throw new ClientException("补充条件无效");
        return transactions.execute(tx -> {
            ResearchRun run = lock(id, owner);
            if (run.status() != Status.WAITING_INPUT || run.revision() != revision) {
                throw new ClientException("任务状态或 revision 已变化，请刷新后再补充条件");
            }
            List<String> constraints = new ArrayList<>(run.brief().constraints());
            if (constraints.size() >= 20) throw new ClientException("研究补充次数已达到上限");
            constraints.add(answer);
            ResearchBrief brief = new ResearchBrief(run.brief().goal(), run.brief().outputType(), constraints,
                    run.brief().allowedKbIds(), run.brief().allowedDocIds());
            Map<String, Object> inputState = Map.of("latestUserInput", Map.of("source", "user_input",
                    "question", run.state().getOrDefault("question", ""), "answer", answer));
            // 输入接口只接受用户条件；知识库和文档范围在这一阶段保持创建时的服务端范围。
            jdbc.update("""
                    UPDATE t_research_run SET brief = ?::jsonb, status = 'QUEUED', revision = revision + 1,
                        state = (state - 'question' - 'researchResult') || ?::jsonb,
                        error_summary = NULL, update_time = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_user_id = ?
                    """, encode(brief), encode(inputState), id, owner);
            appendLocked(id, "main", "INPUT_RECEIVED", "已保存用户补充条件", Map.of("input", answer, "source", "user_input"));
            return get(id, owner);
        });
    }

    public ResearchRun cancel(String id, String owner) {
        return transactions.execute(tx -> {
            ResearchRun run = lock(id, owner);
            if (run.status().terminal()) return run;
            jdbc.update("""
                    UPDATE t_research_run SET status = 'CANCELLED', epoch = epoch + 1, revision = revision + 1,
                        lease_token = NULL, lease_until = NULL, completed_at = CURRENT_TIMESTAMP,
                        state = state || ?::jsonb, update_time = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?
                    """, encode(closeSubtasks(run, "CANCELLED")), id, owner);
            appendLocked(id, "main", "CANCEL_REQUESTED", "已请求取消研究及模型连接",
                    Map.of("remoteComputationStopped", "unknown"));
            return get(id, owner);
        });
    }

    /** 只允许补记本地取消回执和 usage，不能写状态或研究结果。 */
    public void cancelledLocally(Claim claim, Map<String, Object> usage) {
        transactions.executeWithoutResult(tx -> {
            ResearchRun run = lock(claim.run().id(), claim.owner());
            if (run.status() != Status.CANCELLED || run.epoch() != claim.run().epoch() + 1
                    || Boolean.TRUE.equals(run.state().get("localExecutionEnded"))) return;
            jdbc.update("UPDATE t_research_run SET usage = ?::jsonb, state = state || '{\"localExecutionEnded\":true}'::jsonb WHERE id = ?",
                    encode(usage), run.id());
            appendLocked(run.id(), "main", "LOCAL_EXECUTION_ENDED", "本地执行和请求订阅已结束",
                    Map.of("remoteComputationStopped", "unknown"));
        });
    }

    public List<ResearchEvent> events(String id, String owner, long after, int limit) {
        get(id, owner);
        if (after < 0 || limit < 1 || limit > 500) throw new ClientException("事件分页参数无效");
        return jdbc.query("""
                SELECT e.* FROM t_research_event e JOIN t_research_run r ON r.id = e.run_id
                WHERE e.run_id = ? AND r.owner_user_id = ? AND e.sequence_no > ? ORDER BY sequence_no LIMIT ?
                """, (rs, row) -> new ResearchEvent(rs.getLong("sequence_no"), rs.getString("task_id"),
                rs.getString("event_type"), rs.getString("summary"), decode(rs.getString("payload")),
                rs.getTimestamp("create_time").toInstant()), id, owner, after, limit);
    }

    /** 单 JVM 运行器重启时不恢复模型中间状态；保留已存证据、摘要和调用记录。 */
    public void interruptOrphans() {
        List<Map<String, Object>> lost = jdbc.queryForList(
                "SELECT id, owner_user_id FROM t_research_run WHERE status IN ('QUEUED', 'RUNNING')");
        for (Map<String, Object> row : lost) {
            transactions.executeWithoutResult(tx -> {
                ResearchRun run = lock((String) row.get("id"), (String) row.get("owner_user_id"));
                if (run.status() != Status.QUEUED && run.status() != Status.RUNNING) return;
                jdbc.update("""
                        UPDATE t_research_run SET status = 'INTERRUPTED', epoch = epoch + 1, revision = revision + 1,
                            lease_token = NULL, lease_until = NULL, error_summary = 'EXECUTOR_LOST',
                            completed_at = CURRENT_TIMESTAMP, state = state || ?::jsonb, update_time = CURRENT_TIMESTAMP WHERE id = ?
                        """, encode(closeSubtasks(run, "INTERRUPTED")), run.id());
                appendLocked(run.id(), "main", "INTERRUPTED", "执行者已退出，可重新发起研究", Map.of());
            });
        }
    }

    private Map<String, Object> closeSubtasks(ResearchRun run, String status) {
        Map<String, Object> closed = new java.util.LinkedHashMap<>();
        if (run.state().get("subtasks") instanceof Map<?, ?> tasks) tasks.forEach((id, value) -> {
            Map<String, Object> task = new java.util.LinkedHashMap<>((Map<String, Object>) value);
            if ("RUNNING".equals(task.get("status")) || "QUEUED".equals(task.get("status"))) task.put("status", status);
            closed.put(id.toString(), task);
        });
        return closed.isEmpty() ? Map.of() : Map.of("subtasks", closed);
    }

    public void rejectQueued(String id, String owner) {
        transactions.executeWithoutResult(tx -> {
            ResearchRun run = lock(id, owner);
            if (run.status() != Status.QUEUED) return;
            jdbc.update("UPDATE t_research_run SET status = 'FAILED', error_summary = 'RUN_QUEUE_FULL', revision = revision + 1, completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
            appendLocked(id, "main", "FAILED", "研究队列已满，请稍后重新发起", Map.of());
        });
    }

    private ResearchRun lock(String id, String owner) {
        identity(owner);
        return rows(id, owner, true).stream().findFirst()
                .orElseThrow(() -> new ClientException("研究任务不存在或无权访问"));
    }

    private List<ResearchRun> rows(String id, String owner, boolean locked) {
        return jdbc.query("SELECT * FROM t_research_run WHERE id = ? AND owner_user_id = ?" + (locked ? " FOR UPDATE" : ""),
                (rs, row) -> read(rs), id, owner);
    }

    private void appendLocked(String id, String taskId, String type, String summary, Map<String, Object> payload) {
        Long sequence = jdbc.queryForObject("UPDATE t_research_run SET event_sequence = event_sequence + 1 WHERE id = ? RETURNING event_sequence", Long.class, id);
        jdbc.update("INSERT INTO t_research_event (run_id, sequence_no, task_id, event_type, summary, payload) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                id, sequence, taskId, type, summary, encode(payload));
    }

    private ResearchRun read(ResultSet rs) throws SQLException {
        return new ResearchRun(rs.getString("id"), rs.getString("conversation_id"), rs.getString("client_request_id"),
                parse(rs.getString("brief"), ResearchBrief.class), Status.valueOf(rs.getString("status")),
                rs.getLong("revision"), rs.getLong("epoch"), decode(rs.getString("state")),
                rs.getString("artifact") == null ? null : decode(rs.getString("artifact")),
                decode(rs.getString("usage")), rs.getString("error_summary"), instant(rs, "started_at"), instant(rs, "completed_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void identity(String owner) {
        if (owner == null || owner.isBlank()) throw new ClientException("研究任务需要登录用户");
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("研究状态序列化失败", e); }
    }

    private <T> T parse(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("研究状态解析失败", e); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalStateException("研究状态解析失败", e); }
    }
}
