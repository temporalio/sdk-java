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

package io.temporal.testing;

import static org.junit.Assert.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TimeoutFailure;
import io.temporal.worker.Worker;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class TestWorkflowEnvironmentSleepTest {

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnv != null) {
            System.err.println(testEnv.getDiagnostics());
            testEnv.close();
          }
        }
      };

  @WorkflowInterface
  public interface ExampleWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void signal();
  }

  public static class HangingWorkflowWithSignalImpl implements ExampleWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofMinutes(20));
    }

    @Override
    public void signal() {}
  }

  private TestWorkflowEnvironment testEnv;
  private Worker worker;
  private WorkflowClient client;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    setUp(TestEnvironmentOptions.getDefaultInstance());
  }

  private void setUp(TestEnvironmentOptions options) {
    testEnv = TestWorkflowEnvironment.newInstance(options);
    worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(HangingWorkflowWithSignalImpl.class);
    worker.registerWorkflowImplementationTypes(ConfigurableSleepWorkflowImpl.class);
    testEnv.start();
  }

  @After
  public void tearDown() {
    testEnv.close();
  }

  @Test(timeout = 2000)
  public void testSignalAfterStartThenSleep() {
    ExampleWorkflow workflow =
        client.newWorkflowStub(
            ExampleWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    WorkflowClient.start(workflow::execute);
    workflow.signal();
    testEnv.sleep(Duration.ofMinutes(50L));
  }

  @Test
  public void testWorkflowTimeoutDuringSleep() {
    ExampleWorkflow workflow =
        client.newWorkflowStub(
            ExampleWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowExecutionTimeout(Duration.ofMinutes(3))
                .setTaskQueue(WORKFLOW_TASK_QUEUE)
                .build());

    WorkflowClient.start(workflow::execute);

    testEnv.sleep(Duration.ofMinutes(11L));

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    try {
      workflowStub.getResult(Void.class);
      fail("Workflow should fail with timeout exception");
    } catch (WorkflowFailedException e) {
      Throwable cause = e.getCause();
      assertTrue(cause instanceof TimeoutFailure);
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, ((TimeoutFailure) cause).getTimeoutType());
    }
  }

  /**
   * The test service skips ahead for timers, but (correctly) does not skip ahead for timeouts. We
   * used to have a bug that's best explained by example.
   *
   * <p>Start workflow A with an execution timeout of T. Start workflow B that sleeps for X, which
   * is after T. This will leave SelfAdvancingTimerImpl's internal task queue as follows:
   *
   * <pre>
   *   [@ now+T] workflow execution timeout, canceled = true
   *   [@ now+X] fire timer, canceled = false
   * </pre>
   *
   * <p>The test service will let real-time pass until T, then skip time to T+X. This blocks all
   * forward progress for however long X is.
   *
   * <p>If you're thinking "That's silly - the first task is canceled, it should obviously be
   * skipped!" then congratulations, you identified the bug and the fix!
   */
  @Test
  public void timeoutDoesNotBlockTimer() {
    // This is T from the example
    Duration workflowExecutionTimeout = Duration.ofMinutes(5);

    // This is X from the example.
    Duration sleepDuration = workflowExecutionTimeout.multipliedBy(2);

    // This test verifies time-skipping by waiting a small amount of real time for the workflows to
    // complete. In bug-land, they wouldn't complete on time.
    Duration howLongWeWaitForFutures = Duration.ofSeconds(5);

    WorkflowOptions workflowAOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOW_TASK_QUEUE)
            .setWorkflowExecutionTimeout(workflowExecutionTimeout)
            .build();

    WorkflowStub workflowAStub =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowAOptions);

    // workflowA completes immediately, even in bug-land
    workflowAStub.start(0);
    waitForWorkflow(workflowAStub, "A", howLongWeWaitForFutures);

    // Workflow B's execution timeout needs to be longer than its sleep.
    WorkflowOptions workflowBOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOW_TASK_QUEUE)
            .setWorkflowExecutionTimeout(sleepDuration.multipliedBy(2))
            .build();
    WorkflowStub workflowBStub =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowBOptions);

    // In bug land, workflow B wouldn't complete until workflowExecutionTimeout real seconds from
    // now (minus epsilon). Without the bug, it should complete immediately.
    workflowBStub.start(sleepDuration.toMillis());
    waitForWorkflow(workflowBStub, "B", howLongWeWaitForFutures);
  }

  @Test
  public void timeskippingWorksForBothTypesOfUntypedStubs() {
    WorkflowOptions workflowAOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOW_TASK_QUEUE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(30))
            .build();

    WorkflowStub stubA =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowAOptions);

    // The workflow sleeps for 10 minutes, which will take less than 10 seconds if timeskipping
    // works
    long durationToSleep = Duration.ofMinutes(10).toMillis();
    Duration durationToWait = Duration.ofSeconds(10);

    stubA.start(durationToSleep);
    waitForWorkflow(stubA, "newUntypedStubWithOptions", durationToWait);

    // Now use one stub to start the workflow and create another stub using its WorkflowExecution.
    // This simulates the scenario where someone stored a WorkflowExecution in their database,
    // looked it up, and wants to check status.
    WorkflowStub stubB =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowAOptions);
    WorkflowExecution executionB = stubB.start(durationToSleep);

    WorkflowStub stubBPrime = client.newUntypedWorkflowStub(executionB, Optional.empty());
    waitForWorkflow(stubBPrime, "newUntypedStubForWorkflowExecution", durationToWait);
  }

  private void waitForWorkflow(WorkflowStub workflowStub, String workflowName, Duration waitTime) {
    try {
      workflowStub.getResult(waitTime.toMillis(), TimeUnit.MILLISECONDS, Void.class);
    } catch (TimeoutException e) {
      // I haven't seen this happen (instead, the thing below happens), but it's a checked
      // exception, and it _means_ the same thing as below, so we treat it the same
      Assert.fail(
          String.format(
              "Workflow %s didn't return within %s, timeskipping must be broken",
              workflowName, waitTime));
    } catch (WorkflowServiceException e) {
      if (e.getCause() instanceof StatusRuntimeException) {
        if (((StatusRuntimeException) e.getCause()).getStatus().getCode()
            == Status.Code.DEADLINE_EXCEEDED) {
          Assert.fail(
              String.format(
                  "Workflow %s didn't return within %s, timeskipping must be broken",
                  workflowName, waitTime));
        }
      }

      throw e;
    }
  }

  @WorkflowInterface
  public interface ConfigurableSleepWorkflow {

    @WorkflowMethod
    public void execute(long sleepMillis);
  }

  public static class ConfigurableSleepWorkflowImpl implements ConfigurableSleepWorkflow {

    @Override
    public void execute(long sleepMillis) {
      Workflow.sleep(sleepMillis);
    }
  }

  @Test
  public void timeskippingCanBeDisabled() throws TimeoutException {
    // Use a differently configured TestWorkflowEnvironment
    tearDown();
    setUp(TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());

    WorkflowOptions workflowAOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOW_TASK_QUEUE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(30))
            .build();

    WorkflowStub stubA =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowAOptions);

    // The workflow sleeps for 10 minutes - we'll wait for significantly less than that to verify
    // that timeskipping can be disabled. Unfortunately there's tension between this assertion's
    // false positive rate and how long it takes.
    long durationToSleep = Duration.ofMinutes(10).toMillis();
    Duration durationToWait = Duration.ofSeconds(1);

    stubA.start(durationToSleep);
    WorkflowServiceException e =
        Assert.assertThrows(
            WorkflowServiceException.class,
            () -> stubA.getResult(durationToWait.toMillis(), TimeUnit.MILLISECONDS, Void.class));

    Assert.assertNotNull(e.getCause());
    Assert.assertEquals(io.grpc.StatusRuntimeException.class, e.getCause().getClass());
    Assert.assertEquals(
        Status.Code.DEADLINE_EXCEEDED,
        ((io.grpc.StatusRuntimeException) e.getCause()).getStatus().getCode());

    // With timeskipping off, a workflow that doesn't sleep for very long should still finish
    WorkflowStub stubB =
        client.newUntypedWorkflowStub("ConfigurableSleepWorkflow", workflowAOptions);
    WorkflowExecution executionB = stubB.start(Duration.ZERO);
    stubB.getResult(durationToWait.toMillis(), TimeUnit.MILLISECONDS, Void.class);
  }
}
