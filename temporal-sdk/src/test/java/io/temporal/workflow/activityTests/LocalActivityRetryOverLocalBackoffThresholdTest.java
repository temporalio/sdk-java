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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.ControlledActivityImpl;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

public class LocalActivityRetryOverLocalBackoffThresholdTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test(timeout = 20_000)
  public void localActivityRetryOverTheThreshold() {
    Worker worker = testWorkflowRule.getWorker();
    ControlledActivityImpl controlledActivity =
        new ControlledActivityImpl(
            Collections.singletonList(ControlledActivityImpl.Outcome.FAIL), 7, -1);
    worker.registerActivitiesImplementations(controlledActivity);
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);

    WorkflowException e =
        assertThrows(
            WorkflowException.class, () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));
    assertTrue(e.getCause() instanceof ActivityFailure);

    controlledActivity.verifyAttempts();

    List<HistoryEvent> historyEvents =
        testWorkflowRule.getHistoryEvents(
            WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_TIMER_STARTED);
    // attempt retry periods will be
    assertEquals(
        "6 retry periods are expected: [1, 1.2, 1.44, 1.727, 2.073, 2.488] with 2 being larger than 2 (local retry threshold)",
        2,
        historyEvents.size());
    historyEvents.forEach(
        timerEvent ->
            assertEquals(
                2,
                timerEvent.getTimerStartedEventAttributes().getStartToFireTimeout().getSeconds()));
  }

  @Test(timeout = 20_000)
  public void maxAttemptDecreasedOnRetryWakeUp() {
    Worker worker = testWorkflowRule.getWorker();
    ControlledActivityImpl controlledActivity =
        new ControlledActivityImpl(
            Arrays.asList(
                ControlledActivityImpl.Outcome.FAIL, ControlledActivityImpl.Outcome.COMPLETE),
            1,
            -1);
    worker.registerActivitiesImplementations(controlledActivity);
    worker.registerWorkflowImplementationTypes(WorkflowReducingAttemptsImpl.class);
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowStub untypedStub = WorkflowStub.fromTyped(workflowStub);
    WorkflowExecution execution = untypedStub.start(testWorkflowRule.getTaskQueue());

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    WorkflowException e =
        assertThrows(WorkflowException.class, () -> untypedStub.getResult(String.class));
    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();
    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());

    controlledActivity.verifyAttempts();
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setLocalRetryThreshold(Duration.ofSeconds(2))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(1.2)
                      .setMaximumAttempts(7)
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      TestActivities.TestActivity1 activities =
          Workflow.newLocalActivityStub(TestActivities.TestActivity1.class, options);
      activities.execute(taskQueue);

      return "ignored";
    }
  }

  public static class WorkflowReducingAttemptsImpl implements TestWorkflows.TestWorkflow1 {
    private static final AtomicInteger maxAttempts = new AtomicInteger(2);

    @Override
    public String execute(String taskQueue) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setLocalRetryThreshold(Duration.ofSeconds(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(maxAttempts.getAndDecrement())
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      TestActivities.TestActivity1 activities =
          Workflow.newLocalActivityStub(TestActivities.TestActivity1.class, options);
      activities.execute(taskQueue);

      return "ignored";
    }
  }
}
