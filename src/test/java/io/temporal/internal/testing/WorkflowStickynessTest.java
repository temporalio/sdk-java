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

package io.temporal.internal.testing;

import static io.temporal.internal.common.InternalUtils.createNormalTaskList;
import static io.temporal.internal.common.InternalUtils.createStickyTaskList;
import static org.junit.Assert.*;

import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.TestServiceUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowStickynessTest {

  private final String NAMESPACE = "namespace";
  private final String TASK_LIST = "taskList";
  private final String HOST_TASKLIST = "stickyTaskList";
  private final String WORKFLOW_TYPE = "wfType";
  private final String CALLER = "WorkflowStickynessTest";

  private WorkflowServiceStubs service;
  private TestWorkflowService testService;

  @Before
  public void setUp() {
    testService = new TestWorkflowService();
    testService.lockTimeSkipping(CALLER);
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
  public void taskCompletionWithStickyExecutionAttributesWillScheduleDecisionsOnStickyTaskList()
      throws Exception {

    TestServiceUtils.startWorkflowExecution(NAMESPACE, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(NAMESPACE, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    response =
        TestServiceUtils.pollForDecisionTask(
            NAMESPACE, createStickyTaskList(HOST_TASKLIST), service);

    assertEquals(4, response.getHistory().getEventsCount());
    assertEquals(TASK_LIST, response.getWorkflowExecutionTaskList().getName());
    List<HistoryEvent> events = response.getHistory().getEventsList();
    assertEquals(EventType.DecisionTaskCompleted, events.get(0).getEventType());
    assertEquals(EventType.WorkflowExecutionSignaled, events.get(1).getEventType());
    assertEquals(EventType.DecisionTaskScheduled, events.get(2).getEventType());
    assertEquals(EventType.DecisionTaskStarted, events.get(3).getEventType());
  }

  @Test
  public void taskFailureWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(NAMESPACE, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(NAMESPACE, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    response =
        TestServiceUtils.pollForDecisionTask(
            NAMESPACE, createStickyTaskList(HOST_TASKLIST), service);
    TestServiceUtils.respondDecisionTaskFailedWithSticky(response.getTaskToken(), service);
    response =
        TestServiceUtils.pollForDecisionTask(NAMESPACE, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertTrue(response.getHistory().getEvents(0).hasWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }

  @Test
  public void taskTimeoutWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(NAMESPACE, TASK_LIST, WORKFLOW_TYPE, 10, 2, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(NAMESPACE, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, 1, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    TestServiceUtils.pollForDecisionTask(NAMESPACE, createStickyTaskList(HOST_TASKLIST), service);
    testService.unlockTimeSkipping(CALLER);
    testService.sleep(Duration.ofMillis(1100));

    response =
        TestServiceUtils.pollForDecisionTask(NAMESPACE, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.getHistory().getEvents(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }
}
