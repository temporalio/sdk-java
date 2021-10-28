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

import static org.junit.Assert.fail;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.NoArgsReturnsIntActivity;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityCompletionWithPrimitiveValueTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new AsyncActivityWithManualCompletion())
          .build();

  @Test
  public void verifyActivityCompletionClientCompleteExceptionally() {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    workflow.execute();
  }

  public static class TestWorkflowImpl implements NoArgsWorkflow {

    @Override
    public void execute() {
      NoArgsReturnsIntActivity activity =
          Workflow.newActivityStub(
              NoArgsReturnsIntActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      try {
        activity.execute();
        fail();
      } catch (NullPointerException e) {
        // expected
      }
    }
  }

  public static class AsyncActivityWithManualCompletion implements NoArgsReturnsIntActivity {
    @Override
    public int execute() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      ManualActivityCompletionClient completionClient = context.useLocalManualCompletion();
      completionClient.complete(null);
      return -1;
    }
  }
}
