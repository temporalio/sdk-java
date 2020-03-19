/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Modifications copyright (C) 2020 Temporal Technologies, Inc.
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

import static io.temporal.internal.common.InternalUtils.createStickyTaskList;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.internal.worker.DecisionTaskHandler;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.proto.common.StickyExecutionAttributes;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import java.time.Duration;
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
            "domain",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            null,
            Duration.ofSeconds(5),
            service,
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
            "domain",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            "sticky",
            Duration.ofSeconds(5),
            service,
            null);

    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    // Act
    DecisionTaskHandler.Result result = taskHandler.handleDecisionTask(decisionTask);

    // Assert
    assertEquals(1, cache.size());
    assertNotNull(result.getTaskCompleted());
    StickyExecutionAttributes attributes = result.getTaskCompleted().getStickyAttributes();
    assertEquals("sticky", attributes.getWorkerTaskList().getName());
    assertEquals(5, attributes.getScheduleToStartTimeoutSeconds());
  }

  @Test
  public void ifCacheIsEvictedAndPartialHistoryIsReceivedThenTaskFailedIsReturned()
      throws Throwable {
    // Arrange
    DeciderCache cache = new DeciderCache(10, NoopScope.getInstance());
    StickyExecutionAttributes attributes =
        StickyExecutionAttributes.newBuilder()
            .setWorkerTaskList(createStickyTaskList("sticky"))
            .build();
    DecisionTaskHandler taskHandler =
        new ReplayDecisionTaskHandler(
            "domain",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            "sticky",
            Duration.ofSeconds(5),
            service,
            null);

    // Act
    DecisionTaskHandler.Result result =
        taskHandler.handleDecisionTask(HistoryUtils.generateDecisionTaskWithPartialHistory());

    // Assert
    assertEquals(0, cache.size());
    assertNull(result.getTaskCompleted());
    assertNotNull(result.getTaskFailed());
  }

  private ReplayWorkflowFactory setUpMockWorkflowFactory() throws Throwable {
    ReplayWorkflow mockWorkflow = mock(ReplayWorkflow.class);
    ReplayWorkflowFactory mockFactory = mock(ReplayWorkflowFactory.class);

    when(mockFactory.getWorkflow(any())).thenReturn(mockWorkflow);
    when(mockWorkflow.eventLoop()).thenReturn(true);
    return mockFactory;
  }
}
