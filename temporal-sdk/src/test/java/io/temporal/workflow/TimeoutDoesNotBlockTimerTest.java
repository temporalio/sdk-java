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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/** This test guards against regression re: a bug we used to have in the time-skipping logic. */
public class TimeoutDoesNotBlockTimerTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SimpleSleepWorkflow.class).build();

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

    WorkflowStub workflowStub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowLongArg");

    // workflowA completes immediately, even in bug-land
    workflowStub.start(0);
    waitForWorkflow(workflowStub, "A", howLongWeWaitForFutures);

    // Workflow B's execution timeout needs to be longer than its sleep.
    WorkflowOptions workflowBOptions =
        workflowStub
            .getOptions()
            .get()
            .toBuilder()
            .setWorkflowExecutionTimeout(sleepDuration.multipliedBy(2))
            .build();
    workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestWorkflowLongArg", workflowBOptions);

    // In bug land, workflow B wouldn't complete until workflowExecutionTimeout real seconds from
    // now (minus epsilon). Without the bug, it should complete immediately.
    workflowStub.start(sleepDuration.toMillis());
    waitForWorkflow(workflowStub, "B", howLongWeWaitForFutures);
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

  public static class SimpleSleepWorkflow implements TestWorkflows.TestWorkflowLongArg {

    @Override
    public void execute(long sleepMillis) {
      Workflow.sleep(sleepMillis);
    }
  }
}
