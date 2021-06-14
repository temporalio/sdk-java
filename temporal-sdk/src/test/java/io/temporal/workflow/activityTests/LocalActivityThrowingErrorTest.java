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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityThrowingErrorTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LocalActivityThrowsErrorWorkflow.class)
          .setActivityImplementations(new ActivityThrowingErrorTest.Activity1Impl())
          .build();

  @Test
  public void localActivityThrowsError() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(1)
                    .setInitialInterval(Duration.ofMinutes(2))
                    .build())
            .build();

    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      workflow.execute(UUID.randomUUID().toString());
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("java.lang.Error", ((ApplicationFailure) e.getCause().getCause()).getType());
    }
  }

  public static class LocalActivityThrowsErrorWorkflow implements TestWorkflow1 {

    private final TestActivity1 activity1 =
        Workflow.newLocalActivityStub(
            TestActivity1.class,
            LocalActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofMinutes(2))
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public String execute(String input) {
      return activity1.execute(input);
    }
  }
}
