/*
 *  Copyright (C) 2021 Temporal Technologies, Inc. All Rights Reserved.
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

import io.temporal.activity.*;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowRule;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityCompleteWithErrorTest {

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new AsyncActivityWithManualCompletion())
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    @Override
    public String execute(String taskQueue) {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofSeconds(1))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      Promise<Integer> promise = Async.function(activity::execute);
      RuntimeException failure = promise.getFailure();
      Assert.assertNotNull(failure);
      Assert.assertTrue(failure.getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "simulated failure", ((ApplicationFailure) failure.getCause()).getOriginalMessage());
      return "success";
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    int execute();
  }

  public static class AsyncActivityWithManualCompletion implements TestActivity {
    @Override
    public int execute() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      ActivityCompletionClient completionClient = context.useLocalManualCompletion();
      ForkJoinPool.commonPool().execute(() -> asyncActivityFn(context, completionClient));
      return 0;
    }

    private void asyncActivityFn(
        ActivityExecutionContext context, ActivityCompletionClient completionClient) {
      completionClient.completeExceptionally(
          context.getTaskToken(), ApplicationFailure.newFailure("simulated failure", "test"));
    }
  }

  @Test
  public void verifyActivityCompletionClientCompleteExceptionally() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    TestWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());
    String result = workflow.execute(taskQueue);
    Assert.assertEquals("success", result);
  }
}
