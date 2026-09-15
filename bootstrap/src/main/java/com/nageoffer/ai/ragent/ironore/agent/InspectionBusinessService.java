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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.nageoffer.ai.ragent.ironore.agent.TaskAgentModels.*;

/** Local sample submission business. All records are scoped to the logged-in operator. */
@Service
@RequiredArgsConstructor
public class InspectionBusinessService {
    private final JdbcTemplate jdbc;
    private final TaskAgentStore store;

    public List<Sample> samples(String owner) {
        return jdbc.query("SELECT * FROM t_task_agent_sample WHERE owner_user_id=? ORDER BY id", (rs, n) ->
                new Sample(rs.getString("id"), rs.getString("name"), rs.getString("test_type"),
                        rs.getBoolean("label_verified"), rs.getBoolean("handoff_ready"), rs.getString("status")), owner);
    }

    public Sample sample(String owner, String id) {
        return samples(owner).stream().filter(sample -> sample.id().equals(id)).findFirst()
                .orElseThrow(() -> new ClientException("样品不存在或无权访问"));
    }

    public List<Station> stations(String owner) {
        return jdbc.query("SELECT * FROM t_task_agent_station WHERE owner_user_id=? ORDER BY id", (rs, n) ->
                new Station(rs.getString("id"), rs.getString("name"), rs.getString("test_type"),
                        rs.getString("status"), rs.getString("reservation_run_id")), owner);
    }

    public void updateSample(String owner, String id, SampleUpdate update) {
        int changed = jdbc.update("UPDATE t_task_agent_sample SET label_verified=?,handoff_ready=? WHERE owner_user_id=? AND id=? AND status='REGISTERED'",
                update.labelVerified(), update.handoffReady(), owner, id);
        if (changed != 1) throw new ClientException("样品不存在或已经送检，无法修改资料状态");
    }

    public void validate(Run run, Proposal proposal) {
        Sample sample = sample(run.ownerUserId(), run.state().getSampleId());
        if (!"REGISTERED".equals(sample.status())) throw new ClientException("该样品已经办理送检");
        if (!sample.labelVerified() || !sample.handoffReady()) throw new ClientException("样品标签核对或交接资料尚未完成，请操作员补充");
        Station station = stations(run.ownerUserId()).stream().filter(s -> s.id().equals(proposal.stationId()))
                .findFirst().orElseThrow(() -> new ClientException("指定工位不存在"));
        if (!station.testType().equals(sample.testType())) throw new ClientException("工位不支持该样品的检测项目");
        if (!"AVAILABLE".equals(station.status()) || station.reservationRunId() != null) {
            throw new ClientException("工位当前不可预约，请重新查询工位");
        }
    }

    /** Caller holds the run lock and transaction; slot claim, submission and run completion commit together. */
    public Submission submit(Run run) {
        Submission existing = submission(run.id(), run.ownerUserId());
        if (existing != null) return existing;
        Proposal proposal = run.state().getProposal();
        validate(run, proposal);
        int claimed = jdbc.update("UPDATE t_task_agent_station SET reservation_run_id=? WHERE owner_user_id=? AND id=? AND status='AVAILABLE' AND reservation_run_id IS NULL",
                run.id(), run.ownerUserId(), proposal.stationId());
        if (claimed != 1) throw new ClientException("工位刚被其他任务预约，请重新选择");
        int sampleChanged = jdbc.update("UPDATE t_task_agent_sample SET status='SUBMITTED' WHERE owner_user_id=? AND id=? AND status='REGISTERED' AND label_verified=TRUE AND handoff_ready=TRUE",
                run.ownerUserId(), run.state().getSampleId());
        if (sampleChanged != 1) throw new ClientException("样品状态已改变，请重新查询");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO t_task_agent_submission(run_id,owner_user_id,sample_id,station_id,document_id,document_version,proposal_json,created_at) VALUES (?,?,?,?,?,?,?,?)",
                run.id(), run.ownerUserId(), run.state().getSampleId(), proposal.stationId(),
                run.state().getDocument().id(), run.state().getDocument().version(), store.write(proposal), now);
        return submission(run.id(), run.ownerUserId());
    }

    public Submission submission(String id, String owner) {
        List<Submission> results = jdbc.query("SELECT * FROM t_task_agent_submission WHERE run_id=? AND owner_user_id=?", (rs, n) ->
                new Submission(rs.getString("run_id"), rs.getString("sample_id"), rs.getString("station_id"),
                        rs.getString("document_id"), rs.getString("document_version"), rs.getLong("created_at")), id, owner);
        return results.isEmpty() ? null : results.get(0);
    }

    /** Explicit user action; repeated initialization preserves existing bookings and edits. */
    public void initializeDemo(String owner) {
        seed("INSERT INTO t_task_agent_sample(owner_user_id,id,name,test_type,label_verified,handoff_ready) VALUES (?,?,?,?,?,?)",
                owner, "sample-ready", "示例样品 A（资料齐全）", "MOISTURE", true, true);
        seed("INSERT INTO t_task_agent_sample(owner_user_id,id,name,test_type,label_verified,handoff_ready) VALUES (?,?,?,?,?,?)",
                owner, "sample-incomplete", "示例样品 B（待核对标签）", "MOISTURE", false, true);
        seed("INSERT INTO t_task_agent_station(owner_user_id,id,name,test_type,status) VALUES (?,?,?,?,?)",
                owner, "station-01", "水分检测工位 1", "MOISTURE", "AVAILABLE");
        seed("INSERT INTO t_task_agent_station(owner_user_id,id,name,test_type,status) VALUES (?,?,?,?,?)",
                owner, "station-02", "水分检测工位 2", "MOISTURE", "AVAILABLE");
        seed("INSERT INTO t_task_agent_station(owner_user_id,id,name,test_type,status) VALUES (?,?,?,?,?)",
                owner, "station-03", "化学检测工位", "CHEMICAL", "AVAILABLE");
    }

    private void seed(String sql, Object... args) {
        try { jdbc.update(sql, args); }
        catch (DuplicateKeyException existing) { /* Keep this user's existing business state. */ }
    }
}
