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

package io.temporal.testserver.functional;

import static io.temporal.internal.common.InternalUtils.createNormalTaskQueue;
import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.TestServiceStubs;
import io.temporal.serviceclient.TestServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.TestServiceUtils;
import io.temporal.testserver.TestServer;
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
  private final TaskQueue STICKY_QUEUE = createStickyTaskQueue(HOST_TASKQUEUE, TASK_QUEUE);

  private TestServer.InProcessTestServer testServer;
  private WorkflowServiceStubs workflowServiceStubs;
  private TestServiceStubs testServiceStubs;

  @Before
  public void setUp() {
    this.testServer = TestServer.createServer(true);
    this.workflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setChannel(testServer.getChannel())
                .validateAndBuildWithDefaults());
    this.testServiceStubs =
        TestServiceStubs.newServiceStubs(
            TestServiceStubsOptions.newBuilder()
                .setChannel(testServer.getChannel())
                .validateAndBuildWithDefaults());
  }

  @After
  public void tearDown() {
    this.testServiceStubs.shutdownNow();
    this.workflowServiceStubs.shutdownNow();
    this.testServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    this.workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    this.testServer.close();
  }

  @Test
  public void
      taskCompletionWithStickyExecutionAttributesWillScheduleWorkflowTasksOnStickyTaskQueue()
          throws Exception {

    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), STICKY_QUEUE, workflowServiceStubs);
    TestServiceUtils.signalWorkflow(
        response.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(NAMESPACE, STICKY_QUEUE, workflowServiceStubs);

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
    TestServiceUtils.startWorkflowExecution(
        NAMESPACE, TASK_QUEUE, WORKFLOW_TYPE, workflowServiceStubs);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), STICKY_QUEUE, workflowServiceStubs);
    TestServiceUtils.signalWorkflow(
        response.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(NAMESPACE, STICKY_QUEUE, workflowServiceStubs);
    TestServiceUtils.respondWorkflowTaskFailedWithSticky(
        response.getTaskToken(), workflowServiceStubs);
    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

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
        workflowServiceStubs);
    PollWorkflowTaskQueueResponse response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    TestServiceUtils.respondWorkflowTaskCompletedWithSticky(
        response.getTaskToken(), STICKY_QUEUE, Duration.ofSeconds(1), workflowServiceStubs);
    TestServiceUtils.signalWorkflow(
        response.getWorkflowExecution(), NAMESPACE, workflowServiceStubs);
    TestServiceUtils.pollWorkflowTaskQueue(NAMESPACE, STICKY_QUEUE, workflowServiceStubs);

    testServiceStubs
        .blockingStub()
        .unlockTimeSkippingWithSleep(
            SleepRequest.newBuilder()
                .setDuration(ProtobufTimeUtils.toProtoDuration(Duration.ofMillis(1100)))
                .build());

    response =
        TestServiceUtils.pollWorkflowTaskQueue(
            NAMESPACE, createNormalTaskQueue(TASK_QUEUE), workflowServiceStubs);

    // Assert Full history
    // Make sure first is workflow execution started
    assertNotNull(response.getHistory().getEvents(0).getWorkflowExecutionStartedEventAttributes());
    // 10 is the expected number of events for the full history.
    assertEquals(
        "Expected 10 events, but got: " + new WorkflowExecutionHistory(response.getHistory()),
        10,
        response.getHistory().getEventsCount());
  }
}
