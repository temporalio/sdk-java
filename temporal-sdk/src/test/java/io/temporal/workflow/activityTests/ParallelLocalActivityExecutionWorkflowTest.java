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

package io.temporal.workflow.activityTests;

import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ParallelLocalActivityExecutionWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParallelLocalActivityExecutionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testParallelLocalActivityExecutionWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals(
        "sleepActivity1sleepActivity2sleepActivity3sleepActivity4sleepActivity21sleepActivity21sleepActivity21",
        result);
  }

  public static class TestParallelLocalActivityExecutionWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, TestOptions.newLocalActivityOptions());
      List<Promise<String>> results = new ArrayList<>(4);
      for (int i = 1; i <= 4; i++) {
        results.add(Async.function(localActivities::sleepActivity, (long) 1000 * i, i));
      }

      Promise<String> result2 =
          Async.function(
              () -> {
                String result = "";
                for (int i = 0; i < 3; i++) {
                  result += localActivities.sleepActivity(1000, 21);
                }
                return result;
              });

      return results.get(0).get()
          + results.get(1).get()
          + results.get(2).get()
          + results.get(3).get()
          + result2.get();
    }
  }
}
