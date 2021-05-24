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

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class TestRetry {

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new FailingActivityImpl())
          .build();

  @Test
  public void testActivityFailureFirstWorkflowRun() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow1 workflow =
        client.newWorkflowStub(
            TestWorkflow1.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                // Retry the workflow though
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .validateBuildWithDefaults());
    assertEquals("bar", workflow.execute("input"));
  }

  public static class FailingActivityImpl implements TestActivity1 {
    @Override
    public String execute(String input) {
      if (failureCounter.getAndDecrement() > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else {
        return "bar";
      }
    }
  }

  public static class WorkflowImpl implements TestWorkflow1 {
    private final TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                // Don't retry the activity
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String execute(String input) {
      return activity.execute(input);
    }
  }
}
