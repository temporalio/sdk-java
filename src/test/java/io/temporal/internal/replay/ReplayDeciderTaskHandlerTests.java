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
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.internal.worker.DecisionTaskHandler;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplayDeciderTaskHandlerTests {

  private TestWorkflowService testService;
  private WorkflowServiceStubs service;

  @Before
  public void setUp() {
    testService = new TestWorkflowService(true);
    service = testService.newClientStub();
  }

  @After
  public void tearDown() {
    service.shutdownNow();
    try {
      service.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    testService.close();
  }

  @Test
  public void ifStickyExecutionAttributesAreNotSetThenWorkflowsAreNotCached() throws Throwable {
    // Arrange
    DeciderCache cache = new DeciderCache(10, NoopScope.getInstance());
    DecisionTaskHandler taskHandler =
        new ReplayDecisionTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            service,
            () -> false,
            null);

    // Act
    DecisionTaskHandler.Result result =
        taskHandler.handleDecisionTask(HistoryUtils.generateDecisionTaskWithInitialHistory());

    // Assert
    assertEquals(0, cache.size());
    assertNotNull(result.getTaskCompleted());
    assertFalse(result.getTaskCompleted().hasStickyAttributes());
  }

  @Test
  public void ifStickyExecutionAttributesAreSetThenWorkflowsAreCached() throws Throwable {
    // Arrange
    DeciderCache cache = new DeciderCache(10, NoopScope.getInstance());
    DecisionTaskHandler taskHandler =
        new ReplayDecisionTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            "sticky",
            Duration.ofSeconds(5),
            service,
            () -> false,
            null);

    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    // Act
    DecisionTaskHandler.Result result = taskHandler.handleDecisionTask(decisionTask);

    assertTrue(result.isFinalDecision());
    assertEquals(0, cache.size()); // do not cache if final decision
    assertNotNull(result.getTaskCompleted());
    StickyExecutionAttributes attributes = result.getTaskCompleted().getStickyAttributes();
    assertEquals("sticky", attributes.getWorkerTaskQueue().getName());
    assertEquals(5, attributes.getScheduleToStartTimeoutSeconds());
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
