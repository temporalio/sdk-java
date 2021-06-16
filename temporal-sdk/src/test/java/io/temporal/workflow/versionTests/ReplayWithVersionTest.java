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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class ReplayWithVersionTest {

  private static final AtomicInteger COUNTER = new AtomicInteger(1);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @Test
  public void testWorkflowReplaySpanStructure() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow1 workflow =
        client.newWorkflowStub(
            TestWorkflow1.class,
            WorkflowOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumAttempts(2)
                        .build())
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());
    String result = workflow.execute(" version: ");
    assertEquals("Activity version: default.", result);
  }

  public static class ActivityImpl implements TestActivity1 {
    @Override
    public String execute(String input) {
      return "Activity" + input;
    }
  }

  public static class WorkflowImpl implements TestWorkflow1 {
    private final TestActivity1 activity =
        Workflow.newLocalActivityStub(
            TestActivity1.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public String execute(String input) {
      if (COUNTER.getAndDecrement() > 0) {
        throw new OutOfMemoryError();
      }
      int version = Workflow.getVersion("noLocalActivity", Workflow.DEFAULT_VERSION, 1);
      if (version == Workflow.DEFAULT_VERSION) {
        // call Local activity
        return activity.execute(input + "v1.");
      }
      return activity.execute(input + "default.");
    }
  }
}
