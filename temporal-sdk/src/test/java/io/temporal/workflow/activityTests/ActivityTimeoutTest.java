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
import io.temporal.client.*;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.ControlledActivityImpl;
import io.temporal.workflow.shared.ControlledActivityImpl.Outcome;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  // TODO This test takes longer than it should to complete because
  //  of the cached heartbeat that prevents a quick shutdown
  public @Rule Timeout timeout = Timeout.seconds(15);

  /**
   * An activity reaches startToClose timeout once, max retries are set to 1. o
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE})
   *
   * <p>Note {@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE} of the last attempt is getting
   * effectively replaced in-place by {@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}, it doesn't
   * go into the cause chain.
   */
  @Test
  @Parameters({"false", "true"})
  public void maximumAttemptsReached_startToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 1, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(-1, -1, 1, 1, local));

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

    activity.verifyAttempts();
  }

  /**
   * Two startToClose timeouts of the activity limited by the max retries set to 2.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE})
   */
  @Test
  @Parameters({"false", "true"})
  public void maximumAttemptsReached_twiceStartToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 2, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(-1, -1, 1, 2, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());

    assertTrue(startToClose.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose2 = (TimeoutFailure) startToClose.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose2.getTimeoutType());

    assertNull(startToClose2.getCause());

    activity.verifyAttempts();
  }

  /**
   * Two startToClose timeouts of the activity limited by the max retries set to 3.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE})
   *
   * <p>This structure is the same as in {@link
   * #maximumAttemptsReached_twiceStartToCloseTimingOutActivity}, depth of failures under the root
   * ActivityFailure is limited by 2
   */
  @Test
  @Parameters({"false", "true"})
  public void maximumAttemptsReached_threeStartToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 3, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(-1, -1, 1, 3, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());

    assertTrue(startToClose.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose2 = (TimeoutFailure) startToClose.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose2.getTimeoutType());

    assertNull(startToClose2.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test hits a scenario when activity
   *
   * <ul>
   *   <li>fails on the first attempt
   *   <li>reaches startToClose on the second attempt
   *   <li>attempts are limited by 2
   * </ul>
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE}) -> <br>
   * {@link ApplicationFailure}
   */
  @Test
  @Parameters({"false", "true"})
  public void maximumAttemptsReached_failing_startToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Arrays.asList(Outcome.FAIL, Outcome.SLEEP), 2, 5);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    final int ATTEMPTS_COUNT = 2;
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(
            WorkflowException.class, () -> workflow.workflow(10, -1, 1, ATTEMPTS_COUNT, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());

    assertTrue(startToClose.getCause() instanceof ApplicationFailure);
    assertEquals(
        "intentional failure", ((ApplicationFailure) startToClose.getCause()).getOriginalMessage());

    assertNull(startToClose.getCause().getCause());

    activity.verifyAttempts();
  }

  /**
   * This test covers a scenario with a simple scheduleToStart timeout
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_START})
   *
   * <p>Note {@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE} of the last attempt is getting
   * effectively replaced in-place by {@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}, it doesn't
   * go into the cause chain.
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToStartTimeout(boolean local) throws InterruptedException {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 2, 100);

    String taskQueue = "ActivityTimeoutTest-scheduleToStartTimeout-" + UUID.randomUUID();

    Worker worker =
        testWorkflowRule
            .getTestEnvironment()
            .newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setMaxConcurrentActivityExecutionSize(1)
                    .setMaxConcurrentLocalActivityExecutionSize(1)
                    .build());
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getTestEnvironment().getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();

    TestActivityTimeoutWorkflow throwawayWorkflowThatOccupiesActivityWorker =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);
    WorkflowStub.fromTyped(throwawayWorkflowThatOccupiesActivityWorker).start(10, -1, 10, 1, local);
    // allow workflow and an activity to start executing
    while (activity.getLastAttempt() < 1) {
      Thread.sleep(100);
    }

    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(10, 1, 10, 1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));
    if (ExternalServiceTestConfigurator.isUseExternalService() && !local) {
      // https://github.com/temporalio/temporal/issues/3667
      assertEquals(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, activityFailure.getRetryState());
    } else {
      assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    }

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START,
        ((TimeoutFailure) activityFailure.getCause()).getTimeoutType());

    assertNull(activityFailure.getCause().getCause());
  }

  /**
   * This test hits a scenario when an activity reaches startToClose timeout on the first attempt
   * and reaches scheduleToClose timeout after that.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE})
   *
   * <p>Note {@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE} of the last attempt is getting
   * effectively replaced in-place by {@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}, it doesn't
   * go into the cause chain.
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_startToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 1, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(2, -1, 1, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));
    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
        ((TimeoutFailure) activityFailure.getCause()).getTimeoutType());

    assertNull(activityFailure.getCause().getCause());

    activity.verifyAttempts();
  }

  /**
   * This test hits a scenario when an activity reaches startToClose timeout twice and reaches
   * scheduleToClose timeout after that.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE})
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_twiceStartToCloseTimingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 2, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(4, -1, 1, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));
    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());

    assertTrue(scheduleToClose.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) scheduleToClose.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());

    assertNull(startToClose.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test hits a scenario when an activity:
   *
   * <ul>
   *   <li>fails on the first attempt
   *   <li>reaches startToClose on the second attempt
   *   <li>reaches scheduleToClose on the third attempt
   * </ul>
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_START_TO_CLOSE}) <br>
   * an original first failure is not preserved in the chain
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_failing_twiceStartToCloseTimingOut(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Arrays.asList(Outcome.FAIL, Outcome.SLEEP, Outcome.SLEEP), 3, 5);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(10, -1, 3, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());

    assertTrue(scheduleToClose.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) scheduleToClose.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());

    assertNull(startToClose.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test hits a scenario when an activity
   *
   * <ul>
   *   <li>reaches startToClose on the first attempt
   *   <li>fails on the second attempt
   *   <li>reaches startToClose and scheduleToClose on the third attempt
   * </ul>
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}) -> <br>
   * {@link ApplicationFailure}[from the second attempt]
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_startToClose_failing_startToCloseTimingOut(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Arrays.asList(Outcome.SLEEP, Outcome.FAIL, Outcome.SLEEP), 3, 5);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(10, -1, 3, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());

    assertTrue(scheduleToClose.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) scheduleToClose.getCause();
    assertEquals("intentional failure", applicationFailure.getOriginalMessage());

    assertNull(applicationFailure.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test verifies the behavior and observed result of a present scheduleToClose timeout ond an
   * activity that retries and fails immediately every time.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link ApplicationFailure}
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_failingActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.FAIL), 3, -1);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(5, -1, 1, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task failed"));

    assertTrue(activityFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) activityFailure.getCause();
    assertEquals("intentional failure", applicationFailure.getOriginalMessage());

    assertNull(applicationFailure.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test verifies the behavior and observed result of a present scheduleToClose timeout ond an
   * activity that doesn't fit into scheduleToClose on the first attempt.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE})
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_timingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Collections.singletonList(Outcome.SLEEP), 1, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(5, -1, -1, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    if (ExternalServiceTestConfigurator.isUseExternalService() && !local) {
      // https://github.com/temporalio/temporal/issues/3667
      assertEquals(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, activityFailure.getRetryState());
    } else {
      assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    }

    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());

    assertNull(scheduleToClose.getCause());

    activity.verifyAttempts();
  }

  /**
   * This test hits a scenario when an activity
   *
   * <ul>
   *   <li>fails on the first attempt
   *   <li>reaches scheduleToClose on the second attempt
   * </ul>
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}) -> <br>
   * {@link ApplicationFailure}
   */
  @Test
  @Parameters({"false", "true"})
  public void scheduleToCloseTimeout_failing_timingOutActivity(boolean local) {
    ControlledActivityImpl activity =
        new ControlledActivityImpl(Arrays.asList(Outcome.FAIL, Outcome.SLEEP), 2, 100);

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(activity);
    testWorkflowRule.getTestEnvironment().start();

    TestActivityTimeoutWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> workflow.workflow(5, -1, -1, -1, local));

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    if (ExternalServiceTestConfigurator.isUseExternalService() && !local) {
      // https://github.com/temporalio/temporal/issues/3667
      assertEquals(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, activityFailure.getRetryState());
    } else {
      assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
    }

    MatcherAssert.assertThat(
        activityFailure.getMessage(), CoreMatchers.containsString("Activity task timed out"));

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());

    ApplicationFailure applicationFailure = (ApplicationFailure) scheduleToClose.getCause();
    assertEquals("intentional failure", applicationFailure.getOriginalMessage());

    assertNull(applicationFailure.getCause());

    activity.verifyAttempts();
  }

  /**
   * Checks the behavior of heartbeat timeout activity failure in presence of scheduleToClose
   * timeout.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_TIMEOUT}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_SCHEDULE_TO_CLOSE}, [last heartbeat
   * details]) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_HEARTBEAT}, [no heartbeat details])
   *
   * <p>Heartbeats are currently not applicable to local activities.
   */
  @Test
  public void scheduleToClose_heartbeatTimeoutDetails() {
    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestHeartbeatTimeoutScheduleToClose.class);
    worker.registerActivitiesImplementations(new TestActivitiesImpl());
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    WorkflowException e = assertThrows(WorkflowException.class, workflowStub::execute);

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());
    assertEquals("heartbeatValue", scheduleToClose.getLastHeartbeatDetails().get(String.class));

    assertTrue(scheduleToClose.getCause() instanceof TimeoutFailure);
    TimeoutFailure heartbeat = (TimeoutFailure) scheduleToClose.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_HEARTBEAT, heartbeat.getTimeoutType());
    assertEquals(0, heartbeat.getLastHeartbeatDetails().getSize());

    assertNull(heartbeat.getCause());
  }

  /**
   * Checks the behavior of an activity heartbeating past its schedule to close timeout.
   *
   * <ul>
   *   <li>The correct timeout exception is thrown into workflow code, and fails the workflow
   *   <li>The heartbeat call in the activity throws with the expected exception
   * </ul>
   */
  @Test
  public void scheduleToCloseTimeout_onHeartbeat() throws Exception {
    TestActivitiesImpl testActivities = new TestActivitiesImpl();

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestScheduleToCloseTimeoutOnHeartbeat.class);
    worker.registerActivitiesImplementations(testActivities);
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    WorkflowException e = assertThrows(WorkflowException.class, workflowStub::execute);

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure scheduleToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, scheduleToClose.getTimeoutType());
    assertEquals("heartbeatValue", scheduleToClose.getLastHeartbeatDetails().get(String.class));

    assertNull(scheduleToClose.getCause());

    testActivities.activityAttemptCompleted.waitForSignal(10, TimeUnit.SECONDS);

    assertEquals(1, testActivities.completionExceptions.size());
    ActivityCompletionException exception = testActivities.completionExceptions.get(0);
    assertTrue(exception instanceof ActivityTaskTimedOutException);
    ActivityTaskTimedOutException activityTaskTimedOutException =
        (ActivityTaskTimedOutException) exception;

    assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, activityTaskTimedOutException.getTimeoutType());
    long millisPastDeadline =
        System.currentTimeMillis() - activityTaskTimedOutException.getTimeoutDeadline();
    assertTrue(millisPastDeadline > 0 && millisPastDeadline < 10000);
  }

  /**
   * Checks the behavior of an activity heartbeating past its start to close timeout.
   *
   * <ul>
   *   <li>The correct timeout exception is thrown into workflow code, and fails the workflow
   *   <li>The heartbeat call in the activity throws with the expected exception
   * </ul>
   */
  @Test
  public void startToCloseTimeout_onHeartbeat() throws Exception {
    TestActivitiesImpl testActivities = new TestActivitiesImpl();

    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestStartToCloseTimeoutOnHeartbeat.class);
    worker.registerActivitiesImplementations(testActivities);
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    WorkflowException e = assertThrows(WorkflowException.class, workflowStub::execute);

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToClose = (TimeoutFailure) activityFailure.getCause();
    assertEquals(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, startToClose.getTimeoutType());
    assertEquals("heartbeatValue", startToClose.getLastHeartbeatDetails().get(String.class));

    assertNull(startToClose.getCause());

    testActivities.activityAttemptCompleted.waitForSignal(10, TimeUnit.SECONDS);

    assertEquals(1, testActivities.completionExceptions.size());
    ActivityCompletionException exception = testActivities.completionExceptions.get(0);
    assertTrue(exception instanceof ActivityTaskTimedOutException);
    ActivityTaskTimedOutException activityTaskTimedOutException =
        (ActivityTaskTimedOutException) exception;

    assertEquals(
        TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, activityTaskTimedOutException.getTimeoutType());
    long millisPastDeadline =
        System.currentTimeMillis() - activityTaskTimedOutException.getTimeoutDeadline();
    assertTrue(millisPastDeadline > 0 && millisPastDeadline < 10000);
  }

  /**
   * Checks the behavior of heartbeat timeout activity failure in presence of limited retries.
   *
   * <p>The expected structure is <br>
   * {@link ActivityFailure}({@link RetryState#RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED}) -> <br>
   * {@link TimeoutFailure}({@link TimeoutType#TIMEOUT_TYPE_HEARTBEAT}, [last heartbeat details])
   *
   * <p>Heartbeats are currently not applicable to local activities.
   */
  @Test
  public void maxRetries_heartbeatTimeoutDetails() {
    Worker worker = testWorkflowRule.getWorker();
    worker.registerWorkflowImplementationTypes(TestHeartbeatTimeoutMaxAttempts.class);
    worker.registerActivitiesImplementations(new TestActivitiesImpl());
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    WorkflowException e = assertThrows(WorkflowException.class, workflowStub::execute);

    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();

    assertEquals(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, activityFailure.getRetryState());

    assertTrue(activityFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure startToCloseTimeout = (TimeoutFailure) activityFailure.getCause();
    // Start to close timeout never fires here, because the heartbeat timeout is shorter and the
    // activity doesn't heartbeat
    assertEquals(TimeoutType.TIMEOUT_TYPE_HEARTBEAT, startToCloseTimeout.getTimeoutType());
    assertEquals("heartbeatValue", startToCloseTimeout.getLastHeartbeatDetails().get(String.class));

    assertNull(startToCloseTimeout.getCause());
  }

  @WorkflowInterface
  public interface TestActivityTimeoutWorkflow {
    /**
     * @param scheduleToCloseTimeoutSeconds -1 means not set
     * @param scheduleToStartTimeoutSeconds -1 means not set
     * @param startToCloseTimeoutSeconds -1 means not set
     * @param attemptsAllowed how many attempts will be set in the activity RetryOptions, -1 means
     *     not set (unlimited)
     * @param local if the activity that workflow schedules should be local
     */
    @WorkflowMethod
    void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        int attemptsAllowed,
        boolean local);
  }

  public static class TestActivityTimeoutWorkflowImpl implements TestActivityTimeoutWorkflow {

    @Override
    public void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        int attemptsAllowed,
        boolean local) {
      TestActivities.NoArgsReturnsStringActivity activity;
      if (local) {
        LocalActivityOptions.Builder options = LocalActivityOptions.newBuilder();
        if (scheduleToCloseTimeoutSeconds >= 0) {
          options.setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseTimeoutSeconds));
        }
        if (startToCloseTimeoutSeconds >= 0) {
          options.setStartToCloseTimeout(Duration.ofSeconds(startToCloseTimeoutSeconds));
        }
        if (scheduleToStartTimeoutSeconds >= 0) {
          options.setScheduleToStartTimeout(Duration.ofSeconds(scheduleToStartTimeoutSeconds));
        }
        if (attemptsAllowed > 0) {
          options.setRetryOptions(
              RetryOptions.newBuilder().setMaximumAttempts(attemptsAllowed).build());
        }
        activity =
            Workflow.newLocalActivityStub(
                TestActivities.NoArgsReturnsStringActivity.class, options.build());
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
        if (attemptsAllowed > 0) {
          options.setRetryOptions(
              RetryOptions.newBuilder().setMaximumAttempts(attemptsAllowed).build());
        }
        activity =
            Workflow.newActivityStub(
                TestActivities.NoArgsReturnsStringActivity.class, options.build());
      }

      activity.execute();
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
      activities.heartbeatAndWait(5000, false);

      fail();
      return "unexpected completion";
    }
  }

  public static class TestStartToCloseTimeoutOnHeartbeat
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setHeartbeatTimeout(
                  Duration.ofSeconds(10)) // long heartbeat timeout to trigger throttling
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options);

      // true for second argument means to keep heartbeating
      activities.heartbeatAndWait(5000, true);

      fail();
      return "unexpected completion";
    }
  }

  public static class TestScheduleToCloseTimeoutOnHeartbeat
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setHeartbeatTimeout(
                  Duration.ofSeconds(10)) // long heartbeat timeout to trigger throttling
              .setScheduleToCloseTimeout(Duration.ofSeconds(1))
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options);

      // true for second argument means to keep heartbeating
      activities.heartbeatAndWait(5000, true);

      fail();
      return "unexpected completion";
    }
  }

  public static class TestHeartbeatTimeoutMaxAttempts
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout
              // never fires (heartbeat timeout is shorter), but needed for correct ActivityOptions
              .setStartToCloseTimeout(Duration.ofSeconds(5))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(TestActivities.VariousTestActivities.class, options);

      // false for second argument means to heartbeat once to set details and then stop.
      activities.heartbeatAndWait(5000, false);

      fail();
      return "unexpected completion";
    }
  }
}
