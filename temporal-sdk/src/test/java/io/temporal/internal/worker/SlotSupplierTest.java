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
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.stubbing.OngoingStubbing;

@RunWith(Parameterized.class)
public class SlotSupplierTest {
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String RUN_ID = "test-run-id";
  private static final String WORKFLOW_TYPE = "test-workflow-type";
  private static final String TASK_QUEUE = "test-task-queue";

  @Parameterized.Parameter public boolean throwOnPoll;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true}, {false}};
  }

  @Test
  public void supplierIsCalledAppropriately() throws InterruptedException {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    SlotSupplier<WorkflowSlotInfo> mockSupplier = mock(SlotSupplier.class);
    AtomicInteger usedSlotsWhenCalled = new AtomicInteger(-1);
    when(mockSupplier.reserveSlot(
            argThat(
                src -> {
                  usedSlotsWhenCalled.set(src.getUsedSlots().size());
                  return true;
                })))
        .thenReturn(new SlotPermit());

    StickyQueueBalancer stickyQueueBalancer = new StickyQueueBalancer(5, true);
    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    TrackingSlotSupplier<WorkflowSlotInfo> trackingSS =
        new TrackingSlotSupplier<>(mockSupplier, metricsScope);

    WorkflowPollTask poller =
        new WorkflowPollTask(
            client,
            "default",
            TASK_QUEUE,
            "stickytaskqueue",
            "",
            "",
            false,
            trackingSS,
            stickyQueueBalancer,
            metricsScope,
            () -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    OngoingStubbing<PollWorkflowTaskQueueResponse> pollMock =
        when(blockingStub.pollWorkflowTaskQueue(any()));
    if (throwOnPoll) {
      pollMock.thenThrow(new RuntimeException("Poll failed"));
    } else {
      pollMock.thenReturn(pollResponse);
    }

    if (throwOnPoll) {
      assertThrows(RuntimeException.class, () -> poller.poll());
      verify(mockSupplier, times(1)).reserveSlot(any());
      verify(mockSupplier, times(1)).releaseSlot(any());
      assertEquals(0, trackingSS.getUsedSlots().size());
    } else {
      WorkflowTask task = poller.poll();
      assertNotNull(task);
      // We can't test this in the verifier, since it will get an up-to-date reference to the map
      // where the slot *is* used.
      assertEquals(0, usedSlotsWhenCalled.get());
      verify(mockSupplier, times(1))
          .reserveSlot(argThat(arg -> Objects.equals(arg.getTaskQueue(), TASK_QUEUE)));
      verify(mockSupplier, times(0)).releaseSlot(any());
      assertEquals(1, trackingSS.getUsedSlots().size());
    }
  }
}
