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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class TaskAgentModels {
    private TaskAgentModels() { }

    public enum Status { READY, RUNNING, WAITING_INPUT, WAITING_APPROVAL, COMPLETED, CANCELLED, FAILED }

    public record StartRequest(@NotBlank @Size(max = 2000) String goal,
                               @NotBlank @Size(max = 64) String documentId,
                               @NotBlank @Size(max = 64) String sampleId) { }
    public record ReplyRequest(@NotBlank @Size(max = 2000) String message) { }
    public record ApprovalRequest(long revision) { }
    public record SampleUpdate(boolean labelVerified, boolean handoffReady) { }

    public record Document(String id, String name, String version) { }
    public record Evidence(String id, String documentId, String documentVersion,
                           String sheetName, String cellRange, String text, String contentHash) { }
    public record Requirement(String text, List<String> evidenceIds) { }
    public record Proposal(String title, String stationId, List<Requirement> requirements) { }
    public record Decision(String tool, JsonNode arguments, String message) { }
    public record Observation(String tool, String message, JsonNode result) { }
    public record ToolSpec(String name, String description, String arguments) { }
    public record Outcome(Status status, String message, Object result) { }

    @Data
    public static class State {
        private String goal;
        private String sampleId;
        private Document document;
        private List<Evidence> evidence = new ArrayList<>();
        private List<Observation> observations = new ArrayList<>();
        private Proposal proposal;
        private String message;
        private int turns;
        private boolean sampleInspected;
        private boolean stationsListed;
    }

    public record Run(String id, String ownerUserId, Status status, long revision, State state,
                      String leaseToken, long leaseUntil, long createdAt, long updatedAt) { }
    public record Event(long sequence, String type, String message, JsonNode detail, long createdAt) { }
    public record Summary(String id, String goal, String sampleId, Status status, long updatedAt) { }
    public record View(String id, Status status, long revision, State state, List<Event> events,
                       Submission submission, long leaseUntil) { }
    public record Sample(String id, String name, String testType, boolean labelVerified,
                         boolean handoffReady, String status) { }
    public record Station(String id, String name, String testType, String status, String reservationRunId) { }
    public record Submission(String runId, String sampleId, String stationId, String documentId,
                             String documentVersion, long createdAt) { }
}
