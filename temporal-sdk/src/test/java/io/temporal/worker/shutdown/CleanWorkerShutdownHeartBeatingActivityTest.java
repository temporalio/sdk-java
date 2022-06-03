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
import static org.junit.Assert.fail;

import io.temporal.activity.Activity;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.NoArgsReturnsStringActivity;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class CleanWorkerShutdownHeartBeatingActivityTest {

  private static final String EXPECTED_RESULT = "Worker Shutdown";
  private static final CompletableFuture<Boolean> started = new CompletableFuture<>();
  private static final HeartBeatingActivitiesImpl activitiesImpl =
      new HeartBeatingActivitiesImpl(started);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  /**
   * Tests that Activity#heartbeat throws ActivityWorkerShutdownException after {@link
   * WorkerFactory#shutdown()} is closed.
   */
  @Test
  public void testShutdownHeartBeatingActivity() throws ExecutionException, InterruptedException {
    TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnString.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    started.get();
    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events = testWorkflowRule.getHistory(execution).getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DataConverter.getDefaultInstance()
                .fromPayloads(0, Optional.of(ar), String.class, String.class);
        assertEquals(EXPECTED_RESULT, r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnString {

    private final NoArgsReturnsStringActivity activities =
        Workflow.newActivityStub(
            NoArgsReturnsStringActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public String execute() {
      return activities.execute();
    }
  }

  public static class HeartBeatingActivitiesImpl implements NoArgsReturnsStringActivity {
    private final CompletableFuture<Boolean> started;

    public HeartBeatingActivitiesImpl(CompletableFuture<Boolean> started) {
      this.started = started;
    }

    @Override
    public String execute() {
      started.complete(true);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // we ignore interrupted exception here, because the main goal of
        // `testShutdownHeartBeatingActivity` test is to check that heartbeat returns a
        // ActivityWorkerShutdownException
      }
      try {
        Activity.getExecutionContext().heartbeat("foo");
      } catch (ActivityWorkerShutdownException e) {
        return EXPECTED_RESULT;
      }
      fail();
      return "completed";
    }
  }
}
