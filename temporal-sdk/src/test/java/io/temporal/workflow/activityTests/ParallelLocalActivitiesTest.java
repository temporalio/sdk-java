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

public class ParallelLocalActivitiesTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParallelLocalActivitiesWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(20)
          // Use a number lower than the number of concurrent activities to ensure that the
          // queueing of LAs when task executor is full works
          .setWorkerOptions(
              WorkerOptions.newBuilder().setMaxConcurrentLocalActivityExecutionSize(50).build())
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
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("done", result);
    Assert.assertEquals(activitiesImpl.toString(), 100, activitiesImpl.invocations.size());
    List<String> expected = new ArrayList<String>();
    expected.add("interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP);
    expected.add("newThread workflow-method");
    for (int i = 0; i < TestParallelLocalActivitiesWorkflowImpl.COUNT; i++) {
      expected.add("executeLocalActivity SleepActivity");
    }
    for (int i = 0; i < TestParallelLocalActivitiesWorkflowImpl.COUNT; i++) {
      expected.add("local activity SleepActivity");
    }
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(expected.toArray(new String[0]));
  }

  public static class TestParallelLocalActivitiesWorkflowImpl implements TestWorkflow1 {
    static final int COUNT = 100;

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
      List<Promise<String>> laResults = new ArrayList<>();
      Random r = Workflow.newRandom();
      for (int i = 0; i < COUNT; i++) {
        laResults.add(Async.function(localActivities::sleepActivity, (long) r.nextInt(3000), i));
      }
      Promise.allOf(laResults).get();
      return "done";
    }
  }
}
