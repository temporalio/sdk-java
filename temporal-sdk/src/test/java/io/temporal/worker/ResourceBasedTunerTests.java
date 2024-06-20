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
                      new ResourceBasedTuner(
                          ResourceBasedControllerOptions.newBuilder(0.7, 0.7).build()))
                  .build())
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkflowTypes(ResourceTunerWorkflowImpl.class)
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  @Test
  public void canRunWithResourceBasedTuner() {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    workflow.execute(10, 1000);
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
  }

  @Category(IndependentResourceBasedTests.class)
  @Test(timeout = 300 * 1000)
  public void canRunHeavyMemoryWithResourceBasedTuner() {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    workflow.execute(100, 30000000);
  }

  @WorkflowInterface
  public interface ResourceTunerWorkflow {
    @WorkflowMethod
    String execute(int numActivities, int memCeiling);
  }

  public static class ResourceTunerWorkflowImpl implements ResourceTunerWorkflow {
    @Override
    public String execute(int numActivities, int memCeiling) {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setHeartbeatTimeout(Duration.ofSeconds(20))
                  .build());

      List<Promise<Void>> promises = new ArrayList<>();
      for (int j = 0; j < numActivities; j++) {
        Promise<Void> promise = Async.procedure(activity::useResources, memCeiling);
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
}
