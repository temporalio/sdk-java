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

package io.temporal.workflow;

import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflow.v1.PendingChildExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.DescribeWorkflowAsserter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DescribeTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestDescribeWorkflowImpl.class)
          .setActivityImplementations(new TestDescribeActivityImpl())
          .setTestTimeoutSeconds(30)
          .build();

  public DescribeWorkflowAsserter describe(WorkflowExecution execution) {
    DescribeWorkflowAsserter result =
        new DescribeWorkflowAsserter(
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(
                            testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                        .setExecution(execution)
                        .build()));

    // There are some assertions that we can always make...
    return result
        .assertType("TestDescribeWorkflow")
        .assertExecutionId(execution)
        .assertSaneTimestamps()
        .assertTaskQueue(testWorkflowRule.getTaskQueue());
  }

  @Test
  public void testWorkflowDoesNotExist() {
    StatusRuntimeException e =
        Assert.assertThrows(
            io.grpc.StatusRuntimeException.class,
            () ->
                describe(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId("627ecd0c-688b-3fc5-927e-0f7ab7eec09b")
                        .setRunId("8f493dbf-205d-4142-8e0b-dc5cdff404b8")
                        .build()));

    Assert.assertEquals(Status.NOT_FOUND.getCode(), e.getStatus().getCode());
  }

  private WorkflowOptions options() {
    // The task queue isn't known until the test is running, so we can't just declare a constant
    // WorkflowOptions
    return WorkflowOptions.newBuilder()
        .setTaskQueue(testWorkflowRule.getTaskQueue())
        .setWorkflowExecutionTimeout(Duration.ofMinutes(3))
        .setWorkflowRunTimeout(Duration.ofMinutes(2))
        .setWorkflowTaskTimeout(Duration.ofMinutes(1))
        .setMemo(ImmutableMap.of("memo", "random"))
        .build();
  }

  @Test
  public void testSuccessfulActivity() throws InterruptedException {
    String token = "testSuccessfulActivity";
    WorkflowOptions options = options();
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestDescribeWorkflow", options);
    WorkflowExecution execution = stub.start(token, null, Boolean.TRUE, 0);

    // Wait for the activity, so we know what status to expect
    ThreadUtils.waitForWorkflow(token + "-start");

    DescribeWorkflowAsserter asserter =
        describe(execution)
            .assertMatchesOptions(options)
            .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
            .assertNoParent()
            .assertPendingActivityCount(1)
            .assertPendingChildrenCount(0);

    PendingActivityInfo actual = asserter.getActual().getPendingActivities(0);

    // No fancy asserter type for PendingActivityInfo... we just build the expected proto
    PendingActivityInfo expected =
        PendingActivityInfo.newBuilder()
            .setActivityId(actual.getActivityId())
            .setActivityType(ActivityType.newBuilder().setName("TestDescribeActivity").build())
            .setState(PendingActivityState.PENDING_ACTIVITY_STATE_STARTED)
            // TODO: Uncomment when the server is fixed. We should always expect to see an identity
            //  here because any activity that has been picked up by the worker is pending until it
            //  is completed/cancelled/terminated.
            //            .setLastWorkerIdentity(
            //                testWorkflowRule
            //                    .getTestEnvironment()
            //                    .getWorkflowClient()
            //                    .getOptions()
            //                    .getIdentity())
            .setAttempt(1)
            .setMaximumAttempts(2)
            // times should be present, but we can't know what the expected value is if this test is
            // going to run against the real server.
            .setLastStartedTime(actual.getLastStartedTime())
            .setExpirationTime(actual.getExpirationTime())
            // Heads up! We're asserting that heartbeat time == started time, which should be true
            // before the heartbeat
            .setLastHeartbeatTime(actual.getLastStartedTime())
            .build();

    Assert.assertEquals("PendingActivityInfo should match before", expected, actual);

    // Make the activity heartbeat - this should show in the next describe call
    ThreadUtils.waitForWorkflow(token + "-heartbeat");
    ThreadUtils.waitForWorkflow(token + "-after-heartbeat");

    asserter =
        describe(execution)
            .assertMatchesOptions(options)
            .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
            .assertNoParent()
            .assertPendingActivityCount(1)
            .assertPendingChildrenCount(0);

    actual = asserter.getActual().getPendingActivities(0);

    // Now, our PendingActivityInfo has heartbeat data, but is otherwise unchanged
    expected =
        expected
            .toBuilder()
            .setHeartbeatDetails(DescribeWorkflowAsserter.stringsToPayloads("heartbeatDetails"))
            .setLastHeartbeatTime(actual.getLastHeartbeatTime())
            .build();
    Assert.assertEquals("PendingActivityInfo should match after heartbeat", expected, actual);

    // Let the activity finish, which will let the workflow finish.
    ThreadUtils.waitForWorkflow(token + "-finish");

    // Wait for the workflow to finish, so we know what state to expect
    stub.getResult(Void.class);
    describe(execution)
        .assertMatchesOptions(options)
        .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED)
        .assertNoParent()
        .assertPendingActivityCount(0)
        .assertPendingChildrenCount(0);
  }

  @Test
  public void testFailedActivity() throws InterruptedException {
    String token = "testFailedActivity";
    WorkflowOptions options = options();
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestDescribeWorkflow", options);
    WorkflowExecution execution = stub.start(token, null, Boolean.FALSE, 2);

    // Fast-forward until the retry after the failure
    ThreadUtils.waitForWorkflow(token + "-start");
    ThreadUtils.waitForWorkflow(token + "-fail");
    ThreadUtils.waitForWorkflow(token + "-start");

    // Previous test cases have made boilerplate assertions - let's only focus on what's novel
    DescribeWorkflowAsserter asserter =
        describe(execution)
            .assertMatchesOptions(options)
            .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
            .assertPendingActivityCount(1);

    PendingActivityInfo actual = asserter.getActual().getPendingActivities(0);

    Assert.assertEquals(
        "failure message should match",
        "Activity was asked to fail on attempt 1",
        actual.getLastFailure().getMessage());

    PendingActivityInfo expected =
        PendingActivityInfo.newBuilder()
            .setActivityId(actual.getActivityId())
            .setActivityType(ActivityType.newBuilder().setName("TestDescribeActivity").build())
            .setState(PendingActivityState.PENDING_ACTIVITY_STATE_STARTED)
            .setAttempt(2)
            .setMaximumAttempts(2)
            // times should be present, but we can't know what the expected value is if this test is
            // going to run against the real server.
            .setLastStartedTime(actual.getLastStartedTime())
            .setLastHeartbeatTime(actual.getLastHeartbeatTime())
            .setExpirationTime(actual.getExpirationTime())
            // this ends up being a dummy value, but if it weren't, we still wouldn't expect to know
            // it.
            .setLastWorkerIdentity(actual.getLastWorkerIdentity())
            // We don't deeply assert the failure structure since we asserted the message above
            .setLastFailure(actual.getLastFailure())
            .build();

    Assert.assertEquals("PendingActivityInfo should match", expected, actual);

    // Now let the workflow succeed
    ThreadUtils.waitForWorkflow(token + "-finish");
    stub.getResult(Void.class);
    describe(execution)
        .assertMatchesOptions(options)
        .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED)
        .assertNoParent()
        .assertPendingActivityCount(0)
        .assertPendingChildrenCount(0);
  }

  private void testKilledWorkflow(
      String token,
      Consumer<WorkflowStub> killer,
      WorkflowExecutionStatus expectedWorkflowStatus,
      PendingActivityState expectedActivityStatus)
      throws InterruptedException {
    WorkflowOptions options = options();
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestDescribeWorkflow", options);
    // Set the execution up so that it will fail due to activity failures if it isn't otherwise
    // killed
    WorkflowExecution execution = stub.start(token, null, Boolean.FALSE, 3);

    // Let the activity start so we can cancel it
    ThreadUtils.waitForWorkflow(token + "-start");
    killer.accept(stub);

    // Wait for the kill (whatever it was) to get noticed (we don't try to assert intermediate
    // states - those are hard to catch).
    Assert.assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));

    // Previous test cases have made boilerplate assertions - let's only focus on what's novel
    DescribeWorkflowAsserter asserter =
        describe(execution)
            .assertMatchesOptions(options)
            .assertStatus(expectedWorkflowStatus)
            .assertPendingActivityCount(expectedActivityStatus == null ? 0 : 1);

    if (expectedActivityStatus == null) {
      return;
    }

    PendingActivityInfo actual = asserter.getActual().getPendingActivities(0);

    PendingActivityInfo expected =
        PendingActivityInfo.newBuilder()
            .setActivityId(actual.getActivityId())
            .setActivityType(ActivityType.newBuilder().setName("TestDescribeActivity").build())
            .setState(expectedActivityStatus)
            .setAttempt(1)
            .setMaximumAttempts(2)
            // times should be present, but we can't know what the expected value is if this test is
            // going to run against the real server.
            .setLastStartedTime(actual.getLastStartedTime())
            .setLastHeartbeatTime(actual.getLastStartedTime())
            .setExpirationTime(actual.getExpirationTime())
            // this ends up being a dummy value, but if it weren't, we still wouldn't expect to know
            // it.
            .setLastWorkerIdentity(actual.getLastWorkerIdentity())
            .build();

    Assert.assertEquals("PendingActivityInfo should match", expected, actual);
  }

  @Test
  public void testCanceledWorkflow() throws InterruptedException {
    testKilledWorkflow(
        "testCanceledWorkflow",
        WorkflowStub::cancel,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
        PendingActivityState.PENDING_ACTIVITY_STATE_CANCEL_REQUESTED);
  }

  @Test
  public void testTerminatedWorkflow() throws InterruptedException {
    testKilledWorkflow(
        "testTerminatedWorkflow",
        stub -> stub.terminate("testing"),
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
        PendingActivityState.PENDING_ACTIVITY_STATE_STARTED);
  }

  @Test
  public void testFailedWorkflow() throws InterruptedException {
    String token = "testFailedWorkflow";
    testKilledWorkflow(
        token,
        stub -> {
          try {
            // Don't kill the workflow externally here - instead, unblock the activity twice, which
            // will fail the workflow
            ThreadUtils.waitForWorkflow(token + "-fail");
            ThreadUtils.waitForWorkflow(token + "-start");
            ThreadUtils.waitForWorkflow(token + "-fail");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        },
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
        null);
  }

  @Test
  public void testChildWorkflow() throws InterruptedException {
    String token = "testChildWorkflow";
    WorkflowOptions options = options();
    WorkflowStub stub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestDescribeWorkflow", options);
    WorkflowExecution parentExecution = stub.start(null, token, Boolean.FALSE, 0);

    // This unblocks the child workflow's activity
    ThreadUtils.waitForWorkflow(token + "-start");

    DescribeWorkflowAsserter parent =
        describe(parentExecution)
            .assertMatchesOptions(options)
            .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
            .assertNoParent()
            .assertPendingActivityCount(0)
            .assertPendingChildrenCount(1);

    // There's very little of interest to assert on here, but we need the id so we can describe the
    // child
    PendingChildExecutionInfo childInfo = parent.getActual().getPendingChildren(0);
    Assert.assertEquals(
        "child workflow type name should match",
        "TestDescribeWorkflow",
        childInfo.getWorkflowTypeName());

    WorkflowExecution childExecution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(childInfo.getWorkflowId())
            .setRunId(childInfo.getRunId())
            .build();

    // marshal ChildWorkflowOptions to WorkflowOptions because that's what the asserter expects
    WorkflowOptions expectedChildOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(
                TestDescribeWorkflowImpl.CHILD_OPTIONS.getWorkflowExecutionTimeout())
            .setWorkflowRunTimeout(TestDescribeWorkflowImpl.CHILD_OPTIONS.getWorkflowRunTimeout())
            .setWorkflowTaskTimeout(TestDescribeWorkflowImpl.CHILD_OPTIONS.getWorkflowTaskTimeout())
            .setMemo(TestDescribeWorkflowImpl.CHILD_OPTIONS.getMemo())
            .build();

    describe(childExecution)
        .assertMatchesOptions(expectedChildOptions)
        .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
        .assertParent(parentExecution)
        .assertPendingActivityCount(1)
        .assertPendingChildrenCount(0);

    // Unblock the child and wait for the parent to finish, so we know what expected states are
    ThreadUtils.waitForWorkflow(token + "-finish");
    stub.getResult(Void.class);

    describe(parentExecution)
        .assertMatchesOptions(options)
        .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED)
        .assertNoParent()
        .assertPendingActivityCount(0)
        .assertPendingChildrenCount(0);

    describe(childExecution)
        .assertMatchesOptions(expectedChildOptions)
        .assertStatus(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED)
        .assertParent(parentExecution)
        .assertPendingActivityCount(0)
        .assertPendingChildrenCount(0);
  }

  /**
   * We don't test things that require the passage of time here to avoid sleepy tests against the
   * real temporal service. A future commit could introduce a test-environment-only suite that locks
   * time-skipping and exercises time-based scenarios.
   */
  @WorkflowInterface
  public interface TestDescribeWorkflow {
    @WorkflowMethod(name = "TestDescribeWorkflow")
    void run(String myToken, String childToken, boolean heartbeat, int failAttemptsEarlierThan);
  }

  public static class TestDescribeWorkflowImpl implements TestDescribeWorkflow {

    private static final ChildWorkflowOptions CHILD_OPTIONS =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofMinutes(6))
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofMinutes(1))
            .setMemo(ImmutableMap.of("other", "memo"))
            .build();

    private final TestDescribeWorkflow childStub =
        Workflow.newChildWorkflowStub(TestDescribeWorkflow.class, CHILD_OPTIONS);

    private final TestDescribeActivity activityStub =
        Workflow.newActivityStub(
            TestDescribeActivity.class,
            ActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(1))
                        .setMaximumAttempts(2)
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .build());

    @Override
    public void run(
        String myToken, String childToken, boolean heartbeat, int failAttemptsEarlierThan) {
      if (childToken != null) {
        childStub.run(childToken, null, heartbeat, failAttemptsEarlierThan);
      } else {
        activityStub.run(myToken, heartbeat, failAttemptsEarlierThan);
      }
    }
  }

  @ActivityInterface
  public interface TestDescribeActivity {
    @ActivityMethod(name = "TestDescribeActivity")
    void run(String token, boolean heartbeat, int failAttemptsEarlierThan);
  }

  public static class TestDescribeActivityImpl implements TestDescribeActivity {

    public void run(String token, boolean heartbeat, int failAttemptsEarlierThan) {
      try {
        // Wait twice - once to let the test case wait for activity start, and once
        // to let the test case hold activities open until it wants them to finish.
        ThreadUtils.waitForTestCase(token + "-start");

        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        if (heartbeat) {
          ThreadUtils.waitForTestCase(token + "-heartbeat");
          Activity.getExecutionContext().heartbeat("heartbeatDetails");
          ThreadUtils.waitForTestCase(token + "-after-heartbeat");
        } else if (attempt < failAttemptsEarlierThan) {
          ThreadUtils.waitForTestCase(token + "-fail");
          throw new RuntimeException("Activity was asked to fail on attempt " + attempt);
        }

        ThreadUtils.waitForTestCase(token + "-finish");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw CheckedExceptionWrapper.wrap(e);
      }
    }
  }

  /*
   * This class lets test code precisely control the execution of activity code via
   * barriers. We can't just do this with signals because exercise describe needs
   * control over _activity_ code, not just workflow code.
   */
  public static class ThreadUtils {
    private static final Logger log = LoggerFactory.getLogger(ThreadUtils.class);

    private static final Map<String, CyclicBarrier> queues = new ConcurrentHashMap<>();

    public static void waitForTestCase(String token) throws InterruptedException {
      log.info("Workflow is waiting to meet test case: {}", token);
      waitFor("test case", token);
      log.info("Workflow finished meeting test case: {}", token);
    }

    public static void waitForWorkflow(String token) throws InterruptedException {
      log.info("Test case is waiting to meet workflow: {}", token);
      waitFor("workflow", token);
      log.info("Test case finished meeting workflow: {}", token);
    }

    private static void waitFor(String otherParty, String token) throws InterruptedException {
      CyclicBarrier barrier = queues.computeIfAbsent(token, unused -> new CyclicBarrier(2));

      try {
        barrier.await(30, TimeUnit.SECONDS);
      } catch (BrokenBarrierException e) {
        // This happens to waiting threads if a peer gets interrupted.
        log.warn(
            "Barrier broken when waiting for the {} on {}. This is a side-effect of a test thread being interrupted, and is not the cause of a test failure.",
            token,
            otherParty);
      } catch (TimeoutException e) {
        // This is a test failure - the other party didn't show up in time.
        Assert.fail(
            String.format("When waiting on %s, the %s did not arrive in time", token, otherParty));
      }
    }
  }
}
