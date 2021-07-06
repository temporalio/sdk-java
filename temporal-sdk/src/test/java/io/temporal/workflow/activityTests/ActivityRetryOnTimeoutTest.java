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
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ActivityRetryOnTimeoutTest {
  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestActivityRetryOnTimeout.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testActivityRetryOnTimeout() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    // Wall time on purpose
    long start = System.currentTimeMillis();
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(
          String.valueOf(e.getCause()), e.getCause().getCause() instanceof TimeoutFailure);
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    long elapsed = System.currentTimeMillis() - start;
    if (testName.toString().contains("TestService")) {
      Assert.assertTrue("retry timer skips time", elapsed < 5000);
    }
  }

  public static class TestActivityRetryOnTimeout implements TestWorkflow1 {

    @Override
    @SuppressWarnings("Finally")
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      VariousTestActivities activities =
          Workflow.newActivityStub(VariousTestActivities.class, options);
      long start = Workflow.currentTimeMillis();
      try {
        activities.neverComplete(); // should timeout as scheduleToClose is 1 second
        throw new IllegalStateException("unreachable");
      } catch (ActivityFailure e) {
        long elapsed = Workflow.currentTimeMillis() - start;
        if (elapsed < 5000) {
          throw new RuntimeException("Activity retried without delay: " + elapsed);
        }
        throw e;
      }
    }
  }
}
