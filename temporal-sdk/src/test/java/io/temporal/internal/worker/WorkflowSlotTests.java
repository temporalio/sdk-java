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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowSlotTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 100;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 1000;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 10000;
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final TestStatsReporter reporter = new TestStatsReporter();
  static CountDownLatch activityBlockLatch = new CountDownLatch(1);
  static CountDownLatch activityRunningLatch = new CountDownLatch(1);
  static boolean didFail = false;

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

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
          .setActivityImplementations(new TestActivityImpl())
          .setWorkflowTypes(SleepingWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    reporter.flush();
    activityBlockLatch = new CountDownLatch(1);
    activityRunningLatch = new CountDownLatch(1);
    didFail = false;
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
    String workflow(String action);

    @SignalMethod
    void unblock();
  }

  public static class SleepingWorkflowImpl implements TestWorkflow {
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
    public String workflow(String action) {
      Workflow.await(() -> unblocked);
      if (action.equals("fail") && !didFail) {
        didFail = true;
        throw new RuntimeException("fail on purpose");
      } else if (action.equals("local-activity")) {
        localActivity.activity("test");
      } else if (action.equals("activity")) {
        activity.activity("test");
      }
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

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      activityRunningLatch.countDown();
      try {
        activityBlockLatch.await();
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

  @Test
  public void TestTaskSlotsEmittedOnStart() {
    // Verify that no metrics are emitted before the worker is started
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("WorkflowWorker"));
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("ActivityWorker"));
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("LocalActivityWorker"));
    // Start the worker
    testWorkflowRule.getTestEnvironment().start();
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }

  @Test
  public void TestActivityTaskSlots() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "activity");
    workflow.unblock();
    activityRunningLatch.await();
    // The activity slot should be taken and the workflow slot should not be taken
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE - 1,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);

    activityBlockLatch.countDown();
    // Wait for the workflow to finish
    workflow.workflow("activity");
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }

  @Test
  public void TestLocalActivityTaskSlots() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "local-activity");
    workflow.unblock();
    activityRunningLatch.await();
    // The local activity slot should be taken and the workflow slot should be taken
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE - 1,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE - 1);

    activityBlockLatch.countDown();
    workflow.workflow("local-activity");
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }

  @Test
  public void TestLocalActivityHeartbeat() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofSeconds(1))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "local-activity");
    workflow.unblock();
    activityRunningLatch.await();
    // The local activity slot should be taken and the workflow slot should be taken
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE - 1,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE - 1);
    // Take long enough to heartbeat
    Thread.sleep(1000);
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE - 1,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE - 1);

    activityBlockLatch.countDown();
    workflow.workflow("local-activity");
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }

  @Test
  public void TestWFTFailure() {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofMillis(500))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "fail");
    workflow.unblock();
    workflow.workflow("fail");
    // All slots should be available
    assertWorkerSlotCount(
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE,
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  }
}
