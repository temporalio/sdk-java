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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivitiesWorkflowTaskHeartbeatTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(15)
          .build();

  @Test
  public void testLocalActivitiesWorkflowTaskHeartbeat()
      throws ExecutionException, InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(4))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    int count = 5;
    Future<String>[] result = new Future[count];
    for (int i = 0; i < count; i++) {
      TestWorkflow1 workflowStub =
          testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
      result[i] = WorkflowClient.execute(workflowStub::execute, testWorkflowRule.getTaskQueue());
    }
    for (int i = 0; i < count; i++) {
      Assert.assertEquals(
          "sleepActivity0sleepActivity1sleepActivity2sleepActivity3sleepActivity4",
          result[i].get());
    }
    Assert.assertEquals(activitiesImpl.toString(), 5 * count, activitiesImpl.invocations.size());
  }

  public static class TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, TestOptions.newLocalActivityOptions());
      String result = "";
      for (int i = 0; i < 5; i++) {
        result += localActivities.sleepActivity(2000, i);
      }
      return result;
    }
  }
}
