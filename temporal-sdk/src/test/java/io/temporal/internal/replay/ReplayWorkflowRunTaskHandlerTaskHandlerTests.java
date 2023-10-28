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

package io.temporal.internal.replay;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class ReplayWorkflowRunTaskHandlerTaskHandlerTests {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void ifStickyExecutionAttributesAreNotSetThenWorkflowsAreNotCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            testWorkflowRule.getWorkflowServiceStubs(),
            null);

    // Act
    WorkflowTaskHandler.Result result =
        taskHandler.handleWorkflowTask(HistoryUtils.generateWorkflowTaskWithInitialHistory());

    // Assert
    assertEquals(0, cache.size());
    assertNotNull(result.getTaskCompleted());
    assertFalse(result.getTaskCompleted().hasStickyAttributes());
  }

  @Test
  public void workflowTaskFailOnIncompleteHistory() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    // Simulate a stale history node sending a workflow task with an incomplete history
    List<HistoryEvent> history =
        HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory().getEventsList();
    assertEquals(3, history.size());
    assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, history.get(history.size() - 1).getEventType());
    history = history.subList(0, history.size() - 1);
    when(blockingStub.getWorkflowExecutionHistory(any()))
        .thenReturn(
            GetWorkflowExecutionHistoryResponse.newBuilder()
                .setHistory(History.newBuilder().addAllEvents(history).build())
                .build());

    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            client,
            null);

    // Send a poll with a partial history and no cached execution so the SDK will request a full
    // history
    WorkflowTaskHandler.Result result =
        taskHandler.handleWorkflowTask(
            HistoryUtils.generateWorkflowTaskWithInitialHistory().toBuilder()
                .setHistory(History.newBuilder().build())
                .setNextPageToken(ByteString.EMPTY)
                .build());

    // Assert
    assertEquals(0, cache.size());
    assertNotNull(result.getTaskFailed());
    assertTrue(result.getTaskFailed().hasFailure());
    assertEquals(
        "Premature end of stream, expectedLastEventID=3 but no more events after eventID=2",
        result.getTaskFailed().getFailure().getMessage());
  }

  @Test
  public void ifStickyExecutionAttributesAreSetThenWorkflowsAreCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache =
        new WorkflowExecutorCache(10, new WorkflowRunLockManager(), new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            InternalUtils.createStickyTaskQueue("sticky", "taskQueue"),
            Duration.ofSeconds(5),
            testWorkflowRule.getWorkflowServiceStubs(),
            null);

    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    WorkflowTaskHandler.Result result = taskHandler.handleWorkflowTask(workflowTask);

    assertTrue(result.isCompletionCommand());
    assertEquals(0, cache.size()); // do not cache if completion command
    assertNotNull(result.getTaskCompleted());
    StickyExecutionAttributes attributes = result.getTaskCompleted().getStickyAttributes();
    assertEquals("sticky", attributes.getWorkerTaskQueue().getName());
    assertEquals(Durations.fromSeconds(5), attributes.getScheduleToStartTimeout());
  }

  private ReplayWorkflowFactory setUpMockWorkflowFactory() throws Throwable {
    ReplayWorkflow mockWorkflow = mock(ReplayWorkflow.class);
    ReplayWorkflowFactory mockFactory = mock(ReplayWorkflowFactory.class);

    when(mockFactory.getWorkflow(any(), any())).thenReturn(mockWorkflow);
    when(mockWorkflow.eventLoop()).thenReturn(true);
    when(mockWorkflow.getOutput()).thenReturn(Optional.empty());
    return mockFactory;
  }
}
