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

package com.nageoffer.ai.ragent.rag.trace;

import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatTraceRunnerTest {

    private static final String TASK_ID = "task-1";

    @Test
    void closesRunCreatedAfterEarlyCancellationAndSkipsBusinessLogic() {
        RagTraceProperties traceProperties = new RagTraceProperties();
        RagTraceRecordService traceRecordService = mock(RagTraceRecordService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(TASK_ID)).thenReturn(true);
        StreamChatTraceRunner runner = new StreamChatTraceRunner(
                traceProperties,
                traceRecordService,
                taskManager
        );
        AtomicBoolean businessExecuted = new AtomicBoolean(false);

        runner.run(
                "问题",
                "conversation-1",
                TASK_ID,
                mock(StreamCallback.class),
                ignored -> businessExecuted.set(true)
        );

        ArgumentCaptor<RagTraceRunDO> runCaptor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(traceRecordService).startRun(runCaptor.capture());
        verify(traceRecordService).finishRun(
                eq(runCaptor.getValue().getTraceId()),
                eq("CANCELLED"),
                isNull(),
                any(Date.class),
                anyLong()
        );
        assertFalse(businessExecuted.get());
    }

    @Test
    void recordsCancelledWhenSuccessfulCallbackArrivesAfterUserStop() {
        RagTraceProperties traceProperties = new RagTraceProperties();
        RagTraceRecordService traceRecordService = mock(RagTraceRecordService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(TASK_ID)).thenReturn(false, true);
        StreamChatTraceRunner runner = new StreamChatTraceRunner(
                traceProperties,
                traceRecordService,
                taskManager
        );

        runner.run(
                "问题",
                "conversation-1",
                TASK_ID,
                mock(StreamCallback.class),
                StreamCallback::onComplete
        );

        ArgumentCaptor<RagTraceRunDO> runCaptor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(traceRecordService).startRun(runCaptor.capture());
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(traceRecordService).finishRun(
                eq(runCaptor.getValue().getTraceId()),
                statusCaptor.capture(),
                isNull(),
                any(Date.class),
                anyLong()
        );
        assertEquals("CANCELLED", statusCaptor.getValue());
    }
}
