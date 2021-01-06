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

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityPollerPrefetchingTest {

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setMaxConcurrentActivityExecutionSize(1)
                  .setActivityPollThreadCount(5)
                  .build())
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new SleepyMultiplier())
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  /**
   * This workflow reproduces a scenario that was causing a bug with eager activity prefetching. It
   * ensures that we only poll for new activities when there is handler capacity available to
   * process it.
   */
  public static class TestWorkflowImpl implements TestWorkflow {

    @Override
    public String execute(String taskQueue) {
      MultiplierActivity activity =
          Workflow.newActivityStub(
              MultiplierActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofSeconds(10))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                  .setHeartbeatTimeout(Duration.ofSeconds(1))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      List<Promise<Integer>> promises = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        final int value = i;
        promises.add(Async.function(() -> activity.execute(value)));
      }
      Promise.allOf(promises).get();
      return "success";
    }
  }

  @ActivityInterface
  public interface MultiplierActivity {

    @ActivityMethod
    int execute(int value);
  }

  public static class SleepyMultiplier implements MultiplierActivity {

    @Override
    public int execute(int value) {
      Activity.getExecutionContext().heartbeat(value);
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        e.printStackTrace();
      }
      return value * 2;
    }
  }

  /**
   * This test runs a workflow that executes multiple activities in the single handler thread. Test
   * workflow is configured to fail with heartbeat timeout errors in case if activity pollers are
   * too eager to poll tasks before previously fetched tasks are handled.
   */
  @Test
  public void verifyThatActivityIsNotPrefetchedWhenThereIsNoHandlerAvailable() {
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
