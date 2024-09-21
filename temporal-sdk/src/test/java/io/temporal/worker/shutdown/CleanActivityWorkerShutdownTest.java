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

package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class CleanActivityWorkerShutdownTest {

  private static final String COMPLETED = "Completed";
  private static final String INTERRUPTED = "Interrupted";
  private static final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private static final CountDownLatch shutdownNowLatch = new CountDownLatch(1);
  private static final ActivitiesImpl activitiesImpl =
      new ActivitiesImpl(shutdownLatch, shutdownNowLatch);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testShutdown() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    shutdownLatch.await();
    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                0, Optional.of(ar), String.class, String.class);
        assertEquals(COMPLETED, r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  @Test
  public void testShutdownNow() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "now");
    shutdownNowLatch.await();
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    events.forEach(System.out::println);
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                0, Optional.of(ar), String.class, String.class);
        assertEquals(INTERRUPTED, r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestActivity1 activities =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                .build());

    @Override
    public String execute(String now) {
      return activities.execute(now);
    }
  }

  public static class ActivitiesImpl implements TestActivity1 {

    private final CountDownLatch shutdownLatch;
    private final CountDownLatch shutdownNowLatch;

    public ActivitiesImpl(CountDownLatch shutdownLatch, CountDownLatch shutdownNowLatch) {
      this.shutdownLatch = shutdownLatch;
      this.shutdownNowLatch = shutdownNowLatch;
    }

    @Override
    public String execute(String now) {
      if (now == null) {
        shutdownLatch.countDown();
      } else {
        shutdownNowLatch.countDown();
      }
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        // We ignore the interrupted exception here to let the activity complete and return the
        // result. Otherwise, the result is not reported:
        // https://github.com/temporalio/sdk-java/issues/731
        return INTERRUPTED;
      }
      return COMPLETED;
    }
  }
}
