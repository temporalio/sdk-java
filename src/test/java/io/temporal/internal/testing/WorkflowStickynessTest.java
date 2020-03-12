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

package io.temporal.internal.testing;

import static io.temporal.internal.common.InternalUtils.createNormalTaskList;
import static io.temporal.internal.common.InternalUtils.createStickyTaskList;
import static org.junit.Assert.*;

import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.proto.common.HistoryEvent;
import io.temporal.proto.enums.EventType;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import io.temporal.testUtils.TestServiceUtils;
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

  private GrpcWorkflowServiceFactory service;
  private TestWorkflowService testService;

  @Before
  public void setUp() {
    testService = new TestWorkflowService();
    testService.lockTimeSkipping(CALLER);
    service = testService.newClientStub();
  }

  @After
  public void tearDown() {
    service.close();
    testService.close();
  }

  @Test
  public void taskCompletionWithStickyExecutionAttributesWillScheduleDecisionsOnStickyTaskList()
      throws Exception {

    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), DOMAIN, service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);

    assertEquals(4, response.getHistory().getEventsCount());
    assertEquals(TASK_LIST, response.getWorkflowExecutionTaskList().getName());
    List<HistoryEvent> events = response.getHistory().getEventsList();
    assertEquals(EventType.EventTypeDecisionTaskCompleted, events.get(0).getEventType());
    assertEquals(EventType.EventTypeWorkflowExecutionSignaled, events.get(1).getEventType());
    assertEquals(EventType.EventTypeDecisionTaskScheduled, events.get(2).getEventType());
    assertEquals(EventType.EventTypeDecisionTaskStarted, events.get(3).getEventType());
  }

  @Test
  public void taskFailureWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), DOMAIN, service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);
    TestServiceUtils.respondDecisionTaskFailedWithSticky(response.getTaskToken(), service);
    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertTrue(response.getHistory().getEvents(0).hasWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }

  @Test
  public void taskTimeoutWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(DOMAIN, TASK_LIST, WORKFLOW_TYPE, 10, 2, service);
    PollForDecisionTaskResponse response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    TestServiceUtils.respondDecisionTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKLIST, 1, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), DOMAIN, service);
    TestServiceUtils.pollForDecisionTask(DOMAIN, createStickyTaskList(HOST_TASKLIST), service);
    testService.unlockTimeSkipping(CALLER);
    testService.sleep(Duration.ofMillis(1100));

    response =
        TestServiceUtils.pollForDecisionTask(DOMAIN, createNormalTaskList(TASK_LIST), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.getHistory().getEvents(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }
}
