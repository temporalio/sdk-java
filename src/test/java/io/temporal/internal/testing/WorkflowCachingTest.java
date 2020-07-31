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

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;
import static org.junit.Assert.*;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.TestServiceUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkflowCachingTest {

  private final String NAMESPACE = "namespace";
  private final String TASK_QUEUE = "taskQueue";
  private final String HOST_TASKQUEUE = "stickyTaskQueue";
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
  public void
      taskCompletionWithStickyExecutionAttributesWillScheduleWorkflowTasksOnStickyTaskQueue()
          throws Exception {

    TestServiceUtils.startWorkflowExecution(NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, service);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), service);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKQUEUE, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createStickyTaskQueue(HOST_TASKQUEUE), service);

    assertEquals(4, response.getHistory().getEventsCount());
    assertEquals(TASK_QUEUE, response.getWorkflowExecutionTaskQueue().getName());
    List<HistoryEvent> events = response.getHistory().getEventsList();
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED, events.get(0).getEventType());
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED, events.get(1).getEventType());
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED, events.get(2).getEventType());
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, events.get(3).getEventType());
  }

  @Test
  public void taskFailureWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, service);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), service);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKQUEUE, service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createStickyTaskQueue(HOST_TASKQUEUE), service);
    TestServiceUtils.respondWorkflowTaskFailedWithSticky(response.getTaskToken(), service);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertTrue(response.getHistory().getEvents(0).hasWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }

  @Test
  public void taskTimeoutWillRescheduleTheTaskOnTheGlobalList() throws Exception {
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE,
        TASK_QUEUE,
        WORKFLOW_TYPE,
        Duration.ofSeconds(10),
        Duration.ofSeconds(2),
        service);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), service);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), HOST_TASKQUEUE, Duration.ofSeconds(1), service);
    TestServiceUtils.signalWorkflow(response.getWorkflowExecution(), NAMESPACE, service);
    TestServiceUtils.pollWorkflowTaskQueue(
        NAMESPACE, createStickyTaskQueue(HOST_TASKQUEUE), service);
    testService.unlockTimeSkipping(CALLER);
    testService.sleep(Duration.ofMillis(1100));

    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), service);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.getHistory().getEvents(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(10, response.getHistory().getEventsCount());
  }
}
