/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivitySlotThreadPoolTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 100;
  private final int MAX_CONCURRENT_NEXUS_EXECUTION_SIZE = 2;
  private final FixedSizeSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new FixedSizeSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final FixedSizeSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new FixedSizeSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final FixedSizeSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new FixedSizeSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final FixedSizeSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new FixedSizeSlotSupplier<>(MAX_CONCURRENT_NEXUS_EXECUTION_SIZE);

  class ExplodingExecutor extends ThreadPoolExecutor {
    AtomicInteger submitted = new AtomicInteger(0);

    public ExplodingExecutor(
        int corePoolSize,
        int maximumPoolSize,
        long keepAliveTime,
        TimeUnit unit,
        BlockingQueue<Runnable> workQueue) {
      super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    public void execute(@Nonnull Runnable command) {
      int newval = submitted.incrementAndGet();
      // Blow up for a while and then recover
      if (newval > 2 && newval < 10) {
        throw new OutOfMemoryError("boom");
      }
      super.execute(command);
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new CompositeTuner(
                          workflowTaskSlotSupplier,
                          activityTaskSlotSupplier,
                          localActivitySlotSupplier,
                          nexusSlotSupplier))
                  .build())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setOverrideLocalActivityTaskExecutor(
                      new ExplodingExecutor(
                          1, 10, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>()))
                  .build())
          .setActivityImplementations(new TestActivitySemaphoreImpl())
          .setWorkflowTypes(ParallelActivities.class)
          .setDoNotStart(true)
          .build();

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow();
  }

  public static class ParallelActivities implements TestWorkflow {
    private final TestActivity localActivity =
        Workflow.newLocalActivityStub(
            TestActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow() {
      List<Promise<String>> laResults = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        laResults.add(Async.function(localActivity::activity, String.valueOf(i)));
      }
      Promise.allOf(laResults).get();
      return "ok";
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    String activity(String input);
  }

  public static class TestActivitySemaphoreImpl implements TestActivity {
    @Override
    public String activity(String input) {
      return "";
    }
  }

  @Test
  public void TestLAExecutorServiceThrowsOOM() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    workflow.workflow();
  }
}
