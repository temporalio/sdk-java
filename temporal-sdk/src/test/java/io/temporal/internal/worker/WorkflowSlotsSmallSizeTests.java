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

package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.ActivitySlotInfo;
import io.temporal.worker.tuning.CompositeTuner;
import io.temporal.worker.tuning.LocalActivitySlotInfo;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WorkflowSlotsSmallSizeTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 2;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 2;
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final TestStatsReporter reporter = new TestStatsReporter();
  static Semaphore parallelSemRunning = new Semaphore(0);
  static Semaphore parallelSemBlocked = new Semaphore(0);

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Parameterized.Parameter public boolean activitiesAreLocal;

  @Parameterized.Parameters()
  public static Object[] data() {
    return new Object[][] {{true}, {false}};
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
                          localActivitySlotSupplier))
                  .build())
          .setMetricsScope(metricsScope)
          .setActivityImplementations(new TestActivitySemaphoreImpl())
          .setWorkflowTypes(ParallelActivities.class)
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    reporter.flush();
    parallelSemRunning = new Semaphore(0);
    parallelSemBlocked = new Semaphore(0);
  }

  @After
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  private void assertWorkerSlotCount(int worker, int activity, int localActivity) {
    try {
      // There can be a delay in metrics emission, another option if this
      // is too flaky is to poll the metrics.
      Thread.sleep(100);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("WorkflowWorker"), worker);
    // All slots should be available
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("ActivityWorker"), activity);
    // All slots should be available
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("LocalActivityWorker"),
        localActivity);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(boolean useLocalActivity);

    @SignalMethod
    void unblock();
  }

  public static class ParallelActivities implements TestWorkflow {
    boolean unblocked = false;

    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    private final TestActivity localActivity =
        Workflow.newLocalActivityStub(
            TestActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(boolean useLocalActivity) {
      Workflow.await(() -> unblocked);
      List<Promise<String>> laResults = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        if (useLocalActivity) {
          laResults.add(Async.function(localActivity::activity, String.valueOf(i)));
        } else {
          laResults.add(Async.function(activity::activity, String.valueOf(i)));
        }
      }
      Promise.allOf(laResults).get();
      return "ok";
    }

    @Override
    public void unblock() {
      unblocked = true;
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
      parallelSemRunning.release();
      try {
        parallelSemBlocked.acquire();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return "";
    }
  }

  private Map<String, String> getWorkerTags(String workerType) {
    return ImmutableMap.of(
        "worker_type",
        workerType,
        "task_queue",
        testWorkflowRule.getTaskQueue(),
        "namespace",
        "UnitTest");
  }

  private void assertIntraWFTSlotCount(int allowedToRun) {
    int runningLAs = activitiesAreLocal ? allowedToRun : 0;
    int runningAs = activitiesAreLocal ? 0 : allowedToRun;
    int runningWFTs = activitiesAreLocal ? 1 : 0;
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE - runningWFTs,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE - runningAs,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE - runningLAs);
  }

  @Test
  public void TestLocalActivitySlotAtLimit() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, activitiesAreLocal);
    workflow.unblock();
    for (int i = 0; i < 5; i++) {
      parallelSemRunning.acquire(2);
      assertIntraWFTSlotCount(2);
      parallelSemBlocked.release(2);
    }
    workflow.workflow(true);
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }

  @Test
  public void TestLocalActivitySlotHitsCapacity() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofSeconds(1))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, activitiesAreLocal);
    workflow.unblock();
    for (int i = 0; i < 5; i++) {
      parallelSemRunning.acquire(2);
      assertIntraWFTSlotCount(2);
      parallelSemBlocked.release(2);
      // Take too long (hit WFT timeout while trying to schedule LAs)
      if (i == 2) {
        Thread.sleep(1000);
      }
    }
    // Because the WFT fails, the LAs may be re-run, and it's not clearly defined how many of them
    // will, so ensure there are enough permits for the test to complete. What matters is that the
    // slot counts end up at the appropriate values after everything finishes.
    parallelSemBlocked.release(100);
    workflow.workflow(true);
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }
}
