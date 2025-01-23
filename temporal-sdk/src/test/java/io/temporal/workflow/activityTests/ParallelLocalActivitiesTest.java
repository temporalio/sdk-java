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

package io.temporal.workflow.activityTests;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ParallelLocalActivitiesTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();
  static final int TOTAL_LOCAL_ACT_COUNT = 100;
  @Parameterized.Parameter public int maxLocalActivityExecutionSize;

  @Parameterized.Parameters
  public static Object[] data() {
    return new Object[] {50, TOTAL_LOCAL_ACT_COUNT};
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParallelLocalActivitiesWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(20)
          // Use a number lower than the number of concurrent activities to ensure that the
          // queueing of LAs when task executor is full works
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setMaxConcurrentLocalActivityExecutionSize(maxLocalActivityExecutionSize)
                  .build())
          .build();

  @Test
  public void testParallelLocalActivities() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    String willQueue = maxLocalActivityExecutionSize < TOTAL_LOCAL_ACT_COUNT ? "yes" : "";
    String result = workflowStub.execute(willQueue);
    Assert.assertEquals("done", result);
    Assert.assertEquals(
        activitiesImpl.toString(), TOTAL_LOCAL_ACT_COUNT, activitiesImpl.invocations.size());
    List<String> expected = new ArrayList<>();
    expected.add("interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP);
    expected.add("newThread workflow-method");
    for (int i = 0; i < TOTAL_LOCAL_ACT_COUNT; i++) {
      expected.add("executeLocalActivity SleepActivity");
    }
    for (int i = 0; i < TOTAL_LOCAL_ACT_COUNT; i++) {
      expected.add("local activity SleepActivity");
    }
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(expected.toArray(new String[0]));
  }

  public static class TestParallelLocalActivitiesWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String willQueue) {
      LocalActivityOptions laOptions = SDKTestOptions.newLocalActivityOptions();
      // For the case where LAs will be forced to queue, we want to use start-to-close rather
      // than schedule-to-start timeouts.
      if (!willQueue.isEmpty()) {
        laOptions =
            LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build();
      }
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(VariousTestActivities.class, laOptions);
      List<Promise<String>> laResults = new ArrayList<>();
      Random r = Workflow.newRandom();
      for (int i = 0; i < TOTAL_LOCAL_ACT_COUNT; i++) {
        laResults.add(Async.function(localActivities::sleepActivity, (long) r.nextInt(3000), i));
      }
      Promise.allOf(laResults).get();
      return "done";
    }
  }
}
