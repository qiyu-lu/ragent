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

package com.nageoffer.ai.ragent.ironore.model;

public enum RobotMissionStatus {
    READY,
    DISPATCHED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    DISPATCH_FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    public boolean active() {
        return this == DISPATCHED || this == RUNNING;
    }

    public static RobotMissionStatus fromGateway(String value) {
        if (value == null || value.isBlank()) {
            return DISPATCHED;
        }
        try {
            return RobotMissionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return FAILED;
        }
    }
}
