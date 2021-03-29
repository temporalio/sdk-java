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
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.common.RetryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalAsyncCompletionWorkflowTest {

  public static final int MAX_CONCURRENT_ACTIVITIES = 1;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setMaxConcurrentActivityExecutionSize(MAX_CONCURRENT_ACTIVITIES)
                  .setActivityPollThreadCount(5)
                  .build())
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new AsyncActivityWithManualCompletion())
          .setTestTimeoutSeconds(15)
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
                  .setScheduleToStartTimeout(Duration.ofSeconds(10))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                  .setHeartbeatTimeout(Duration.ofSeconds(1))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      List<Promise<Integer>> promises = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        promises.add(Async.function(() -> activity.execute(2)));
      }
      Promise.allOf(promises).get();
      for (Promise<Integer> promise : promises) {
        if (promise.getFailure() != null) {
          return "exception";
        }
        if (promise.get() != 4) { // All activities compute 2 * 2
          return "wrong result";
        }
      }
      return "success";
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    int execute(int value);
  }

  public static class AsyncActivityWithManualCompletion implements TestActivity {
    private final AtomicInteger concurrentActivitiesCount = new AtomicInteger(0);

    @Override
    public int execute(int value) {
      int concurrentActivities = concurrentActivitiesCount.incrementAndGet();
      if (concurrentActivities > MAX_CONCURRENT_ACTIVITIES) {
        throw new RuntimeException(
            String.format(
                "More than %d activities was running concurrently!", MAX_CONCURRENT_ACTIVITIES));
      }
      ActivityExecutionContext context = Activity.getExecutionContext();
      context.heartbeat(value);
      ManualActivityCompletionClient completionClient = context.useLocalManualCompletion();
      ForkJoinPool.commonPool().execute(() -> asyncActivityFn(value, completionClient));
      return 0;
    }

    private void asyncActivityFn(int value, ManualActivityCompletionClient completionClient) {
      try {
        Thread.sleep(500);
        concurrentActivitiesCount.decrementAndGet();
        completionClient.complete(value * 2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        e.printStackTrace();
        concurrentActivitiesCount.decrementAndGet();
        completionClient.fail(e);
      }
    }
  }

  /**
   * This test runs 10 async activities in parallel. The expectation is that
   * MAX_CONCURRENT_ACTIVITIES limit is being respected and only 1 activity should be running at the
   * same time.
   */
  @Test
  public void verifyLocalActivityCompletionRespectsConcurrencySettings() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    String result = workflow.execute(taskQueue);
    Assert.assertEquals("success", result);
  }
}
