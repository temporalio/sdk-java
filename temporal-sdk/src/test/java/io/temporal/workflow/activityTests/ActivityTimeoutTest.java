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
import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.*;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

/**
 * This class unites all tests regarding activity timeouts. It verifies that Test Server and Real
 * Temporal Server behave the same way in terms of activity timeout structure in different
 * scenarios.
 *
 * <p>This test will also check that local activity timeouts have the same exact structure where
 * applicable. so users can freely swap between local and regular activities without adopting
 * failures handling code.
 */
@RunWith(JUnitParamsRunner.class)
public class ActivityTimeoutTest {
  private TestWorkflowEnvironment testEnvironment;
  private static final String TASK_QUEUE = "test-activities";

  // TODO This test takes longer than it should to complete because
  //  of the cached heartbeat that prevents a quick shutdown
  public @Rule Timeout timeout = Timeout.seconds(15);

  @Before
  public void setUp() {
    testEnvironment =
        TestWorkflowEnvironment.newInstance(
            ExternalServiceTestConfigurator.configuredTestEnvironmentOptions().build());
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  @Parameters({"false"})
  public void testActivityStartToCloseTimeout(boolean local) {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);

    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(10, 10, 1, true, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
        ((TimeoutFailure) activityFailure.getCause()).getTimeoutType());

    assertNull(activityFailure.getCause().getCause());
  }

  // TODO Parametrize when scheduleToStart support is added for local activities
  @Parameters({"false"})
  @Test
  public void testActivityScheduleToStartTimeout(boolean local) {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);

    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(10, 1, 10, true, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();
    assertEquals(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START,
        ((TimeoutFailure) activityFailure.getCause()).getTimeoutType());

    assertNull(activityFailure.getCause().getCause());
  }

  @Test
  @Parameters({"false"})
  public void testActivityScheduleToCloseTimeout(boolean local) {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);

    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(2, 10, 1, false, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();
    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
        ((TimeoutFailure) activityFailure.getCause()).getTimeoutType());

    assertNull(activityFailure.getCause().getCause());
  }

  // Heartbeats are currently not applicable to local activities
  // Checks the behavior of heartbeat timeout activity failure in presence of schedule to close
  @Test
  public void testHeartbeatTimeoutDetails_scheduleToClose() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestHeartbeatTimeoutScheduleToClose.class);
    worker.registerActivitiesImplementations(new TestActivitiesImpl());
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        client.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class, options);
    String result = workflowStub.execute();
    Assert.assertEquals("heartbeatValue", result);
  }

  // Heartbeats are currently not applicable to local activities
  // Checks the behavior of heartbeat timeout activity failure in presence of start to close and
  // limited retries
  @Test
  public void testHeartbeatTimeoutDetails_startToClose() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestHeartbeatTimeoutStartToClose.class);
    worker.registerActivitiesImplementations(new TestActivitiesImpl());
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        client.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class, options);
    String result = workflowStub.execute();
    Assert.assertEquals("heartbeatValue", result);
  }

  @WorkflowInterface
  public interface TestActivityTimeoutWorkflow {
    @WorkflowMethod
    void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        boolean disableRetries,
        boolean local);
  }

  public static class TestActivityTimeoutWorkflowImpl implements TestActivityTimeoutWorkflow {

    @Override
    public void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        boolean disableRetries,
        boolean local) {
      TestActivities.TestActivity1 activity;
      if (local) {
        LocalActivityOptions.Builder options = LocalActivityOptions.newBuilder();
        if (scheduleToCloseTimeoutSeconds >= 0) {
          options.setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseTimeoutSeconds));
        }
        if (startToCloseTimeoutSeconds >= 0) {
          options.setStartToCloseTimeout(Duration.ofSeconds(startToCloseTimeoutSeconds));
        }
        // TODO add scheduleToStart for local activities
        // .setScheduleToStartTimeout(Duration.ofSeconds(scheduleToStartTimeoutSeconds));
        if (disableRetries) {
          options.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build());
        }
        activity =
            Workflow.newLocalActivityStub(TestActivities.TestActivity1.class, options.build());
      } else {
        ActivityOptions.Builder options = ActivityOptions.newBuilder();
        if (scheduleToCloseTimeoutSeconds >= 0) {
          options.setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseTimeoutSeconds));
        }
        if (startToCloseTimeoutSeconds >= 0) {
          options.setStartToCloseTimeout(Duration.ofSeconds(startToCloseTimeoutSeconds));
        }
        if (scheduleToStartTimeoutSeconds >= 0) {
          options.setScheduleToStartTimeout(Duration.ofSeconds(scheduleToStartTimeoutSeconds));
        }
        if (disableRetries) {
          options.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build());
        }
        activity = Workflow.newActivityStub(TestActivities.TestActivity1.class, options.build());
      }

      activity.execute("foo");
    }
  }

  public static class TimingOutActivityImpl implements TestActivities.TestActivity1 {

    @Override
    public String execute(String input) {
      while (true) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    }
  }

  public static class TestHeartbeatTimeoutScheduleToClose
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options);

      // false for second argument means to heartbeat once to set details and then stop.
      ActivityFailure e =
          assertThrows(ActivityFailure.class, () -> activities.heartbeatAndWait(5000, false));
      assertEquals(RetryState.RETRY_STATE_TIMEOUT, e.getRetryState());

      assertTrue(e.getCause() instanceof TimeoutFailure);
      TimeoutFailure scheduleToCloseTimeout = (TimeoutFailure) e.getCause();
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToCloseTimeout.getTimeoutType());

      assertTrue(scheduleToCloseTimeout.getCause() instanceof TimeoutFailure);
      TimeoutFailure heartbeatTimeout = (TimeoutFailure) scheduleToCloseTimeout.getCause();

      assertEquals(TimeoutType.TIMEOUT_TYPE_HEARTBEAT, heartbeatTimeout.getTimeoutType());
      assertEquals(0, heartbeatTimeout.getLastHeartbeatDetails().getSize());
      return (scheduleToCloseTimeout.getLastHeartbeatDetails().get(String.class));
    }
  }

  public static class TestHeartbeatTimeoutStartToClose
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
              .setStartToCloseTimeout(Duration.ofSeconds(5))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options);

      // false for second argument means to heartbeat once to set details and then stop.
      ActivityFailure e =
          assertThrows(ActivityFailure.class, () -> activities.heartbeatAndWait(5000, false));
      assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, e.getRetryState());

      assertTrue(e.getCause() instanceof TimeoutFailure);
      TimeoutFailure startToCloseTimeout = (TimeoutFailure) e.getCause();
      assertEquals(TimeoutType.TIMEOUT_TYPE_HEARTBEAT, startToCloseTimeout.getTimeoutType());

      assertNull(startToCloseTimeout.getCause());

      return (startToCloseTimeout.getLastHeartbeatDetails().get(String.class));
    }
  }
}
