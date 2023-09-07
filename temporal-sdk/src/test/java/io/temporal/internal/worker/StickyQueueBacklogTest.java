/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.concurrent.Semaphore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.stubbing.OngoingStubbing;

@RunWith(Parameterized.class)
public class StickyQueueBacklogTest {
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String RUN_ID = "test-run-id";
  private static final String WORKFLOW_TYPE = "test-workflow-type";

  @Parameterized.Parameter public boolean throwOnPoll;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true}, {false}};
  }

  @Test
  public void stickyQueueBacklogResetTest() {
    // Verify the sticky queue backlog is reset on an empty response or failure
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    Semaphore executorSlotsSemaphore = new Semaphore(10);
    StickyQueueBalancer stickyQueueBalancer = new StickyQueueBalancer(2, true);

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    WorkflowPollTask poller =
        new WorkflowPollTask(
            client,
            "default",
            "taskqueue",
            "stickytaskqueue",
            "",
            "",
            false,
            executorSlotsSemaphore,
            stickyQueueBalancer,
            metricsScope,
            () -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            // Set a large backlog count
            .setBacklogCountHint(100)
            .build();

    OngoingStubbing<PollWorkflowTaskQueueResponse> pollMock =
        when(blockingStub.pollWorkflowTaskQueue(
                eq(
                    PollWorkflowTaskQueueRequest.newBuilder()
                        .setTaskQueue(
                            TaskQueue.newBuilder()
                                .setName("stickytaskqueue")
                                .setNormalName("taskqueue")
                                .setKind(TaskQueueKind.TASK_QUEUE_KIND_STICKY)
                                .build())
                        .setNamespace("default")
                        .build())))
            .thenReturn(pollResponse);
    if (throwOnPoll) {
      pollMock.thenThrow(new RuntimeException("Poll failed"));
    }
    pollMock.thenReturn(null);

    WorkflowTask task = poller.poll();
    // Expect a nonempty poll response, the sticky queue backlog should now be set
    assertNotNull(task);
    // On a null poll or failure the task queue the backlog should be reset
    if (throwOnPoll) {
      assertThrows(RuntimeException.class, () -> poller.poll());
    } else {
      assertNull(poller.poll());
    }
    assertEquals(TaskQueueKind.TASK_QUEUE_KIND_STICKY, stickyQueueBalancer.makePoll());
    // If the backlog was not reset this would be a sticky task
    assertEquals(TaskQueueKind.TASK_QUEUE_KIND_NORMAL, stickyQueueBalancer.makePoll());
  }
}
