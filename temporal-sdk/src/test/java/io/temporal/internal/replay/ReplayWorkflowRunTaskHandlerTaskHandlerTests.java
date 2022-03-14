/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
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

import com.google.protobuf.util.Durations;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class ReplayWorkflowRunTaskHandlerTaskHandlerTests {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void ifStickyExecutionAttributesAreNotSetThenWorkflowsAreNotCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, new NoopScope());
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
  public void ifStickyExecutionAttributesAreSetThenWorkflowsAreCached() throws Throwable {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, new NoopScope());
    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            "sticky",
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

    when(mockFactory.getWorkflow(any())).thenReturn(mockWorkflow);
    when(mockWorkflow.eventLoop()).thenReturn(true);
    when(mockWorkflow.getOutput()).thenReturn(Optional.empty());
    return mockFactory;
  }
}
