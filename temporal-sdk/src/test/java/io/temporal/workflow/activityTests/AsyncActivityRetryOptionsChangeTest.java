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

package io.temporal.workflow.activityTests;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.io.IOException;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityRetryOptionsChangeTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncActivityRetryOptionsChange.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testAsyncActivityRetryOptionsChange() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  public static class TestAsyncActivityRetryOptionsChange implements TestWorkflow1 {

    private VariousTestActivities activities;

    @Override
    public String execute(String taskQueue) {
      ActivityOptions.Builder options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(5))
              .setStartToCloseTimeout(Duration.ofSeconds(10));
      if (Workflow.isReplaying()) {
        options.setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setDoNotRetry(NullPointerException.class.getName())
                .setMaximumAttempts(3)
                .build());
      } else {
        options.setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .setDoNotRetry(NullPointerException.class.getName())
                .build());
      }
      this.activities = Workflow.newActivityStub(VariousTestActivities.class, options.build());
      Async.procedure(activities::throwIO).get();
      return "ignored";
    }
  }
}
