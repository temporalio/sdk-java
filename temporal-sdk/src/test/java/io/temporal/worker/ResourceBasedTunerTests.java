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

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class ResourceBasedTunerTests {

  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final Map<String, String> TAGS_NAMESPACE =
      new ImmutableMap.Builder<String, String>().putAll(MetricsTag.defaultTags(NAMESPACE)).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      ResourceBasedTuner.newBuilder()
                          .setControllerOptions(
                              ResourceBasedControllerOptions.newBuilder(0.7, 0.7).build())
                          .build())
                  .build())
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkflowTypes(ResourceTunerWorkflowImpl.class)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  @Test(timeout = 300 * 1000)
  public void canRunWithResourceBasedTuner() throws InterruptedException {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    workflow.execute(5, 5, 1000);
    Map<String, String> nsAndTaskQueue =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_NAMESPACE)
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .build();
    reporter.assertGauge(MetricsType.RESOURCE_MEM_USAGE, nsAndTaskQueue, (val) -> val > 0);
    reporter.assertGauge(
        MetricsType.RESOURCE_CPU_USAGE,
        nsAndTaskQueue,
        (val) -> {
          // CPU use can be so low as to be 0, so can't really make any assertion here.
          return true;
        });
    reporter.assertGauge(MetricsType.RESOURCE_MEM_PID, nsAndTaskQueue, (val) -> true);
    reporter.assertGauge(MetricsType.RESOURCE_CPU_PID, nsAndTaskQueue, (val) -> true);
    // Verify no slots are leaked
    Thread.sleep(100); // wait a beat for slots to actually get released
    reporter.assertGauge(MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("WorkflowWorker"), 0);
    reporter.assertGauge(MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("ActivityWorker"), 0);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("LocalActivityWorker"), 0);
  }

  @Category(IndependentResourceBasedTests.class)
  @Test(timeout = 300 * 1000)
  public void canRunHeavyMemoryWithResourceBasedTuner() {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    workflow.execute(50, 50, 30000000);
  }

  @WorkflowInterface
  public interface ResourceTunerWorkflow {
    @WorkflowMethod
    String execute(int numActivities, int localActivities, int memCeiling);
  }

  public static class ResourceTunerWorkflowImpl implements ResourceTunerWorkflow {
    @Override
    public String execute(int numActivities, int localActivities, int memCeiling) {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setHeartbeatTimeout(Duration.ofSeconds(20))
                  .build());

      SleepActivity localActivity =
          Workflow.newLocalActivityStub(
              SleepActivity.class,
              LocalActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .build());

      List<Promise<Void>> promises = new ArrayList<>();
      for (int j = 0; j < numActivities; j++) {
        Promise<Void> promise = Async.procedure(activity::useResources, memCeiling);
        promises.add(promise);
      }
      for (int j = 0; j < localActivities; j++) {
        Promise<Void> promise = Async.procedure(localActivity::useResources, memCeiling);
        promises.add(promise);
      }

      for (Promise<Void> promise : promises) {
        promise.get();
      }

      return "I'm done";
    }
  }

  @ActivityInterface
  public interface SleepActivity {
    void useResources(int memCeiling);
  }

  public static class ActivitiesImpl implements SleepActivity {
    @Override
    public void useResources(int memCeiling) {
      try {
        int randNumBytes = (int) (Math.random() * memCeiling);
        @SuppressWarnings("unused")
        byte[] bytes = new byte[randNumBytes];
        // Need to wait at least a second to give metrics a chance to be reported
        // (and also simulate some actual work in the activity)
        Thread.sleep(1100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
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
}
