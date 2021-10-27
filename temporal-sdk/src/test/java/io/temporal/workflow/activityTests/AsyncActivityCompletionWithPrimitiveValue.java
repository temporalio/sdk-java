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

import static junit.framework.TestCase.assertNull;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.common.RetryOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityCompletionWithPrimitiveValue {

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
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofSeconds(1))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      Promise<Long> promise = Async.function(activity::execute);
      Long result = promise.get();
      assertNull(result);
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    long execute();
  }

  public static class AsyncActivityWithManualCompletion implements TestActivity {
    @Override
    public long execute() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      ManualActivityCompletionClient completionClient = context.useLocalManualCompletion();
      completionClient.complete(null);
      return -1;
    }
  }
}
