/*
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

package com.uber.cadence.internal.testing;

import static com.uber.cadence.internal.common.InternalUtils.createNormalTaskList;
import static com.uber.cadence.internal.common.InternalUtils.createStickyTaskList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.internal.testservice.TestWorkflowService;
import com.uber.cadence.testUtils.TestServiceUtils;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowStickynessTest {

  private final String DOMAIN = "domain";
  private final String TASK_LIST = "taskList";
  private final String HOST_TASKLIST = "stickyTaskList";
  private final String WORKFLOW_TYPE = "wfType";
  private final String CALLER = "WorkflowStickynessTest";

  private TestWorkflowService service;

  @Before
  public void setUp() {
    service = new TestWorkflowService();
    service.lockTimeSkipping(CALLER);
  }

  @After
  public void tearDown() {
    service.close();
  }

  @Test
  public void taskCompletionWithStickyExecutionAttributesWillScheduleDecisionsOnStickyTaskList()
      throws Exception {

    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.taskToken, HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.workflowExecution, DOMAIN, service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);

    assertEquals(4, response.history.getEventsSize());
    assertEquals(TASK_LIST, response.getWorkflowExecutionTaskList().getName());
    List<HistoryEvent> events = response.history.getEvents();
    assertEquals(EventType.DecisionTaskCompleted, events.get(0).eventType);
    assertEquals(EventType.WorkflowExecutionSignaled, events.get(1).eventType);
    assertEquals(EventType.DecisionTaskScheduled, events.get(2).eventType);
    assertEquals(EventType.DecisionTaskStarted, events.get(3).eventType);
  }

  @Test
  public void taskFailureWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.taskToken, HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.workflowExecution, DOMAIN, service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);
    TestServiceUtils.respondDecisionTaskFailedWithSticky(response.taskToken, service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.history.events.get(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.history.getEventsSize());
  }

  @Test
  public void taskTimeoutWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, 10, 2, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.taskToken, HOST_TASKLIST, 1, service);
    TestServiceUtils.signalWorkflow(response.workflowExecution, DOMAIN, service);
    TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);
    service.unlockTimeSkipping(CALLER);
    service.sleep(Duration.ofMillis(1100));

    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.history.events.get(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.history.getEventsSize());
  }
}
